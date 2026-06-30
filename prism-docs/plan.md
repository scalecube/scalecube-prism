# Roadmap & status

How prism is built, what is done, what remains — and the evidence for every "done". This is the
overview; per-phase work items and definition-of-done live in [`phases/`](phases/README.md), and the
binding *why* behind each choice lives in the [ADRs](decisions/).

The L0 core (`scalecube-cluster`) stays stable underneath the whole time — there is **no big-rewrite
milestone**. Prism is a decorator on top of it (ADR-0001).

---

## 1. Where we are

The **headline objective is met**: a partition-safe singleton elector and an AP service registry, on
one gossip substrate, with a one-line API — built, working end-to-end across real netty clusters, and
verified by both formal methods and deterministic simulation. 12 code modules, 15 ADRs, 2 TLA+ specs,
63 tests across 20 classes.

The **self-electing (dynamic) quorum is now implemented and shipped** opt-in
(`PrismConfig.withDynamicQuorum`): formally verified (TLA+ `AtMostOneLeader` + `NoTokenRegression`),
leader-driven single-member reconfiguration with the §7.1 high-water carried to a majority of the new
config, a durable committed-config chain, and a roster derived from cluster-gossip metadata — see §4.
What remains is the Phase 5 research frontier (quorum read-repair tier, EPaxos) plus the Phase 0 L0
hardening that lives upstream in `scalecube-cluster`. (Journal compaction — once an open item — now
ships: the append-only lease/config journals compact in place, bounding disk and restart cost.)

---

## 2. Progress matrix

Legend: ✅ done & verified · 🟡 partial · ⬜ not started.

| Capability | State | Evidence |
|------------|:----:|----------|
| Deterministic simulator (virtual clock, seeded RNG) | ✅ | `prism-sim/SimCluster`, `ReconfigSimCluster` |
| HLC versioning, single-writer, restart-safe | ✅ | `prism-versioning`; [ADR-0003](decisions/0003-single-writer-lww-versioning.md) |
| AP service registry (LWW-CRDT, watch, freshness) | ✅ | `prism-registry`; [guarantees](guarantees.md) |
| Delta + Merkle-root live anti-entropy | ✅ | `MerkleTree`, `GossipServiceRegistry`; bench below |
| Majority-quorum lease + monotone epoch + fencing | ✅ | `prism-consensus`; [ADR-0012](decisions/0012-distributed-quorum-lease-elector.md) |
| Singleton elector, one-line `Prism` API | ✅ | `prism-elector`, `prism-runtime`; `prism-examples`; [ADR-0011](decisions/0011-public-api-shape.md) |
| Durability (WAL acceptor journal + durable HLC) | ✅ | `prism-persistence` |
| Schema'd binary wire codec (no deserialization-RCE) | ✅ | `prism-codec`; [ADR-0009](decisions/0009-schema-codec-no-jdk-serialization.md) |
| Observability metrics | ✅ | `prism-observability`; [ADR-0014](decisions/0014-observability-metrics-spi.md) |
| Formal spec — lease election | ✅ | `spec/LeaseElection.tla` (TLC: AtMostOneLeader, AgreementPerEpoch) |
| Formal spec — self-electing quorum | ✅ | `spec/SelfElectingQuorum.tla` (TLC: safe holds 86M states; unsafe → counterexample) |
| DST — elector safety under chaos | ✅ | `ElectorSafetyFuzzTest`, `FaultInjectionTest` (500 seeds), `SkewedClockSafetyFuzzTest` (per-acceptor skew, 300 seeds); [ADR-0013](decisions/0013-formal-verification-and-dst.md) |
| DST — dynamic-quorum safety | ✅ | `ReconfigurationSafetyFuzzTest` (300 seeds) |
| CI (build + tests + TLC) | ✅ | `.github/workflows/ci.yml` |
| Benchmarks | ✅ | `prism-bench` (numbers below) |
| **Self-electing quorum — implementation** | ✅ | shipped opt-in (`withDynamicQuorum`): leader-driven single-member reconfig + §7.1 majority high-water carry + durable config chain + gossip-metadata roster; verified by TLA+ (`AtMostOneLeader` + `NoTokenRegression`), unit, real-transport & elector-driven E2E, and DST. Open: journal compaction |
| **Leader affinity** (preferred/sticky/no-failback + promote/demote) | ✅ | `affinity`/`promote`/`demote` on the elector; [ADR-0016](decisions/0016-leader-affinity.md); `LeaseElectorAffinityTest` (8) + `LeaderAffinityExample` (`force` promote pending) |
| `QUORUM` read-repair tier | ✅ | `ServiceRegistry.lookupQuorum` — majority fan-out over the cluster's own transport + LWW repair (`RegistryReadCodecTest`, `QuorumReadE2eTest`). Opt-in per read; not yet auto-applied per stored tier |
| EPaxos leaderless engine | ⬜ | [ADR-0007](decisions/0007-raft-first-epaxos-shaped.md) rationale only |
| Registry owned-slice persistence | ⬜ | versions durable; the slice itself is not |
| Full Raft command-log (`CONSENSUS` tier state) | ⬜ | election-only today |
| Phase 0 — L0 hardening (Lifeguard, metadata versioning) | 🟡 | spec'd here; lives in `scalecube-cluster` |

### Measured (prism-bench, indicative)
HLC `now()` ~44.5M ops/s · `RegistryStore.apply` ~5.4M ops/s · `Acceptor.handle` ~27.8M ops/s ·
Merkle build(100k keys) 14 ms, diff(1 changed) 0.16 µs. The strong path is consensus-RTT-bound, not
CPU-bound; these confirm the data-plane primitives are not the bottleneck.

---

## 3. What is proven (and how)

Two independent kinds of evidence, by design — model checking is exhaustive within bounds; simulation
exercises the *real* code across randomized histories. See [`guarantees.md`](guarantees.md) for the
authoritative contract and [`spec/README.md`](spec/README.md) for the TLC results and traces.

- **Mutual exclusion (never two Actives).** `AtMostOneLeader` model-checked; held across 500 elector
  seeds and 300 reconfiguration seeds on the real kernel under partition, loss and clock skew.
- **Fencing-epoch monotonicity.** `AgreementPerEpoch` model-checked; asserted in every DST run. DST
  found that single-member reconfiguration alone does *not* guarantee the *global* token order, which
  drove the high-water state-transfer rule (ADR-0015 §7.1) — now validated.
- **Registry convergence (SEC).** Per-source LWW + monotone HLC ⇒ strong eventual consistency;
  asserted under reordering/duplication across seeds.
- **Crash safety.** Acceptor and HLC recover their high-water from the WAL; journal tests cover it.

---

## 4. Self-electing quorum — shipped & verified ✅

The deepest and highest-risk subsystem. Per [ADR-0015](decisions/0015-self-electing-quorum.md) it was
gated behind a verification bar **before any code**; that bar passed, and the implementation then
hardened against a real safety gap the extended model surfaced.

**Verified:**
- ✅ **Model checking.** `SelfElectingQuorum.tla` proves **`AtMostOneLeader`** (single-member safe;
  multi-member jump → split-brain counterexample) **and `NoTokenRegression`** (leader-driven safe;
  an unconstrained config swap → fencing-regression counterexample). Safe config: 614,330 distinct
  states, depth 16. CI runs all three configs (two as negative controls).
- ✅ **DST.** `ReconfigSimCluster` + `ReconfigurationSafetyFuzzTest` — never-two-leaders + monotone
  fencing across 300 seeds, plus self-formation, self-heal, and an oracle-has-teeth negative control.
- ✅ **The finding.** Extending the model with `NoTokenRegression` proved single-member alone is
  *not* sufficient: transferring the §7.1 high-water only to joiners lets a shrink resurrect a stale
  lower-epoch lease. Fixed by carrying the high-water to a **majority of the new config** before
  retiring the old one (regression test drives the real `ReconfigurationManager`).

**Shipped (opt-in, `PrismConfig.withDynamicQuorum(target)`):**
1. ✅ `dynamicQuorum` flag + plumbing (static quorum stays the default).
2. ✅ Single-member reconfiguration committed through consensus (configs form a single-step chain).
3. ✅ §7.1 high-water carried to a **majority of the new config** (not just joiners) — the safety fix.
4. ✅ Deterministic replacement; odd-size targeting; leader-protected shrink; self-heal while a
   majority survives; safe-unavailable on majority loss.
5. ✅ Durable committed-config chain (`config.journal`) — no reset to C0 on restart
   (see [`persistence.md`](persistence.md)).
6. ✅ Gossip-metadata-derived roster + live set — each node advertises its consensus address; the
   quorum forms/heals from the live cluster instead of a hand-listed set.

**Acceptance — met:** the elector drives reconfiguration end-to-end (the real `ReconfigurationManager`
via `PrismImpl`, not the harness) in `DynamicQuorumE2eTest` (real 3-node netty: elects one leader,
self-heals to a single leader at a higher epoch on leader loss); the DST checks the same invariants;
and `DynamicQuorumTransportIntegrationTest` covers config replication + §7.1 over real transport.

**Open:** none for the dynamic quorum itself. (Journal compaction — §5, #7 — now ships: the
append-only journals compact in place, bounding disk and restart cost.)

### Dueling proposers — found by the E2E concurrent test, fixed with Paxos ✅
The black-box `ElectorE2eTest` (real netty, public API, reactive concurrent campaigns) surfaced a real
**liveness** gap — safety was never violated (never two leaders), only convergence:

- Under *perfectly-synchronized* concurrent campaigns, the original single-phase quorum CAS produced a
  **same-epoch split** with no majority, and the lease rule (takeover needs a *strictly higher* epoch)
  could not break the tie. A naïve escalation (best-effort max epoch) **broke fencing monotonicity** —
  the DST caught a `112 → 111` regression — so it was rejected.
- **Fix (shipped):** single-decree **Paxos** for acquisition — a `PREPARE`/promise round orders
  competing proposers (one ballot wins) and reveals the high-water from a majority, so escalation is
  monotone; a valid same-owner renewal is exempt from the promise guard, preserving stickiness. The
  randomized backoff (`[T, 2T]`) breaks dueling. Acquisition commits one ballot atomically, so no
  mixed-epoch state and fencing stays monotone. See ADR-0012.
- **Verified:** all 4 DST suites stay green (safety + fencing), and the formerly-`@Disabled`
  `concurrentCampaignElectsExactlyOneLeader` is **re-enabled and passing**.

---

## 5. The rest of the backlog (prioritized)

| # | Item | Why / acceptance | Risk |
|---|------|------------------|------|
| 1 | **Phase 0 — Lifeguard + metadata versioning** (in `scalecube-cluster`) | Suppresses false deaths that cause elector flapping; version-stamp `GetMetadataResponse` to fix the unversioned-pull race. **Accept:** no flapping under slow-node chaos; metadata converges by version. | Low — upstream, additive |
| 2 | ✅ **Self-electing quorum implementation** (§4) — *done* | Removed the static-config requirement; auto-form + self-heal from gossip metadata; §7.1 majority high-water carry; durable config chain. **Accepted:** §4 (elector-driven E2E + DST + TLA+). | High — safety-critical |
| 3 | **Registry owned-slice persistence** | Survive restart without re-advertising; pairs with durable HLC + stable `memberId`. **Accept:** restart replays the owned slice; no version regress. | Low–med |
| 4 | **`QUORUM` read-repair tier** | The middle of the dial: on-demand freshness across *k* without full consensus. **Accept:** read returns a value not older than any committed write it is concurrent with; sim-proven. | Med |
| 5 | **Full Raft command-log** | Richer `CONSENSUS`-tier state beyond single-decree election (locks, small replicated maps). **Accept:** linearizability suite. | Med–high |
| 6 | **EPaxos engine** | Leaderless strong tier, aligned with the gossip ethos (ADR-0007). **Accept:** same linearizability suite behind the shared interface. | High |
| 7 | ✅ **Journal compaction** — *done* | The lease/config journals compact in place (atomic temp-file + fsync + rename) every ~1024 / ~256 appends down to the highest-epoch state, bounding disk and restart cost; the torn-final-record case is tolerated on recovery. Safety preserved (highest epoch always retained). A tunable interval + per-record checksum remain possible polish. | Med |
| 8 | ✅ **Partition + concurrent reconfiguration fuzz** — *found & fixed a real bug* | The new seeded driver (`RealReconfigSimCluster`) runs the **real** `ReconfigurationManager`/`ConfigReplicator`/`LeaseTransfer` and, with clean partitions enabled, caught a real quorum-intersection bug: `QuorumConsensusStore.collect` counted the local acceptor's vote even when the node was **not** a member of the current config, so a non-member proposer (lagging / reconfigured out) could win a lease with a **minority** of real members and fork a reconfiguration. Fixed (count self only when `members.contains(self)`) + regression test. The driver now **passes 200 seeds with partitions + loss + kill/revive down to a single survivor**. | High — safety-critical (resolved) |
| 9 | ✅ **Non-blocking renewal path** (review F5/F16, O1) — *done* | Each elector round is now structured **plan (under the lock) → store I/O (lock released) → commit (under the lock)**, so the blocking quorum round (`QuorumConsensusStore.collect`) and the durable acceptor's `fsync` never run while the lock is held; a per-group `roundInFlight` guard keeps same-group rounds serialized despite the released lock, and a "if the lease is no longer wanted on commit, release it" step keeps a racing `resign` from leaking a believer. A new `LeaseElectorConcurrencyTest` proves a lock-only call (`leadership`) does not block while a store round is parked in I/O (it would against the old code), and exercises the resign-during-acquire undo path; the full suite (sim safety fuzz, real-quorum integration, E2E concurrent contention) stays green. Safety is unchanged — it rests on the `ConsensusStore`, never on holding this lock. | Med — partition/tail-latency, not safety (resolved) |
| 10 | ✅ **Anti-entropy: exchange the diff** (review F6/F7, O2) — *done* | The beacon now carries the catalog's **per-bucket Merkle digest** (sparse-encoded by `MerkleDigestCodec`, so a small catalog beacons a few bytes), and `handleAntiEntropy` rebuilds the peer's tree (`MerkleTree.fromBucketHashes`), runs `MerkleTree.diff`, and re-advertises **only this node's owned entries in the differing buckets** (`reAdvertiseBuckets`) — `diff()` is now wired into the protocol, not just built. A new `diffReadvertisesOnlyTheChangedBucketNotTheWholeSlice` test asserts far fewer entries cross the wire than the owned slice (proportional to the delta); the Merkle-tree cache (F7) is preserved. A peer whose beacon can't be parsed falls back to the full-slice `reAdvertiseOwned()`, so convergence is unconditional. | Med — scaling/traffic, not safety (resolved) |

---

## 6. Sequencing rules (non-negotiable)

1. **Never** ship gossip-only singleton election — [ADR-0006](decisions/0006-consensus-not-gossip-for-election.md).
2. Simulator (Phase 1) **before** consensus/elector (Phase 3/4) — safety needs reproducible partition
   sim ([ADR-0010](decisions/0010-sim-before-consensus.md)). *(Done; the rule stands for all future
   safety-critical work.)*
3. No UDP / Aeron / self-electing quorum **preemptively** — measured optimizations only, behind a
   passed verification gate ([ADR-0008](decisions/0008-transport-tcp-stays-no-aeron-core.md)).
4. No reactive operators in the consensus core — [ADR-0004](decisions/0004-reactive-vs-deterministic-boundary.md).
5. Any new safety-critical mechanism gets a **TLA+ spec and a DST fuzz** before it ships
   (the bar ADR-0015 set, now the project standard).

```
done:  Phase 1 ─► Phase 2 ─► Phase 3 ─► Phase 4
                    │                       │
                    └──────────────► Phase 5 (verified ─► implement)
parallel: Phase 0 (upstream in scalecube-cluster)
```

---

## 7. Where you can stop

Each phase is independently valuable; you do not have to reach the end.

- **P0–1:** a cleaner, well-tested scalecube.
- **P2:** a versioned, causally-consistent registry beyond Eureka — many teams stop here.
- **P4 (current):** the safe singleton elector — the actual objective, met.
- **P5:** the full per-key consistency dial — the research frontier.
