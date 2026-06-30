# scalecube-prism — Product Document

> **One line.** prism turns the gossip cluster you already run into a self-healing **service registry**
> (AP) *and* a provably-safe **never-two-leaders** singleton elector (CP) — one fabric, one dependency,
> a per-key consistency dial. **AP by default, strong consistency opt-in.**

| | |
|---|---|
| **Product** | scalecube-prism — a registry + elector layer over `scalecube-cluster` |
| **Audience for this doc** | product, engineering-leadership, and evaluating architects |
| **Status** | Core shipped & verified (TLA+ + deterministic simulation + E2E/CI); selected tiers on the roadmap |
| **License / platform** | Apache-2.0 · Java 17+ · Maven · reactive (Project Reactor) API |
| **Source of truth** | This document is reconciled against the implementation (see §13 *Evidence* and §14 *Accuracy notes*). Where docs and code disagreed, code won. |

---

## 1. Executive summary

Every clustered system must continuously answer two questions: **"where is everyone?"** (discovery) and
**"who is in charge?"** (leadership). They are the same need — shared cluster state — at two different
consistency points. The industry default is to solve them with *two* systems: an eventually-consistent
registry (Eureka / Consul catalog / raw gossip) **plus** a strongly-consistent coordinator (ZooKeeper /
etcd) — two clusters to operate, two failure models, and a layer of glue between them.

**prism collapses both onto one substrate.** It rides the SWIM/gossip membership of `scalecube-cluster`
and adds:

- a **versioned, self-healing service registry** (AP) — correct under reordering and duplication, healed
  by Merkle anti-entropy, never a second cluster to run; and
- a **singleton elector** (CP) — at most one leader per group, **never two**, even during a network
  partition, with a monotone fencing token — its safety **model-checked in TLA+** and **fuzzed by
  deterministic simulation** rather than merely asserted.

A **per-key consistency dial** lets one cluster serve both: cheap gossip for the 99% of discovery data,
consensus only for the few keys that are leadership or locks.

**Why it matters commercially:** one dependency instead of two clustered systems means lower operational
cost, a single failure model to reason about, and a smaller attack surface — while the safety-critical
path carries machine-checked proof, which is the differentiator buyers of coordination infrastructure
actually care about.

---

## 2. The problem

### 2.1 Discovery in an AP world is deceptively hard
A gossip/SWIM cluster gives you membership cheaply, but a *registry* on top needs more than "who's alive":

- **Versioning** — events arrive out of order; without a per-key version a late, stale update silently
  overwrites a newer one. ("Last write the socket delivered" is *not* last-*writer*-wins.)
- **Convergence** — gossip drops and reorders; without anti-entropy two nodes can disagree forever and
  never notice.
- **Lifecycle** — a dead provider must disappear; a draining one must be deprioritized; a live deregister
  must not resurrect via a lingering gossip copy.
- **Security** — if the registry deserializes arbitrary objects off the wire (the JDK default), any peer
  that can spoof one achieves remote code execution.

### 2.2 Leadership over gossip is impossible to do safely — naively
The tempting shortcut, "elect the lowest live member id the gossip view agrees on," is **wrong**:

- **FLP** — in an asynchronous network you cannot guarantee consensus with a possibly-failed process;
  gossip membership is only *eventually* consistent, so two partitions can each believe they hold the
  lowest id and **both act as leader** — split-brain.
- **Detection ≠ decision** — a failure detector can *suspect*; only a *quorum* can *decide*. Safe
  leadership needs consensus machinery (a majority + fencing), not a membership view.

prism exists to provide discovery done *correctly* and leadership done *safely*, on one substrate, without
forcing the user to assemble and operate two distributed systems plus the glue between them.

---

## 3. Target users & personas

| Persona | Pain today | What prism gives them |
|---------|-----------|------------------------|
| **Platform / infra engineer** running a scalecube (or JVM micro-service) fleet | Operates Eureka *and* ZooKeeper/etcd; two upgrade cycles, two on-call runbooks | One library on the cluster they already run; one ops surface |
| **Service author** needing "exactly one active gateway/scheduler/owner" | Hand-rolls leader election over gossip and gets split-brain under partition | A one-line `campaign()` with a proven never-two guarantee + a fencing token |
| **Architect** evaluating coordination infra | Vendors *claim* safety; no evidence | TLA+ specs + deterministic-simulation fuzzing + E2E tests in-repo |
| **SRE** | A stale or duplicated registry entry causes mis-routing; no way to detect drift | HLC versioning + Merkle anti-entropy + observable convergence metrics |
| **Security reviewer** | Java-serialization on the wire = deserialization-gadget RCE | A schema'd binary codec; no `ObjectInputStream` anywhere |

---

## 4. Value proposition & positioning

- 🎚️ **One substrate, a whole spectrum.** Per-key tiers (eventual → causal → quorum → consensus) on a
  single gossip layer — pay for strong consistency only on the keys that need it.
- 🛡️ **Safety you can *check*, not just trust.** "Never two leaders" rests on single-decree Paxos, is
  model-checked in TLA+, and is fuzzed by deterministic simulation across hundreds of seeded
  partition/loss/skew/churn scenarios.
- 🔒 **Secure by construction.** A schema'd binary wire codec — no Java serialization, so no
  deserialization-gadget RCE surface.
- 🧩 **Layered, never forked.** `scalecube-cluster` stays a clean, upgradable dependency underneath;
  dependencies flow strictly downward.

### Positioning vs. the alternatives

| You want… | Common answer | prism |
|-----------|---------------|-------|
| Eventually-consistent discovery | Eureka, raw gossip | ✅ the `EVENTUAL`/`CAUSAL` tiers — *with* per-key versioning + anti-entropy |
| Strongly-consistent coordination | ZooKeeper, etcd, Consul | ✅ the `CONSENSUS` elector — no second cluster to operate |
| Both, today | ZK **and** Eureka (two systems) | **one** system with a per-key dial |
| A raw membership layer | scalecube-cluster | prism is the **registry + elector** on top of it |

---

## 5. Goals & non-goals

**Functional goals**
- A versioned, self-healing service registry with per-key consistency selection.
- A singleton elector: at most one active leader per group — sticky, self-healing, fenced.
- A one-object entry point that decorates an existing cluster.

**Non-goals (deliberate scope boundaries)**
- **Not a general-purpose datastore.** The registry is a fully-replicated *catalog* (KB–low-MB/node); for
  large or sharded data, use partitioning + a replication factor instead.
- **Not a replacement for scalecube-cluster.** prism layers on L0's public API and brings its own
  versioning.
- **Not a second ops surface.** prism rides the cluster you already operate.

---

## 6. Product capabilities (verified feature inventory)

Status legend, reconciled against the implementation:

- ✅ **Supported** — shipped and verified.
- 🟡 **Partial** — mechanism shipped; hardening or full wiring tracked in the [roadmap](plan.md).
- 🔭 **Roadmap** — designed, not yet built.

### 6.1 Service registry (discovery) — AP

| | Capability | What it does | Verified in |
|---|------------|--------------|-------------|
| ✅ | **register / update / deregister** | A node advertises its *own* services with a property map; `update` is a read-modify-write of a single property under lock; `deregister` writes a versioned tombstone (never a bare delete). | `GossipServiceRegistry`, `RegistryStore` |
| ✅ | **lookup / list** | Local, always-available reads of live entries (tombstones filtered); no network hop, possibly slightly stale. | `GossipServiceRegistry` |
| ✅ | **lookupQuorum** | Fresh-at-read-time lookup (the `QUORUM` tier): fans out to a majority of members over the cluster's own transport, merges last-writer-wins, repairs locally, returns the freshest; errors if a majority is unreachable. Opt-in per read. | `GossipServiceRegistry`, `RegistryReadCodec` |
| ✅ | **watch (snapshot-then-stream)** | A new subscriber gets the current catalog as `REGISTERED` events, then a live stream of `REGISTERED`/`UPDATED`/`DEREGISTERED`/`EXPIRED`. Best-effort live stream (not gap-free under extreme churn). | `GossipServiceRegistry` |
| ✅ | **Per-key versioning (HLC)** | Every entry is stamped by a Hybrid Logical Clock; readers apply an update **iff** its version is strictly greater — dissemination is idempotent and reorder-safe. | `HybridLogicalClock`, `RegistryStore.apply` |
| ✅ | **Single-writer-per-(owner,service) LWW-CRDT** | Each key has exactly one writer (its owner); self-echoes are ignored; merge is commutative/associative/idempotent → strong eventual consistency. | `RegistryStore`, `GossipServiceRegistry` |
| ✅ | **Delta gossip dissemination** | Each change is encoded as a compact binary delta and spread over the existing gossip transport — no coordinator. | `RegistryGossipCodec` |
| ✅ | **Merkle anti-entropy** | A periodic beacon carries a sparse Merkle digest; peers diff it (O(depth)) and **re-advertise only the differing buckets** — proportional-to-delta repair, not full re-sync. Root is cached and rebuilt lazily on change. | `MerkleTree`, `MerkleDigestCodec` |
| ✅ | **Membership-driven lifecycle** | A `REMOVED`/`DEAD` member's entries are purged (membership *is* the tombstone), emitting `EXPIRED`. | `handleMembership`, `purgeOwner` |
| ✅ | **Freshness tokens (session guarantees)** | `freshness(ownerId)` returns the highest version seen from that owner — an application-level handle for read-your-writes / monotonic reads. | `OwnerFreshnessToken` |
| ✅ | **Instance selection** | `lookup` returns every alive instance (one per owner) with an `alive()` flag; the caller filters/ranks by health, weight, zone, latency from properties. | `ServiceEntry`, examples |
| 🟡 | **Tombstone GC** | Tombstones are reclaimed by LWW supersession or owner purge; there is **no time-based GC**. Adequate today; flagged for a TTL sweep. | `RegistryStore` |

**How it works (elaboration).** The registry is a per-owner map of `(service → versioned entry)`. A write
path is `register/update/deregister → stamp an HLC version → RegistryStore.apply (LWW) → encode a binary
delta → gossip broadcast`; the read path is a pure local map read. Three design choices make this both
*available* and *correct*:

- **Single-writer-per-key removes write conflicts entirely.** A node only ever writes entries it owns, and
  the gossip receiver ignores its own echoes, so two nodes can never race on the same key. That is what
  turns the registry into a conflict-free replicated data type (CRDT): with no concurrent writers, merge is
  just "keep the higher version," which is commutative, associative, and idempotent — replicas that have
  seen the same updates agree regardless of arrival order or duplication. ✔ verified `RegistryStore.apply`
  rejects any update whose version is `≤` the stored one (`RegistryStore.java:61`).
- **`deregister` is a versioned tombstone, never a bare delete.** A delete that simply removed the key would
  be resurrected by the next anti-entropy round (a peer still holding the old entry would re-advertise it).
  Instead a tombstone is a normal higher-versioned write that says "gone"; it out-versions the live entry
  everywhere and only later gets reclaimed (by a newer entry or by owner purge). The trade-off is that
  tombstones linger — there is no time-based GC today (🟡), which is acceptable for a catalog but is flagged
  for a future TTL sweep.
- **`watch` trades a durable log for simplicity.** A subscriber gets a complete snapshot (`REGISTERED` for
  everything currently known) then a best-effort live multicast — it is *not* a gap-free event log: an event
  landing between snapshot capture and sink activation can be missed. The convergent source of truth is the
  store, so a consumer needing certainty reconciles via `lookup`/`list` (+ `freshness` for read-your-writes)
  rather than treating `watch` as a journal. ✔ verified (`GossipServiceRegistry.java:262–279`).

**Anti-entropy, elaborated.** Gossip alone can silently drop an update and leave two nodes disagreeing
forever. The Merkle beacon fixes that without re-sending the catalog: each node periodically broadcasts a
*sparse digest* of its catalog hashed into 2^depth buckets; a peer diffs the digest in `O(depth)` and
re-advertises **only the entries in the buckets that differ**. Repair cost is therefore proportional to the
*drift*, not the catalog size, and the Merkle root is cached and rebuilt lazily only after a local change.
This is the Dynamo/Cassandra anti-entropy pattern applied to a service catalog.

### 6.2 The consistency dial (per-key tiers)

**What it is, in one sentence.** Consistency in prism is **not a system-wide setting** — it is a property
*of each key*, chosen by that key's owner when the key is created. The "dial" is the four-value enum
`ConsistencyTier`; turning it up on one key does not affect any other key or any other node.

**Who sets it, and how.** The owner sets the tier at registration:

```java
registry.register("cache",  Map.of("region","eu"), ConsistencyTier.EVENTUAL).block(); // turned down
registry.register("orders", Map.of("weight","100"));  // 2-arg overload → defaults to CAUSAL
```

It is pinned at creation, travels with the entry (it's a field on every `ServiceEntry`, gossiped on every
delta), and is preserved across `update`. There is no per-*read* knob — the tier is a property of the key,
not of an individual request.

**The one rule.** *Declare the weakest tier that is still correct for that data.* The tiers form a ladder
from most-available to most-consistent; every rung **up** buys a stronger guarantee and **costs** latency
and availability (CAP under a partition; PACELC even without one). So you climb only as far as correctness
forces you — never higher.

```
available · fast · stale-tolerant   ─────────────▶   linearizable · safe · coordinated
   EVENTUAL    →    CAUSAL      →       QUORUM      →     CONSENSUS
```

| | Tier | **What you GET** (the guarantee) | **What it COSTS** | Choose it when… |
|---|------|----------------------------------|-------------------|-----------------|
| ✅ | `EVENTUAL` | Convergence only: replicas agree *eventually*; last-writer-wins per key; no ordering between reads. | Reads may be stale; no session guarantee. (Always local, never blocks.) | a brief stale value is harmless — tags, labels, coarse health |
| ✅ | `CAUSAL` *(default)* | Everything in `EVENTUAL` **plus** session guarantees — **read-your-writes** and **monotonic reads** (you never see your own write disappear, or time go backward), tracked by a freshness token. | Only carrying causal/freshness context. (Still AP and local.) | the 99% of discovery data — service properties, weight, status, version |
| ✅ | `QUORUM` *(via `lookupQuorum`)* | Read-time freshness: a read repairs across a **majority of members** to answer "is this current *right now*, before I route?" | The read does network round-trips to a majority — real latency, and it **errors** if a majority is unreachable. | the occasional lookup that must be fresh at the instant of routing |
| 🔭→✅ | `CONSENSUS` | **Linearizable**: a value chosen by a majority; the *never-two* property holds in **every** execution, even a partition. | Full CP cost: a quorum round-trip per decision, and the minority side stops answering under partition. | the rare *never-two* keys — leadership, singleton ownership, locks |

**A decision rule you can apply in one pass:**
1. Will a stale read cause a *correctness* bug (not just a momentary inefficiency)? **No →** `EVENTUAL`.
2. Might a caller write a value and immediately read it back, or must newer never be hidden by older?
   **Yes →** `CAUSAL` (this is why it's the default — it's the least-surprising cheap option).
3. Must the value be provably fresh *at read time* before you act on it? **Yes →** `QUORUM`.
4. Must there be *exactly one* of something, ever, even during a partition? **Yes →** `CONSENSUS`
   (reach for it through `elector()`, not a registry write — see below).

#### What is actually enforced today (the honest part)

The data model carries the tier on every entry; here is exactly how much of the ladder runs today:

- **`EVENTUAL` and `CAUSAL` are live and are the registry.** Both ride the same gossip CRDT; the visible
  difference is that `CAUSAL` honours the session guarantees via freshness tokens. `CAUSAL` is the
  default and the registry's local-read behaviour.
- **`QUORUM` is live as `registry.lookupQuorum(service)`.** Because the registry is fully replicated, the
  read fans out to a **majority of live members** over the cluster's own transport, merges replies
  last-writer-wins, **repairs** the local store, and returns the freshest instances — erroring with
  `QuorumUnavailableException` if a majority is unreachable (CAP). One nuance: it is **opt-in per read**
  (you call `lookupQuorum`), *not yet* auto-applied to every read of a key whose stored tier is `QUORUM`.
  Verified by `RegistryReadCodecTest` + `QuorumReadE2eTest`.
- **`CONSENSUS` is live — but as the *elector*, not as a registry tier.** Electing a leader *is* choosing a
  key at the `CONSENSUS` tier, and that machinery (single-decree Paxos lease, §6.3) is the most heavily
  verified surface in the project. What does **not** exist is a per-key Paxos register behind a
  `CONSENSUS`-tagged *registry* write. If you need a linearized decision, call `elector()` — do not tag a
  `register` as `CONSENSUS` and expect linearizability.

> **Bottom line.** Three rungs now have running code: `EVENTUAL`/`CAUSAL` (local gossip reads) and
> `QUORUM` (the opt-in `lookupQuorum` majority read-repair). The strong rung (`CONSENSUS`) is real but
> delivered through the elector API rather than registry-key routing. What remains is the **automatic L2
> router** that would dispatch *every* read/write to the mechanism matching the key's stored tier — and
> because the tier is already pinned and gossiped on every entry, when it lands no data needs reshaping.
> See §14.2.

### 6.3 Singleton elector (leadership) — CP

| | Capability | What it does | Verified in |
|---|------------|--------------|-------------|
| ✅ | **Never-two-leaders election** | At most one member is `active` per group, via **single-decree Paxos** (PREPARE/promise + ACCEPT) over a majority quorum — safe even under partition. | `Acceptor`, `LeaseElector` |
| ✅ | **Monotone fencing epoch** | Every leadership carries an always-increasing epoch (the Paxos ballot); stamp it on external actions and the downstream rejects a lower epoch, neutralizing a zombie ex-leader. | `LeaseRecord.epoch`, `Leadership.epoch()` |
| ✅ | **Sticky leadership** | A healthy leader renews without a PREPARE; a challenger's PREPARE **cannot preempt** a valid same-owner lease (the load-bearing Acceptor rule). | `Acceptor` (promise guard + same-owner renewal exemption) |
| ✅ | **Safe, bounded failover** | On ungraceful death the lease expires (≤ `leaseTtl`) and a standby wins at a higher epoch; graceful `resign` hands off near-instantly. | `LeaseElector` |
| ✅ | **Per-group independent leaders** | Election is scoped to a group key; `service-A` and `service-B` elect independent leaders on the same machinery; failover in one never touches the other. | `LeaseRecord` (group key), `MultiGroupElectionExample` |
| ✅ | **Leadership stream & queries** | `leadership(group)` streams active/revoked transitions (with epoch); `campaign` / `resign` / `currentLeader`. | `SingletonElector`, `LeaseElector` |
| ✅ | **Partition-safe majority quorum** | A majority lease across an odd number of failure domains tolerates losing any one; the minority is *safely unavailable* — it loses availability, never safety. | `QuorumConsensusStore` |
| ✅ | **Dedicated consensus transport** | The elector binds a transport separate from gossip, so consensus RTT and gossip cadence are tuned independently. | `PrismConfig`, `QuorumNode` |
| ✅ | **In-memory store option** | `InMemoryConsensusStore` for single-JVM demos/tests; `QuorumConsensusStore` for real distributed deployment. | both present |

**How it works (elaboration).** Leadership is a *lease* won through **single-decree Paxos** and kept alive
by renewal. The acceptor is the safety kernel — ~50 lines whose rule I read in full:

- **Acquire (two phases).** A proposer sends `PREPARE(ballot)` to a majority; each acceptor promises the
  ballot only if it is `≥` the highest it has already promised, and replies with what it knows. Having a
  majority promise, the proposer sends `ACCEPT(self@ballot)`. Two majorities always intersect, so at most
  one proposer can collect a majority for a given decision — that is the mathematical core of "never two."
- **Renew (one phase).** A current leader re-`ACCEPT`s at the same/greater epoch without a `PREPARE` — the
  fast path that keeps a healthy leader in place every tick.
- **The fencing epoch is the Paxos ballot**, so it is monotone by construction: a takeover of an *expired*
  lease is allowed only at a *strictly higher* epoch (`Acceptor.java:80`). A deposed leader's epoch is
  therefore permanently below the new one; a downstream that records the highest epoch it has seen will
  reject the zombie — closing the split-brain window end-to-end (the caller must honour the token; §12).
- **Stickiness is one carefully-placed clause.** A challenger's `PREPARE` raises the promised floor, which
  would normally block a lower-ballot `ACCEPT` — *except* a valid same-owner renewal is exempted, so a
  challenger cannot knock out a healthy leader merely by preparing a higher ballot. ✔ verified: the
  `validSameOwnerRenewal` exemption and the three-way accept rule are exactly `Acceptor.java:77–94`.

**Why Paxos and not a one-shot CAS.** A single-phase quorum compare-and-set livelocks when proposers
campaign in lockstep (each grabs its own acceptor → a same-epoch split → no majority, and the takeover rule
needs a strictly higher epoch nobody can safely learn). The `PREPARE` round both *orders* the proposers (one
ballot wins) and *reveals the high-water* from a majority so escalation stays monotone. **Failover cost:** on
a hard crash there is a bounded zero-leader window of `≤ leaseTtl` (5 s default) before a standby wins;
graceful `resign` makes it near-instant. That "briefly zero rather than possibly two" choice is mandatory in
a partitionable system and is the right one for anything that must not double-serve.

**Per-group independence** means the elector multiplexes any number of leases keyed by group string, each
with its own epoch and lease — one elector instance can run leadership for `gateway`, `scheduler`, and a
hundred shard-owner groups concurrently, and a failover in one is invisible to the others.

### 6.4 Leader affinity (ADR-0016)

| | Capability | What it does | Verified in |
|---|------------|--------------|-------------|
| ✅ | **Preference-biased election (Mode A)** | A `PREFERRED` node wins a *free/expired* lease immediately; a `STANDBY` waits a yield window; preference is re-evaluated every tick (locality can change). | `LeaseElector.affinity` |
| ✅ | **`INELIGIBLE` exclusion** | A drained node never campaigns (the no-failover analog). | `Preference` enum |
| ✅ | **Sticky / no-failback** | A returning preferred node never preempts a healthy leader; `autoMove` steps down once when a leader becomes non-preferred. | `LeaseElector` |
| ✅ | **Controller-driven promote/demote (Mode B)** | `promote` is cooperative (wins only if free); `demote` releases and stays passive. | `LeaseElector.promote/demote` |
| 🔭 | **`force` promote variant** | Preemptive promotion — there is deliberately **no** path to override a healthy lease today. | — |

**How it works (elaboration).** Affinity biases *who* leads without ever weakening *never two* — it only
influences a campaign when the lease is actually acquirable, and it rides the same acceptor kernel above.
There are two cooperative modes:

- **Mode A — autonomous (preference-biased).** Each node supplies a `Preference` evaluated every tick:
  `PREFERRED` campaigns immediately for a free/expired lease; `STANDBY` waits a yield window first (giving a
  preferred node time to win); `INELIGIBLE` never campaigns (a drained node). Because preference is
  re-read each tick, it tracks changing facts — e.g. "am I co-located with the active anchor's zone?" The
  classic use is an AZ-pinned gateway: keep leadership in the same AZ as some anchor, but never fail back
  aggressively. `autoMove` lets a leader that has *become* non-preferred step down once, cleanly.
- **Mode B — controller-driven.** Nodes stay passive and an external orchestrator calls `promote` /
  `demote`. `promote` is **cooperative**: it wins only if the lease is free/expired and otherwise returns
  `false` — it never preempts. `demote` releases and stays passive.

The deliberate gap is a **`force` promote** (🔭): there is intentionally no path to preempt a *healthy*
lease, because that would reintroduce the split-brain risk the elector exists to remove. The no-failback
property — a returning preferred node does not knock out the current healthy leader — falls straight out of
the acceptor stickiness clause verified in §6.3.

### 6.5 Self-electing / self-healing quorum (ADR-0015)

| | Capability | What it does | Verified in |
|---|------------|--------------|-------------|
| ✅ | **Dynamic quorum (opt-in)** | `withDynamicQuorum(target)` turns `quorumMembers` into a candidate roster; the quorum sizes itself to the odd target. Off by default (static quorum is the default). | `PrismConfig`, `ReconfigurationManager` |
| ✅ | **Single-member reconfiguration** | Grow/shrink/heal one member at a time, committed through consensus; adjacent configs' majorities overlap → never-two preserved across changes. The single-member rule is proven *necessary* in TLA+. | `ReconfigurationManager`, `QuorumConfig.isSingleMemberChange` |
| ✅ | **Fencing high-water state transfer (§7.1)** | The leader pushes its fencing high-water to a majority of the *new* config so the epoch floor can't regress — a fix the simulator surfaced. | `ReconfigurationManager` |
| ✅ | **Gossip-metadata roster** | Each node advertises its consensus address as gossip metadata, so the roster derives from the live cluster. | `MetadataRoster` |
| 🟡 | **Runtime transport wiring** | Mechanism + policy shipped and formally verified; remaining transport wiring lands in layers. | per ADR-0015 |
| 🔭 | **Durable epoch-floor on the dynamic path** | Hardening tracked in ADR-0015. | — |

**How it works (elaboration).** A static quorum is a hand-listed set of consensus addresses — correct, but
operationally rigid (replacing a dead member means a config edit and redeploy). The dynamic quorum
(`withDynamicQuorum(target)`, off by default) lets the group *form and heal itself*:

- **Single-member steps only.** The leader observes the live roster and plans one change at a time —
  grow `{n0}→{n0,n1}`, shrink, or replace a dead member. The reason it must be *single*-member is subtle and
  load-bearing: adjacent configurations must have *overlapping majorities* so that no two leaders can be
  elected across a change. A two-member jump can split into disjoint majorities → two leaders. This is not
  a guess — the TLA+ model includes a deliberate `_unsafe` config that makes a two-member change and TLC
  finds the split-brain counterexample (§13).
- **Fencing high-water transfer (§7.1).** Before a new member can participate, the leader pushes its fencing
  high-water to a majority of the *new* configuration, so the epoch floor can never regress through a
  reconfiguration — a fix the deterministic simulator surfaced (the `_nofence` counterexample proves it is
  necessary).
- **Roster from gossip.** Each node advertises its consensus address as cluster metadata, so the candidate
  roster is derived from the live cluster rather than a static list.

What is shipped and formally verified is the mechanism and policy; some runtime transport wiring and a
durable epoch-floor on the dynamic path are still landing in layers (🟡/🔭). The static quorum remains the
default and is the recommended choice unless you specifically need self-healing membership.

### 6.6 Durability & persistence (opt-in)

Enable with `withPersistenceDir(dir)` + a **stable member id**. All journals are write-ahead and `fsync`'d
before acknowledging; each keeps only the highest-epoch state and compacts in place (atomic rename), so
disk stays bounded.

| | Journal | File | What it guarantees | Verified in |
|---|---------|------|--------------------|-------------|
| ✅ | **Lease journal** | `lease.journal` | A restart never forgets the fencing high-water or hands out a lower epoch. Per-group highest-epoch lease; compacts every 1024 appends. | `FileLeaseJournal` |
| ✅ | **Clock journal (HLC high-water)** | `clock.journal` | HLC versions never regress across restarts (high-water persisted *ahead* of issuance). | `FileClockJournal` |
| ✅ | **Config journal** | `config.journal` | The committed quorum-config chain survives restarts (dynamic-quorum path only). | `FileConfigJournal` |

**How it works (elaboration).** Durability is opt-in (`withPersistenceDir`) and exists to protect the two
things that must *never go backwards*: the fencing epoch and the HLC version. The shared discipline is
**write-ahead + fsync before acknowledge**, plus **highest-state-only** retention so disk stays bounded:

- **Lease journal** — every acceptance is appended and `force(true)`-synced *before* the acceptor replies,
  so a crash can never forget a lease it acknowledged or hand out a lower epoch on restart. It keeps the
  highest-epoch lease per group and compacts in place every ~1024 appends (write temp → fsync → atomic
  rename), so the file stays ≈ one line per group.
- **Clock journal** — the HLC persists a high-water *ahead* of the versions it actually issues
  (a 1-second persist window), so it only fsyncs ~once per window rather than per stamp, yet on restart it
  resumes strictly above anything it could have handed out. ✔ verified: `persistAhead()` advances the bound
  by a window and the constructor resumes from the persisted value (`HybridLogicalClock.java:70–75, 64–67`),
  and `now()`/`update()` are strictly monotonic (`:82–91, 100–122`).
- **Config journal** — on the dynamic-quorum path, the committed configuration chain is journaled the same
  way so membership survives restarts.

The one operational contract: **a stable member id**. Durable state is keyed by node identity, so a node
that restarts under a fresh id would not recognise its own journaled leases — the docs call this out, and it
is the single most common persistence pitfall.

### 6.7 Security

| | Capability | What it does | Verified in |
|---|------------|--------------|-------------|
| ✅ | **Schema'd binary wire codec** | Every cross-node message is encoded field-by-field via primitive `DataOutput/InputStream` — **no `ObjectInputStream`**, so there is no deserialization-gadget RCE surface. | `WireWriter`, `WireReader` |
| ✅ | **Versioned, bounded messages** | A leading version byte validates and bounds every message and enables wire evolution; spoofed junk is rejected without constructing an object graph. | `RegistryGossipCodec`, `MerkleDigestCodec` |

**How it works (elaboration).** The threat being designed out is the deserialization-gadget RCE — the class
of bug behind the Log4Shell family — which exists whenever a process calls `ObjectInputStream.readObject` on
bytes an attacker can influence. prism's codec makes that impossible *by construction*: the reader and writer
handle **only** primitives and length-prefixed UTF strings through `DataInput/OutputStream`, and there is no
`readObject` anywhere on a network path. ✔ verified by reading `WireReader` end to end — its only inputs are
`readByte/Boolean/Int/Long` and a nullable `readUTF`, never object construction (`WireReader.java:9–13,
38–39`). Each message also carries a leading version byte, which both bounds/validates the frame (spoofed
junk is rejected before any structure is built) and lets the wire format evolve compatibly. The cost is that
every record needs a hand-written field-by-field codec — a deliberate trade of convenience for a removed
attack surface (ADR-0009).

### 6.8 Observability

| | Capability | What it does | Verified in |
|---|------------|--------------|-------------|
| ✅ | **Metrics SPI** | A minimal, dependency-free `Metrics` interface — `increment(name)` and `gauge(name, value)` — with a zero-overhead `NOOP` default. | `Metrics` |
| ✅ | **In-memory implementation** | `InMemoryMetrics` (thread-safe counters + gauges, with `count()`/`gaugeValue()` accessors) for tests and introspection. | `InMemoryMetrics` |
| 🔭 | **Micrometer / OpenTelemetry adapter** | The SPI is shaped for it, but **no concrete adapter ships today** — wiring one is the deployer's task (or a roadmap item). | — |

**Metric signals actually emitted (8):** `prism.elector.granted`, `prism.elector.revoked`,
`prism.registry.ae.beacon`, `prism.registry.ae.readvertise`, `prism.registry.ae.readvertise.entries`
(gauge), `prism.registry.event.registered`, `…updated`, `…deregistered` (plus `…membership_changed`).

**How it works (elaboration).** Observability is a deliberately thin SPI so prism stays dependency-free at
its core. ✔ verified the interface is exactly two methods — `increment(name)` and `gauge(name, value)` —
with a static `NOOP` that makes metrics zero-overhead until a sink is wired (`Metrics.java:30,38,12`). The
shipped concrete sink is `InMemoryMetrics` (thread-safe counters/gauges with `count()`/`gaugeValue()`
read accessors) for tests and introspection. A **Micrometer/OpenTelemetry adapter is not shipped** (🔭) —
the SPI is shaped for it and writing one is a small deployer task, but treating it as available today would
be wrong. The eight signals actually emitted (see table below the next paragraph) cover the two questions an
operator asks: *is exactly one leader being granted/revoked as expected?* (`prism.elector.*`) and *is the
catalog converging cheaply?* (`prism.registry.ae.*` beacons/re-advertisements, plus per-event counters).

### 6.9 Platform & integration

| | Capability | What it does |
|---|------------|--------------|
| ✅ | **One-object entry point** | `new PrismImpl(cluster[, config[, metrics]]).startAwait()` decorates a scalecube `Cluster`; `registry()` and `elector()` hang off it (`elector()` requires a `PrismConfig`). |
| ✅ | **Reactive + blocking API** | Mutating ops return `Mono<Void>`; `*Await()` blocking variants mirror scalecube conventions; streams are `Flux`. |
| ✅ | **Layered, never forked** | Dependencies flow strictly downward into `scalecube-cluster`; L0 stays upgradable. |
| ✅ | **14 runnable examples** | Simple → advanced; every capability on this page has a runnable program. |
| ✅ | **Java 17+, Maven** | `mvn clean install`; resolves `scalecube-cluster` from Maven Central / GitHub Packages. |

---

## 7. How it works (architecture at a glance)

```
  L4  Singleton elector      lease · epoch · fencing · affinity      prism-elector
  L3  Consensus engine       quorum lease · self-electing quorum     prism-consensus
  ───────── boundary: reactive above · deterministic below ─────────
  L2  Consistency router     per-key tier dispatch (EVENTUAL/CAUSAL local reads; QUORUM via lookupQuorum)
  L1  Service registry       versioned per-key CRDT map (reactive)   prism-registry
  L0  scalecube-cluster      SWIM + gossip + membership (AP)         (dependency)
```

- **Two ports per node** — the gossip transport (membership/registry) and a **dedicated consensus
  transport** (lease RPC) — sized and tuned independently.
- **Reactive plumbing, deterministic core** — L0–L2 are reactive (event streams); L3–L4 are a
  single-threaded deterministic state machine so a replicated decision is reproducible and model-checkable.
- **Quorum placement** — an odd number across failure domains; a 3-node quorum across 3 AZs survives any
  single-AZ loss and never elects two leaders; a minority side is *safely unavailable* by design.

Full treatment: [architecture.md](architecture.md) (context · module · component · runtime · deployment
views, formal foundations, quality-attribute scenarios).

---

## 8. Non-functional characteristics

- **Performance (indicative, prism-bench).** HLC `now()` ≈ 44.5M ops/s; `RegistryStore.apply` ≈ 5.4M
  ops/s; `Acceptor.handle` ≈ 27.8M ops/s; Merkle build(100k keys) ≈ 14 ms, diff(1 changed) ≈ 0.16 µs.
  The data-plane primitives are not the bottleneck.
- **Latency model.** The AP path (`lookup`/`list`) is local — no network hop. The strong path
  (`CONSENSUS` acquire/renew) is one majority round trip on the consensus transport — RTT-bound, not
  CPU-bound. Failover worst case ≈ `leaseTtl` on ungraceful death.
- **Availability / CAP.** PACELC **PA/EL**: on partition the registry favors availability and the minority
  elector side favors safety over availability; else favor latency. Majority loss is unavailable *by
  design* (never unsafe).
- **Scaling envelope.** Full replication suits catalogs of KB–low-MB/node across thousands of nodes; beyond
  that, partition the data (a non-goal).
- **Durability.** Write-ahead + `fsync` before ack; highest-epoch-only journals with in-place compaction.
- **Security.** No Java serialization anywhere on the wire; versioned, bounded messages.

---

## 9. Configuration surface (`PrismConfig`)

Constructed as `new PrismConfig(consensusAddress, quorumMembers, transportFactory)`; immutable, copied by
`with*` methods.

| Setting | Wither | Default | Controls |
|---------|--------|---------|----------|
| `consensusAddress` | (ctor) | required | this node's consensus-transport address; must equal what the transport advertises and appear in `quorumMembers` |
| `quorumMembers` | (ctor) | required | all quorum members' consensus addresses (candidate roster when dynamic) |
| `transportFactory` | (ctor) | required | factory for the dedicated consensus transport |
| `leaseTtl` | `withLeaseTtl` | **5 s** | lease validity before renewal is required |
| `tickInterval` | `withTickInterval` | **1 s** | renewal/acquisition cadence (keep ≤ `leaseTtl`/3) |
| `callTimeout` | `withCallTimeout` | **1 s** | per-peer consensus call timeout |
| `persistenceDir` | `withPersistenceDir` | none (in-memory) | enables the three journals; **requires a stable member id** |
| `dynamicQuorum` | `withDynamicQuorum` | **false** | self-electing/self-healing quorum (ADR-0015) |
| `targetQuorumSize` | `withDynamicQuorum(target)` | **3** (odd) | target quorum size when dynamic |

Full reference (every option, method, tier, tunable, metric): [config-reference.md](config-reference.md).

---

## 10. Use cases

| Story | Pattern | Feature | Validating test |
|-------|---------|---------|-----------------|
| Route to the best healthy instance | discover many, filter by `alive()` + rank by weight | registry + instance selection | `InstanceSelectionExample`, `UseCaseE2eTest` (A2) |
| Zero-downtime drain before deploy | set `weight=0` / `status=draining`, then `deregister` | registry `update` + lifecycle | `RegistryWatchExample`, `UseCaseE2eTest` (A4) |
| Exactly one active gateway/scheduler | `campaign` + `leadership` stream + fence every side effect | elector + fencing epoch | `GatewayElectionExample`, `PrismGatewayExample`, `ElectorE2eTest` |
| AZ-pinned leadership | `affinity` with `PREFERRED` in-zone | leader affinity | `LeaderAffinityExample`, `AffinityE2eTest` |
| Self-managing consensus group | `withDynamicQuorum(3)` + `withPersistenceDir` | self-electing quorum + durability | `DynamicQuorumExample`, `DynamicQuorumE2eTest` |
| Survive restarts without regressing fencing | journaled acceptor + stable id | durability | `DurableLeaseExample` |

Business-framed user stories: [use-cases.md](use-cases.md).

---

## 11. Competitive comparison

| Dimension | ZooKeeper / etcd | Eureka / raw gossip | Consul | **prism** |
|-----------|------------------|---------------------|--------|-----------|
| Discovery (AP) | (via separate tooling) | ✅ but no per-key version/anti-entropy | ✅ | ✅ versioned (HLC) + Merkle anti-entropy |
| Safe leadership (CP) | ✅ | ❌ (split-brain under partition) | ✅ | ✅ Paxos lease + fencing, **proven** |
| Per-key consistency dial | ❌ (strong everywhere) | ❌ (eventual everywhere) | partial | ✅ (eventual → causal live; quorum/consensus designed) |
| Second cluster to operate | yes | yes (with a CP store beside it) | yes | **no** — rides your existing cluster |
| Wire security | varies | varies | varies | ✅ no Java serialization by construction |
| Machine-checked safety | informal | none | informal | ✅ TLA+ + deterministic simulation |

---

## 12. Limitations & constraints (honest)

- **Registry is a catalog, not a database** — fully replicated, KB–low-MB/node; not for large/sharded data.
- **Majority loss is unavailable by design** — the only override would be an operator-acknowledged unsafe
  force.
- **End-to-end leader safety needs cooperation** — the fencing guarantee is only end-to-end true if the
  **downstream resource rejects a lower epoch**; prism provides the monotone token, honoring it is the
  caller's contract.
- **Tiers aren't auto-routed yet** — `QUORUM` is available as the opt-in `lookupQuorum` read, but neither
  `QUORUM` nor `CONSENSUS` is *automatically* applied to a key based on its stored tier; per-key
  `CONSENSUS` registry writes don't exist (use the elector for strong coordination). See §6.2, §14.
- **No shipped Micrometer/OTel adapter** — the SPI is ready; the adapter is not (§6.8).
- **Best-effort live `watch` stream** — the snapshot is complete; the live stream is not guaranteed
  gap-free under extreme churn (anti-entropy + re-subscribe reconciles).

---

## 13. Evidence & verification

The "verified" claims are backed by in-repo artifacts across three layers:

- **Formal (TLA+).** `spec/LeaseElection.tla` checks `TypeOK`, **`AtMostOneLeader`**, `AgreementPerEpoch`.
  `spec/SelfElectingQuorum.tla` checks safety across reconfiguration and `NoTokenRegression` — with two
  deliberate **counterexample** configs (`_unsafe`: multi-member change → split-brain; `_nofence`:
  unconstrained reconfig → epoch regression) proving each constraint is load-bearing.
- **Deterministic simulation (DST).** Seeded, reproducible fuzzing on the real consensus kernel:
  `ElectorSafetyFuzzTest` (300 seeds × 200 steps), `FaultInjectionTest` (30% loss + partitions + clock
  jumps), `SkewedClockSafetyFuzzTest` (skew up to ±1 TTL), `ReconfigurationSafetyFuzzTest`,
  `MultiGroupChurnStressTest` — all asserting `AtMostOneLeader` + fencing monotonicity. Any failure
  replays from its seed.
- **E2E on real transport.** `ElectorE2eTest`, `RegistryE2eTest`, `DynamicQuorumE2eTest`, `AffinityE2eTest`,
  `UseCaseE2eTest`, `NegativeCasesE2eTest` — real TCP cluster, public API, concurrent contention.

Project standard: any new safety-critical mechanism ships with a TLA+ spec and a DST fuzz.

### 13.1 Spot-verification log (this revision)

The feature inventory was first assembled by a parallel code audit; the following claims were then
**re-checked by reading the source directly** for this revision (✔ = confirmed against the cited lines):

| Claim | Evidence | Verdict |
|-------|----------|---------|
| Acceptor rule = free \| same-owner renew ≥ epoch \| take over expired at strictly higher epoch; promise guard exempts a valid same-owner renewal (stickiness) | `Acceptor.java:77–94` | ✔ |
| No Java serialization on the wire — only primitives + length-prefixed UTF, never `readObject` | `WireReader.java:9–13, 38–39` | ✔ |
| Metrics SPI is exactly `increment(name)` + `gauge(name, value)` with a `NOOP` default | `Metrics.java:30, 38, 12` | ✔ |
| Registry LWW rejects any update with version ≤ stored | `RegistryStore.java:61` | ✔ |
| `lookup` is an unconditional local read — no per-key tier routing (the freshness path is the separate `lookupQuorum`) | `GossipServiceRegistry.java:247–251` | ✔ (see §14.2) |
| `shutdown()` stops the underlying cluster | `PrismImpl.java:390` | ✔ (confirms §14.1) |
| `watch` = snapshot-then-best-effort-stream, not a gap-free log | `GossipServiceRegistry.java:262–279` | ✔ |
| HLC strictly monotonic + restart-safe via persisted high-water | `HybridLogicalClock.java:82–91, 100–122, 70–75, 64–67` | ✔ |
| `PrismConfig` defaults: leaseTtl 5s, tickInterval 1s, callTimeout 1s, targetQuorumSize 3 | `PrismConfig.java:20–26` | ✔ |

The dynamic-quorum single-member necessity and the fencing-transfer fix were verified at the *spec* level
(the `_unsafe` and `_nofence` TLA+ counterexample configs), not re-read line-by-line here.

---

## 14. Accuracy notes (for maintainers — reconciliation findings)

This document was reconciled against the code. One discrepancy between the existing prose docs and the
implementation remains open, and a second has since been partly addressed:

1. **`shutdown()` and cluster ownership.** The `Prism` interface javadoc and the README say prism "does
   not own the cluster's lifecycle." The implementation, `PrismImpl.shutdown()`, **does stop the
   underlying cluster** (`cluster.shutdown()`), and `PrismImpl`'s own javadoc says it owns that lifecycle.
   → Pick one contract and align the docs + interface javadoc to it. This document describes the
   *implemented* behavior (shutdown stops the cluster) and flags the conflict.
2. **`QUORUM`/`CONSENSUS` as registry tiers.** The `QUORUM` rung is now implemented as the opt-in
   `registry.lookupQuorum(service)` (majority fan-out + LWW repair over the cluster's own
   transport). What is *still* missing is **automatic** per-key routing — neither `QUORUM` nor `CONSENSUS`
   is applied to a read/write just because the key's stored tier says so — and there is no per-key
   `CONSENSUS` registry register (strong coordination is the elector API). The L2 auto-router is the
   remaining roadmap item.

Minor: the Metrics SPI methods are `increment`/`gauge` (an earlier draft referenced a `count` write
method — `count()` exists only as a *read* accessor on `InMemoryMetrics`).

---

## 15. Roadmap

- ✅ **`QUORUM` read-repair tier** — *shipped* as the opt-in `registry.lookupQuorum(service)` (majority
  fan-out + LWW repair over the cluster's own transport — no extra port).
- 🔭 **Automatic L2 tier routing** — apply `QUORUM`/`CONSENSUS` to a read/write *automatically* from the
  key's stored tier (today `lookupQuorum` is opt-in per read), and wire a registry key to be
  consensus-backed.
- 🔭 **Durable epoch-floor** on the dynamic-quorum path; **gossip-pool-derived roster**.
- 🔭 **EPaxos leaderless engine** and a full Raft command log for richer `CONSENSUS`-tier state.
- 🔭 **`force` promote variant** for leader affinity.
- 🔭 **Micrometer/OpenTelemetry adapter** for the Metrics SPI.

Live status matrix: [plan.md](plan.md). The full guarantee contract: [guarantees.md](guarantees.md).

---

## 16. Appendix — public API surface

**Entry point** — `Prism`: `start()` / `startAwait()`, `registry()`, `elector()`, `cluster()`,
`member()`, `shutdown()`.

**`ServiceRegistry`**: `register(service, props[, tier])` (defaults to `CAUSAL`), `update(service, key,
value)`, `deregister(service)`, `lookup(service)`, `lookupQuorum(service)` (→ `Mono<Collection<…>>`;
errors with `QuorumUnavailableException`), `list()`, `watch()`, `freshness(ownerId)`.

**`SingletonElector`**: `campaign(group)`, `resign(group)`, `leadership(group)` (`Flux<Leadership>`),
`currentLeader(group)`, `affinity(group, preferenceSupplier, yieldWindow, autoMove)`, `promote(group)`,
`demote(group)`.

**Core records / enums**: `ServiceEntry` (service, owner, address, properties, `Version`, tier, `alive`),
`RegistryEvent` (`REGISTERED`/`UPDATED`/`DEREGISTERED`/`EXPIRED`), `Leadership` (group, member, epoch,
active), `ConsistencyTier` (`EVENTUAL`/`CAUSAL`/`QUORUM`/`CONSENSUS`), `Preference`
(`PREFERRED`/`STANDBY`/`INELIGIBLE`), `Version` (HLC: logical, physical), `FreshnessToken` (ownerId, upTo),
`Metrics` (`increment`, `gauge`, `NOOP`).

---

### Documentation map

**Learn it:** [onboarding](../ONBOARDING.md) · [getting-started](getting-started.md) ·
[user-guide](user-guide.md) · [examples](../prism-examples/README.md)
**Understand it:** [architecture](architecture.md) · [guarantees](guarantees.md) · [context](context.md)
**Building blocks:** [CRDT+HLC](crdt-hlc.md) · [Merkle anti-entropy](anti-entropy-merkle.md) ·
[Paxos](paxos.md) · [tunable consistency](tunable-consistency.md) · [self-electing quorum](self-electing-quorum.md) ·
[codec & security](codec-security.md) · [formal verification + DST](formal-verification-dst.md)
**Operate it:** [config & API reference](config-reference.md) · [persistence](persistence.md) ·
[troubleshooting](troubleshooting.md) · [runbook](ops/runbook.md)
**Decide & prove:** [ADRs](decisions/) · [TLA+ specs](spec/) · [roadmap](plan.md)
