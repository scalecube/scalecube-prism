package io.scalecube.prism.consensus;

import java.util.Optional;

/**
 * An acceptor's reply: whether it accepted, and the lease it currently holds (if any). A plain DTO;
 * it crosses the wire via {@link LeaseCodec}, never Java serialization.
 */
public final class LeaseResponse {

  private final boolean ok;
  private final LeaseRecord current; // nullable
  private final long promised; // the acceptor's reserved ballot (PREPARE replies); 0 otherwise

  private LeaseResponse(boolean ok, LeaseRecord current, long promised) {
    this.ok = ok;
    this.current = current;
    this.promised = promised;
  }

  /**
   * Builds a response (with no promised ballot — for GET/ACCEPT replies).
   *
   * @param ok whether the acceptor accepted/promised the proposal (always true for GET)
   * @param current the acceptor's current lease, or null
   * @return the response
   */
  public static LeaseResponse of(boolean ok, LeaseRecord current) {
    return new LeaseResponse(ok, current, 0L);
  }

  /**
   * Builds a PREPARE reply carrying the acceptor's reserved ballot, so a rejected proposer learns
   * how high it must go to be promised.
   *
   * @param ok whether the acceptor promised the proposer's ballot
   * @param current the acceptor's current lease, or null
   * @param promised the acceptor's currently reserved ballot
   * @return the response
   */
  public static LeaseResponse promise(boolean ok, LeaseRecord current, long promised) {
    return new LeaseResponse(ok, current, promised);
  }

  /** A negative response used when a peer is unreachable. */
  public static LeaseResponse fail() {
    return new LeaseResponse(false, null, 0L);
  }

  public boolean ok() {
    return ok;
  }

  /** The acceptor's reserved (promised) ballot — relevant on PREPARE replies; 0 otherwise. */
  public long promised() {
    return promised;
  }

  /**
   * The acceptor's current lease, if any.
   *
   * @return the current lease
   */
  public Optional<LeaseRecord> currentLease() {
    return Optional.ofNullable(current);
  }
}
