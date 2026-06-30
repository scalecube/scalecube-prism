package io.scalecube.prism.consensus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

@DisplayName("Self-electing quorum: leader-driven single-member reconfiguration")
class ReconfigurationManagerTest {

  private static final String GROUP = "gw";
  private static final long TTL = 10_000L;
  private static final List<String> POOL = List.of("n0", "n1", "n2", "n3", "n4");

  private final AtomicLong clock = new AtomicLong(1000);
  private final Map<String, Acceptor> acceptors = new HashMap<>();

  @BeforeEach
  void setup() {
    acceptors.clear();
    for (String n : POOL) {
      acceptors.put(n, new Acceptor());
    }
  }

  /** Routes peer calls straight to the target acceptor (transport-free, deterministic). */
  private PeerCaller caller() {
    return (peer, req) -> Mono.just(acceptors.get(peer).handle(req, clock.get()));
  }

  private QuorumConsensusStore storeFor(String self, QuorumConfig config) {
    return new QuorumConsensusStore(
        self, config::members, acceptors.get(self), caller(), clock::get, Duration.ofMillis(50));
  }

  /** Pushes the high-water record straight onto a member's acceptor (the §7.1 transfer). */
  private LeaseTransfer transfer() {
    return (member, highWater) ->
        acceptors.get(member).handle(LeaseRequest.accept(highWater), clock.get()).ok();
  }

  /** Makes {@code self} the leader by acquiring the lease over its current config. */
  private void electLeader(String self, QuorumConfig config) {
    QuorumConsensusStore store = storeFor(self, config);
    long now = clock.get();
    LeaseRecord lease = new LeaseRecord(GROUP, self, 1, now + TTL);
    assertTrue(store.compareAndSet(GROUP, null, lease), "precondition: leader must acquire");
  }

  /** A shared in-memory replicator: remembers the highest committed config; always reachable. */
  private static final class MemReplicator implements ConfigReplicator {
    private ConfigRecord latest;

    @Override
    public boolean commit(ConfigRecord record, List<String> currentConfig) {
      if (latest == null || record.epoch() > latest.epoch()) {
        latest = record;
      }
      return true; // a majority is assumed reachable in this unit test
    }

    @Override
    public Optional<ConfigRecord> latestKnown() {
      return Optional.ofNullable(latest);
    }
  }

  private ReconfigurationManager manager(
      String self, QuorumConfig config, ConfigReplicator replicator, Set<String> live, int target) {
    return new ReconfigurationManager(
        self,
        GROUP,
        config,
        storeFor(self, config),
        replicator,
        transfer(),
        () -> live,
        () -> POOL,
        target,
        clock::get);
  }

  /**
   * Given a single-node bootstrap config {n0} where n0 is the leader and four more members are live,
   * When the reconfiguration manager ticks,
   * Then the quorum grows itself one member at a time to the target size 3, the joining members
   * receive the fencing high-water (§7.1), and n0 remains the single leader throughout.
   */
  @Test
  void growsFromSingleNodeBootstrapToTarget() {
    QuorumConfig config = new QuorumConfig(List.of("n0"));
    electLeader("n0", config);
    Set<String> live = new HashSet<>(POOL);
    ReconfigurationManager mgr = manager("n0", config, new MemReplicator(), live, 3);

    for (int i = 0; i < 6; i++) {
      mgr.tick();
    }

    assertEquals(List.of("n0", "n1", "n2"), config.members(), "should self-form to target 3");
    assertEquals(2, config.epoch(), "two single-member growth steps");
    // §7.1: the joiners learned the high-water lease (epoch floor raised).
    for (String joined : List.of("n1", "n2")) {
      Optional<LeaseRecord> held =
          acceptors.get(joined).handle(LeaseRequest.get(GROUP), clock.get()).currentLease();
      assertTrue(held.isPresent() && held.get().epoch() >= 1, joined + " must hold high-water");
    }
    // n0 is still the unique leader under the grown config.
    Optional<LeaseRecord> lease = storeFor("n0", config).get(GROUP);
    assertTrue(lease.isPresent() && lease.get().owner().equals("n0"), "n0 stays leader");
  }

  /**
   * Given a healthy three-member quorum {n0,n1,n2} led by n0, with n1 permanently dead and a live
   * replacement available,
   * When the manager ticks,
   * Then it self-heals: drops the dead member and grows back toward target, one single-member step at
   * a time, never including the dead member in the final config.
   */
  @Test
  void selfHealsByReplacingDeadMember() {
    QuorumConfig config = new QuorumConfig(List.of("n0", "n1", "n2"));
    electLeader("n0", config);
    Set<String> live = new HashSet<>(Set.of("n0", "n2", "n3", "n4")); // n1 dead
    ReconfigurationManager mgr = manager("n0", config, new MemReplicator(), live, 3);

    for (int i = 0; i < 6; i++) {
      mgr.tick();
    }

    assertEquals(3, config.members().size(), "heals back to target size");
    assertFalse(config.members().contains("n1"), "dead member is evicted");
    assertTrue(config.members().containsAll(List.of("n0", "n2")), "live members retained");
    Optional<LeaseRecord> lease = storeFor("n0", config).get(GROUP);
    assertTrue(lease.isPresent() && lease.get().owner().equals("n0"), "n0 stays leader");
  }

  /**
   * Given a quorum where n0 is the leader,
   * When a non-leader (n1) runs its reconfiguration manager,
   * Then it never originates a configuration change (only the leader reconfigures).
   */
  @Test
  void nonLeaderNeverReconfigures() {
    QuorumConfig leaderConfig = new QuorumConfig(List.of("n0", "n1", "n2"));
    electLeader("n0", leaderConfig);
    QuorumConfig followerConfig = new QuorumConfig(List.of("n0", "n1", "n2"));
    ReconfigurationManager follower =
        manager("n1", followerConfig, new MemReplicator(), new HashSet<>(POOL), 3);

    for (int i = 0; i < 5; i++) {
      follower.tick();
    }

    assertEquals(0, followerConfig.epoch(), "a non-leader must not originate reconfiguration");
    assertEquals(List.of("n0", "n1", "n2"), followerConfig.members());
  }

  /**
   * Given a leader that has grown the quorum and a lagging follower sharing the replicator,
   * When the follower ticks,
   * Then it catches up to the latest committed configuration (convergence), without originating it.
   */
  @Test
  void followerCatchesUpToLatestCommittedConfig() {
    MemReplicator replicator = new MemReplicator();
    QuorumConfig leaderConfig = new QuorumConfig(List.of("n0"));
    electLeader("n0", leaderConfig);
    ReconfigurationManager leader =
        manager("n0", leaderConfig, replicator, new HashSet<>(POOL), 3);
    for (int i = 0; i < 6; i++) {
      leader.tick();
    }
    assertEquals(List.of("n0", "n1", "n2"), leaderConfig.members());

    QuorumConfig followerConfig = new QuorumConfig(List.of("n0"));
    ReconfigurationManager follower =
        manager("n1", followerConfig, replicator, new HashSet<>(POOL), 3);
    follower.tick(); // catch-up only (n1 is not leader)

    assertEquals(leaderConfig.epoch(), followerConfig.epoch(), "follower converges in epoch");
    assertEquals(leaderConfig.members(), followerConfig.members(), "follower adopts latest config");
  }

  /**
   * Given leader n0 committed at fencing epoch 3 on a majority {n0,n3,n4} of a five-member config,
   * with stragglers {n1,n2} still holding n0's OLDER epoch-1 lease,
   * When the manager shrinks the quorum toward target 3 — which drops the high-water holders n3,n4 —
   * Then the committed fencing epoch never regresses: §7.1's high-water carry re-establishes epoch 3
   * on a majority of each new config before the old one is retired (the safety obligation proven by
   * SelfElectingQuorum.tla, NoTokenRegression). With the previous joiner-only transfer this regressed
   * to epoch 1 — a stale lower-epoch lease resurrected by the shrink.
   */
  @Test
  void shrinkDoesNotRegressFencingEpoch() {
    final long now = clock.get();
    for (String n : List.of("n0", "n3", "n4")) {
      acceptors.get(n).handle(LeaseRequest.accept(new LeaseRecord(GROUP, "n0", 3, now + TTL)), now);
    }
    for (String n : List.of("n1", "n2")) {
      acceptors.get(n).handle(LeaseRequest.accept(new LeaseRecord(GROUP, "n0", 1, now + TTL)), now);
    }
    QuorumConfig config = new QuorumConfig(POOL); // {n0..n4}; n0 is the committed leader at epoch 3
    ReconfigurationManager mgr = manager("n0", config, new MemReplicator(), new HashSet<>(POOL), 3);

    assertEquals(3, committedEpoch(config), "precondition: committed fencing epoch is 3");
    for (int i = 0; i < 8; i++) {
      mgr.tick();
      assertTrue(committedEpoch(config) >= 3, "fencing epoch must never regress (step " + i + ")");
    }
    assertEquals(3, config.members().size(), "shrinks to target size 3");
    assertTrue(committedEpoch(config) >= 3, "final committed fencing epoch did not regress");
  }

  /** Highest fencing epoch a majority of {@code config} currently holds for owner n0, or -1. */
  private long committedEpoch(QuorumConfig config) {
    final long now = clock.get();
    final List<Long> epochs = new ArrayList<>();
    for (String n : config.members()) {
      acceptors
          .get(n)
          .handle(LeaseRequest.get(GROUP), now)
          .currentLease()
          .filter(l -> l.owner().equals("n0") && !l.isExpired(now))
          .ifPresent(l -> epochs.add(l.epoch()));
    }
    final int majority = config.members().size() / 2 + 1;
    if (epochs.size() < majority) {
      return -1;
    }
    epochs.sort((a, b) -> Long.compare(b, a)); // descending
    return epochs.get(majority - 1); // highest epoch held by at least a majority
  }
}
