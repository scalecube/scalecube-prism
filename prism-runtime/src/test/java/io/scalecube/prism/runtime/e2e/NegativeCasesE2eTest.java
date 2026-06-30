package io.scalecube.prism.runtime.e2e;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.scalecube.cluster.ClusterImpl;
import io.scalecube.prism.Prism;
import io.scalecube.prism.runtime.PrismConfig;
import io.scalecube.prism.runtime.PrismImpl;
import io.scalecube.transport.netty.tcp.TcpTransportFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Negative / failure-path E2E — the cases that must <b>not</b> succeed. Safety means the wrong things
 * are prevented, so these assert that a minority cannot elect, that losing the majority drops
 * leadership (safe-unavailable, not split-brain), that discovery of an absent service is empty (not an
 * error), and that misuse fails loudly. Real clusters, public API.
 */
@DisplayName("E2E negative cases: what must NOT happen")
class NegativeCasesE2eTest {

  private static final Duration SETTLE = Duration.ofSeconds(15);
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
   * Given a 3-node quorum reduced to a single survivor (two nodes killed → a minority of one),
   * When the survivor campaigns,
   * Then it can NEVER acquire leadership — a minority must not elect (safety over availability).
   */
  @Test
  void minorityCannotElect() {
    List<Prism> cluster = startCluster(3);
    Prism survivor = cluster.get(0);

    // Kill the other two → the survivor is a minority of the 3-member quorum.
    cluster.get(1).shutdown().block(Duration.ofSeconds(5));
    cluster.get(2).shutdown().block(Duration.ofSeconds(5));
    nodes.remove(cluster.get(1));
    nodes.remove(cluster.get(2));

    survivor.elector().campaign("gateway").subscribe();

    // Give it ample time and ticks; it must still have no leader (no majority to grant a lease).
    E2e.sleep(6000);
    assertFalse(survivor.elector().currentLeader("gateway").isPresent(),
        "a minority of the quorum must never elect a leader");
  }

  /**
   * Given an established leader,
   * When the quorum loses its majority (two of three die),
   * Then the leader cannot renew and leadership lapses — safe-unavailable, never a second leader.
   */
  @Test
  void losingMajorityDropsLeadership() {
    List<Prism> cluster = startCluster(3);
    cluster.forEach(p -> p.elector().campaign("gateway").subscribe());
    E2e.await(SETTLE, "a leader is established",
        () -> cluster.get(0).elector().currentLeader("gateway").isPresent());

    // Identify the leader, keep it alive, kill the other two → leader is now in a minority.
    String leaderId = cluster.get(0).elector().currentLeader("gateway").orElseThrow().id();
    Prism leader = cluster.stream().filter(p -> p.member().id().equals(leaderId))
        .findFirst().orElseThrow();
    for (Prism p : cluster) {
      if (p != leader) {
        p.shutdown().block(Duration.ofSeconds(5));
        nodes.remove(p);
      }
    }

    E2e.await(SETTLE, "leadership lapses once the majority is lost",
        () -> leader.elector().currentLeader("gateway").isEmpty());
    assertTrue(leader.elector().currentLeader("gateway").isEmpty(),
        "with no majority the lease cannot be renewed — safe-unavailable");
  }

  /**
   * Given a registry,
   * When looking up a service nobody advertised,
   * Then the result is an empty collection (a miss is normal, not an error or null).
   */
  @Test
  void lookupOfUnknownServiceIsEmpty() {
    Prism node = registryNode();
    assertTrue(node.registry().lookup("does-not-exist").isEmpty(), "unknown lookup is empty");
    assertTrue(node.registry().list().isEmpty(), "empty catalog is empty, not null");
  }

  /**
   * Given a prism started WITHOUT a PrismConfig (registry-only),
   * When the elector is requested,
   * Then it fails loudly rather than silently returning a broken elector.
   */
  @Test
  void electorWithoutConfigFailsLoudly() {
    Prism node = registryNode();
    assertThrows(UnsupportedOperationException.class, node::elector);
  }

  // ---- helpers ----

  private Prism registryNode() {
    Prism prism = new PrismImpl(new ClusterImpl().transportFactory(TcpTransportFactory::new))
        .startAwait();
    nodes.add(prism);
    return prism;
  }

  private List<Prism> startCluster(int n) {
    List<String> quorum = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      quorum.add("127.0.0.1:" + E2e.freePort());
    }
    List<Prism> cluster = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      ClusterImpl c = new ClusterImpl().transportFactory(TcpTransportFactory::new);
      if (!cluster.isEmpty()) {
        Prism seed = cluster.get(0);
        c = c.membership(opts -> opts.seedMembers(seed.cluster().address()));
      }
      PrismConfig config =
          new PrismConfig(quorum.get(i), quorum, TcpTransportFactory::new)
              .withLeaseTtl(Duration.ofSeconds(2))
              .withTickInterval(Duration.ofMillis(400));
      Prism prism = new PrismImpl(c, config).startAwait();
      nodes.add(prism);
      cluster.add(prism);
    }
    return cluster;
  }
}
