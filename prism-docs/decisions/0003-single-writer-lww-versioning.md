# 0003 — Single-writer-per-key + monotonic HLC version + LWW

Status: **Accepted** (rationale expanded to research grade; decision unchanged)

## Context
scalecube's metadata is one opaque, **unversioned** blob per member, pull-fetched; the response
carries no version, so a holder cannot tell which version it has and out-of-order events race. For a
registry whose properties (weight, status) drive routing, that is unsafe. We need a convergent,
conflict-free representation.

## Theory: CRDTs, single-writer, and strong eventual consistency
A **CRDT** (Shapiro, Preguiça, Baquero, Zawirski, 2011) converges without coordination if replica
state forms a **join-semilattice** and merge is the least-upper-bound (commutative, associative,
idempotent), giving **Strong Eventual Consistency (SEC)**: replicas that have delivered the same set
of updates have equal state.

prism keys are **single-writer** (each member owns its keys). That collapses the hard case: there are
**no concurrent writes to the same key**, so per key the updates are totally ordered by their writer.
An LWW-register keyed by a monotonic per-writer version is then a trivial join-semilattice
(`merge = max by version`), and SEC follows.

> **Proposition (SEC for the per-source LWW map).** With unique, monotonically increasing versions per
> key and `apply(x) ≜ keep argmax(version)`, any two replicas that have observed the same set of
> updates hold identical live state, regardless of delivery order or duplication.
> *Proof sketch.* `max` over a totally-ordered version domain is commutative, associative, idempotent;
> the per-key state is its LUB; the map is a product of these lattices. Order/duplication independence
> is exactly idempotent-commutative merge. ∎ (Mechanically checked across 200 seeds —
> `RegistryConvergenceTest`.)

## Why HLC, and why not vector clocks
- **Wall-clock LWW is wrong under skew:** a causally-later write can carry a smaller wall-clock and be
  dropped. The **Lamport clock** (1978) fixes this — `A → B ⇒ L(A) < L(B)` — so a causally-later
  update always wins. We use **Hybrid Logical Clocks** (Kulkarni et al., 2014): Lamport's causality
  fused with physical time, kept within bounded drift of real time so the same stamp is usable for
  lease TTLs and is human-meaningful.
- **No vector clocks / ITC needed:** vector clocks *detect concurrency*; with single-writer-per-key
  there is no concurrency to detect, so a single scalar HLC suffices — far cheaper. (If multi-writer
  keys were ever required, the design would need MV-registers/ITCs and explicit conflict resolution —
  which is exactly what we avoid by enforcing single-writer.)

## Decision
Model the registry as a **per-owner, single-writer, per-key** map of LWW-registers stamped by a
**Hybrid Logical Clock**; readers apply iff `version > stored` (idempotent, reorder-safe). Enforce
single-writer as a hard invariant. Persist the HLC high-water so versions never regress across restart
(durable clock, ADR-0013/persistence). This supersedes reliance on scalecube's blob metadata.

## Consequences
- Strong eventual consistency for free — no consensus for the common case (ADR-0002).
- The fencing epoch reuses the same Lamport-counter idea (ADR-0006/0012).
- Restart correctness with a stable id requires the durable clock; otherwise an ephemeral id makes a
  restart a new writer (no continuity needed).

## References
1. Shapiro, Preguiça, Baquero, Zawirski. *Conflict-free Replicated Data Types.* SSS, 2011.
2. Lamport. *Time, Clocks, and the Ordering of Events in a Distributed System.* CACM, 1978.
3. Kulkarni, Demirbas, Madappa, Avva, Leone. *Logical Physical Clocks (HLC).* OPODIS, 2014.
4. Almeida, Baquero, Fonte. *Interval Tree Clocks.* OPODIS, 2008 (the multi-writer alternative we
   deliberately don't need).
