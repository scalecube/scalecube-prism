# prism-sim

**Phase:** 1 (build early) · **Status:** planned (package declared)

## What it does
A deterministic discrete-event simulator and the safety/liveness property tests that run on it.
Drives whole clusters of prism nodes under controlled networks — partitions, packet loss, clock
skew, churn — fast and **reproducibly**.

## Goal
Make correctness *provable and repeatable*. This is the foundation that lets every later phase be
trusted: you cannot verify "never two Actives under partition" or "membership converges" without a
reproducible way to inject faults and replay the exact scenario.

## How
- Built on scalecube's `NetworkEmulator` (from `scalecube-cluster-testlib`).
- **Virtual clock + seeded RNG** → a 1000-node, 30-minute scenario runs in seconds and reproduces
  bit-for-bit from a seed (the FoundationDB / TigerBeetle approach).
- Scenario DSL for partitions, latency, loss, node kill/restart, and metadata churn.

## Properties it asserts
- **Membership:** convergence; no false-positive deaths under healthy-but-slow conditions (validates
  Lifeguard); partition heal.
- **Registry:** value convergence; per-key version monotonicity; tombstone correctness (no zombie
  keys).
- **Elector (critical):** **never two Actives** across partitions and chaos; stickiness; safe handoff;
  fencing rejects stale-epoch actions.
- **Self-electing quorum (ADR-0015):** **never two leaders across reconfiguration** and monotone
  fencing epochs under single-member growth/shrink + partition + loss.

## Harnesses
| Class | Models | Drives |
|-------|--------|--------|
| `SimCluster` | fixed-membership quorum under partition / virtual-time jumps / per-acceptor clock skew (`setClockSkew`) / loss | real `Acceptor` + `QuorumConsensusStore` + `LeaseElector` |
| `ReconfigSimCluster` | **dynamic (self-electing) quorum** — a committed config evolving by single-member changes over a fixed acceptor pool, with §7.1 high-water state transfer | same real kernel; harness enforces the reconfiguration policy |

Both expose a **god-view oracle** (`trueLeaders`, `currentLeaderEpoch`) computed independently of what
any elector believes. `ReconfigSimCluster`'s oracle mirrors the TLA+ `Leader(o)`: an owner leads if a
majority of the *current* OR *previous* config holds its unexpired lease; its epoch reading is the
**committed** epoch (held by a majority), the honest fencing token.

### Tests
- `ElectorSafetyFuzzTest` — never-two + fencing monotonicity, fixed membership, 300 seeds.
- `SkewedClockSafetyFuzzTest` — the same gauntlet with acceptors up to ±1 TTL out of step
  (`setClockSkew`), proving safety is clock-independent and exercising the zombie window, 300 seeds.
- `FaultInjectionTest` — 30 % loss + recovery, 200 seeds.
- `MultiGroupChurnStressTest` — at scale: one cluster, 8 logical groups with random membership and
  randomly-ordered campaigns, under continuous **node kill/revive churn** (random order), partitions,
  loss and clock skew; asserts per-group never-two + fencing monotonicity across 60 seeds. (`SimCluster`
  gained `kill`/`revive`, `aliveMembers`, per-(node,group) `campaign`, and a group-agnostic `chaosStep`.)
- `ReconfigurationSafetyFuzzTest` — never-two + fencing monotonicity **across reconfiguration**
  (300 seeds), self-formation from a single node, self-heal after the leader is removed, and a
  negative control that constructs multi-member split-brain at the raw acceptors to prove the oracle
  has teeth. This is the DST half of the ADR-0015 §13 gate; see `prism-docs/spec/README.md` for the
  TLA+ half.

## Important
- **Build this before `prism-consensus` / `prism-elector`** (Phase 1 before Phase 3/4). It's the gate
  on all safety-critical work.
- Depends on the runtime modules to drive them; keep simulation logic out of the production modules.
- Candidate to graduate into its own repo later (a reusable deterministic SWIM simulator).

## Depends on
`prism-registry`, `prism-consensus`, `prism-elector`, `scalecube-cluster-testlib`, `slf4j-api`
