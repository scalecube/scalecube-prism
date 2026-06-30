# Phase 1 — Deterministic test harness (`prism-sim`)

**Depends on:** `scalecube-cluster` (uses `cluster-testlib`'s `NetworkEmulator`).
**Goal:** reproducible, fast, fault-injecting simulation — the gate that makes every safety claim
verifiable. Build this **before** consensus/elector (ADR-0010).

## Scope
**In:** virtual time, deterministic network, cluster factory, scenario DSL, invariant checkers, seed
reproducibility. **Out:** production code (lives in the runtime modules).

## Work items

### 1.1 — Virtual time · M
- Drive scalecube schedulers with Reactor's `VirtualTimeScheduler` so periodic protocol tasks advance
  under simulator control rather than wall-clock.
- Provide `advanceTime(Duration)` to step the world deterministically.
- **Risk:** real Netty I/O can't be virtualized → **use the in-memory `NetworkEmulatorTransport`,
  never Netty, in sim**.

### 1.2 — Deterministic network · M
- Wrap `NetworkEmulator` with a **seeded RNG** for loss/latency/reorder (no `Math.random`,
  no wall-clock — pass the seed in).
- Partition control: `partition(Set<A>, Set<B>)`, `heal()`; per-link loss and latency distributions.

### 1.3 — Cluster factory · S
- Spin up *N* in-process nodes wired to the emulated transport + virtual scheduler; helpers to
  `kill(node)`, `restart(node)` (with stable id for restart-resume tests).

### 1.4 — Scenario DSL · S
- Fluent scenario script: `seed → build(N) → advanceTime → partition → … → assert`.
- A scenario is a pure function of its seed (reproducible).

### 1.5 — Invariant checkers · M
- `ConvergenceChecker` (all nodes reach identical membership/registry view within a bound).
- `MonotonicityChecker` (per-key versions never regress at any observer).
- `SingleLeaderChecker` (≤1 Active per group across the whole run — used by Phase 4).
- `NoFalseDeathChecker` (no DEAD for a node that never actually stopped — used by Phase 0).

### 1.6 — Reproducibility harness · S
- `run(seed)` produces a deterministic transcript; same seed ⇒ identical outcome. Failing seeds are
  saved as regression cases.

## Definition of done
- A 100+ node, multi-minute scenario runs in seconds and reproduces **bit-for-bit** from a seed.
- Checkers can fail a scenario with a minimal, replayable transcript.
- At least one membership convergence test and one partition-heal test pass on scalecube as-is.

## Risks
- **Hidden nondeterminism** (`Math.random`, `Date.now`, thread scheduling) → centralize all randomness
  and time behind the seed; ban direct clock/RNG use in sim and runtime hot paths.
- **scalecube internal scheduling** not fully virtualizable → if `VirtualTimeScheduler` proves
  insufficient, fall back to a controlled real-time scheduler with a fixed small tick and tolerance.
