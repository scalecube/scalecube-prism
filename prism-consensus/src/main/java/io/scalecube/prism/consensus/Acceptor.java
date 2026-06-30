package io.scalecube.prism.consensus;

import java.util.HashMap;
import java.util.Map;

/**
 * The acceptor half of the quorum lease protocol — the per-node state a proposer must convince a
 * majority of. This is the safety kernel, and its rule is exactly the {@code Accept} action of the
 * TLA+ spec ({@code prism-docs/spec/LeaseElection.tla}):
 *
 * <ul>
 *   <li>a free acceptor (no lease held) accepts any proposal;
 *   <li>the current owner may renew at an equal-or-higher epoch;
 *   <li>a <b>different</b> owner may take over <b>only if</b> the lease is expired <b>and</b> the
 *       proposed epoch is strictly higher.
 * </ul>
 *
 * <p>The last clause is what gives fencing-epoch monotonicity and prevents a higher epoch from
 * preempting a still-valid lease (a stickiness hazard surfaced by writing the spec).
 *
 * <p>GET returns the raw stored lease (including expired) so a proposer can pick a strictly greater
 * epoch; callers (the elector) apply expiry when deciding leadership. Thread-safe.
 */
public final class Acceptor {

  private final Map<String, LeaseRecord> accepted = new HashMap<>();
  private final Map<String, Long> promised = new HashMap<>(); // Paxos phase-1: reserved ballots
  private final LeaseJournal journal;

  /** Creates a non-durable acceptor (in-memory; tests / single-node). */
  public Acceptor() {
    this(LeaseJournal.noop());
  }

  /**
   * Creates an acceptor whose acceptances are made durable through {@code journal} and whose state
   * is recovered from it on construction — so a crash never forgets an accepted lease.
   *
   * @param journal durable lease storage
   */
  public Acceptor(LeaseJournal journal) {
    this.journal = journal;
    this.accepted.putAll(journal.load());
  }

  /**
   * Applies a request and returns the reply.
   *
   * @param request the proposer's request
   * @param nowMillis current time in millis
   * @return the reply (acceptance + current lease)
   */
  public synchronized LeaseResponse handle(LeaseRequest request, long nowMillis) {
    final String group = request.group();
    final LeaseRecord current = accepted.get(group);

    if (request.isGet()) {
      final long ballot = request.prepareBallot();
      if (ballot == 0) {
        return LeaseResponse.of(true, current); // plain GET (raw; callers apply expiry)
      }
      // PREPARE (phase 1): promise the ballot only if it is ≥ the highest already promised. Reply
      // with the current promised floor so a rejected proposer can retry above it (Paxos liveness).
      final long floor = promised.getOrDefault(group, 0L);
      final boolean promisedOk = ballot >= floor;
      if (promisedOk) {
        promised.put(group, ballot);
      }
      return LeaseResponse.promise(promisedOk, current, Math.max(floor, ballot));
    }

    // ACCEPT (phase 2). The lease rule (free | same-owner renew at ≥ epoch | take over an expired
    // lease only at a strictly higher epoch) preserves mutual exclusion and fencing. The Paxos
    // promise guard additionally rejects a proposal below a reserved ballot — UNLESS it is a valid
    // same-owner renewal (so a challenger's PREPARE never knocks out a healthy leader: stickiness).
    final LeaseRecord next = request.toLease();
    final boolean ruleOk =
        current == null
            || (current.owner().equals(next.owner()) && next.epoch() >= current.epoch())
            || (current.isExpired(nowMillis) && next.epoch() > current.epoch());
    final boolean validSameOwnerRenewal =
        current != null
            && current.owner().equals(next.owner())
            && !current.isExpired(nowMillis);
    final boolean promiseBlocks =
        next.epoch() < promised.getOrDefault(group, 0L) && !validSameOwnerRenewal;

    if (ruleOk && !promiseBlocks) {
      journal.append(next); // write-ahead: durable before we acknowledge
      accepted.put(group, next);
      if (next.epoch() > promised.getOrDefault(group, 0L)) {
        promised.put(group, next.epoch()); // a committed ballot is also promised
      }
      return LeaseResponse.of(true, next);
    }
    return LeaseResponse.of(false, current);
  }
}
