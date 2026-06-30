package io.scalecube.prism.sim;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * At-scale stress for <b>multi-group</b> election: many logical groups over one cluster, with
 * randomly-ordered campaigns and continuous <b>node kill/revive churn</b>, partitions, link loss and
 * clock skew. The point is to attack the safety invariant from every interleaving an adversary could
 * produce — random membership per group, random campaign order, and random kill/join order — and show
 * it never breaks.
 *
 * <p>Per-group safety: at most one leader per group at every step (an acceptor holds one lease per
 * group; two majorities of {@code n} can't both exist — pigeonhole). Plus per-group fencing-epoch
 * monotonicity. Both must hold for <em>every</em> group across all seeds.
 */
@DisplayName("Multi-group election at scale: random orders + node kill/join churn")
class MultiGroupChurnStressTest {

  private static final Duration TTL = Duration.ofMillis(1000);
  private static final int NODES = 7;
  private static final int GROUPS = 8;
  private static final int SEEDS = 60;
  private static final int STEPS = 80;

  /**
   * Given one 7-node cluster running 8 logical groups, each with a random participant subset and all
   * campaigns issued in random order,
   * When the cluster is stressed for many steps with random partitions, loss, clock jumps, and random
   * node kill/revive churn (also in random order),
   * Then every group always has at most one leader and never regresses its fencing epoch — across 60
   * seeded runs.
   */
  @Test
  void neverTwoLeadersPerGroupUnderChurnAndRandomOrders() {
    for (long seed = 0; seed < SEEDS; seed++) {
      Random rnd = new Random(seed);
      SimCluster sim = new SimCluster(NODES, seed, TTL);
      sim.setMessageLoss(15);
      List<String> members = sim.members();

      // Build groups with random membership, and a single randomly-ordered campaign workload.
      List<String> groups = new ArrayList<>();
      List<String[]> campaigns = new ArrayList<>();
      for (int g = 0; g < GROUPS; g++) {
        String group = "g" + g;
        groups.add(group);
        List<String> shuffled = new ArrayList<>(members);
        Collections.shuffle(shuffled, rnd);
        int size = 3 + rnd.nextInt(NODES - 2); // 3..NODES participants
        for (String node : shuffled.subList(0, size)) {
          campaigns.add(new String[] {node, group});
        }
      }
      Collections.shuffle(campaigns, rnd); // RANDOM ORDER across all (node, group) campaigns
      for (String[] c : campaigns) {
        sim.campaign(c[0], c[1]);
      }

      long[] lastEpoch = new long[GROUPS];
      java.util.Arrays.fill(lastEpoch, -1);

      for (int step = 0; step < STEPS; step++) {
        churnInRandomOrder(sim, rnd);
        sim.chaosStep();

        for (int g = 0; g < GROUPS; g++) {
          String group = groups.get(g);
          // SAFETY: never two leaders for this group, under any interleaving.
          assertTrue(
              sim.trueLeaders(group) <= 1,
              "two leaders in " + group + " at seed=" + seed + " step=" + step
                  + " alive=" + sim.aliveMembers());

          // FENCING MONOTONICITY: this group's committed leader epoch never regresses.
          long epoch = sim.currentLeaderEpoch(group);
          if (epoch >= 0) {
            assertTrue(
                epoch >= lastEpoch[g],
                "epoch regressed in " + group + " at seed=" + seed + " step=" + step);
            lastEpoch[g] = epoch;
          }
        }
      }
    }
  }

  /** Randomly kills and/or revives nodes, with the two actions themselves applied in random order. */
  private void churnInRandomOrder(SimCluster sim, Random rnd) {
    List<Runnable> actions = new ArrayList<>();
    if (rnd.nextInt(100) < 30 && sim.aliveMembers().size() > 1) {
      List<String> alive = sim.aliveMembers();
      actions.add(() -> sim.kill(alive.get(rnd.nextInt(alive.size()))));
    }
    if (rnd.nextInt(100) < 35 && !sim.downMembers().isEmpty()) {
      List<String> dead = sim.downMembers();
      actions.add(() -> sim.revive(dead.get(rnd.nextInt(dead.size()))));
    }
    Collections.shuffle(actions, rnd); // kill-then-revive or revive-then-kill, at random
    for (Runnable a : actions) {
      a.run();
    }
  }
}
