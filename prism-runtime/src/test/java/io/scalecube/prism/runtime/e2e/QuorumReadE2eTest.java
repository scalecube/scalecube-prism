package io.scalecube.prism.runtime.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.scalecube.cluster.ClusterImpl;
import io.scalecube.prism.Prism;
import io.scalecube.prism.registry.ConsistencyTier;
import io.scalecube.prism.registry.ServiceEntry;
import io.scalecube.prism.runtime.PrismImpl;
import io.scalecube.transport.netty.tcp.TcpTransportFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for the {@code QUORUM} read-repair tier ({@link
 * io.scalecube.prism.registry.ServiceRegistry#lookupQuorum}) over a real multi-node netty cluster,
 * driven through the public API. A quorum read fans out to a majority of members over the registry's
 * dedicated RPC transport, merges replies last-writer-wins, repairs the local view, and returns the
 * freshest instances.
 */
@DisplayName("E2E: QUORUM read-repair over a real cluster (public API)")
class QuorumReadE2eTest {

  private static final Duration CONVERGE = Duration.ofSeconds(15);
  private static final Duration RPC = Duration.ofSeconds(10);
  private final List<Prism> nodes = new ArrayList<>();

  @AfterEach
  void teardown() {
    nodes.forEach(
        p -> {
          try {
            p.shutdown().block(Duration.ofSeconds(5));
          } catch (RuntimeException ignored) {
            // best-effort
          }
        });
    nodes.clear();
  }

  /**
   * Given a single-node cluster (self is already a majority),
   * When a quorum read runs,
   * Then it answers from the local view without contacting anyone.
   */
  @Test
  void singleNodeQuorumReadReturnsLocal() {
    Prism only = node(null);
    only.registry().register("orders", Map.of("weight", "100"), ConsistencyTier.QUORUM).block();

    Collection<ServiceEntry> result = only.registry().lookupQuorum("orders").block(RPC);

    assertFalse(result == null || result.isEmpty(), "single-node quorum read returns the local entry");
    assertEquals("100", result.iterator().next().properties().get("weight"));
  }

  /**
   * Given a provider that registers a service,
   * When a separate consumer issues a quorum read,
   * Then the read reaches a majority and returns the service (repairing the consumer's local view —
   * the read can win before gossip has delivered it).
   */
  @Test
  void quorumReadDiscoversAcrossCluster() {
    Prism seed = node(null);
    Prism provider = node(seed);
    Prism consumer = node(seed);

    provider.registry().register("orders", Map.of("weight", "100", "zone", "eu")).block();

    E2e.await(
        CONVERGE,
        "consumer's quorum read discovers orders",
        () -> {
          Collection<ServiceEntry> r = consumer.registry().lookupQuorum("orders").block(RPC);
          return r != null && !r.isEmpty();
        });

    Collection<ServiceEntry> r = consumer.registry().lookupQuorum("orders").block(RPC);
    assertEquals("100", r.iterator().next().properties().get("weight"));
    assertEquals("eu", r.iterator().next().properties().get("zone"));
    // The quorum read also repaired the local view.
    assertFalse(consumer.registry().lookup("orders").isEmpty(), "local view was repaired");
  }

  /**
   * Given a service whose property is updated at the provider,
   * When a consumer issues a quorum read,
   * Then it converges to the newest value (read-repair applies the higher version).
   */
  @Test
  void quorumReadReflectsLatestUpdate() {
    Prism seed = node(null);
    Prism provider = node(seed);
    Prism consumer = node(seed);

    provider.registry().register("orders", Map.of("weight", "100")).block();
    E2e.await(
        CONVERGE,
        "initial value visible via quorum read",
        () -> {
          Collection<ServiceEntry> r = consumer.registry().lookupQuorum("orders").block(RPC);
          return r != null && !r.isEmpty();
        });

    provider.registry().update("orders", "weight", "250").block();

    E2e.await(
        CONVERGE,
        "quorum read reflects the updated weight",
        () -> {
          Collection<ServiceEntry> r = consumer.registry().lookupQuorum("orders").block(RPC);
          return r != null
              && !r.isEmpty()
              && "250".equals(r.iterator().next().properties().get("weight"));
        });

    assertTrue(true);
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
