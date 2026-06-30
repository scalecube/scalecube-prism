package io.scalecube.prism.consensus;

import java.util.Optional;

/**
 * A linearizable key→{@link LeaseRecord} register with compare-and-set — the minimal
 * consensus primitive the singleton elector needs. The {@code CONSENSUS}-tier guarantee (a single
 * agreed value across the cluster) is what makes "never two leaders" possible.
 *
 * <p>Implementations:
 *
 * <ul>
 *   <li>{@link InMemoryConsensusStore} — in-process, linearizable; for tests and single-node only.
 *   <li>{@link QuorumConsensusStore} — the distributed implementation: single-decree Paxos over a
 *       majority quorum of {@link Acceptor}s (ADR-0012).
 * </ul>
 */
public interface ConsensusStore {

  /**
   * Returns the current lease for a group, if any.
   *
   * @param group the group key
   * @return the current lease, or empty
   */
  Optional<LeaseRecord> get(String group);

  /**
   * Commits {@code next} if it is admissible, returning whether it was committed.
   *
   * <p><b>The two implementations differ in how {@code expected} is used — read this.</b>
   *
   * <ul>
   *   <li>{@link InMemoryConsensusStore}: a true compare-and-set — it commits only if the current
   *       value {@code equals(expected)} (classic optimistic concurrency).
   *   <li>{@link QuorumConsensusStore}: {@code expected} is <b>advisory, effectively ignored</b>.
   *       The authoritative decision is the acceptor rule applied across a majority (free, or
   *       same-owner renewal at >= epoch, or expired takeover at a strictly higher epoch, under the
   *       Paxos promise). Two callers must never both succeed for the same epoch regardless of
   *       {@code expected}.
   * </ul>
   *
   * <p>A {@code null} {@code expected} means "absent". The quorum store rejects a {@code null}
   * {@code next} (a lease is released by writing an expired record, not by clearing it).
   *
   * @param group the group key
   * @param expected the value the caller believes is current (honored by the in-memory store;
   *     advisory in the quorum store)
   * @param next the value to commit
   * @return true if {@code next} was committed
   */
  boolean compareAndSet(String group, LeaseRecord expected, LeaseRecord next);

  /**
   * Paxos PREPARE (phase 1): reserve {@code ballot} across the quorum and learn the highest lease
   * already accepted. Used by the elector to acquire safely under contention — only after a
   * majority promises does it ACCEPT (via {@link #compareAndSet}); the promise both orders
   * proposers (so a same-epoch split resolves) and reveals any value to respect. Default
   * (single, linearizable store) trivially promises and returns its current value.
   *
   * @param group the group key
   * @param ballot the ballot to reserve (unique per proposer, monotonically increasing)
   * @return whether a majority promised, plus the highest accepted lease seen
   */
  default PrepareResult prepare(String group, long ballot) {
    return PrepareResult.of(true, get(group).orElse(null), 0L);
  }
}
