package io.scalecube.prism.sim;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Deterministic-simulation verification that drives the <b>real</b> reconfiguration code —
 * {@link io.scalecube.prism.consensus.ReconfigurationManager} with a real
 * {@link io.scalecube.prism.consensus.ConfigReplicator}/{@link io.scalecube.prism.consensus.LeaseTransfer}
 * over the real acceptor/store/elector kernel — not a harness reimplementation (review finding F2).
 *
 * <p>{@link ReconfigurationSafetyFuzzTest} validates the reconfiguration <em>rule</em> on a
 * harness-driven config swap; this validates the shipped <em>multi-step rollout</em>: each node runs
 * its own {@code ReconfigurationManager.tick()} (catch-up, leader-only single-member planning, the
 * §7.1 high-water carry to a majority of the new config, dissemination, local commit) concurrently,
 * under staggered, lossy delivery, clean network partitions, and kill/revive churn, with an
 * independently-running elector. Across hundreds of seeds the god-view oracle (against the single
 * authoritative committed chain) must never observe two leaders, a forked config chain, or a
 * regressed fencing epoch.
 *
 * <p>This driver earned its keep: with partitions enabled it caught a <b>real safety bug</b> —
 * {@code QuorumConsensusStore} counted the local acceptor's vote even when the node was <em>not</em> a
 * member of the current config, so a non-member proposer (a node lagging or reconfigured out) could
 * win a lease with only a <em>minority</em> of real members and fork a reconfiguration. Fixed by
 * counting self only when {@code members.contains(self)}; regression test in
 * {@code QuorumConsensusStoreTest#nonMemberProposerCannotWinWithMinorityOfMembers}.
 */
@DisplayName("Self-electing quorum safety: REAL reconfiguration manager under seeded chaos")
class RealReconfigSafetyFuzzTest {

  private static final Duration TTL = Duration.ofMillis(1000);

  /**
   * Given a 5-node pool (bootstrap config of 3, target 3) whose real reconfiguration managers and
   * electors run concurrently under clean partitions, 20% link loss, clock jumps and kill/revive
   * churn (down toward a single survivor),
   * When the god-view oracle is checked at every step across many seeds,
   * Then never two majority-backed leaders, never a forked config chain, and the committed fencing
   * epoch never regresses — the real, non-atomic reconfiguration rollout preserves the safety the
   * model proves for the rule.
   */
  @Test
  void realReconfigurationNeverSplitsLeadershipOrRegressesFencing() {
    for (long seed = 0; seed < 200; seed++) {
      RealReconfigSimCluster sim = new RealReconfigSimCluster(5, 3, 3, seed, TTL);
      sim.setMessageLoss(20);
      sim.campaignAll();
      long lastEpoch = -1;
      for (int step = 0; step < 150; step++) {
        sim.chaosStep();

        assertTrue(
            sim.trueLeaders() <= 1, "two leaders at seed=" + seed + " step=" + step);

        // INTEGRITY: the committed config chain never forks (one config per epoch).
        assertTrue(
            !sim.configForkDetected(), "config chain forked at seed=" + seed + " step=" + step);

        long epoch = sim.committedEpoch();
        if (epoch >= 0) {
          assertTrue(
              epoch >= lastEpoch,
              "committed fencing epoch regressed at seed=" + seed + " step=" + step
                  + " (" + lastEpoch + " -> " + epoch + ")");
          lastEpoch = epoch;
        }
      }
    }
  }
}
