# 0004 — Reactive plumbing vs. deterministic consensus — a hard boundary

Status: **Accepted** (rationale expanded to research grade; decision unchanged)

## Context
scalecube is built on Project Reactor (`Mono`/`Flux`) with scheduler hops (`publishOn`). That suits
membership/gossip/registry. But the consensus/elector core has a different, non-negotiable
requirement: **determinism**.

## Theory: replicated state machines must be deterministic
The **state-machine replication** approach (Lamport, 1978; Schneider's survey, 1990) replicates a
service by feeding **the same sequence of commands** to **deterministic** state machines on each
replica; identical input ⇒ identical output. Determinism is therefore a *correctness prerequisite*,
not an optimization: a replica whose transitions depend on thread interleaving, scheduler timing, or
ambient nondeterminism (`Math.random`, wall-clock reads) can diverge from its peers and from its own
re-execution.

Determinism also underpins **deterministic simulation testing** (ADR-0013): a single-threaded,
seed-driven state machine lets a whole cluster's execution be replayed bit-for-bit from a seed
(FoundationDB, TigerBeetle). Reactor's `publishOn` hops reorder events nondeterministically — the very
hazard that produced the metadata pull race we fixed — and would make both replication and replay
unsound.

## Decision
- **L0–L2 (membership, gossip, registry): reactive.** Keep `Mono`/`Flux`; the event-stream model fits
  and the 200 ms–1 s cadence makes any overhead irrelevant.
- **L3–L4 (consensus, elector): a single-threaded deterministic loop, NOT reactive.** All inputs
  (messages, timers-as-logical-events, proposals) are ordered through one queue; no scheduler hops,
  no ambient randomness/clock reads in the transition function (time/randomness are injected).
- **Adapt at the boundary only:** scalecube's reactive events are converted to loop events in a thin,
  audited adapter (`TransportPeerCaller`/`QuorumNode` bridge). This is the Aeron Cluster discipline (a
  single-threaded, allocation-disciplined agent) and the actor-model discipline (serialized state
  mutation).

## Consequences
- The consensus state machine is reasoned about sequentially, unit-tested deterministically, and
  replayed from a seed — enabling the DST fuzzer and (future) log replay.
- A standing rule: no reactive operators inside the consensus/elector core; the only async element is
  a scheduled `tick()` that *calls into* the deterministic loop.
- Two execution models coexist in the codebase, by design, separated by the adapter.

## References
1. Lamport. *Time, Clocks, and the Ordering of Events.* CACM, 1978 (SMR origin).
2. Schneider. *Implementing Fault-Tolerant Services Using the State Machine Approach.* ACM CSUR, 1990.
3. FoundationDB / TigerBeetle deterministic-simulation-testing methodology (engineering reports).
