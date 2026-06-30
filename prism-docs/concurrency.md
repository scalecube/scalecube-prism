# Concurrency model & audit

How threads and shared state interact in prism, and the discipline that keeps it correct. (Inspired
by the dpdk repo's `audit-threading-concurrency.md` — an explicit, auditable concurrency map.)

## The two execution models (ADR-0004)
- **Reactive plumbing (L0–L2):** membership, gossip, registry run on Project Reactor schedulers
  (`publishOn`), non-blocking. Suits the event-stream nature; the 200 ms–1 s cadence makes overhead
  irrelevant.
- **Deterministic core (L3–L4):** consensus/elector logic is a single-threaded, ambient-nondeterminism-
  free state machine. Determinism is a *correctness prerequisite* for replication and for
  deterministic-simulation testing, not an optimization.
- **Boundary:** reactive events are adapted to the deterministic core **only** at the module edge.
  No reactive operators inside the core.

## Thread map
| Component | Threads that touch it | Discipline |
|-----------|-----------------------|------------|
| `RegistryStore` | registry user calls (subscriber thread) + cluster handler callbacks (scheduler thread) | confined by a single `lock`; all mutate/read paths synchronize |
| `GossipServiceRegistry` | same as above + anti-entropy ticker (daemon `ScheduledExecutorService`) | `lock`-guarded store access; gossip I/O issued **outside** the lock; events to a `Sinks.Many` (busy-loop emit) |
| `HybridLogicalClock` | any caller (`now`/`update`/`current`) | all mutating methods `synchronized`; persist-ahead under the same monitor |
| `Acceptor` | the consensus transport listener thread + local proposer calls | `handle` is `synchronized`; one lease per group; journal append before ack |
| `QuorumConsensusStore` | the local elector loop | proposer is effectively stateless; consults local `Acceptor` (synchronized) + remote peers via `PeerCaller`; blocks on the reactor pipeline with a timeout |
| `LeaseElector` | user campaign/resign + a daemon ticker (`start(interval)`) | state under a single `lock`, but each consensus round is plan→**I/O off the lock**→commit; a per-group `roundInFlight` guard serializes same-group rounds; deterministic `tick()`; leadership events to per-group sinks |
| `InMemory*Store` (tests/sim) | test/sim driver thread(s) | `synchronized` maps |

## Invariants the discipline protects
1. **Single-writer-per-key** (registry): only the owning node mutates its keys; enforced at the
   facade. Combined with monotone HLC versions ⇒ strong eventual consistency (ADR-0003).
2. **One lease per acceptor per group**: the basis of mutual exclusion (ADR-0012). Never bypass
   `Acceptor.handle`.
3. **Monotone epochs**: the acceptor never accepts a non-increasing epoch from a different owner; the
   durable journal preserves this across crash (ADR-0013).
4. **Deterministic ordering in the core**: no `Math.random`, no wall-clock reads, no scheduler hops
   inside the consensus/elector transition; time and randomness are injected (so the sim can replay).

## Rules for contributors
- Touch `RegistryStore`/elector state **only** under the owning component's lock (or on its
  single-threaded loop). Do not leak references to internal mutable state.
- Do **not** perform network I/O while holding the lock; snapshot under the lock, I/O outside.
- Keep the consensus/elector core free of reactive operators; bridge at the boundary adapter.
- Anything that must survive a crash (accepted leases, HLC high-water) goes through the journals
  **before** it is acknowledged or acted upon (write-ahead).

## Where it's checked
- Convergence under reordering/duplication: `RegistryConvergenceTest` (property, 200 seeds).
- Never-two / fencing-monotonicity under partitions + virtual-time jumps + loss: `ElectorSafetyFuzzTest`,
  `FaultInjectionTest` (deterministic, seeded); and under **per-acceptor clock skew** (nodes disagree
  on "now"): `SkewedClockSafetyFuzzTest`.
- Consensus I/O runs off the elector lock (a lock-only call isn't blocked by an in-flight round) and a
  resign racing an acquire doesn't leak leadership: `LeaseElectorConcurrencyTest`.
- Crash-safety of acceptors and the clock: `FileLeaseJournalTest`, `FileClockJournalTest`.
