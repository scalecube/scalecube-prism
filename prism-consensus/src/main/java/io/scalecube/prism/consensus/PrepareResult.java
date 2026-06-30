package io.scalecube.prism.consensus;

import java.util.Optional;

/**
 * The outcome of a Paxos PREPARE (phase 1) round across the quorum: whether a <b>majority
 * promised</b> the proposer's ballot, the highest-ballot lease those acceptors already accepted
 * (the value to respect — re-propose if still a valid claim, else propose itself), and the highest
 * <b>promised</b> ballot any acceptor reported (so a rejected proposer retries above it).
 */
public final class PrepareResult {

  private final boolean majorityPromised;
  private final LeaseRecord highestAccepted; // nullable
  private final long highestPromised;

  private PrepareResult(boolean majority, LeaseRecord highestAccepted, long highestPromised) {
    this.majorityPromised = majority;
    this.highestAccepted = highestAccepted;
    this.highestPromised = highestPromised;
  }

  /**
   * Builds a prepare result.
   *
   * @param majorityPromised whether a majority promised the ballot
   * @param highestAccepted the highest-ballot lease seen across the responders, or null
   * @param highestPromised the highest reserved ballot reported by any responder
   * @return the result
   */
  public static PrepareResult of(
      boolean majorityPromised, LeaseRecord highestAccepted, long highestPromised) {
    return new PrepareResult(majorityPromised, highestAccepted, highestPromised);
  }

  /** Whether a majority of the quorum promised this proposer's ballot. */
  public boolean majorityPromised() {
    return majorityPromised;
  }

  /** The highest-ballot lease already accepted across the responders, if any. */
  public Optional<LeaseRecord> highestAccepted() {
    return Optional.ofNullable(highestAccepted);
  }

  /** The highest reserved (promised) ballot any responder reported — retry strictly above it. */
  public long highestPromised() {
    return highestPromised;
  }
}
