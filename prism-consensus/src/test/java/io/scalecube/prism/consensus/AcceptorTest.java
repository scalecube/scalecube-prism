package io.scalecube.prism.consensus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The acceptor accept rule — the safety kernel — focusing on the <b>rejections</b> that make "never
 * two leaders" and fencing monotonicity hold. A free acceptor accepts; the current owner may renew at
 * an equal-or-higher epoch; a different owner may take over only if the lease is expired AND the epoch
 * is strictly higher. Everything else must be refused.
 */
@DisplayName("Acceptor accept rule: rejections (the safety kernel)")
class AcceptorTest {

  private static final String G = "gw";
  private static final long NOW = 1000L;

  private static LeaseRecord lease(String owner, long epoch, long expiresAt) {
    return new LeaseRecord(G, owner, epoch, expiresAt);
  }

  private static boolean accept(Acceptor a, LeaseRecord lease, long now) {
    return a.handle(LeaseRequest.accept(lease), now).ok();
  }

  /**
   * Given a free acceptor,
   * When any proposal arrives,
   * Then it is accepted (baseline — the only unconditional accept).
   */
  @Test
  void freeAcceptorAcceptsAnyProposal() {
    Acceptor a = new Acceptor();
    assertTrue(accept(a, lease("A", 1, NOW + 100), NOW));
  }

  /**
   * Given an acceptor holding a valid (unexpired) lease for owner A,
   * When a different owner B proposes — even at a much higher epoch —,
   * Then it is REJECTED: a valid lease is never preempted (the heart of mutual exclusion).
   */
  @Test
  void rejectsDifferentOwnerWhileLeaseIsValid() {
    Acceptor a = new Acceptor();
    assertTrue(accept(a, lease("A", 1, NOW + 1000), NOW));
    assertFalse(accept(a, lease("B", 99, NOW + 1000), NOW), "valid lease must not be preempted");
    assertEquals("A", a.handle(LeaseRequest.get(G), NOW).currentLease().orElseThrow().owner());
  }

  /**
   * Given an acceptor holding an EXPIRED lease for owner A at epoch 5,
   * When a different owner B proposes at an equal or lower epoch,
   * Then it is REJECTED: takeover requires a STRICTLY higher epoch (fencing monotonicity).
   */
  @Test
  void rejectsDifferentOwnerTakeoverWithoutStrictlyHigherEpoch() {
    Acceptor a = new Acceptor();
    assertTrue(accept(a, lease("A", 5, NOW + 10), NOW)); // valid now
    final long later = NOW + 100; // A@5 is now expired

    assertFalse(accept(a, lease("B", 5, later + 10), later), "equal epoch cannot take over");
    assertFalse(accept(a, lease("B", 3, later + 10), later), "lower epoch cannot take over");
    assertEquals(5, a.handle(LeaseRequest.get(G), later).currentLease().orElseThrow().epoch());
  }

  /**
   * Given an acceptor holding an expired lease for owner A at epoch 5,
   * When a different owner B proposes at a strictly higher epoch,
   * Then it is accepted (legitimate failover) — the only different-owner accept.
   */
  @Test
  void acceptsDifferentOwnerTakeoverWhenExpiredAndStrictlyHigher() {
    Acceptor a = new Acceptor();
    assertTrue(accept(a, lease("A", 5, NOW + 10), NOW));
    final long later = NOW + 100;
    assertTrue(accept(a, lease("B", 6, later + 10), later), "expired + higher epoch may take over");
    assertEquals("B", a.handle(LeaseRequest.get(G), later).currentLease().orElseThrow().owner());
  }

  /**
   * Given the current owner holding epoch 5,
   * When it proposes a LOWER epoch,
   * Then it is REJECTED: an owner's epoch never goes backward (monotone fencing token).
   */
  @Test
  void rejectsSameOwnerEpochRegression() {
    Acceptor a = new Acceptor();
    assertTrue(accept(a, lease("A", 5, NOW + 1000), NOW));
    assertFalse(accept(a, lease("A", 4, NOW + 1000), NOW), "same owner cannot lower its epoch");
    assertTrue(accept(a, lease("A", 5, NOW + 2000), NOW), "renew at equal epoch is allowed");
    assertTrue(accept(a, lease("A", 6, NOW + 2000), NOW), "renew at higher epoch is allowed");
  }

  /**
   * Given a GET request,
   * When the lease is expired,
   * Then GET still returns it raw (callers apply expiry; a proposer needs the epoch to escalate).
   */
  @Test
  void getReturnsRawLeaseIncludingExpired() {
    Acceptor a = new Acceptor();
    assertTrue(accept(a, lease("A", 5, NOW + 10), NOW));
    final long later = NOW + 100;
    LeaseRecord got = a.handle(LeaseRequest.get(G), later).currentLease().orElseThrow();
    assertEquals(5, got.epoch());
    assertTrue(got.isExpired(later), "GET returns the raw (expired) record");
  }
}
