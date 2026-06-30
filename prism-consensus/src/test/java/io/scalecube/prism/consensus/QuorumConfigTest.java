package io.scalecube.prism.consensus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Self-electing quorum: configuration policy & single-member discipline")
class QuorumConfigTest {

  /**
   * Given a fresh quorum config seeded with three members,
   * When it is created,
   * Then current and previous both equal the seed at epoch 0, with majority 2.
   */
  @Test
  void seedsCurrentAndPreviousAtEpochZero() {
    QuorumConfig config = new QuorumConfig(List.of("n3", "n1", "n2"));
    assertEquals(List.of("n1", "n2", "n3"), config.members()); // normalized (sorted)
    assertEquals(List.of("n1", "n2", "n3"), config.previous());
    assertEquals(0, config.epoch());
    assertEquals(2, config.majority());
  }

  /**
   * Given a committed config,
   * When a single-member growth is committed at a higher epoch,
   * Then current advances, previous remembers the old config, and the epoch moves up.
   */
  @Test
  void commitsSingleMemberChangeAndRemembersPrevious() {
    QuorumConfig config = new QuorumConfig(List.of("n1", "n2", "n3"));
    assertTrue(config.commit(1, List.of("n1", "n2", "n3", "n4")));
    assertEquals(List.of("n1", "n2", "n3", "n4"), config.members());
    assertEquals(List.of("n1", "n2", "n3"), config.previous());
    assertEquals(1, config.epoch());
  }

  /**
   * Given a committed config,
   * When a commit arrives at an epoch not greater than the current one,
   * Then it is ignored (stale/duplicate) and the config is unchanged.
   */
  @Test
  void ignoresStaleOrDuplicateEpoch() {
    QuorumConfig config = new QuorumConfig(List.of("n1", "n2", "n3"));
    assertTrue(config.commit(5, List.of("n1", "n2", "n3", "n4")));
    assertFalse(config.commit(5, List.of("n1", "n2", "n3", "n4", "n5"))); // same epoch
    assertFalse(config.commit(3, List.of("n1", "n2"))); // lower epoch
    assertEquals(List.of("n1", "n2", "n3", "n4"), config.members());
    assertEquals(5, config.epoch());
  }

  /**
   * Given a committed config,
   * When a multi-member jump is attempted,
   * Then it is rejected — the single-member rule is enforced, not merely advised (the TLA+ unsafe
   * counterexample can never be reached through this API).
   */
  @Test
  void rejectsMultiMemberJump() {
    QuorumConfig config = new QuorumConfig(List.of("n0", "n1", "n2"));
    assertThrows(
        IllegalArgumentException.class, () -> config.commit(1, List.of("n0", "n3", "n4")));
    assertEquals(List.of("n0", "n1", "n2"), config.members()); // untouched
    assertEquals(0, config.epoch());
  }

  /**
   * Given various live counts and a configured target,
   * When the desired odd size is computed,
   * Then it follows the policy 1→1, 2→1, 3→3, 10→3 (target 3) and 4→3, 5→5 (target 5).
   */
  @Test
  void desiredSizeIsOddAndCappedByLiveAndTarget() {
    assertEquals(1, QuorumConfig.desiredSize(1, 3));
    assertEquals(1, QuorumConfig.desiredSize(2, 3));
    assertEquals(3, QuorumConfig.desiredSize(3, 3));
    assertEquals(3, QuorumConfig.desiredSize(10, 3));
    assertEquals(3, QuorumConfig.desiredSize(4, 5));
    assertEquals(5, QuorumConfig.desiredSize(5, 5));
    assertEquals(1, QuorumConfig.desiredSize(0, 3)); // never below 1
  }

  /**
   * Given a config that is below the desired size with live candidates available,
   * When the next step is planned,
   * Then it proposes adding exactly one (lowest-id) live candidate.
   */
  @Test
  void planGrowsTowardTargetOneMemberAtATime() {
    Optional<List<String>> step =
        QuorumConfig.planNextStep(
            List.of("n1"), Set.of("n1", "n2", "n3"), List.of("n1", "n2", "n3"), 3);
    assertEquals(Optional.of(List.of("n1", "n2")), step); // single add, lowest candidate
  }

  /**
   * Given a config containing a dead member,
   * When the next step is planned,
   * Then it proposes removing exactly that dead member (self-heal), one step.
   */
  @Test
  void planRemovesDeadMemberToSelfHeal() {
    Optional<List<String>> step =
        QuorumConfig.planNextStep(
            List.of("n1", "n2", "n3"), Set.of("n1", "n3", "n4"), List.of("n1", "n3", "n4"), 3);
    assertEquals(Optional.of(List.of("n1", "n3")), step); // drop dead n2 (single-member)
  }

  /**
   * Given a config already at the desired size and fully alive,
   * When the next step is planned,
   * Then no change is proposed.
   */
  @Test
  void planIsEmptyWhenHealthyAndRightSized() {
    Optional<List<String>> step =
        QuorumConfig.planNextStep(
            List.of("n1", "n2", "n3"), Set.of("n1", "n2", "n3"), List.of("n1", "n2", "n3"), 3);
    assertTrue(step.isEmpty());
  }

  /**
   * Given a config larger than the desired size with all members alive,
   * When the next step is planned,
   * Then it proposes removing exactly one (highest-id) member to shrink toward the target.
   */
  @Test
  void planShrinksTowardTarget() {
    Optional<List<String>> step =
        QuorumConfig.planNextStep(
            List.of("n1", "n2", "n3", "n4", "n5"),
            Set.of("n1", "n2", "n3", "n4", "n5"),
            List.of("n1", "n2", "n3", "n4", "n5"),
            3);
    assertEquals(Optional.of(List.of("n1", "n2", "n3", "n4")), step); // drop highest, single step
  }

  /**
   * Given the single-member predicate,
   * When comparing sets,
   * Then exactly-one add/remove is single-member; equal sets and two-member diffs are not.
   */
  @Test
  void singleMemberChangePredicate() {
    assertTrue(QuorumConfig.isSingleMemberChange(List.of("a", "b"), List.of("a", "b", "c")));
    assertTrue(QuorumConfig.isSingleMemberChange(List.of("a", "b", "c"), List.of("a", "b")));
    assertFalse(QuorumConfig.isSingleMemberChange(List.of("a", "b"), List.of("a", "b")));
    assertFalse(QuorumConfig.isSingleMemberChange(List.of("a", "b"), List.of("a", "c"))); // swap = 2
    assertFalse(QuorumConfig.isSingleMemberChange(List.of("a", "b"), List.of("a", "c", "d")));
  }

  /** A minimal in-memory {@link ConfigJournal} (the file-backed one lives in prism-persistence). */
  private static final class InMemoryConfigJournal implements ConfigJournal {
    private ConfigRecord latest;

    @Override
    public void append(ConfigRecord config) {
      if (latest == null || config.epoch() > latest.epoch()) {
        latest = config;
      }
    }

    @Override
    public Optional<ConfigRecord> load() {
      return Optional.ofNullable(latest);
    }
  }

  /**
   * Given a quorum that committed a chain of single-member changes through a durable journal,
   * When a fresh QuorumConfig is constructed from the same journal (a restart),
   * Then it resumes at the latest committed config and epoch — not the bootstrap seed C0 — so the
   * config epoch never regresses across a restart.
   */
  @Test
  void recoversCommittedConfigChainAcrossRestart() {
    InMemoryConfigJournal journal = new InMemoryConfigJournal();
    QuorumConfig config = new QuorumConfig(List.of("n0"), journal);
    assertTrue(config.commit(1, List.of("n0", "n1")));
    assertTrue(config.commit(2, List.of("n0", "n1", "n2")));
    assertTrue(config.commit(3, List.of("n1", "n2"))); // single-member shrink

    // Restart: a new config seeded with the original C0 must recover the committed chain.
    QuorumConfig recovered = new QuorumConfig(List.of("n0"), journal);
    assertEquals(3, recovered.epoch(), "epoch resumes, never regresses to 0");
    assertEquals(List.of("n1", "n2"), recovered.members(), "recovers the committed member set");
  }

  /**
   * Given a durable journal that already holds a committed config,
   * When a node restarts and then commits the next single-member step,
   * Then the step is relative to the recovered config (single-member from {n1,n2}), proving recovery
   * feeds back into the single-member discipline.
   */
  @Test
  void recoveredConfigContinuesSingleMemberChain() {
    InMemoryConfigJournal journal = new InMemoryConfigJournal();
    new QuorumConfig(List.of("n0", "n1", "n2"), journal).commit(1, List.of("n1", "n2"));

    QuorumConfig recovered = new QuorumConfig(List.of("n0", "n1", "n2"), journal);
    assertEquals(List.of("n1", "n2"), recovered.members());
    assertTrue(recovered.commit(2, List.of("n1", "n2", "n3")), "single-member from recovered config");
    assertEquals(2, recovered.epoch());
  }
}
