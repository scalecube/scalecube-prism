package io.scalecube.prism.runtime.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.scalecube.cluster.ClusterImpl;
import io.scalecube.prism.Prism;
import io.scalecube.prism.registry.ConsistencyTier;
import io.scalecube.prism.registry.RegistryEvent;
import io.scalecube.prism.registry.ServiceEntry;
import io.scalecube.prism.runtime.PrismImpl;
import io.scalecube.prism.version.FreshnessToken;
import io.scalecube.transport.netty.tcp.TcpTransportFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end, black-box registry tests over a real multi-node netty cluster, driven through the
 * public {@code Prism} / {@code ServiceRegistry} API exactly as a client would — register, discover,
 * watch, update, deregister, tiers, freshness, and membership-driven lifecycle. No internals are
 * touched; the registry disseminates and reconciles on its own, and the tests <b>await</b> the
 * observable outcomes (lookups converging; {@code watch} events arriving).
 */
@DisplayName("E2E: service registry over a real cluster (public API)")
class RegistryE2eTest {

  private static final Duration CONVERGE = Duration.ofSeconds(15);
  private static final Duration DEAD = Duration.ofSeconds(30); // SWIM failure detection + purge
  private final List<Prism> nodes = new ArrayList<>();

  @AfterEach
  void teardown() {
    nodes.forEach(p -> {
      try {
        p.shutdown().block(Duration.ofSeconds(5));
      } catch (RuntimeException ignored) {
        // best-effort
      }
    });
    nodes.clear();
  }

  /**
   * Given a provider that registers a service and a separate consumer,
   * When gossip converges,
   * Then the consumer discovers the service with its properties (eventually-consistent discovery).
   */
  @Test
  void registerThenDiscoverAcrossNodes() {
    Prism seed = node(null);
    Prism provider = node(seed);
    Prism consumer = node(seed);

    provider.registry().register("orders", Map.of("weight", "100", "zone", "eu")).block();

    E2e.await(CONVERGE, "consumer discovers orders",
        () -> !consumer.registry().lookup("orders").isEmpty());

    ServiceEntry e = consumer.registry().lookup("orders").iterator().next();
    assertEquals("100", e.properties().get("weight"));
    assertEquals("eu", e.properties().get("zone"));
  }

  /**
   * Given a consumer watching the registry,
   * When a provider registers, updates (drains), and deregisters a service,
   * Then the consumer's watch stream observes REGISTERED, then UPDATED, then DEREGISTERED.
   */
  @Test
  void watchObservesLifecycleEvents() {
    Prism seed = node(null);
    Prism consumer = node(seed);
    List<RegistryEvent> events = new CopyOnWriteArrayList<>();
    consumer.registry().watch().subscribe(events::add);

    Prism provider = node(seed);
    provider.registry().register("orders", Map.of("weight", "100")).block();
    E2e.await(CONVERGE, "REGISTERED observed",
        () -> events.stream().anyMatch(ev -> ev.type() == RegistryEvent.Type.REGISTERED
            && ev.entry().service().equals("orders")));

    provider.registry().update("orders", "weight", "0").block(); // drain
    E2e.await(CONVERGE, "UPDATED observed",
        () -> events.stream().anyMatch(ev -> ev.type() == RegistryEvent.Type.UPDATED
            && "0".equals(ev.entry().properties().get("weight"))));

    provider.registry().deregister("orders").block();
    E2e.await(CONVERGE, "DEREGISTERED observed",
        () -> events.stream().anyMatch(ev -> ev.type() == RegistryEvent.Type.DEREGISTERED));
  }

  /**
   * Given two providers advertising the same service name with different properties,
   * When the consumer discovers it,
   * Then it sees both instances (one per owner) and can select among them.
   */
  @Test
  void discoversMultipleInstancesOfAService() {
    Prism seed = node(null);
    seed.registry().register("orders", Map.of("weight", "100")).block();
    Prism provider2 = node(seed);
    provider2.registry().register("orders", Map.of("weight", "50")).block();
    Prism consumer = node(seed);

    E2e.await(CONVERGE, "consumer sees both instances",
        () -> consumer.registry().lookup("orders").size() == 2);

    assertEquals(2, consumer.registry().lookup("orders").size());
  }

  /**
   * Given services registered at different consistency tiers,
   * When the consumer discovers them,
   * Then each entry reports its declared tier (EVENTUAL vs the default CAUSAL).
   */
  @Test
  void registersAtDifferentConsistencyTiers() {
    Prism seed = node(null);
    Prism provider = node(seed);
    provider.registry().register("cache", Map.of("k", "v"), ConsistencyTier.EVENTUAL).block();
    provider.registry().register("orders", Map.of("k", "v")).block(); // default CAUSAL
    Prism consumer = node(seed);

    E2e.await(CONVERGE, "both tiers discovered",
        () -> !consumer.registry().lookup("cache").isEmpty()
            && !consumer.registry().lookup("orders").isEmpty());

    assertEquals(ConsistencyTier.EVENTUAL,
        consumer.registry().lookup("cache").iterator().next().tier());
    assertEquals(ConsistencyTier.CAUSAL,
        consumer.registry().lookup("orders").iterator().next().tier());
  }

  /**
   * Given a provider whose writes advance a freshness token,
   * When it registers and then updates,
   * Then the freshness token is monotonic (the basis of read-your-writes session guarantees).
   */
  @Test
  void freshnessTokenIsMonotonic() {
    Prism provider = node(null);
    provider.registry().register("orders", Map.of("weight", "100")).block();
    String owner = provider.registry().lookup("orders").iterator().next().owner();

    FreshnessToken before = provider.registry().freshness(owner);
    provider.registry().update("orders", "weight", "150").block();
    FreshnessToken after = provider.registry().freshness(owner);

    assertTrue(before.upTo().compareTo(after.upTo()) <= 0, "freshness advances monotonically");
  }

  /**
   * Given a provider whose service is discovered cluster-wide,
   * When the provider node dies,
   * Then the consumer eventually purges its entries (membership is the tombstone).
   */
  @Test
  void deadProviderEntriesArePurged() {
    Prism seed = node(null);
    Prism consumer = node(seed);
    Prism provider = node(seed);
    provider.registry().register("orders", Map.of("weight", "100")).block();
    E2e.await(CONVERGE, "discovered before death",
        () -> !consumer.registry().lookup("orders").isEmpty());

    provider.shutdown().block(Duration.ofSeconds(5));
    nodes.remove(provider);

    E2e.await(DEAD, "entries purged after the provider dies",
        () -> consumer.registry().lookup("orders").isEmpty());
    assertTrue(consumer.registry().lookup("orders").isEmpty(), "dead provider's entries are gone");
  }

  // ---- helpers ----

  private Prism node(Prism seed) {
    ClusterImpl cluster = new ClusterImpl().transportFactory(TcpTransportFactory::new);
    if (seed != null) {
      cluster = cluster.membership(opts -> opts.seedMembers(seed.cluster().address()));
    }
    Prism prism = new PrismImpl(cluster).startAwait();
    nodes.add(prism);
    return prism;
  }
}
