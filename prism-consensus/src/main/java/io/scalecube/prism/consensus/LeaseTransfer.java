package io.scalecube.prism.consensus;

/**
 * Transfers the fencing-epoch high-water to a member of the configuration (ADR-0015 §7.1). A member
 * must learn the current high-water epoch <b>before</b> it counts toward a quorum, or a new leader
 * could win a majority at a lower epoch — preserving mutual exclusion but regressing the fencing
 * token. {@code SelfElectingQuorum.tla} ({@code NoTokenRegression}) proves this must hold for a
 * <b>majority of the new configuration</b>, not just for joining members: a shrink that drops a
 * high-water holder can otherwise let a stale, lower-epoch lease regain a majority of the smaller
 * config and resurrect it.
 *
 * <p>Mechanically this is a {@code LeaseRequest.accept(highWater)} to the member's acceptor: it
 * adopts the record (free, same owner, or strictly-higher epoch over an expired lease), raising its
 * epoch floor. It never grants a live lease beyond what exists, since the record carries the
 * existing owner and expiry.
 */
@FunctionalInterface
public interface LeaseTransfer {

  /**
   * Pushes the high-water lease record to {@code member}'s acceptor.
   *
   * @param member the member's address
   * @param highWater the current highest-epoch lease record for the group
   * @return true if the member acknowledged and now holds (at least) the high-water epoch
   */
  boolean transfer(String member, LeaseRecord highWater);
}
