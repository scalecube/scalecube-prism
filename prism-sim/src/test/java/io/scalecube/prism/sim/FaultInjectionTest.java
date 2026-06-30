package io.scalecube.prism.sim;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Elector safety under fault injection (loss + partitions + skew)")
class FaultInjectionTest {

  private static final Duration TTL = Duration.ofMillis(1000);

  /**
   * Given a 5-node quorum subjected to 30% per-link message loss on top of random partitions and
   * clock skew, across many seeds,
   * When the god-view oracle is checked at every step,
   * Then safety holds: never two leaders and fencing epochs never regress.
   */
  @Test
  void safetyHoldsUnderHeavyMessageLoss() {
    for (long seed = 0; seed < 200; seed++) {
      SimCluster sim = new SimCluster(5, seed, TTL);
      sim.setMessageLoss(30);
      sim.campaignAll("gw");
      long lastEpoch = -1;
      for (int step = 0; step < 200; step++) {
        sim.chaosStep("gw");
        assertTrue(sim.trueLeaders("gw") <= 1, "two leaders at seed=" + seed + " step=" + step);
        long epoch = sim.currentLeaderEpoch("gw");
        if (epoch >= 0) {
          assertTrue(epoch >= lastEpoch, "epoch regressed at seed=" + seed + " step=" + step);
          lastEpoch = epoch;
        }
      }
    }
  }

  /**
   * Given a cluster that suffered heavy loss and partitions,
   * When the network fully heals,
   * Then liveness is restored — the cluster converges to exactly one leader.
   */
  @Test
  void recoversToOneLeaderAfterFaultsClear() {
    SimCluster sim = new SimCluster(5, 7L, TTL);
    sim.setMessageLoss(50);
    sim.campaignAll("gw");
    for (int step = 0; step < 100; step++) {
      sim.chaosStep("gw");
    }
    sim.heal();
    for (int step = 0; step < 12; step++) {
      sim.tickAll();
    }
    assertTrue(sim.trueLeaders("gw") == 1, "healed cluster must elect exactly one leader");
  }
}
