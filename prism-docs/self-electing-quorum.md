# Self-electing quorum in prism — how a quorum grows and heals itself, safely

prism's consensus tier normally runs over a **hand-listed static quorum** — the supported, default,
production path. The *self-electing* (dynamic) quorum lets that group **form, size, and heal itself**
through consensus instead of being maintained by hand. This page explains the mechanism, why the
safety crux is **single-member reconfiguration**, and — honestly — what is implemented today versus
what is still deferred.

> **One-line answer:** the consensus group reconfigures *itself* through consensus, **one member at a
> time**, so adjacent configurations always have overlapping majorities — two leaders can never be
> certified across a config change. It is **opt-in and gated**: shipped behind
> `PrismConfig.withDynamicQuorum(target)`, with the static quorum remaining the default (ADR-0015).

---

## 1. The problem — a fixed roster can't grow or heal itself

A static quorum (ADR-0007, ADR-0012) is a member list you maintain by hand. It works, but it can't
**form itself** from what the cluster already knows, **size itself** to a target (a 10-node cluster
should run a 3- or 5-node quorum, not a 10-node one), or **heal itself** when a quorum member dies
permanently. Doing those automatically is exactly where consensus systems historically break:
membership change is *the* classic source of split-brain (Raft's joint-consensus and
single-server-change subtleties; Jepsen has found membership-change split-brains in production
systems). The whole design problem is to get self-formation/sizing/healing **without** ever allowing
two leaders. See [`decisions/0015-self-electing-quorum.md`](decisions/0015-self-electing-quorum.md).

---

## 2. The approach in 60 seconds

Three rules carry the safety; everything else is policy.

1. **Bootstrap from the seed roster.** The initial config `C0` is a deterministic subset of the seed
   roster `R`, committed only once a majority of `R` is reachable (a lone "I am the quorum"
   bootstrap is provably split-brain-prone — ADR-0015 §5).
2. **Reconfigure through consensus, one member at a time.** The live gossip view only *suggests*
   changes; it never mutates the active config. Each change is committed by a **majority of the
   current config**, so configs form a totally ordered single-step chain `C0 → C1 → C2 → …`, each
   adjacent pair differing by exactly one member.
3. **The current leader is the sole reconfigurator.** Only the lease holder originates a change, so
   config proposals are serialized by leadership itself — there are never two competing proposers.

Single-member changes are what guarantee adjacent configs' majorities **overlap**, which is what
preserves "never two leaders" across a reconfiguration (§4).

---

## 3. How it maps to the code

The configuration and its evolution policy live in
[`QuorumConfig`](../prism-consensus/src/main/java/io/scalecube/prism/consensus/QuorumConfig.java); the
leader-driven loop lives in
[`ReconfigurationManager`](../prism-consensus/src/main/java/io/scalecube/prism/consensus/ReconfigurationManager.java);
config commits are disseminated by a
[`ConfigReplicator`](../prism-consensus/src/main/java/io/scalecube/prism/consensus/ConfigReplicator.java)
over the
[`TransportConfigReplicator`](../prism-consensus/src/main/java/io/scalecube/prism/consensus/TransportConfigReplicator.java);
the dynamic node is wired by `QuorumNode.attachDynamic`
([`QuorumNode.java`](../prism-consensus/src/main/java/io/scalecube/prism/consensus/QuorumNode.java)).

| ADR-0015 concept | prism code |
|---|---|
| **Committed config** `(epoch, members, previous)` | `QuorumConfig` — holds current + previous member set and a monotonic config epoch that totally orders the chain. The wire form is [`ConfigRecord`](../prism-consensus/src/main/java/io/scalecube/prism/consensus/ConfigRecord.java) `(epoch, members)`. |
| **Single-member commit rule** | `QuorumConfig.commit(newEpoch, newMembers)` accepts only a strictly higher epoch **and** a single-member delta; multi-member jumps throw, they are not merely discouraged (`isSingleMemberChange`). |
| **Follower catch-up** | `QuorumConfig.adopt(...)` — a lagging node may have missed several individually-single-member steps, so adopt requires only a higher epoch, never originating a change. |
| **Deterministic membership planning** | `QuorumConfig.planNextStep(current, live, roster, target, keep)` — a *pure* function: priority (1) drop a dead member, (2) grow toward `desiredSize`, (3) shrink toward it; candidates chosen by **sorted id** so every observer computes the same step. |
| **Odd target sizing** | `QuorumConfig.desiredSize(liveCount, target)` rounds down to odd (`1 → 3 → 5`), so the active size never settles on an even value (which tolerates *fewer* failures). |
| **Leader-driven loop** | `ReconfigurationManager.tick()` = `catchUp()` then, only `if (isLeader())`, one `step()`. `isLeader()` is "this node holds a valid lease for the group". |
| **Commit to current majority** | `ReconfigurationManager.step()` → `replicator.commit(record, current)` must be adopted by a majority of the **current** config before `config.commit(...)` advances locally. |
| **Per-operation membership re-read** | the dynamic store is built with `config::members` as its membership supplier (`QuorumNode.attachDynamic`); `QuorumConsensusStore.compareAndSet/get/prepare` each call `membership.get()` afresh, so quorum math always uses the *currently committed* config. |
| **Opt-in gate** | `PrismConfig.withDynamicQuorum(target)` ([`PrismConfig.java`](../prism-runtime/src/main/java/io/scalecube/prism/runtime/PrismConfig.java)); the control-group loop runs in [`PrismImpl`](../prism-runtime/src/main/java/io/scalecube/prism/runtime/PrismImpl.java). |

The acceptor rule itself is **unchanged** — dynamic quorum reuses the exact safety kernel
(`Acceptor.handle`) that single-decree Paxos uses (see [`paxos.md`](paxos.md)); it only changes
*which set of acceptors* a quorum is counted over, and re-reads that set per operation.

---

## 4. Why single-member is the safety crux

The argument is quorum intersection across *adjacent* configs:

- **Lemma (single-member intersection).** If two configs differ by at most one member, any majority
  of one and any majority of the other share at least one acceptor (ADR-0015 §8, Lemma 1).
- **Theorem (mutual exclusion across reconfiguration).** Under the acceptor rule with single-member
  reconfiguration, no two distinct owners are simultaneously certified under adjacent configs —
  because their majorities overlap, and an acceptor holds at most one lease per group (§8, Theorem 1).

This is not a stylistic preference; it is **necessary**, and the formal model demonstrates the
failure when you break it. The companion TLA+ spec
[`spec/SelfElectingQuorum.tla`](spec/SelfElectingQuorum.tla) models a current and a previous config
plus a `Reconfigure` action bounded by a `ReconfigDelta` constant. TLC was run on both configs
([`spec/README.md`](spec/README.md)):

| Config | `ReconfigDelta` | Result |
|---|---|---|
| [`spec/SelfElectingQuorum.cfg`](spec/SelfElectingQuorum.cfg) | `1` (single-member) | **No error** — `AtMostOneLeader` & `TypeOK` hold over 86,298,140 states (4,865,740 distinct, depth 21). |
| [`spec/SelfElectingQuorum_unsafe.cfg`](spec/SelfElectingQuorum_unsafe.cfg) | `5` (any jump) | **Counterexample at depth 4** — a multi-member jump yields two disjoint majorities and two simultaneous leaders. |

The counterexample is the whole point: jump `{n1,n3,n4} → {n1,n2,n4}` (two members at once), owner
`a` keeps `{n1,n3}` of the *previous* config while owner `b` wins `{n2,n4}` of the *new* one — the
majorities are disjoint, so both are leader. Single-member reconfiguration is load-bearing, not
decorative.

---

## 5. §7.1 — the fencing high-water transfer to a joining member

Single-member reconfiguration gives you *mutual exclusion* for free. It does **not**, by itself, give
you *global fencing-token monotonicity*. If successive config steps evict the members that remember a
high epoch, a new leader can win a majority of the new config on fresh members that never saw that
epoch — and so be granted a fencing token **lower** than a previous leader's. Mutual exclusion still
holds, but a fence that can go backwards is no fence for external resources.

The fix (ADR-0015 §7.1) is mandatory and implemented: a committing reconfiguration re-establishes the
**fencing-epoch high-water** on a **majority of the new config** before the old config is retired. In
code, `ReconfigurationManager.step()` reads the high-water lease (`store.get(group)`) and calls
[`LeaseTransfer`](../prism-consensus/src/main/java/io/scalecube/prism/consensus/LeaseTransfer.java)`.transfer(member, highWater)`
(transport-backed
[`TransportLeaseTransfer`](../prism-consensus/src/main/java/io/scalecube/prism/consensus/TransportLeaseTransfer.java))
for the members of the planned config, counting acknowledgements; it commits **only if a majority of
the new config now holds the high-water** (else it retries — safe-unavailable, never unsafe).

A subtle but important correction surfaced here. The first cut transferred the high-water **only to
joining members** — and the TLA+ model, once extended with a `NoTokenRegression` invariant, proved
that is *insufficient*: a single-member **shrink** that drops a high-water holder lets a stale
lower-epoch lease regain a majority of the smaller config and be re-certified — a fencing regression
`AtMostOneLeader` does not catch. `SelfElectingQuorum.tla` now checks both invariants (and a
`LeaderDriven = FALSE` negative control reproduces the regression); a unit test
(`ReconfigurationManagerTest.shrinkDoesNotRegressFencingEpoch`) drives the **real**
`ReconfigurationManager` through the trace and is red on joiner-only, green on the majority carry.

---

## 6. What is implemented vs. deferred — the honest status

This is the most intricate, safety-critical subsystem in the project, and it ships **last and
opt-in**. ADR-0015 §13 records what passed before exposure.

**Implemented and verified** (opt-in behind `PrismConfig.withDynamicQuorum(target)`):

- Single-member commit + deterministic policy (`QuorumConfig`, `planNextStep`, `desiredSize`).
- Leader-driven reconfiguration with the §7.1 high-water carried to a **majority of the new config**
  (`ReconfigurationManager`) — the safety fix above.
- Config replication over the transport
  (`ConfigRecord` / `ConfigCodec` / `TransportConfigReplicator`, wired by `QuorumNode.attachDynamic`).
- **Durable committed-config chain** (`ConfigJournal` / `FileConfigJournal`): a restart resumes the
  committed config instead of resetting to C0 (see [`persistence.md`](persistence.md)).
- **Gossip-metadata-derived roster + live set** (`MetadataRoster`): each node advertises its consensus
  address; the roster/liveness come from `cluster.members()`. Configured members are the seed C0 and a
  fallback.
- The `PrismImpl` control-group loop; elector-driven reconfiguration end-to-end.
- TLA+ model-checking of **`AtMostOneLeader` *and* `NoTokenRegression`** (single-member + leader-driven
  both shown load-bearing via negative controls); a deterministic-simulation fuzz over the **real**
  acceptor/store/elector kernel; an elector-driven real-transport E2E (`DynamicQuorumE2eTest`); and
  `RealReconfigSimCluster` — a seeded fuzz that drives the **real** `ReconfigurationManager` itself
  under partitions, loss and kill/revive churn (it caught a real quorum-intersection bug: a
  non-member proposer counting its own vote — now fixed and regression-tested).

**Explicitly deferred / under investigation today** (ADR-0015 §14):

- **Journal compaction** — *shipped*: the lease/config journals compact in place to bound disk and
  restart cost (see [`persistence.md`](persistence.md)). A tunable interval + per-record checksum
  remain possible polish.
- **Joint consensus / multi-member jumps** — viable but deliberately not built; single-member changes
  give the same safety with far less state and model-checking surface (§11). Kept in reserve.
- **`forceReconfigure`** — the operator-acknowledged escape hatch for majority loss is unsafe by
  construction and is a documented correctness hole, not a feature (§9, §14).

Majority loss is **unavailable by design**: if more than half a config's members fail at once, no
majority exists to commit even the healing reconfiguration, so the system safely stops rather than
overriding the majority rule. That is the price of "never two leaders," not a defect.

---

## 7. Proof / evidence

Two complementary artifacts, both run 2026-06-28:

- **Model checking.** [`spec/SelfElectingQuorum.tla`](spec/SelfElectingQuorum.tla) with
  [`SelfElectingQuorum.cfg`](spec/SelfElectingQuorum.cfg) (`ReconfigDelta=1`) proves
  `AtMostOneLeader` exhaustively; [`SelfElectingQuorum_unsafe.cfg`](spec/SelfElectingQuorum_unsafe.cfg)
  (`ReconfigDelta=5`) produces the split-brain counterexample — together showing single-member is
  *necessary*. Details and the full TLC numbers are in [`spec/README.md`](spec/README.md).
- **Deterministic simulation.** A reconfiguration/auto-heal fault mode in `prism-sim` drives the real
  `Acceptor` / `QuorumConsensusStore` / `LeaseElector` through seeded single-member growth/shrink,
  partitions, link loss, and clock jumps. "Never two leaders" holds across 300 seeds × 200 steps; a
  negative-control reconstructs the multi-member split-brain to confirm the oracle isn't vacuously
  green; and the fencing-epoch regression that motivated §7.1 is reproduced and repaired (ADR-0015
  §13.2).

Model checking abstracts the protocol; simulation exercises the code — see
[`guarantees.md`](guarantees.md) and [`decisions/0015-self-electing-quorum.md`](decisions/0015-self-electing-quorum.md).
The static quorum remains the default and supported production path until this path's remaining
hardening (§6) lands.
