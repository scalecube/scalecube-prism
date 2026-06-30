# 0005 — Membership lifecycle drives registry entry removal

Status: **Accepted** (rationale expanded to research grade; decision unchanged)

## Context
Deletion is the hard part of any replicated set/map. We must remove a service when its owner dies,
without the deletion being undone by anti-entropy from a replica that hasn't yet seen it.

## Theory: why deletes need tombstones
In a state-based replicated set, **a bare removal is not a join-semilattice operation** — it is not
monotone, so it does not commute with a concurrent/late add. Concretely, in a grow-only set adds
always win; to delete safely you need a **tombstone**: a versioned "removed" marker that *dominates*
the add it cancels (the OR-Set / 2P-Set construction; Shapiro et al., 2011). The tombstone restores
monotonicity, so anti-entropy can never resurrect a deleted key. The cost is **tombstone
accumulation**, requiring GC once the delete has provably propagated (a classic distributed
garbage-collection problem; safe pruning needs a causal-stability or dissemination-window bound).

## The registry-specific simplification
A service registry has a structural advantage: the **common deletion is "the owner died," and SWIM
already detects that**. We bind each entry's lifecycle to its owner's membership status:

- Owner `DEAD`/`REMOVED` ⇒ **purge that owner's entries** and emit `EXPIRED`. **Membership is the
  tombstone** for crashes — no per-key tombstone or GC needed for the dominant case. (Safety rests on
  SWIM's eventually-strong detector and the single-writer rule: only the dead owner could have
  written those keys, so no live writer is contradicted by the purge.)
- **Explicit live deregistration** (a service stops while its host keeps running) is the residual case
  that *does* need a **versioned tombstone** (LWW with a higher version, ADR-0003) plus **TTL/GC**
  after the dissemination window.

So we pay the full tombstone+GC machinery only for the rare live-deregister, and let membership cover
the common crash case for free.

## Decision
Subscribe the registry to `Cluster.listenMembership()`. On owner removal, purge and emit `EXPIRED`.
For live deregistration, write a versioned tombstone, swept by GC after the dissemination window.

## Consequences
- Eliminates the general tombstone-GC cost for the dominant (crash) deletion path.
- Consumers must still treat lookups as hints: a dead owner's entries linger until death propagates
  (the stale-positive window — bounded by failure-detection + dissemination time).
- Correctness depends on SWIM/Lifeguard accuracy; a false-positive `DEAD` would purge a live owner's
  entries until it re-advertises (anti-entropy heals it — ADR on anti-entropy / Merkle).

## References
1. Shapiro, Preguiça, Baquero, Zawirski. *Conflict-free Replicated Data Types* (OR-Set / 2P-Set
   tombstones). SSS, 2011.
2. Demers et al. *Epidemic Algorithms for Replicated Database Maintenance* (death certificates / dormant
   deletes). PODC, 1987.
3. Das, Gupta, Motwani. *SWIM.* DSN, 2002 (the failure detector that serves as the crash tombstone).
