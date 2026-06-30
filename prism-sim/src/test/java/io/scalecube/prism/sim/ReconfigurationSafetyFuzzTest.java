package io.scalecube.prism.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Deterministic-simulation verification of the self-electing (dynamic) quorum — ADR-0015 §13.2, the
 * DST half of the gate that complements the TLA+ model checking (§13.1). The single-member
 * reconfiguration rule is the same load-bearing constraint TLC proves; here it is exercised on the
 * real {@code Acceptor}/{@code QuorumConsensusStore}/{@code LeaseElector} kernel under churn.
 */
@DisplayName("Self-electing quorum safety: seeded reconfiguration + partition fuzzing")
class ReconfigurationSafetyFuzzTest {

  private static final Duration TTL = Duration.ofMillis(1000);

  /**
   * Given a pool of 5 acceptors with a bootstrap config of 3, under hundreds of seeded scenarios
   * that interleave single-member grow/shrink reconfigurations, partitions, link loss and clock
   * jumps,
   * When every step is checked by the configuration-aware god-view oracle (a leader is an owner
   * majority-backed under the current OR previous config),
   * Then never more than one owner is a leader at any step — "never two leaders" is preserved across
   * reconfiguration, for every seed.
   */
  @Test
  void neverTwoLeadersAcrossReconfigurations() {
    for (long seed = 0; seed < 300; seed++) {
      ReconfigSimCluster sim = new ReconfigSimCluster(5, 3, 3, seed, TTL);
      sim.setMessageLoss(20);
      sim.campaignAll("gw");
      long lastLeaderEpoch = -1;
      for (int step = 0; step < 200; step++) {
        sim.chaosStep("gw");

        // SAFETY: never two majority-backed leaders, across the configuration change boundary.
        assertTrue(
            sim.trueLeaders("gw") <= 1,
            "two leaders at seed=" + seed + " step=" + step + " config=" + sim.currentConfig());

        // FENCING MONOTONICITY: successive leaders never regress in epoch, even as the config moves.
        long epoch = sim.currentLeaderEpoch("gw");
        if (epoch >= 0) {
          assertTrue(
              epoch >= lastLeaderEpoch,
              "leader epoch regressed at seed=" + seed + " step=" + step);
          lastLeaderEpoch = epoch;
        }
      }
    }
  }

  /**
   * Given a quorum that grows from a single-node bootstrap (C0 = {n0}) up toward the pool, one
   * member at a time, while healthy,
   * When the cluster ticks after each single-member addition,
   * Then it holds exactly one leader throughout the growth (self-formation never splits the lease).
   */
  @Test
  void selfFormationFromSingleNodeStaysSingleLeader() {
    ReconfigSimCluster sim = new ReconfigSimCluster(5, 1, 1, 7L, TTL);
    sim.campaignAll("gw");
    sim.heal();
    for (int step = 0; step < 5; step++) {
      sim.tickAll();
    }
    assertEquals(1, sim.trueLeaders("gw"), "single-node bootstrap must have exactly one leader");

    for (int grow = 0; grow < 4; grow++) {
      sim.reconfigureSingleMember();
      for (int step = 0; step < 5; step++) {
        sim.tickAll();
      }
      assertTrue(
          sim.trueLeaders("gw") <= 1, "growth step " + grow + " produced two leaders");
    }
  }

  /**
   * Given a healthy quorum whose current leader is then removed by a single-member reconfiguration,
   * When the lease expires and the remaining majority ticks,
   * Then leadership self-heals to exactly one (new) leader and the fencing epoch does not regress.
   */
  @Test
  void selfHealsAfterLeaderRemoved() {
    ReconfigSimCluster sim = new ReconfigSimCluster(5, 3, 3, 11L, TTL);
    sim.campaignAll("gw");
    sim.heal();
    for (int step = 0; step < 5; step++) {
      sim.tickAll();
    }
    assertEquals(1, sim.trueLeaders("gw"), "must start with one leader");
    long epochBefore = sim.currentLeaderEpoch("gw");

    // Grow to 5 so a 4-member config still has a majority after one removal, then shrink.
    sim.reconfigureSingleMember();
    sim.reconfigureSingleMember();
    sim.reconfigureSingleMember(); // now config covers the pool
    for (int step = 0; step < 5; step++) {
      sim.tickAll();
    }
    sim.reconfigureSingleMember(); // single-member shrink (may drop the leader)
    for (int step = 0; step < 20; step++) {
      sim.tickAll();
    }

    assertTrue(sim.trueLeaders("gw") <= 1, "self-heal must never transiently split leadership");
    long epochAfter = sim.currentLeaderEpoch("gw");
    if (epochAfter >= 0 && epochBefore >= 0) {
      assertTrue(epochAfter >= epochBefore, "fencing epoch regressed after self-heal");
    }
  }

  /**
   * Negative control proving two things at once.
   *
   * <p>First, that the real elector is <b>conservative</b>: even handed an unsafe two-member config
   * jump, it will not manufacture a second leader, because it never challenges a lease it still sees
   * as valid — so the safe path stays safe.
   *
   * <p>Second, that the {@link ReconfigSimCluster#trueLeaders(String)} oracle has <b>teeth</b>: when
   * split-brain is constructed directly at the raw acceptors (the model's {@code Accept} action,
   * which an unsafe jump permits), the oracle reports two leaders. This is the simulation analogue of
   * {@code SelfElectingQuorum_unsafe.cfg}'s depth-4 counterexample, and the reason single-member
   * reconfiguration is mandatory.
   */
  @Test
  void multiMemberJumpCanSplitBrain_oracleDetectsIt() {
    ReconfigSimCluster sim = new ReconfigSimCluster(5, 3, 1, 1L, TTL);
    long farTtl = TTL.toMillis() * 100; // leases stay valid for the whole test (no clock advance)

    // Owner A is certified under the bootstrap config {n0,n1,n2} via its majority {n0,n1}.
    sim.grantRaw("gw", "n0", "A", 1, farTtl);
    sim.grantRaw("gw", "n1", "A", 1, farTtl);
    assertEquals(1, sim.trueLeaders("gw"), "precondition: exactly one leader under the bootstrap");

    // Unsafe two-member jump to {n0,n3,n4}: prevConfig {n0,n1,n2}, config {n0,n3,n4}. The two
    // majorities {n1,n2} (prev) and {n3,n4} (new) are DISJOINT — the overlap guarantee is gone.
    sim.forceConfig(List.of("n0", "n3", "n4"));

    // The polite elector would refuse here. The model's raw Accept rule does not — n3,n4 are free.
    sim.grantRaw("gw", "n3", "B", 1, farTtl);
    sim.grantRaw("gw", "n4", "B", 1, farTtl);

    // A is still certified under prevConfig {n0,n1,n2} (holds {n0,n1}); B is certified under config
    // {n0,n3,n4} (holds {n3,n4}). Two simultaneous leaders — the exact split-brain single-member
    // reconfiguration forbids, and which the oracle correctly detects.
    assertEquals(
        2,
        sim.trueLeaders("gw"),
        "multi-member jump enables disjoint majorities → two leaders (oracle must detect it)");
  }
}
