package io.scalecube.prism.runtime.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.scalecube.cluster.ClusterImpl;
import io.scalecube.cluster.Member;
import io.scalecube.prism.Prism;
import io.scalecube.prism.elector.Preference;
import io.scalecube.prism.runtime.PrismConfig;
import io.scalecube.prism.runtime.PrismImpl;
import io.scalecube.transport.netty.tcp.TcpTransportFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end leader affinity (ADR-0016) over a real 3-node netty cluster, via the public elector API
 * with the elector self-ticking. Mode A (preference-biased) and Mode B (controller-driven
 * promote/demote). Preference also sidesteps dueling proposers: only the PREFERRED node contends
 * immediately while standbys yield, so there is no synchronized split.
 */
@DisplayName("E2E: leader affinity over a real cluster (public API)")
class AffinityE2eTest {

  private static final Duration SETTLE = Duration.ofSeconds(20);
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
   * Given three nodes where exactly one declares itself PREFERRED (the others STANDBY with a yield
   * window) and all campaign,
   * When the election settles,
   * Then the preferred node wins — biased election without breaking mutual exclusion.
   */
  @Test
  void preferredNodeWinsTheElection() {
    List<Prism> cluster = startCluster(3);
    Prism preferred = cluster.get(1);

    for (Prism p : cluster) {
      Preference pref = (p == preferred) ? Preference.PREFERRED : Preference.STANDBY;
      p.elector().affinity("gateway", () -> pref, Duration.ofSeconds(3), false);
      p.elector().campaign("gateway").subscribe();
    }

    E2e.await(SETTLE, "preferred node leads",
        () -> leader(cluster, "gateway").map(id -> id.equals(preferred.member().id())).orElse(false));

    assertEquals(preferred.member().id(), leader(cluster, "gateway").orElseThrow(),
        "the PREFERRED node wins the election");
  }

  /**
   * Given a controller driving leadership (nodes do not campaign),
   * When it promotes one node and then another,
   * Then the first wins and the second's cooperative promote does not preempt it.
   */
  @Test
  void controllerPromoteIsCooperative() {
    List<Prism> cluster = startCluster(3);

    boolean won1 = cluster.get(0).elector().promote("scheduler").block();
    assertTrue(won1, "controller promotes node0 into the free group");
    E2e.await(SETTLE, "node0 leads",
        () -> leader(cluster, "scheduler").map(id -> id.equals(cluster.get(0).member().id()))
            .orElse(false));

    boolean won2 = cluster.get(1).elector().promote("scheduler").block();
    assertFalse(won2, "cooperative promote does not preempt a valid leader");
    assertEquals(cluster.get(0).member().id(), leader(cluster, "scheduler").orElseThrow());
  }

  /**
   * Given a controller-promoted leader,
   * When it is demoted and another node is promoted,
   * Then leadership moves to the second node (graceful controller-driven handoff).
   */
  @Test
  void controllerDemoteThenPromoteHandsOff() {
    List<Prism> cluster = startCluster(3);
    cluster.get(0).elector().promote("scheduler").block();
    E2e.await(SETTLE, "node0 leads",
        () -> leader(cluster, "scheduler").isPresent());

    cluster.get(0).elector().demote("scheduler").block();
    boolean won2 = cluster.get(1).elector().promote("scheduler").block();

    assertTrue(won2, "after demote the group is free for node1");
    E2e.await(SETTLE, "node1 leads",
        () -> leader(cluster, "scheduler").map(id -> id.equals(cluster.get(1).member().id()))
            .orElse(false));
    assertEquals(cluster.get(1).member().id(), leader(cluster, "scheduler").orElseThrow());
  }

  // ---- helpers ----

  private Optional<String> leader(List<Prism> cluster, String group) {
    return cluster.get(0).elector().currentLeader(group).map(Member::id);
  }

  private List<Prism> startCluster(int n) {
    List<String> quorum = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      quorum.add("127.0.0.1:" + E2e.freePort());
    }
    List<Prism> cluster = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      cluster.add(node(quorum.get(i), quorum, cluster.isEmpty() ? null : cluster.get(0)));
    }
    return cluster;
  }

  private Prism node(String consensusAddress, List<String> quorum, Prism seed) {
    ClusterImpl cluster = new ClusterImpl().transportFactory(TcpTransportFactory::new);
    if (seed != null) {
      cluster = cluster.membership(opts -> opts.seedMembers(seed.cluster().address()));
    }
    PrismConfig config =
        new PrismConfig(consensusAddress, quorum, TcpTransportFactory::new)
            .withLeaseTtl(Duration.ofSeconds(2))
            .withTickInterval(Duration.ofMillis(400));
    Prism prism = new PrismImpl(cluster, config).startAwait();
    nodes.add(prism);
    return prism;
  }
}
