# Formal specification

`LeaseElection.tla` is a TLA+ model of prism's single-decree **Paxos** lease elector — the safety
kernel behind `prism-consensus` (`Acceptor` + `QuorumConsensusStore` + `LeaseElector`). It models the
shipped **two-phase** protocol: phase 1 PREPARE/promise (`Acceptor.promised`) and phase 2 ACCEPT
gated by both the lease rule and the Paxos promise guard.

## Why
Tests (even seeded fuzzing) sample the state space; a model checker explores it exhaustively up to the
configured bounds. TLA+ is what AWS, Azure, and MongoDB use to validate their core replication
protocols. This is the artifact that turns "we tested it" into "the protocol is correct by
construction, and the code is checked against it."

## What it proves (within `LeaseElection.cfg` bounds: 3 nodes, 2 owners, epochs ≤ 4)
- **`AtMostOneLeader`** — never two distinct owners are majority-backed at once (mutual exclusion).
- **`AgreementPerEpoch`** — at most one owner can be majority-backed at any single epoch (the
  single-decree property; the basis of fencing).

### TLC result (run 2026-06-28, tla2tools 2.19, 3 nodes / 2 owners / MaxEpoch 4)
**No error** — `TypeOK`, `AtMostOneLeader`, and `AgreementPerEpoch` all hold over the complete state
graph: 12,333 distinct states, search depth 10.

## The model ↔ code correspondence
| Spec | Code |
|------|------|
| `Prepare(b)` (promise iff `b > promised`) | `Acceptor.handle` PREPARE branch + `Acceptor.promised` |
| `PromiseOk(n,o,e)` (promise guard + renewal exemption) | `Acceptor.handle` `promiseBlocks` / `validSameOwnerRenewal` |
| `RuleOk(n,o,e)` (lease rule) | `Acceptor.handle` `ruleOk` |
| `Acquire(o, e)` (majority of `Accept`) | `QuorumConsensusStore.prepare` + `compareAndSet` |
| `Expire(n)` (epoch/promise retained) | `LeaseRecord.isExpired` + monotonic epochs |
| `Leader(o)` (valid majority) | `SimCluster.trueLeaders` god-view oracle |

The two-phase rule is identical in both. **Phase 2** accepts a proposal only when (a) the lease rule
holds — a different owner may take over **only when the lease is expired and the proposed epoch is
strictly higher** — *and* (b) the proposed ballot is not below the acceptor's promised floor, **unless**
it is a still-valid same-owner renewal (the `validSameOwnerRenewal` exemption). The promise guard is
what makes dueling proposers converge instead of livelocking: a proposer that loses the PREPARE race
backs off and retries above the promised floor rather than preempting.

## Running it
Install the TLA+ tools (`tla2tools.jar`) and run:

```
java -cp tla2tools.jar tlc2.TLC LeaseElection.tla -config LeaseElection.cfg
```

The `prism-sim` deterministic fuzzer checks the same invariants (`AtMostOneLeader` and
fencing-epoch monotonicity) on the real implementation across hundreds of seeds — model checking and
simulation testing as complementary evidence.

## `SelfElectingQuorum.tla` — dynamic membership (ADR-0015)
Models reconfiguration of the consensus group and proves **two** safety properties hold **across
configuration changes**:

- **`AtMostOneLeader`** — never two leaders, when reconfiguration is **single-member** (adjacent
  configs have overlapping majorities) and an acceptor holds one lease.
- **`NoTokenRegression`** — the fencing epoch of the certified leader never goes backwards; no owner
  is ever certified below the highest epoch already committed.

Three configs isolate which constraint earns which guarantee:

- `SelfElectingQuorum.cfg` (`ReconfigDelta = 1`, `LeaderDriven = TRUE`) → **both hold**.
- `SelfElectingQuorum_unsafe.cfg` (`ReconfigDelta = 5`) → **`AtMostOneLeader` counterexample**: a
  multi-member jump yields two disjoint majorities and two simultaneous leaders.
- `SelfElectingQuorum_nofence.cfg` (`ReconfigDelta = 1`, `LeaderDriven = FALSE`) → **`NoTokenRegression`
  counterexample**: even a *single-member* but *unconstrained* config swap can shrink below a stale,
  still-valid, lower-epoch lease and resurrect it.

```
java -cp tla2tools.jar tlc2.TLC SelfElectingQuorum.tla -config SelfElectingQuorum.cfg
java -cp tla2tools.jar tlc2.TLC SelfElectingQuorum.tla -config SelfElectingQuorum_unsafe.cfg
java -cp tla2tools.jar tlc2.TLC SelfElectingQuorum.tla -config SelfElectingQuorum_nofence.cfg
```

### TLC results (run 2026-06-29, tla2tools 2.19, 5 nodes / 2 owners / MaxEpoch 3, MinQuorum 3)
| Config | Setting | Result | Scope |
|--------|---------|--------|-------|
| `SelfElectingQuorum.cfg` | `ReconfigDelta=1`, `LeaderDriven=TRUE` | **No error** — `AtMostOneLeader`, `NoTokenRegression`, `TypeOK` hold | 12,729,070 states / 614,330 distinct / depth 16 |
| `SelfElectingQuorum_unsafe.cfg` | `ReconfigDelta=5` | **Counterexample** — two simultaneous leaders | depth 4 |
| `SelfElectingQuorum_nofence.cfg` | `LeaderDriven=FALSE` | **Counterexample** — fencing epoch regresses | depth 9 |

> ### The finding: single-member alone is necessary but **not sufficient** for fencing monotonicity
> Adding `NoTokenRegression` to the model surfaced a real, subtle safety gap. With single-member
> reconfiguration that transfers the §7.1 high-water **only to joining members** (the first-cut
> implementation in `ReconfigurationManager`), TLC finds this trace:
> 1. owner `a` leads at a high epoch on a majority of a 5-member config;
> 2. a member holding that high-water is **removed** by a single-member shrink;
> 3. a smaller config's majority now consists of stragglers holding `a`'s *older* lease — so `a` is
>    re-certified at a **lower** epoch than was already committed. **`AtMostOneLeader` does not catch
>    this** (only one owner is certified — the stale one).
>
> **The fix (proven safe here):** reconfiguration must be **leader-driven** and re-establish the
> leader's lease (the high-water) on a **majority of the new config**, gated by the acceptor rule,
> *before the old config is retired*. Then a stale valid lease blocks an unsafe shrink until it
> expires, and the fencing token never regresses. This is the safety obligation the dynamic-quorum
> implementation must meet (tracked as the `ReconfigurationManager` high-water-carry fix).

> Note: making the spec TLC-checkable surfaced one modeling defect — `NONE` was defined via an
> unbounded `CHOOSE`, which TLC cannot evaluate. It is now a declared model-value constant
> (`CONSTANT NONE`, `ASSUME NONE \notin Owners`; set `NONE = NONE` in each `.cfg`). The model
> semantics are unchanged; only the encoding became finite-checkable.
