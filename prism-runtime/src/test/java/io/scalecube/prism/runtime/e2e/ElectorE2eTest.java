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
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * End-to-end, black-box elector tests driven the way a real client uses prism: the public
 * {@code Prism} API over a real 3-node netty cluster, with the elector running its <b>own</b> timers
 * (no internal {@code tick()}). Operations are issued <b>concurrently and non-blocking</b> (all nodes
 * campaign at once on parallel schedulers) so genuine races go through the quorum — exposing any
 * mutual-exclusion defect rather than hiding it behind sequential calls.
 */
@DisplayName("E2E: elector under real concurrent contention (public API, self-ticking)")
class ElectorE2eTest {

  private static final Duration SETTLE = Duration.ofSeconds(15);
  private final List<Prism> nodes = new ArrayList<>();

  @AfterEach
  void teardown() {
    nodes.forEach(p -> {
      try {
        p.shutdown().block(Duration.ofSeconds(5));
      } catch (RuntimeException ignored) {
        // best-effort teardown
      }
    });
    nodes.clear();
  }

  /**
   * Given three nodes that all campaign for the same group at the same instant (a real race),
   * When the election settles,
   * Then exactly one leader exists and every node agrees on it — mutual exclusion under contention.
   *
   * <p>This previously exposed a dueling-proposer livelock under perfectly-synchronized campaigns;
   * it now converges because acquisition is two-phase Paxos (PREPARE/promise orders the proposers so
   * one ballot wins) with a randomized backoff. Safety was never at risk — only convergence.
   */
  @Test
  void concurrentCampaignElectsExactlyOneLeader() {
    List<Prism> cluster = startCluster(3);
    String group = "gateway";

    // Fire all campaigns concurrently on parallel threads (as independent reactive clients would).
    Flux.merge(
            cluster.stream()
                .map(p -> p.elector().campaign(group).subscribeOn(Schedulers.boundedElastic()))
                .collect(Collectors.toList()))
        .blockLast(SETTLE);

    E2e.await(SETTLE, "all nodes agree on exactly one leader",
        () -> distinctLeaders(cluster, group).size() == 1);

    Set<String> leaders = distinctLeaders(cluster, group);
    assertEquals(1, leaders.size(), "exactly one leader, agreed by every node: " + leaders);
  }

  /**
   * Given a settled leader and all nodes subscribed to the leadership stream,
   * When the leader node is shut down,
   * Then a different surviving node becomes leader at a strictly higher fencing epoch (failover).
   */
  @Test
  void leaderFailoverPromotesAStandbyAtHigherEpoch() {
    List<Prism> cluster = startCluster(3);
    String group = "gateway";

    // Track the highest active fencing epoch each node observes, via the leadership Flux.
    AtomicLong maxEpoch = new AtomicLong(-1);
    cluster.forEach(p ->
        p.elector().leadership(group).subscribe(l -> {
          if (l.active()) {
            maxEpoch.accumulateAndGet(l.epoch(), Math::max);
          }
        }));

    cluster.forEach(p -> p.elector().campaign(group).subscribe());
    E2e.await(SETTLE, "initial leader", () -> distinctLeaders(cluster, group).size() == 1);

    String firstLeader = distinctLeaders(cluster, group).iterator().next();
    long epochBefore = maxEpoch.get();
    Prism fallen = cluster.stream()
        .filter(p -> p.member().id().equals(firstLeader)).findFirst().orElseThrow();
    List<Prism> survivors = cluster.stream().filter(p -> p != fallen).collect(Collectors.toList());

    fallen.shutdown().block(Duration.ofSeconds(5));
    nodes.remove(fallen);

    E2e.await(SETTLE, "failover to a new leader among survivors",
        () -> {
          Set<String> ls = distinctLeaders(survivors, group);
          return ls.size() == 1 && !ls.contains(firstLeader);
        });

    Set<String> after = distinctLeaders(survivors, group);
    assertEquals(1, after.size(), "exactly one leader after failover: " + after);
    assertTrue(maxEpoch.get() > epochBefore,
        "fencing epoch strictly increased on failover (" + epochBefore + " -> " + maxEpoch.get() + ")");
  }

  /**
   * Given the same cluster running two independent logical groups,
   * When every node campaigns for both,
   * Then each group elects exactly one leader, independently (groups are logical, not separate
   * clusters). Campaigns are issued per node (not perfectly synchronized), as real nodes do.
   */
  @Test
  void independentLeaderPerGroup() {
    List<Prism> cluster = startCluster(3);

    for (Prism p : cluster) {
      p.elector().campaign("service-A").subscribe();
      p.elector().campaign("service-B").subscribe();
    }

    E2e.await(SETTLE, "both groups elect one leader each",
        () -> distinctLeaders(cluster, "service-A").size() == 1
            && distinctLeaders(cluster, "service-B").size() == 1);

    assertEquals(1, distinctLeaders(cluster, "service-A").size());
    assertEquals(1, distinctLeaders(cluster, "service-B").size());
  }

  /**
   * Given a leader that resigns voluntarily,
   * When it steps down,
   * Then a surviving node takes over promptly (graceful handoff over the real quorum store).
   */
  @Test
  void resignHandsOffToAnotherNode() {
    List<Prism> cluster = startCluster(3);
    String group = "gateway";

    cluster.forEach(p -> p.elector().campaign(group).subscribe());
    E2e.await(SETTLE, "initial leader", () -> distinctLeaders(cluster, group).size() == 1);
    String first = distinctLeaders(cluster, group).iterator().next();

    cluster.stream().filter(p -> p.member().id().equals(first)).findFirst().orElseThrow()
        .elector().resign(group).block(Duration.ofSeconds(5));

    E2e.await(SETTLE, "handoff to a different node",
        () -> {
          Set<String> ls = distinctLeaders(cluster, group);
          return ls.size() == 1 && !ls.contains(first);
        });
    assertTrue(!distinctLeaders(cluster, group).contains(first), "graceful resign handed off");
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

  private List<Prism> startCluster(int n) {
    int[] ports = new int[n];
    List<String> quorum = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      ports[i] = E2e.freePort();
      quorum.add("127.0.0.1:" + ports[i]);
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
