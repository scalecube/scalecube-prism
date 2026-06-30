package io.scalecube.prism.sim;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Elector safety: seeded fuzzing over partitions & clock skew")
class ElectorSafetyFuzzTest {

  private static final Duration TTL = Duration.ofMillis(1000);

  /**
   * Given a 5-node quorum under hundreds of seeded scenarios of random partitions, clock jumps and
   * re-campaigns,
   * When every step is checked by the god-view oracle,
   * Then never more than one owner is ever majority-backed (no two leaders) — across all seeds.
   */
  @Test
  void neverTwoLeadersAcrossManySeeds() {
    for (long seed = 0; seed < 300; seed++) {
      SimCluster sim = new SimCluster(5, seed, TTL);
      sim.campaignAll("gw");
      long lastLeaderEpoch = -1;
      for (int step = 0; step < 200; step++) {
        sim.chaosStep("gw");

        // SAFETY: never two majority-backed leaders at once.
        assertTrue(
            sim.trueLeaders("gw") <= 1, "two leaders at seed=" + seed + " step=" + step);

        // FENCING MONOTONICITY: successive leaders never regress in epoch.
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
   * Given a healthy, fully-connected quorum (no partitions) where all nodes campaign,
   * When the cluster ticks for a while,
   * Then it converges to exactly one leader (liveness).
   */
  @Test
  void convergesToOneLeaderWhenHealthy() {
    SimCluster sim = new SimCluster(5, 42L, TTL);
    sim.campaignAll("gw");
    sim.heal();
    for (int step = 0; step < 10; step++) {
      sim.tickAll();
    }
    assertTrue(sim.trueLeaders("gw") == 1, "healthy cluster must elect exactly one leader");
  }
}
