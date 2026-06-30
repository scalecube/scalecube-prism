# 0012 — Distributed singleton elector via a quorum lease

Status: **Accepted** (rationale expanded to research grade; decision unchanged)

Companion spec: [`prism-docs/spec/LeaseElection.tla`](../spec/LeaseElection.tla).

## Context
"Never two leaders" must hold even under partition. Gossip cannot provide it (ADR-0006). Full Raft (a
replicated command log) is large and is not needed *just* for mutual exclusion. scalecube provides no
consensus and its `Cluster` facade exposes no point-to-point RPC. We need a correct, minimal,
distributed primitive.

## System model
Asynchronous network (loss/reorder/partition), crash-recovery with stable storage (the write-ahead
lease journal makes acceptors crash-safe), non-Byzantine, loosely-synchronised clocks used only for
leases (ADR-0013). Failure detection by SWIM/Lifeguard (`◇S`), informing handoff, never deciding it.

## The primitive: a single-decree, majority-quorum lease
Each quorum member is an **acceptor** holding at most one `(owner, epoch, expiry)` lease per group. A
proposer becomes leader by getting a **majority** of acceptors to accept its lease. The acceptor rule
(the safety kernel) accepts `(o, e)` iff: the cell is free; or a same-owner renewal with
`e ≥ current.epoch`; or the current lease is expired **and** `e > current.epoch`.

> **Lemma (quorum intersection, fixed config).** Any two majorities of a set `C` intersect:
> `|Q1| + |Q2| ≥ 2(⌊|C|/2⌋+1) > |C|`, so `|Q1 ∩ Q2| ≥ 1`. ∎

> **Theorem (mutual exclusion).** No two distinct owners are simultaneously certified (each backed by
> a majority of valid leases). *Proof.* By the Lemma their backing majorities share an acceptor, which
> would then hold two leases — impossible (one lease per acceptor). ∎

> **Theorem (fencing monotonicity).** A later certified owner has a strictly greater epoch than a
> concurrently-valid earlier one (the overlapping acceptor only accepts a different owner at a strictly
> higher epoch). ∎ — hence a stale leader's actions are fenced downstream.

**Why single-phase (no Paxos prepare).** Classic Paxos uses a prepare phase to adopt previously
accepted values; mutual exclusion does not require adopting a value, only refusing conflicting ones.
Using the **epoch as the ballot** and **rejecting equal-epoch proposals from a different owner** makes
concurrent same-epoch contenders fail and retry rather than double-grant — so a single accept round to
a majority is safe. (Liveness may need backoff to avoid dueling; safety holds regardless.) This is the
lease/lock specialization of consensus, as in Chubby and Boxwood.

## Decisions
- **Configured static quorum** (3/5), majority over the set (dynamic membership deferred to ADR-0015).
- **Dedicated consensus transport per node** (a separate scalecube `Transport`/port), reached via the
  `PeerCaller` seam — kept distinct from gossip, because the `Cluster` facade exposes no p2p RPC.
- **Quorum read returns the highest-epoch lease present on a majority**; expiry is not filtered in the
  read so a new proposer always selects a strictly greater fencing epoch.

## Liveness
Progress requires a majority reachable within the partial-synchrony window (FLP-mandated; ADR-0006).
A minority partition loses availability, never safety. Lease renewal keeps leadership sticky; only on
genuine loss does the lease expire and a standby take over (a bounded zero-leader gap; graceful
`resign`/`LEAVING` shrinks it to near-zero).

### Acquisition is two-phase Paxos (dueling-proposer fix)
A *single-phase* CAS livelocks under perfectly-synchronized concurrent campaigns: each proposer claims
its own acceptor → a same-epoch split with no majority, and the takeover rule (strictly-higher epoch)
cannot break the tie. A real end-to-end concurrent test exposed this (safety held — never two — only
convergence stalled). Acquisition is therefore **single-decree Paxos**:

1. **PREPARE** a unique, monotone ballot (`counter << 20 | nodeTag`) across the quorum. An acceptor
   *promises* not to accept below a reserved ballot and returns its current lease. The promise both
   **orders competing proposers** (one ballot wins) and **reveals the high-water from a majority** (so
   escalation is monotone — a best-effort, lossy max-epoch escalation was tried and **broke fencing
   monotonicity**, caught by the DST).
2. If a majority promises and no still-valid claim of another owner exists, **ACCEPT** self at the
   ballot. The promise guard is **waived for a valid same-owner renewal**, so a challenger's PREPARE
   can never knock out a healthy leader — *stickiness is preserved*.

Acquisition commits one ballot atomically (no mixed-epoch state), so the fencing token stays monotone.
A randomized backoff (`[T, 2T]`, Raft-style) breaks dueling; in-process tests/simulation use no backoff
(deterministic). The fencing epoch is now the Paxos ballot (large, unique, monotone) rather than a
small counter.

## Verification
Deterministic unit tests of the acceptor rule and quorum (`AcceptorTest` rejections,
minority-cannot-acquire, takeover-increments-epoch); a scalecube-style integration test over
`NetworkEmulator` (partition the leader → standby takes over, never two); a real-netty E2E test of
**concurrent campaigns electing exactly one leader** (the dueling-proposer case the Paxos acquisition
fixes); the TLA+ spec (`AtMostOneLeader`, `AgreementPerEpoch`); the seeded fuzzer (ADR-0013).

## Consequences
- Correct "never two" under partition; a single mutable register, **not** a general replicated log —
  sufficient for election; richer `CONSENSUS`-tier state needs the full log (ADR-0007).
- Graceful `resign` clears the lease (epoch may reset); crash-failover always increments the epoch, so
  fencing is preserved across crashes. Lease exclusion is modulo clock skew — fencing covers the gap.

## References
1. Burrows. *The Chubby Lock Service for Loosely-Coupled Distributed Systems.* OSDI, 2006.
2. MacCormick et al. *Boxwood: Abstractions as the Foundation for Storage Infrastructure.* OSDI, 2004
   (leases over Paxos).
3. Lamport. *Paxos Made Simple.* 2001; Howard et al. *Flexible Paxos.* OPODIS, 2016 (quorum
   intersection).
4. Kleppmann. *How to do distributed locking* (fencing). 2016.
