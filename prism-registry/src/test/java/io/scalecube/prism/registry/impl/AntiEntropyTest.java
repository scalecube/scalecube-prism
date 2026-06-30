package io.scalecube.prism.registry.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.scalecube.cluster.ClusterMessageHandler;
import io.scalecube.prism.metrics.Metrics;
import io.scalecube.prism.registry.ConsistencyTier;
import io.scalecube.prism.registry.ServiceEntry;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Registry anti-entropy: Merkle-root beacon heals missed updates")
class AntiEntropyTest {

  /**
   * Given a registration that a peer missed (it happened while disconnected),
   * When the peer broadcasts its catalog Merkle root and the owner sees the roots differ,
   * Then the owner re-advertises its slice and the peer converges (the missed update heals).
   */
  @Test
  void rootBeaconHealsAMissedUpdate() {
    GossipServiceRegistry a = new GossipServiceRegistry();
    GossipServiceRegistry b = new GossipServiceRegistry();
    FakeCluster ca = new FakeCluster("A", "A@addr");
    FakeCluster cb = new FakeCluster("B", "B@addr");
    ClusterMessageHandler ha = a.bind(ca);
    ClusterMessageHandler hb = b.bind(cb);

    // A registers while disconnected from B — the update is "missed".
    a.register("orders", Map.of("weight", "100"), ConsistencyTier.CAUSAL).block();
    assertTrue(b.lookup("orders").isEmpty());

    // Now connect the two.
    ca.peer = hb;
    cb.peer = ha;

    // B beacons its (different) digest → A detects divergence → A re-advertises → B converges.
    b.broadcastBeacon();

    assertEquals(1, b.lookup("orders").size());
    assertEquals("100", weight(b.lookup("orders")));
  }

  /**
   * Given two already-converged replicas,
   * When a root beacon is exchanged,
   * Then roots match and nothing changes (no needless re-advertisement).
   */
  @Test
  void convergedReplicasDoNotChangeOnBeacon() {
    GossipServiceRegistry a = new GossipServiceRegistry();
    GossipServiceRegistry b = new GossipServiceRegistry();
    FakeCluster ca = new FakeCluster("A", "A@addr");
    FakeCluster cb = new FakeCluster("B", "B@addr");
    ca.peer = b.bind(cb);
    cb.peer = a.bind(ca);

    a.register("orders", Map.of("weight", "100"), ConsistencyTier.CAUSAL).block(); // reaches B

    b.broadcastBeacon(); // trees already match

    assertEquals(1, b.lookup("orders").size());
    assertEquals("100", weight(b.lookup("orders")));
  }

  /**
   * Given a node owning many services, all but one already converged with a peer,
   * When the peer beacons its per-bucket digest and the owner diffs against it,
   * Then the owner re-advertises ONLY its entries in the differing bucket(s) — far fewer than its
   * whole owned slice — and the peer converges. Proves traffic is proportional to the delta (this
   * fails against the old root-mismatch → re-advertise-everything path).
   */
  @Test
  void diffReadvertisesOnlyTheChangedBucketNotTheWholeSlice() {
    RecordingMetrics aeMetrics = new RecordingMetrics();
    GossipServiceRegistry a = new GossipServiceRegistry(aeMetrics);
    GossipServiceRegistry b = new GossipServiceRegistry();
    FakeCluster ca = new FakeCluster("A", "A@addr");
    FakeCluster cb = new FakeCluster("B", "B@addr");
    ca.peer = b.bind(cb);
    cb.peer = a.bind(ca);

    final int owned = 12;
    for (int i = 0; i < owned; i++) {
      a.register("svc-" + i, Map.of("weight", "100"), ConsistencyTier.CAUSAL).block(); // reaches B
    }

    // A registers one more service while B is disconnected — B misses exactly this one.
    ca.peer = null;
    a.register("late", Map.of("weight", "100"), ConsistencyTier.CAUSAL).block();
    ca.peer = b.bind(cb);
    assertTrue(b.lookup("late").isEmpty());

    b.broadcastBeacon(); // B's digest → A diffs → A re-advertises only the differing bucket(s)

    assertEquals(1, b.lookup("late").size(), "the missed update did not heal");
    long readvertised = aeMetrics.gauges.getOrDefault("prism.registry.ae.readvertise.entries", -1L);
    assertTrue(readvertised >= 1, "nothing was re-advertised");
    assertTrue(
        readvertised < owned + 1,
        "re-advertised the whole owned slice (" + readvertised + " of " + (owned + 1)
            + ") instead of just the differing bucket");
  }

  private static String weight(java.util.Collection<ServiceEntry> entries) {
    return new ArrayList<>(entries).get(0).properties().get("weight");
  }

  /** Captures the latest value of each gauge so a test can assert on it. */
  private static final class RecordingMetrics implements Metrics {
    final java.util.Map<String, Long> gauges = new ConcurrentHashMap<>();

    @Override
    public void increment(String name) {
      // not needed for these assertions
    }

    @Override
    public void gauge(String name, long value) {
      gauges.put(name, value);
    }
  }
}
