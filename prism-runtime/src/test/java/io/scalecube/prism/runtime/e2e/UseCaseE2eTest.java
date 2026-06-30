package io.scalecube.prism.runtime.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.scalecube.cluster.ClusterImpl;
import io.scalecube.prism.Prism;
import io.scalecube.prism.metrics.Metrics;
import io.scalecube.prism.registry.RegistryEvent;
import io.scalecube.prism.registry.ServiceEntry;
import io.scalecube.prism.runtime.PrismConfig;
import io.scalecube.prism.runtime.PrismImpl;
import io.scalecube.transport.netty.tcp.TcpTransportFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Business-use-case validation, framed as user stories (see {@code prism-docs/use-cases.md}). Each
 * test is a real-cluster, public-API scenario showing how prism solves a concrete need. These fill
 * the use cases not already covered by {@code RegistryE2eTest} / {@code ElectorE2eTest} /
 * {@code AffinityE2eTest}: client-side load balancing (A2), zero-downtime drain (A4), and election
 * observability (D3).
 */
@DisplayName("E2E: business use cases (user-story scenarios)")
class UseCaseE2eTest {

  private static final Duration CONVERGE = Duration.ofSeconds(15);
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
   * A2 — client-side load balancing.
   * As a gateway, given two instances of "orders" with different weights and both healthy,
   * When I discover them and select by health then weight,
   * Then I route to the highest-weight healthy instance.
   */
  @Test
  void clientSideLoadBalancing() {
    Prism seed = registryNode(null);
    seed.registry().register("orders", Map.of("weight", "100", "status", "passing")).block();
    Prism small = registryNode(seed);
    small.registry().register("orders", Map.of("weight", "50", "status", "passing")).block();
    Prism gateway = registryNode(seed);

    E2e.await(CONVERGE, "both instances discovered",
        () -> gateway.registry().lookup("orders").size() == 2);

    ServiceEntry chosen =
        gateway.registry().lookup("orders").stream()
            .filter(ServiceEntry::alive)
            .filter(e -> "passing".equals(e.properties().get("status")))
            .max(Comparator.comparingInt(e -> Integer.parseInt(e.properties().get("weight"))))
            .orElseThrow();

    assertEquals("100", chosen.properties().get("weight"), "route to the highest-weight healthy node");
  }

  /**
   * A4 — zero-downtime deploy via drain.
   * As an operator, given a live instance a gateway is watching,
   * When I drain it (status=draining, weight=0) and then deregister it,
   * Then the gateway first sees it deprioritized (so it stops sending new traffic) and then removed —
   * no request is routed to a stopped process.
   */
  @Test
  void zeroDowntimeDrainBeforeShutdown() {
    Prism seed = registryNode(null);
    Prism gateway = registryNode(seed);
    List<RegistryEvent> events = new CopyOnWriteArrayList<>();
    gateway.registry().watch().subscribe(events::add);

    Prism instance = registryNode(seed);
    instance.registry().register("orders", Map.of("weight", "100", "status", "passing")).block();
    E2e.await(CONVERGE, "instance live",
        () -> events.stream().anyMatch(ev -> ev.type() == RegistryEvent.Type.REGISTERED));

    // Drain: stop new traffic (weight 0 + status draining) while in-flight requests finish.
    instance.registry().update("orders", "status", "draining").block();
    instance.registry().update("orders", "weight", "0").block();
    E2e.await(CONVERGE, "gateway sees the drain",
        () -> gateway.registry().lookup("orders").stream()
            .anyMatch(e -> "draining".equals(e.properties().get("status"))
                && "0".equals(e.properties().get("weight"))));

    // Then remove it cleanly.
    instance.registry().deregister("orders").block();
    E2e.await(CONVERGE, "gateway sees it removed",
        () -> gateway.registry().lookup("orders").isEmpty());
    assertTrue(gateway.registry().lookup("orders").isEmpty(), "drained instance removed cleanly");
  }

  /**
   * D3 — observability.
   * As an SRE, given the elector wired with a metrics sink,
   * When a node becomes leader,
   * Then the {@code prism.elector.granted} counter is recorded (so elections are alertable).
   */
  @Test
  void electionMetricsAreRecorded() {
    CountingMetrics metrics = new CountingMetrics();
    String address = "127.0.0.1:" + E2e.freePort();
    PrismConfig config = new PrismConfig(address, List.of(address), TcpTransportFactory::new);
    Prism prism =
        new PrismImpl(new ClusterImpl().transportFactory(TcpTransportFactory::new), config, metrics)
            .startAwait();
    nodes.add(prism);

    prism.elector().campaign("gateway").subscribe();
    E2e.await(CONVERGE, "node becomes leader",
        () -> prism.elector().currentLeader("gateway").isPresent());

    assertTrue(metrics.count("prism.elector.granted") >= 1,
        "becoming leader records the prism.elector.granted metric");
  }

  // ---- helpers ----

  private Prism registryNode(Prism seed) {
    ClusterImpl cluster = new ClusterImpl().transportFactory(TcpTransportFactory::new);
    if (seed != null) {
      cluster = cluster.membership(opts -> opts.seedMembers(seed.cluster().address()));
    }
    Prism prism = new PrismImpl(cluster).startAwait();
    nodes.add(prism);
    return prism;
  }

  /** A tiny counting {@link Metrics} sink (the SPI a Micrometer/OTel adapter implements). */
  private static final class CountingMetrics implements Metrics {
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    @Override
    public void increment(String name) {
      counters.computeIfAbsent(name, k -> new AtomicLong()).incrementAndGet();
    }

    @Override
    public void gauge(String name, long value) {
      counters.computeIfAbsent(name, k -> new AtomicLong()).set(value);
    }

    long count(String name) {
      return counters.getOrDefault(name, new AtomicLong()).get();
    }
  }
}
