package io.scalecube.prism.consensus;

/**
 * A request to an acceptor: read the current lease (GET), reserve a ballot (PREPARE), or propose a
 * lease (ACCEPT) — the three phases of the single-decree Paxos used for leadership (ADR-0012). A
 * PREPARE is a GET that also carries a {@code prepareBallot} the acceptor promises not to accept
 * below. A plain DTO; it crosses the wire via {@link LeaseCodec}, never Java serialization.
 */
public final class LeaseRequest {

  private final boolean get;
  private final String group;
  private final String owner;
  private final long epoch;
  private final long expiresAt;
  private final long prepareBallot; // > 0 ⇒ this GET also PREPAREs (reserves) the ballot

  private LeaseRequest(
      boolean get, String group, String owner, long epoch, long expiresAt, long prepareBallot) {
    this.get = get;
    this.group = group;
    this.owner = owner;
    this.epoch = epoch;
    this.expiresAt = expiresAt;
    this.prepareBallot = prepareBallot;
  }

  /**
   * Builds an ACCEPT request proposing a lease.
   *
   * @param lease the proposed lease
   * @return the request
   */
  public static LeaseRequest accept(LeaseRecord lease) {
    return new LeaseRequest(
        false, lease.group(), lease.owner(), lease.epoch(), lease.expiresAt(), 0);
  }

  /**
   * Builds a GET request reading the current lease.
   *
   * @param group the group
   * @return the request
   */
  public static LeaseRequest get(String group) {
    return new LeaseRequest(true, group, "", 0, 0, 0);
  }

  /**
   * Builds a PREPARE request: a read that also reserves {@code ballot} (the acceptor promises not
   * to accept below it). Paxos phase-1, which orders competing proposers.
   *
   * @param group the group
   * @param ballot the ballot to reserve (must be &gt; 0)
   * @return the request
   */
  public static LeaseRequest prepare(String group, long ballot) {
    return new LeaseRequest(true, group, "", 0, 0, ballot);
  }

  public boolean isGet() {
    return get;
  }

  /** The reserved ballot for a PREPARE, or 0 for a plain GET. */
  public long prepareBallot() {
    return prepareBallot;
  }

  public String group() {
    return group;
  }

  /**
   * The proposed lease (ACCEPT requests only).
   *
   * @return the proposed lease
   */
  public LeaseRecord toLease() {
    return new LeaseRecord(group, owner, epoch, expiresAt);
  }
}
