package io.scalecube.prism.registry;

/**
 * Signals that a {@link ServiceRegistry#lookupQuorum(String) quorum read} could not reach a
 * majority of members within its call budget, so it refused to answer from a possibly-stale local
 * view. This is the {@code QUORUM} tier honoring its CAP bargain: under a partition the minority
 * side becomes unavailable for a fresh read rather than return a value it cannot vouch for. Callers
 * may fall back to {@link ServiceRegistry#lookup(String)} for an always-available (possibly stale)
 * answer.
 */
public final class QuorumUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception with a detail message.
   *
   * @param message human-readable detail (service, responders reached, majority required)
   */
  public QuorumUnavailableException(String message) {
    super(message);
  }
}
