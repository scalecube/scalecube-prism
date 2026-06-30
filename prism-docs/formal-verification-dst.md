# Formal verification + DST in prism — how "never two leaders" is *checked*

prism's headline safety claim is that there is **never two leaders**. That is a universally-quantified
property over every interleaving of crashes, partitions, reordering, and clock skew — exactly the kind
of claim ordinary tests cannot establish. prism's "proof" pillar therefore pairs **two complementary
forms of machine-checked evidence**: a TLA+ specification model-checked by TLC, and **deterministic
simulation testing (DST)** of the real implementation. This page explains what each one actually does,
what it covers that the other can't, and where the honest limits are.

> **One-line answer:** TLC **exhaustively** checks the *protocol* within finite bounds (the design is
> correct), and DST checks the *real code* across hundreds of fault-injected, seed-reproducible
> executions (the implementation matches the design). Together they bound safety with strong evidence.
> It is **not** an unbounded proof for arbitrary N, and **not** a liveness guarantee in production.

---

## 1. Why testing alone is insufficient

A safety property is "for *all* executions, P holds." Unit and integration tests **sample** a
vanishingly small slice of that space, and when concurrency is involved they are non-reproducible: a
green run proves nothing, and a red run you can't replay teaches nothing. You cannot sample your way
to a universal claim. The two techniques below attack the gap from opposite ends —
see [`decisions/0010-sim-before-consensus.md`](decisions/0010-sim-before-consensus.md) and
[`decisions/0013-formal-verification-and-dst.md`](decisions/0013-formal-verification-and-dst.md).

- **Model checking** (TLA+/TLC) is **exhaustive but bounded**: it explores *every* interleaving up to
  a finite configuration (small N, bounded epochs), catching subtle ordering bugs. Its blind spot is
  the *model–code gap* — it verifies the spec, not the bytes that ship.
- **DST** runs the **real code** over deep, adversarial, fault-injecting schedules, **reproducibly
  from a seed** — unbounded depth but **sampled**. Its blind spot is non-exhaustiveness.

Each covers the other's blind spot. This is the methodology Amazon documented for its core services
(Newcombe et al., CACM 2015), and that MongoDB, Azure Cosmos, FoundationDB, and TigerBeetle apply to
their replication protocols. The `Proof` row of the [`README`](../README.md) names exactly this pair.

---

## 2. The TLA+ side — exhaustively checking the protocol

[`spec/LeaseElection.tla`](spec/LeaseElection.tla) models prism's single-decree Paxos lease elector:
the `promised` variable, the `Prepare(b)` action (promise iff `b > promised`), and the `Accept` action
gated by **both** the lease rule (`RuleOk`) and the Paxos promise guard (`PromiseOk`, with the
still-valid same-owner-renewal exemption). A candidate is `Leader(o)` for an instant iff a **majority**
of acceptors hold a *valid* lease for it. TLC then explores the complete bounded state graph and checks
three invariants:

- **`TypeOK`** — the state is well-typed (the structural invariant).
- **`AtMostOneLeader`** — never two distinct owners are majority-backed at once (mutual exclusion).
- **`AgreementPerEpoch`** — at most one owner is majority-backed at any single epoch (the single-decree
  property, and the basis of fencing).

The bounds live in [`spec/LeaseElection.cfg`](spec/LeaseElection.cfg): **3 nodes, 2 owners, epochs ≤ 4**.

> **TLC result** (run 2026-06-28, tla2tools 2.19): **No error** — `TypeOK`, `AtMostOneLeader`, and
> `AgreementPerEpoch` all hold over the complete state graph: **12,333 distinct states, search
> depth 10**.

The model↔code correspondence is line-for-line; see [`spec/README.md`](spec/README.md). The acceptor
rule in [`prism-consensus/.../Acceptor.java`](../prism-consensus/src/main/java/io/scalecube/prism/consensus/Acceptor.java)
is the spec's `Accept` action verbatim — which is exactly what makes "check the spec" mean something
about the code.

---

## 3. The self-electing-quorum demonstration — safe vs. unsafe, on purpose

[`spec/SelfElectingQuorum.tla`](spec/SelfElectingQuorum.tla) extends the static model with a current
and previous committed configuration and a `Reconfigure` action bounded by `ReconfigDelta` (the number
of members one reconfiguration may change). It proves "never two leaders" is preserved **across config
changes** — but only when reconfiguration is **single-member**, because adjacent configs then have
**overlapping majorities** and an acceptor holds only one lease. The spec is run *twice*, as a
matched safe/unsafe pair:

> **TLC results** (run 2026-06-28, tla2tools 1.7.4, 5 nodes / 2 owners / MaxEpoch 3 / MinQuorum 3):
>
> | Config | `ReconfigDelta` | Result | Scope |
> |--------|-----------------|--------|-------|
> | [`SelfElectingQuorum.cfg`](spec/SelfElectingQuorum.cfg) | 1 (single-member) | **No error** — `AtMostOneLeader` & `TypeOK` hold | 86,298,140 states / 4,865,740 distinct / depth 21 |
> | [`SelfElectingQuorum_unsafe.cfg`](spec/SelfElectingQuorum_unsafe.cfg) | 5 (any jump) | **Counterexample** at depth 4 — two simultaneous leaders | 3,747 distinct states |

The unsafe run is a **negative control**: it is *expected* to fail, and TLC dutifully prints the
split-brain trace — a config jumps `{n1,n3,n4} → {n1,n2,n4}`, owner `a` is still certified under the
old config while owner `b` wins a majority of the new one, the two majorities are disjoint, and both
are leader. Running both shows the single-member rule is **load-bearing, not decorative** — the formal
proof obligation behind [`decisions/0015-self-electing-quorum.md`](decisions/0015-self-electing-quorum.md).

---

## 4. The DST side — checking the real code under faults

DST runs the **actual** `Acceptor` + `QuorumConsensusStore` + `LeaseElector` kernel, not a model of
it. The harness, [`SimCluster`](../prism-sim/src/main/java/io/scalecube/prism/sim/SimCluster.java),
drives every source of nondeterminism — **time and the network** — from a **virtual clock** and a
**seeded `Random`**, so an entire long, adversarial execution is **reproducible bit-for-bit from its
seed** (the FoundationDB / TigerBeetle approach, scoped to the safety kernel).

Each `chaosStep` injects faults deterministically from the seed:

- **partitions** — a random two-way split (`repartition`) that may heal;
- **message loss** — a per-link drop rate (`setMessageLoss` / `injectLoss`, e.g. 30% in
  [`FaultInjectionTest`](../prism-sim/src/test/java/io/scalecube/prism/sim/FaultInjectionTest.java));
- **virtual-time jumps** — the shared clock leaps up to 1.5× the TTL, which can expire a lease and
  trigger failover;
- **per-acceptor clock skew** — via `setClockSkew`, each acceptor judges lease expiry against its own
  (global + fixed offset) clock, so nodes genuinely disagree on "now" — directly exercising the
  fencing-covered "zombie former leader" window (the god-view oracle judges each acceptor in its own
  time frame, so it stays honest under skew);
- **re-campaigns** and, in the at-scale test, **node kill/revive churn**.

After every step a **god-view oracle** reads *every acceptor's* stored state directly — independent of
what any elector *believes* — and enforces the same invariants TLC checks:

- `trueLeaders(group) <= 1` — **never two leaders** (the count of owners holding a majority of
  unexpired leases must never exceed 1);
- `currentLeaderEpoch(group)` is **monotone** — successive leaders **never regress** the fencing epoch.

The tests sweep **hundreds of seeds**:
[`ElectorSafetyFuzzTest`](../prism-sim/src/test/java/io/scalecube/prism/sim/ElectorSafetyFuzzTest.java)
runs 300 seeds × 200 steps over partitions and virtual-time jumps;
[`SkewedClockSafetyFuzzTest`](../prism-sim/src/test/java/io/scalecube/prism/sim/SkewedClockSafetyFuzzTest.java)
re-runs that gauntlet with acceptors up to ±1 TTL out of step, asserting the same invariants hold —
safety is clock-independent (quorum intersection + monotone fencing), so it must survive any skew;
[`FaultInjectionTest`](../prism-sim/src/test/java/io/scalecube/prism/sim/FaultInjectionTest.java)
adds 30% message loss across 200 seeds; and
[`MultiGroupChurnStressTest`](../prism-sim/src/test/java/io/scalecube/prism/sim/MultiGroupChurnStressTest.java)
attacks 8 logical groups on one cluster with randomized campaign order and kill/revive churn. The
**superpower** is the seed: any failure reproduces *exactly*, so a flaky-looking timing bug becomes a
permanent regression case — you save the seed, not a screenshot.

The dynamic-membership half is mirrored by
[`ReconfigSimCluster`](../prism-sim/src/main/java/io/scalecube/prism/sim/ReconfigSimCluster.java) and
[`ReconfigurationSafetyFuzzTest`](../prism-sim/src/test/java/io/scalecube/prism/sim/ReconfigurationSafetyFuzzTest.java),
whose config-aware oracle (`Leader(o)` = certified under current **or** previous config) is the DST
analogue of `SelfElectingQuorum.tla`'s safe run — and which deliberately constructs the unsafe
multi-member split-brain (`forceConfig` + `grantRaw`) to prove the oracle has teeth, exactly matching
TLC's depth-4 counterexample.

---

## 5. How the artifacts map to files

| Artifact | File |
|---|---|
| **Static lease-elector spec** (`AtMostOneLeader`, `AgreementPerEpoch`, `TypeOK`) | [`spec/LeaseElection.tla`](spec/LeaseElection.tla) + [`spec/LeaseElection.cfg`](spec/LeaseElection.cfg) |
| **Dynamic-membership spec — safe** (`ReconfigDelta = 1`, invariant holds) | [`spec/SelfElectingQuorum.tla`](spec/SelfElectingQuorum.tla) + [`spec/SelfElectingQuorum.cfg`](spec/SelfElectingQuorum.cfg) |
| **Dynamic-membership spec — unsafe** (`ReconfigDelta = 5`, counterexample) | [`spec/SelfElectingQuorum_unsafe.cfg`](spec/SelfElectingQuorum_unsafe.cfg) |
| **Recorded TLC results + model↔code map** | [`spec/README.md`](spec/README.md) |
| **DST harness** (virtual clock + seeded RNG, partitions/loss/skew, god-view oracle) | [`SimCluster.java`](../prism-sim/src/main/java/io/scalecube/prism/sim/SimCluster.java) |
| **Never-two-leaders + fencing-monotonicity fuzz** (300 seeds) | [`ElectorSafetyFuzzTest.java`](../prism-sim/src/test/java/io/scalecube/prism/sim/ElectorSafetyFuzzTest.java) |
| **Per-acceptor clock-skew fuzz** (±1 TTL skew, 300 seeds; exercises the zombie window) | [`SkewedClockSafetyFuzzTest.java`](../prism-sim/src/test/java/io/scalecube/prism/sim/SkewedClockSafetyFuzzTest.java) |
| **Fault injection** (30% loss + partitions + time jumps, 200 seeds) | [`FaultInjectionTest.java`](../prism-sim/src/test/java/io/scalecube/prism/sim/FaultInjectionTest.java) |
| **Multi-group at-scale stress** (8 groups, kill/revive churn) | [`MultiGroupChurnStressTest.java`](../prism-sim/src/test/java/io/scalecube/prism/sim/MultiGroupChurnStressTest.java) |
| **Self-electing-quorum DST** (config-aware oracle; unsafe-jump control) | [`ReconfigSimCluster.java`](../prism-sim/src/main/java/io/scalecube/prism/sim/ReconfigSimCluster.java) + [`ReconfigurationSafetyFuzzTest.java`](../prism-sim/src/test/java/io/scalecube/prism/sim/ReconfigurationSafetyFuzzTest.java) |
| **Decisions** | [`decisions/0010-sim-before-consensus.md`](decisions/0010-sim-before-consensus.md), [`decisions/0013-formal-verification-and-dst.md`](decisions/0013-formal-verification-and-dst.md) |

---

## 6. CI — both run on every change

Both pillars are wired into [`.github/workflows/ci.yml`](../.github/workflows/ci.yml):

- The **`build`** job runs `mvn -B -ntp verify`, which executes the full DST suite — the safety fuzzer,
  the fault-injection tests, the multi-group stress test, and the reconfiguration fuzz — on every push
  and PR.
- The **`spec`** job downloads `tla2tools.jar` and runs TLC over `LeaseElection.cfg` and
  `SelfElectingQuorum.cfg`, then runs `SelfElectingQuorum_unsafe.cfg` as a negative control whose exit
  status is **inverted** — the build fails if the unsafe config *stops* producing its split-brain
  counterexample. The DST fuzzers are the hard gate; the `spec` job is `continue-on-error` so a
  transient TLA+-tools download outage can't block a merge.

Spec↔code correspondence is therefore an enforced invariant: changing the acceptor rule means updating
the spec and re-checking, or CI surfaces the drift.

---

## 7. The return on this — a real bug it caught

This isn't ceremony. Formalising the `Accept` action **revealed a real defect**: the original rule
allowed a higher epoch to **preempt a still-valid lease** (a stickiness hazard). The spec forced the
precise rule — a different owner may take over **only when the lease is expired *and* the epoch is
strictly higher** — and the code was tightened to match, removing a bug class *before it shipped*
(see [`decisions/0013-formal-verification-and-dst.md`](decisions/0013-formal-verification-and-dst.md)).
Separately, DST surfaced a fencing-epoch *regression* across reconfiguration, which is why
`ReconfigSimCluster` models a mandatory high-water state-transfer step.

---

## 8. What is explicitly *out* of scope — the honest limits

| Limit | What it means |
|---|---|
| **Model checking is bounded** | TLC's results hold *within the configured constants* (3–5 nodes, epochs ≤ 3–4). It is not a proof for unbounded N — it is exhaustive only inside the box. |
| **DST samples, it does not exhaust** | Hundreds of seeds is deep adversarial coverage, but still a (large) sample of the infinite execution space. It can refute safety decisively and reproducibly; it cannot certify the whole space. |
| **Neither proves liveness in production** | Both target **safety** ("never two leaders," fencing monotonicity). Liveness ("a leader is eventually elected") is bounded by FLP and is exercised only in the healthy/healed convergence tests, not guaranteed under arbitrary asynchrony. |
| **DST models pause/partition, not state loss** | Acceptors keep their state across isolation (a pause/partition, not a state-losing crash). Hard crash-recovery needs durable acceptors before it can be modelled here — a tracked gap for `prism-persistence`. |

The combination is **far stronger than tests alone** and matches industry best practice for this class
of system — but it is *bounded safety with strong evidence*, not an unconditional theorem. That
honesty is the point: see [`guarantees.md`](guarantees.md) and [`spec/README.md`](spec/README.md).
