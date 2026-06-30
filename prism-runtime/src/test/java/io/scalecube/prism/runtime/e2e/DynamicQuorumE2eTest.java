package io.scalecube.prism.runtime.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.scalecube.cluster.ClusterImpl;
import io.scalecube.cluster.Member;
import io.scalecube.prism.Prism;
import io.scalecube.prism.runtime.PrismConfig;
import io.scalecube.prism.runtime.PrismImpl;
import io.scalecube.transport.netty.tcp.TcpTransportFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * §4 acceptance: the self-electing-quorum path driven <b>end-to-end by the real elector and
 * {@link io.scalecube.prism.consensus.ReconfigurationManager}</b> through {@link PrismImpl} over a
 * real 3-node netty cluster (not the simulation harness). The dynamic quorum runs its own
 * reconfiguration ticker; these black-box tests only observe the public {@code Prism} API and assert
 * the same invariants the deterministic fuzz checks: exactly one leader, and a strictly higher
 * fencing epoch on failover (never two leaders, never a regressed token) across a leader loss.
 */
@DisplayName("E2E: self-electing quorum elects and self-heals (public API, real transport)")
class DynamicQuorumE2eTest {

  private static final Duration SETTLE = Duration.ofSeconds(20);
  private final List<Prism> nodes = new ArrayList<>();

  @AfterEach
  void teardown() {
    nodes.forEach(
        p -> {
          try {
            p.shutdown().block(Duration.ofSeconds(5));
          } catch (RuntimeException ignored) {
            // best-effort teardown
          }
        });
    nodes.clear();
  }

  /**
   * Given a three-node cluster running the self-electing-quorum path (withDynamicQuorum), each node
   * advertising its consensus address to the gossip pool,
   * When every node campaigns for a group,
   * Then exactly one leader is elected and every node agrees — the dynamic quorum operates correctly
   * end-to-end (the reconfiguration ticker runs alongside, without breaking election).
   */
  @Test
  void dynamicQuorumElectsExactlyOneLeader() {
    List<Prism> cluster = startDynamicCluster(3);
    String group = "gateway";

    cluster.forEach(p -> p.elector().campaign(group).subscribe());
    E2e.await(
        SETTLE,
        "all nodes agree on exactly one leader",
        () -> distinctLeaders(cluster, group).size() == 1);

    assertEquals(1, distinctLeaders(cluster, group).size(), "exactly one leader on the dynamic path");
  }

  /**
   * Given a settled leader on the self-electing-quorum path,
   * When the leader node is shut down (membership shrinks, the quorum must self-heal),
   * Then a surviving node becomes the single leader at a strictly higher fencing epoch — never two
   * leaders, and the fencing token never regresses, across the reconfiguration.
   */
  @Test
  void dynamicQuorumSelfHealsToSingleLeaderOnLeaderLoss() {
    List<Prism> cluster = startDynamicCluster(3);
    String group = "gateway";

    AtomicLong maxEpoch = new AtomicLong(-1);
    cluster.forEach(
        p ->
            p.elector()
                .leadership(group)
                .subscribe(
                    l -> {
                      if (l.active()) {
                        maxEpoch.accumulateAndGet(l.epoch(), Math::max);
                      }
                    }));

    cluster.forEach(p -> p.elector().campaign(group).subscribe());
    E2e.await(SETTLE, "initial leader", () -> distinctLeaders(cluster, group).size() == 1);

    String firstLeader = distinctLeaders(cluster, group).iterator().next();
    long epochBefore = maxEpoch.get();
    Prism fallen =
        cluster.stream()
            .filter(p -> p.member().id().equals(firstLeader))
            .findFirst()
            .orElseThrow();
    List<Prism> survivors = cluster.stream().filter(p -> p != fallen).collect(Collectors.toList());

    fallen.shutdown().block(Duration.ofSeconds(5));
    nodes.remove(fallen);

    E2e.await(
        SETTLE,
        "self-heal to a new single leader among survivors",
        () -> {
          Set<String> ls = distinctLeaders(survivors, group);
          return ls.size() == 1 && !ls.contains(firstLeader);
        });

    Set<String> after = distinctLeaders(survivors, group);
    assertEquals(1, after.size(), "exactly one leader after self-heal: " + after);
    assertTrue(
        maxEpoch.get() > epochBefore,
        "fencing epoch strictly increased on failover (" + epochBefore + " -> " + maxEpoch.get() + ")");
  }

  // ---- helpers ----

  /** The set of leader ids reported by the given nodes (a quorum read each) — size 1 = agreement. */
  private static Set<String> distinctLeaders(List<Prism> from, String group) {
    Set<String> ids = ConcurrentHashMap.newKeySet();
    for (Prism p : from) {
      p.elector().currentLeader(group).map(Member::id).ifPresent(ids::add);
    }
    return ids;
  }

  private List<Prism> startDynamicCluster(int n) {
    List<String> seed = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      seed.add("127.0.0.1:" + E2e.freePort());
    }
    List<Prism> cluster = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      cluster.add(node(seed.get(i), seed, cluster.isEmpty() ? null : cluster.get(0)));
    }
    return cluster;
  }

  private Prism node(String consensusAddress, List<String> seed, Prism clusterSeed) {
    ClusterImpl cluster = new ClusterImpl().transportFactory(TcpTransportFactory::new);
    if (clusterSeed != null) {
      cluster = cluster.membership(opts -> opts.seedMembers(clusterSeed.cluster().address()));
    }
    PrismConfig config =
        new PrismConfig(consensusAddress, seed, TcpTransportFactory::new)
            .withLeaseTtl(Duration.ofSeconds(2))
            .withTickInterval(Duration.ofMillis(400))
            .withDynamicQuorum(3);
    Prism prism = new PrismImpl(cluster, config).startAwait();
    nodes.add(prism);
    return prism;
  }
}
