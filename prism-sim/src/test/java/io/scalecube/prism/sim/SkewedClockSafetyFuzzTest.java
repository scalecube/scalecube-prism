package io.scalecube.prism.sim;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Elector safety under per-acceptor clock skew")
class SkewedClockSafetyFuzzTest {

  private static final Duration TTL = Duration.ofMillis(1000);

  /**
   * Given a 5-node quorum where every acceptor runs on a different (skewed) clock — up to a full TTL
   * apart, so nodes genuinely disagree on whether a lease has expired,
   * When hundreds of seeded scenarios pile partitions and clock jumps on top of that skew, and every
   * acceptor judges expiry by its OWN clock (the god-view oracle does too),
   * Then never more than one owner is ever majority-backed and the fencing epoch never regresses —
   * safety is clock-independent (quorum intersection + monotone fencing), so it must survive any
   * amount of skew. This directly exercises the fencing-covered "zombie former leader" window.
   */
  @Test
  void neverTwoLeadersDespiteClockSkew() {
    for (long seed = 0; seed < 300; seed++) {
      SimCluster sim = new SimCluster(5, seed, TTL);
      sim.setClockSkew(TTL.toMillis()); // acceptors up to ±1 TTL out of step
      sim.campaignAll("gw");
      long lastLeaderEpoch = -1;
      for (int step = 0; step < 200; step++) {
        sim.chaosStep("gw");

        // SAFETY: never two majority-backed leaders, even with disagreeing clocks.
        assertTrue(sim.trueLeaders("gw") <= 1, "two leaders at seed=" + seed + " step=" + step);

        // FENCING MONOTONICITY: successive leaders never regress in epoch.
        long epoch = sim.currentLeaderEpoch("gw");
        if (epoch >= 0) {
          assertTrue(
              epoch >= lastLeaderEpoch, "leader epoch regressed at seed=" + seed + " step=" + step);
          lastLeaderEpoch = epoch;
        }
      }
    }
  }
}
