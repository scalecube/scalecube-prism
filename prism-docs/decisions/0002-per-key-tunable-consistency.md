# 0002 — Per-key tunable consistency over one substrate (Prism)

Status: **Accepted** (rationale expanded to research grade; decision unchanged)

## Context
Distributed data systems usually pick **one** point on the consistency spectrum globally: Eureka/Serf
= eventual (AP), Aeron Cluster = linearizable (CP), Consul = a hard seam between a gossip tier and a
separately-operated Raft tier. We want the availability and local-read latency of gossip *and* strong
guarantees for the few keys that need them, without running two systems.

## The consistency spectrum and what theory permits
Consistency models form a partial order (Viotti & Vukolić survey, 2016): **linearizable ⊐ sequential
⊐ causal+ ⊐ read-your-writes/monotonic ⊐ eventual**. **CAP** (Gilbert & Lynch, 2002) forbids
linearizability with availability under partition; **PACELC** (Abadi, 2012) adds the latency cost
*even without* partitions. Crucially, the achievable level is not a system-wide constant — it can be
chosen **per operation / per object**:

- **Tunable consistency** (Dynamo/Cassandra R/W quorums) selects strength per request.
- **Consistency-based SLAs** (Pileus / Tuba, Terry et al., 2013) pick the strongest level meeting a
  latency budget per read.
- **RedBlue consistency** (Li et al., OSDI 2012) proves that *commutative* ("blue") operations can run
  eventually-consistent while only non-commutative ("red") operations need strong coordination — a
  direct theoretical license for mixing levels by operation semantics.
- **Correctables** (Guerraoui et al., 2016) expose a strength/latency knob to the caller.

## Decision
Make consistency a **per-key attribute** served by **one** membership/dissemination substrate. The
owner of a key declares the weakest tier that is still correct:
`EVENTUAL ⟶ CAUSAL ⟶ QUORUM ⟶ CONSENSUS`. A router (L2) dispatches reads/writes to the matching
mechanism. **AP is the default; strong consistency is opt-in per key.**

This is the RedBlue/Pileus insight realized structurally: because prism keys are single-writer
(ADR-0003), cross-key operations commute, so the vast majority are safely "blue" (eventual/causal),
and only the rare "red" keys (locks, singleton ownership) pay for `CONSENSUS`.

## Why one substrate, not two stacks
Operating a separate gossip cluster and Raft cluster (the Consul model) doubles the operational
surface and forces an early, global CP/AP commitment. A single membership/gossip fabric with a
per-key router lets a single key move along the spectrum without redeployment, and confines the
expensive coordination to where it is declared necessary.

## Consequences
- One system to run; the strong tier is reached for only where justified.
- Requires a clean tier abstraction and an **unforgeable per-key tier tag** (pin at key creation).
- The `CONSENSUS` tier pulls in real consensus machinery (ADR-0006, ADR-0007, ADR-0012).
- The contract to consumers must state, per tier, exactly which session guarantees hold (ADR-0011).

## References
1. Gilbert & Lynch. *Brewer's Conjecture and the Feasibility of CAP.* SIGACT News, 2002.
2. Abadi. *Consistency Tradeoffs in Modern Distributed Database Design (PACELC).* IEEE Computer, 2012.
3. Viotti & Vukolić. *Consistency in Non-Transactional Distributed Storage Systems.* ACM CSUR, 2016.
4. Terry et al. *Consistency-Based Service Level Agreements for Cloud Storage (Pileus).* SOSP, 2013.
5. Li et al. *Making Geo-Replicated Systems Fast as Possible, Consistent when Necessary (RedBlue).*
   OSDI, 2012.
6. Bailis et al. *Highly Available Transactions.* VLDB, 2014.
