# prism — Architecture

> **One sentence.** prism takes one gossip membership substrate (scalecube-cluster's SWIM) and
> *refracts* it into a **per-key consistency spectrum** — a service registry and a provably-safe
> singleton elector on the same fabric, **AP by default, strong consistency opt-in**.

This document is the deep reference for *what prism is, why it exists, how it is built, and why each
choice is the right one.* It is written to be read top-to-bottom for fast clarity, then dipped into for
specific concerns. Binding rationale for individual decisions lives in the [ADRs](decisions/); the
authoritative guarantee contract is [`guarantees.md`](guarantees.md); the runnable usage is the
[user guide](user-guide.md).

**Contents**
1. [The pitch — what you get in 30 seconds](#1-the-pitch)
2. [The problem — why prism exists](#2-the-problem)
3. [Goals, non-goals, quality attributes](#3-goals-non-goals-quality-attributes)
4. [Considerations & key trade-offs](#4-considerations--key-trade-offs)
5. [The solution — conceptual model](#5-the-solution--conceptual-model)
6. [Architecture views](#6-architecture-views) (context · module · component · runtime · deployment · data)
7. [Cross-cutting concerns](#7-cross-cutting-concerns)
8. [Quality-attribute scenarios — answers to your concerns](#8-quality-attribute-scenarios)
9. [Formal foundations](#9-formal-foundations)
10. [Performance characteristics](#10-performance-characteristics)
11. [Alternatives considered](#11-alternatives-considered)
12. [Limitations & roadmap](#12-limitations--roadmap)
13. [Evidence & further reading](#13-evidence--further-reading)

---

## 1. The pitch

You have a cluster of services. You need two things almost everyone needs and almost everyone gets
subtly wrong:

1. **Discovery** — "who offers service X, where are they, and what are their properties right now?"
2. **Leadership** — "exactly one node owns this responsibility at a time — and *never two*, even during
   a network partition."

prism gives you both, on the membership layer you already run, behind a one-line wrapper:

```java
Prism prism = new PrismImpl(cluster, config).startAwait();
prism.registry();   // AP, versioned, self-healing service registry
prism.elector();    // CP, partition-safe "never two leaders" singleton
```

**What makes it different**

- **One substrate, a whole spectrum.** Most stacks bolt a strongly-consistent store (ZooKeeper/etcd)
  next to an eventually-consistent registry (Eureka/gossip) and operate two systems. prism runs **one
  fabric** and lets each *key* choose its consistency tier — eventual, causal, quorum, or consensus.
- **Safety you can check, not just trust.** The elector's "never two leaders" is **model-checked in
  TLA+** and **fuzzed by deterministic simulation** across hundreds of seeded partition/loss
  scenarios — not asserted by a README.
- **Secure by construction.** The wire format is a hand-rolled schema'd codec — **no Java
  serialization**, so there is no deserialization-gadget RCE surface (the class of bug behind Log4Shell
  cousins).
- **Built on, never forking, scalecube-cluster.** L0 stays a clean, upgradable dependency.

**Where prism fits**

| You want… | Reach for | prism's take |
|-----------|-----------|--------------|
| Eventually-consistent discovery | Eureka, raw gossip | ✅ the `EVENTUAL`/`CAUSAL` tiers — plus versioning + anti-entropy |
| Strongly-consistent coordination | ZooKeeper, etcd, Consul | ✅ the `CONSENSUS` elector — without a second cluster to run |
| Both, today, as two systems | ZK **and** Eureka | prism is **one** system with a per-key dial |
| A raw membership layer | scalecube-cluster | prism is the **registry + elector** layered on it |

---

## 2. The problem

### 2.1 Discovery in an AP world is deceptively hard
A gossip/SWIM cluster gives you membership cheaply and resiliently, but a *registry* on top needs more
than "who's alive":

- **Versioning.** In a distributed system events arrive out of order. Without a per-key version, a
  late-arriving stale update silently overwrites a newer one. (Plain "last write the socket delivered"
  is not last-*writer*-wins.)
- **Convergence.** Gossip drops and reorders messages. Without anti-entropy, two nodes can disagree
  forever on a key and never notice.
- **Lifecycle.** A dead provider must disappear; a draining one must be deprioritized; a live
  deregister must not resurrect via a lingering gossip copy.
- **Security.** If the registry deserializes arbitrary objects off the wire (the JDK default), any peer
  — or anything that can spoof one — can achieve remote code execution.

### 2.2 Leadership over gossip is *impossible* to do safely — naively
The tempting shortcut is "elect the lowest member id the gossip view agrees on." It is **wrong**:

- **FLP.** In an asynchronous network you cannot guarantee consensus with a possibly-failed process;
  gossip membership is *eventually* consistent, so two partitions can each believe they hold the lowest
  live id and **both act as leader** — split-brain.
- **Detection ≠ decision.** A failure detector can suspect; only a *quorum* can decide. Leadership is a
  consensus problem and needs consensus machinery (a majority + fencing), not a membership view.

prism exists to provide discovery done *correctly* and leadership done *safely*, on one substrate,
without making the user assemble (and operate) two distributed systems and a pile of subtle glue.

---

## 3. Goals, non-goals, quality attributes

### 3.1 Functional goals
- A **versioned, self-healing service registry** with per-key consistency selection.
- A **singleton elector**: at most one active leader per group, sticky, self-healing, fenced.
- A **one-object** entry point that decorates an existing cluster.

### 3.2 Non-goals
- A general-purpose datastore. The registry is a *catalog* (KB–low-MB/node), fully replicated; it is
  the wrong tool for large or sharded data (use partitioning + a replication factor instead).
- Replacing scalecube-cluster. prism layers on L0's public API and brings its own versioning.
- A second ops surface. prism rides the cluster you already run.

### 3.3 Quality attributes (and how the architecture delivers them)

| Attribute | How prism addresses it | Where |
|-----------|------------------------|-------|
| **Safety** (never two leaders) | single-decree majority-quorum lease + monotone fencing epoch; TLA+ + DST | §9, ADR-0012 |
| **Availability** | AP registry (local reads always succeed); minority loses availability, never safety | §8, ADR-0002 |
| **Partition tolerance** | quorum intersection; minority cannot acquire; registry converges on heal | §8, §9 |
| **Consistency, tunable** | per-key tier (eventual → consensus) on one protocol | §5, ADR-0002 |
| **Security** | schema'd binary codec, no JDK serialization | §7, ADR-0009 |
| **Durability** | write-ahead lease journal + durable HLC high-water | §7, ADR-0013 |
| **Testability** | deterministic single-threaded core → reproducible simulation | §7, §9, ADR-0004/0010 |
| **Operability** | metrics SPI, runbook, config reference, troubleshooting | §7, ops docs |
| **Evolvability** | versioned wire format; decorator over L0; ADR-governed change | §7, ADR-0001 |

---

## 4. Considerations & key trade-offs

Architecture is the set of decisions that are expensive to change. The load-bearing ones:

- **AP by default, CP opt-in (CAP/PACELC).** Most registry data tolerates staleness; forcing
  linearizability on it would trade away availability and latency for no benefit. So the *default* is
  AP (PACELC: **PA/EL** — on partition favor availability, else favor latency), and strong consistency
  is a deliberate per-key choice for the few keys that need it (leadership, locks). → ADR-0002.
- **One substrate, refracted (the Prism model).** Rather than two systems, one gossip fabric carries
  every tier; the tier changes the *protocol applied to a key*, not the cluster. Fewer moving parts,
  one failure model to reason about. → §5.
- **Consensus, not gossip, for election.** Leadership is a consensus problem (FLP); we pay for a real
  quorum + fencing only on the keys that need it. → ADR-0006.
- **Build on L0, never fork.** Dependencies flow strictly downward into scalecube-cluster; prism never
  patches it. L0 stays upgradable. → ADR-0001.
- **Reactive plumbing, deterministic core.** L0–L2 are reactive (fits event streams); L3–L4 are a
  single-threaded deterministic state machine (a replicated decision must be reproducible and
  model-checkable). The boundary is explicit. → ADR-0004.
- **Single-writer-per-key.** Each key has exactly one writer (its owner). Cross-key operations then
  commute, so the registry is a CRDT and most data is safely eventual (the RedBlue insight). → ADR-0003.
- **Schema'd wire format.** Security and evolvability over convenience: no `ObjectInputStream`. →
  ADR-0009.
- **Dedicated consensus transport.** The elector binds a *separate* transport from gossip, so consensus
  RTT and gossip cadence don't interfere. → ADR-0008/0012.

---

## 5. The solution — conceptual model

**The metaphor.** White light hits a prism and fans out into a spectrum. Here the "white light" is one
gossip substrate; the "spectrum" is four consistency tiers. A key picks where on the spectrum it sits.

```
                                    ┌───────────── EVENTUAL   (gossip LWW)
   one substrate                    │
   (SWIM + gossip)  ──▶  ◣ prism ◢ ─┼───────────── CAUSAL     (gossip + session guarantees)   ◀ default
   membership +                     │
   dissemination                    ├───────────── QUORUM     (read-repair across k)
                                    │
                                    └───────────── CONSENSUS  (elected quorum: leader/locks)

       available · fast · stale-tolerant  ─────────▶  linearizable · safe · coordinated
```

**Two pillars on the spectrum**

- **Service registry** (L1/L2) — a per-key, HLC-versioned, single-writer **LWW-map CRDT**, gossiped as
  deltas and reconciled by **Merkle-root anti-entropy**. Lives at `EVENTUAL`/`CAUSAL` (with `QUORUM`
  read-repair designed).
- **Singleton elector** (L4 on the L3 consensus engine) — a **majority-quorum lease** with a monotone
  **fencing epoch**. Lives at `CONSENSUS`. Optionally a **self-electing/self-healing quorum**
  (ADR-0015).

The owner of each key declares the **weakest tier that is still correct** — you never pay for more
consistency than the data needs.

---

## 6. Architecture views

Following the 4+1 model: context, module (logical), component, runtime (process), deployment, plus a
data view. All diagrams are ASCII by design (the docs carry no external assets).

### 6.1 Context view — prism in its environment

```
        ┌─────────────────────────────────────────────────────────────┐
        │                         Your service node                     │
        │                                                               │
        │   application code                                            │
        │        │  registry.register/lookup/watch                      │
        │        │  elector.campaign/leadership                         │
        │        ▼                                                       │
        │   ┌──────────┐   decorates   ┌───────────────────────────┐    │
        │   │  prism   │──────────────▶│  scalecube-cluster (L0)    │    │
        │   └────┬─────┘               │  SWIM membership + gossip  │    │
        │        │                     └───────────────┬───────────┘    │
        │        │ dedicated consensus transport       │ gossip transport│
        └────────┼─────────────────────────────────────┼───────────────┘
                 │                                       │
        ┌────────▼─────────┐                   ┌─────────▼──────────┐
        │ peer prism nodes │ ◀── quorum RPC ──▶│ peer cluster nodes │ ◀─ gossip ─▶ …
        │ (acceptors)      │                   │ (membership/gossip)│
        └──────────────────┘                   └────────────────────┘
                 │
        ┌────────▼───────────────────────────┐
        │ downstream resource (fencing-aware) │  rejects a stale leader's lower epoch
        └─────────────────────────────────────┘
```

External actors: the **application**, the **peer nodes** (two transports: gossip + consensus), and any
**downstream resource** a leader drives — which must honor fencing epochs for end-to-end safety.

### 6.2 Module view — layers & dependency rule

```
  ┌──────────────────────────────────────────────────────────────────┐
  │ L4  Singleton elector        lease · epoch · fencing               │  prism-elector
  ├──────────────────────────────────────────────────────────────────┤
  │ L3  Consensus engine         quorum-lease now · Raft/EPaxos-shaped │  prism-consensus
  │        ▲ deterministic single-threaded loop (NOT reactive)         │
  ├──────── boundary: reactive above · deterministic below ───────────┤
  │ L2  Consistency router       per-key tier dispatch                 │  prism-registry
  ├──────────────────────────────────────────────────────────────────┤
  │ L1  Service registry         versioned per-key CRDT map (reactive) │  prism-registry
  ├──────────────────────────────────────────────────────────────────┤
  │ L0  scalecube-cluster        SWIM + gossip + membership (AP)       │  external dependency
  └──────────────────────────────────────────────────────────────────┘
   cross-cutting: prism-versioning · prism-codec · prism-persistence ·
                  prism-observability · prism-sim · prism-bench · prism-runtime · prism-docs
```

**Rule:** dependencies flow **downward** to L0; nothing points back up into scalecube-cluster — that
keeps L0 a clean, upgradable dependency rather than a fork (ADR-0001).

| Module | Role |
|--------|------|
| `prism-api` | L1–L4 public contracts (`Prism`, `ServiceRegistry`, `SingletonElector`, tiers, versions) |
| `prism-versioning` | Hybrid Logical Clock versions + freshness tokens |
| `prism-codec` | schema'd wire format (no JDK serialization) |
| `prism-registry` | L1 catalog (LWW-CRDT store, Merkle tree, gossip) + L2 router |
| `prism-consensus` | L3 quorum-lease (acceptor, quorum store, dynamic-quorum reconfiguration) |
| `prism-elector` | L4 safe singleton election |
| `prism-persistence` | durable lease journal + durable HLC high-water (stable-id resume) |
| `prism-runtime` | `PrismImpl` — decorates a `Cluster`, wires registry + elector + durability |
| `prism-observability` | metrics SPI + in-memory impl |
| `prism-sim` | deterministic simulator + property/fault-injection tests |
| `prism-bench` | core-algorithm throughput/latency harness |
| `prism-docs` | this folder (architecture, ADRs, TLA+ specs, guarantees, guides, runbook) |

### 6.3 Component view — inside the two pillars

**Registry (L1/L2)**

```
   register/update/deregister ─▶ RegistryStore (single lock, LWW by HLC Version)
                                     │              ▲
        watch() ◀── Sinks.Many ──────┘              │ apply(delta, tombstone)
                                                    │
   gossip in ─▶ RegistryGossipCodec ─▶ ────────────┘
   anti-entropy: MerkleTree(root,diff) ─▶ broadcast root ─▶ peer diff ─▶ re-advertise owned slice
```

**Consensus + elector (L3/L4)**

```
   LeaseElector.tick
     ├─ renew (sticky): ACCEPT(self@ballot) ─▶ majority Acceptors            ── keep leading
     └─ acquire (two-phase Paxos):
          1. PREPARE(ballot) ─▶ majority Acceptors   (promise + return high-water)
          2. if promised & no valid other claim ─▶ ACCEPT(self@ballot) ─▶ majority
        ▼
   Leadership events (active / fencing epoch = ballot)

   dynamic quorum: ReconfigurationManager.tick ─▶ planNextStep (single-member) ─▶
        LeaseTransfer (§7.1 high-water) + ConfigReplicator (commit to majority) ─▶ QuorumConfig
```

The **Acceptor** is the safety kernel — the `Accept`/`Promise` actions of Paxos plus the lease rule:
free → accept; same owner → renew at ≥ epoch; different owner → take over only if expired **and**
strictly higher epoch; and reject any ballot below a `promised` one **unless** it is a valid
same-owner renewal (so a challenger's PREPARE never preempts a healthy leader — *stickiness*).

**Why Paxos here.** A single-phase quorum CAS livelocks when proposers campaign in perfect lockstep
(each claims its own acceptor → a same-epoch split → no majority, and the takeover rule needs a
*strictly higher* epoch nobody can safely learn). The **PREPARE/promise** round fixes this two ways at
once: the promise **orders competing proposers** so exactly one ballot wins, and it **reveals the
high-water from a majority** so escalation is monotone (a best-effort, lossy max-epoch escalation was
tried and broke fencing monotonicity — the DST caught it). Acquisition commits one ballot atomically,
so the fencing token never regresses. This is single-decree Paxos; the lease's TTL turns each term over
so a new election (higher ballot) can happen, and a randomized backoff breaks dueling proposers.

### 6.4 Runtime views — key sequences

**(a) Advertise & discover (write/read path)**

```
 owner: register(svc, props, tier)
   └▶ stamp HLC Version ─▶ RegistryStore.apply (LWW) ─▶ encode delta ─▶ gossip broadcast
                                                                          │
 peer: gossip in ─▶ decode ─▶ apply iff version > stored ─▶ emit RegistryEvent ─▶ watch() subscribers
 consumer: lookup(svc)  ─▶ local catalog read (always available, possibly stale) + FreshnessToken
```

**(b) Election, failover & fencing**

```
 n1,n2,n3 campaign ─▶ each tick: PREPARE(ballot) to a majority, then ACCEPT(self@ballot)
   even in lockstep, the promise round picks one winner: majority grants n1 (ballot b1) ── n1 ACTIVE,
   renews stickily (challengers' PREPAREs don't preempt a valid lease) ──
 n1 crashes (stops renewing)
   lease expires (≤ leaseTtl)  ─▶ n2 PREPAREs a higher ballot b2, ACCEPTs ─▶ n2 ACTIVE
 n1 returns as a zombie, still thinks it leads
   downstream sees n2@b2, then n1@b1 (b1 < b2) ─▶ REJECTS n1  (fencing: no double-serve)
```

**(c) Self-electing quorum — single-member reconfiguration (ADR-0015)**

```
 leader L (valid lease, fencing high-water H)
   observe live members ─▶ planNextStep → single-member step Cnext  (grow/shrink/heal)
   §7.1 state transfer: push H to any JOINING member (raise its epoch floor)
   ConfigReplicator.commit(Cnext) to a MAJORITY of the current config
   on success ─▶ QuorumConfig advances (Cprev, Cnext)   ── overlap guarantees: still never two leaders
```

### 6.5 Deployment view — a realistic topology

```
        AZ-1                         AZ-2                         AZ-3
   ┌─────────────┐             ┌─────────────┐             ┌─────────────┐
   │ node A      │             │ node B      │             │ node C      │
   │ gossip :4801│◀── SWIM ───▶│ gossip :4801│◀── SWIM ───▶│ gossip :4801│
   │ consensus   │◀═ quorum ══▶│ consensus   │◀═ quorum ══▶│ consensus   │
   │   :7001     │   RPC       │   :7001     │   RPC       │   :7001     │
   └─────────────┘             └─────────────┘             └─────────────┘
        majority quorum across AZs ⇒ tolerates losing any one AZ (one leader, always)
```

- **Two ports per node:** the gossip transport (membership/registry) and the **dedicated consensus
  transport** (lease RPC) — sized and tuned independently.
- **Quorum placement:** an odd number across failure domains; a 3-node quorum across 3 AZs survives any
  single-AZ loss and never elects two leaders. A minority side is *safely unavailable* by design.
- **Durability:** with a persistence dir + stable member id, each acceptor's WAL and the HLC high-water
  survive restarts.

### 6.6 Data view — the core records

| Record | Fields | Notes |
|--------|--------|-------|
| `ServiceEntry` | service, owner, address, properties, **Version**, tier, alive | one per (owner, service) |
| `Version` (HLC) | logical, physical | total order; restart-safe; LWW key |
| `FreshnessToken` | ownerId, upTo (Version) | session guarantees (read-your-writes) |
| `LeaseRecord` | group, owner, **epoch**, expiresAt | the fencing token is `epoch` |
| `ConfigRecord` | epoch, members | committed quorum config (single-step chain) |

All cross-node messages are encoded field-by-field by `prism-codec` (a leading version byte enables
evolution) — never via Java serialization.

---

## 7. Cross-cutting concerns

- **Concurrency & threading.** Reactive schedulers above the boundary; a single-threaded deterministic
  loop below it. Shared state (`RegistryStore`, `Acceptor`, elector) is lock-guarded or confined; I/O
  happens outside locks. Full thread map and invariants: [`concurrency.md`](concurrency.md) (ADR-0004).
- **Time.** A **Hybrid Logical Clock** (logical counter correlated with physical time) provides a total,
  restart-safe order without synchronized clocks — the basis of LWW and freshness (ADR-0003).
- **Failure detection.** SWIM (+ Lifeguard, upstream) suspects failures; consensus *decides*. Election
  needs an `◇S`-class detector (Chandra–Toueg) — detection feeds, but never replaces, the quorum.
- **Durability.** Write-ahead: acceptors journal acceptances and the HLC its high-water *before*
  acknowledging, so a crash never forgets a lease or regresses a version (ADR-0013).
- **Security.** Schema'd binary codec, no `ObjectInputStream` ⇒ no deserialization-gadget RCE; a
  version byte bounds and validates every message (ADR-0009).
- **Observability.** A `Metrics` SPI (NOOP default; in-memory impl; Micrometer/OTel adapters) surfaces
  election grants/revokes, anti-entropy activity, and convergence (ADR-0014; thresholds in the runbook).

---

## 8. Quality-attribute scenarios

Concrete "when X happens, prism does Y" — the answers to the questions a careful reader will ask. Each
is backed by code, a test, or a proof (see §9, [`guarantees.md`](guarantees.md)).

| Concern / event | What prism does | Why it's safe / the cost |
|-----------------|-----------------|--------------------------|
| **Network partition splits the cluster** | only the majority side can hold the lease; the minority cannot acquire | quorum intersection; minority loses *availability*, never *safety* |
| **The active leader crashes** | its lease expires (≤ `leaseTtl`); a standby wins at a higher epoch | bounded zero-leader gap is the price of never-two; graceful `resign` ≈ instant |
| **A zombie old-leader keeps acting** | its epoch is now stale; the downstream rejects it | fencing-epoch monotonicity (requires the resource to check the epoch) |
| **Two nodes both think they lead** | impossible to both be majority-backed | proven `AtMostOneLeader` (TLA+) + 500-seed DST |
| **Gossip drops/reorders a registry update** | anti-entropy reconciles within a beacon; LWW converges | Merkle-root compare (O(1)) then targeted diff |
| **A provider dies** | membership `DEAD` purges its entries (membership *is* the tombstone) | no zombie keys; live deregister uses a versioned tombstone + GC |
| **A node restarts** | with durability, it resumes without regressing versions/epochs | WAL + durable HLC; needs a stable member id |
| **A malicious/spoofed peer sends junk** | the schema codec rejects it; no object graph is constructed | no deserialization-gadget surface |
| **A quorum member dies permanently (dynamic)** | the leader replaces it by single-member reconfiguration | overlap keeps never-two; majority loss ⇒ safe-unavailable |
| **Majority of the quorum is lost** | the system is unavailable until a member returns | CAP, by design; the only override is an operator-acknowledged unsafe force |

---

## 9. Formal foundations

prism is backed by theory and machine-checked evidence, not convention.

- **Registry (AP) = Strong Eventual Consistency.** A per-source **LWW-map CRDT** stamped by an **HLC**
  forms a join-semilattice; merge is commutative/associative/idempotent, so all replicas that have seen
  the same updates agree — regardless of order or duplication (ADR-0003). Single-writer-per-key makes
  cross-key ops commute (the **RedBlue**/CALM intuition), which is *why* most data is safely eventual
  (ADR-0002).
- **Elector (CP) = at-most-one leader.** A **single-decree Paxos** lease plus a monotone **fencing
  epoch** (the ballot): any two majorities intersect and an acceptor holds one lease, so two owners can
  never both be majority-backed; the PREPARE/promise round additionally orders concurrent proposers so
  exactly one ballot wins (no dueling-proposer livelock) while a valid same-owner renewal is exempt
  from the promise guard (stickiness) (ADR-0012). Model-checked as `AtMostOneLeader` /
  `AgreementPerEpoch` in [`spec/LeaseElection.tla`](spec/), fuzzed on the real kernel in `prism-sim`,
  and exercised under real concurrent contention by `ElectorE2eTest`.
- **Why consensus, not gossip.** Asynchronous consensus with a faulty process is impossible (**FLP**);
  progress needs an `◇S` failure detector (**Chandra–Toueg**). So election uses a real quorum; gossip
  membership only *feeds* the detector (ADR-0006).
- **Determinism enables proof.** The L3/L4 core is a single-threaded state machine (**Schneider** SMR),
  which makes **deterministic simulation testing** (FoundationDB/TigerBeetle style) and TLA+ refinement
  meaningful (ADR-0004/0010/0013).
- **Dynamic membership.** Seed-bootstrapped, **single-member** reconfiguration through consensus keeps
  adjacent configs' majorities overlapping, preserving never-two across changes;
  [`spec/SelfElectingQuorum.tla`](spec/) **proves the single-member rule is necessary** (the
  multi-member config produces a split-brain counterexample), and DST surfaced the mandatory **§7.1
  high-water state transfer** for fencing monotonicity (ADR-0015).

The consolidated, citable statement of what holds (and what does not) with its evidence map is
[`guarantees.md`](guarantees.md).

---

## 10. Performance characteristics

- **Data-plane primitives are not the bottleneck** (prism-bench, indicative): HLC `now()` ≈ 44.5M
  ops/s; `RegistryStore.apply` ≈ 5.4M ops/s; `Acceptor.handle` ≈ 27.8M ops/s; Merkle build(100k keys)
  ≈ 14 ms, diff(1 changed) ≈ 0.16 µs.
- **The strong path is RTT-bound, not CPU-bound.** A `CONSENSUS` acquire/renew costs one majority round
  trip on the dedicated consensus transport; tune `leaseTtl`/`tickInterval` to your RTT (keep
  `tickInterval ≤ leaseTtl/3`). Failover worst case ≈ `leaseTtl` on ungraceful death.
- **The AP path is local.** `lookup`/`list` read the local catalog with no network hop; dissemination
  cost is gossip-cadence delta traffic plus a periodic Merkle-root beacon (O(1) compare).
- **Scaling envelope.** Full replication suits catalogs of KB–low-MB/node across thousands of nodes.
  Beyond that, the registry model is the wrong tool — partition the data instead (a non-goal, §3.2).

Sizing guidance: [`config-reference.md`](config-reference.md); operations: [`ops/runbook.md`](ops/runbook.md).

---

## 11. Alternatives considered

| Alternative | Why not (as the whole answer) |
|-------------|-------------------------------|
| **ZooKeeper / etcd** for everything | strong-everywhere forces CP latency/availability onto data that doesn't need it, and is a *second* cluster to run; prism reserves consensus for the keys that need it, on the fabric you already have |
| **Eureka / raw gossip** for everything | no real leadership, no per-key versioning/anti-entropy; prism adds HLC versioning, Merkle anti-entropy, and a *safe* elector |
| **Gossip-based "lowest-id" election** | unsafe under partition (FLP) — split-brain; prism uses a quorum lease + fencing (ADR-0006) |
| **Fork scalecube-cluster and embed everything** | couples you to a fork, loses upstream fixes; prism is a strict decorator (ADR-0001) |
| **Java serialization on the wire** | deserialization-gadget RCE; prism uses a schema'd codec (ADR-0009) |
| **Reactive consensus core** | non-determinism defeats model-checking/DST; prism keeps L3/L4 single-threaded (ADR-0004) |

Each row links to the ADR that records the full analysis.

---

## 12. Limitations & roadmap

Honest current state (see [`plan.md`](plan.md) for the live status matrix):

- **Live & verified:** registry (`EVENTUAL`/`CAUSAL`) with anti-entropy; the `CONSENSUS` singleton
  elector (quorum lease + fencing); durability; security codec; observability; TLA+ + DST.
- **In progress:** the self-electing **dynamic quorum** — designed and formally verified (TLA+ + DST);
  policy + leader-driven reconfiguration implemented; runtime transport wiring landing in layers
  (ADR-0015).
- **Designed, not yet built:** the **`QUORUM` read-repair tier**; **leader affinity**
  (preference-biased / controller-driven promote-demote, ADR-0016); a full **Raft command log** for
  richer `CONSENSUS`-tier state; an **EPaxos** leaderless engine.
- **Inherent limitations:** full-replication scale ceiling (§10); majority loss is unavailable by
  design (§8); end-to-end leader safety requires the downstream to honor fencing epochs (§8).

Project standard: any new safety-critical mechanism gets a **TLA+ spec and a DST fuzz before it ships**
(the bar ADR-0015 set).

---

## 13. Evidence & further reading

- **Start here:** [getting-started](getting-started.md) → [user-guide](user-guide.md).
- **What is promised:** [`guarantees.md`](guarantees.md) (safety/liveness/consistency contract + evidence).
- **Why each choice:** the [ADRs](decisions/) (numbered, append-only, model the reasoning).
- **Machine-checked proofs:** [`spec/`](spec/) (TLA+ models + TLC results) and `prism-sim` (DST).
- **Operate it:** [config-reference](config-reference.md) · [troubleshooting](troubleshooting.md) ·
  [concurrency](concurrency.md) · [runbook](ops/runbook.md).
- **Background & lineage:** [`context.md`](context.md) (SWIM/gossip, the 90s P2P roots, Consul/Eureka/Aeron).
