<div align="center">

# <img src=".github/prism.svg" alt="" height="34" valign="middle"> scalecube-prism

### Gossip + CRDTs for discovery · Paxos + leases + fencing for leadership · one cluster, one dependency.

A self-healing **service registry** (AP) and a **never-two-leaders** singleton elector (CP) on the
same fabric — with a **per-key consistency dial** so each key picks how strong it needs to be.

<sub><b>gossip</b> (SWIM) finds nodes · <b>CRDTs</b> (HLC-versioned) converge the registry without a
coordinator · <b>Paxos</b> + a fenced lease elects exactly one leader, even in a partition ·
<b>TLA+ &amp; deterministic simulation</b> prove it.</sub>

**AP by default, strong consistency opt-in. Built on `scalecube-cluster` — layered, never forked.**

![Java](https://img.shields.io/badge/Java-17%2B-007396)
![Build](https://img.shields.io/badge/build-Maven-success)
![Consistency](https://img.shields.io/badge/consistency-AP%20%2B%20CP%20dial-E8590C)
![Safety](https://img.shields.io/badge/safety-TLA%2B%20%2B%20deterministic%20sim-2E7D32)
![Layered](https://img.shields.io/badge/scalecube--cluster-layered%2C%20not%20forked-555)

</div>

---

## What is this?

You run a cluster of services. You need two things nearly everyone needs — and nearly everyone wires
together out of two separate systems plus a pile of glue:

1. **Discovery** — *who offers service X, where, and with what properties right now?*
2. **Leadership** — *exactly one node owns this responsibility — and **never two**, even during a
   network partition.*

**prism gives you both, on the membership layer you already have, behind one wrapper:**

```java
Prism prism = new PrismImpl(cluster, config).startAwait();

prism.registry();   // AP, versioned, self-healing service registry
prism.elector();    // CP, partition-safe "never two leaders" singleton
```

Most stacks bolt an eventually-consistent registry (Eureka/gossip) next to a strongly-consistent
coordinator (ZooKeeper/etcd) and operate two clusters. prism runs **one** fabric and lets each *key*
pick how consistent it needs to be.

> **AP and CP** come from the CAP theorem — the choice a system makes when the network *partitions*
> (some nodes can't reach others). **AP** (Available) = every node keeps answering, accepting answers
> may be briefly stale — right for *discovery* (a slightly-stale service list beats none). **CP**
> (Consistent) = the minority side stops answering rather than risk a wrong answer — right for
> *leadership* (better no leader for a moment than **two**). prism is **AP for the registry, CP for the
> elector**, and the per-key dial lets you choose.

## How it works — the building blocks (plain version)

prism isn't a new algorithm; it's a careful assembly of well-understood ones, each doing the job it's
best at. If you know these names, you already know how prism behaves:

| The piece | The well-known approach | What it does here, in one line |
|-----------|-------------------------|--------------------------------|
| **Membership** | **SWIM + gossip** (via scalecube-cluster) | nodes find each other and detect failures; the always-on substrate everything rides |
| **Discovery / registry** | **CRDT** — a per-key last-writer-wins map, versioned by a **Hybrid Logical Clock** | every node converges to the same service catalog without a coordinator; late/duplicate updates can't clobber newer ones |
| **Anti-entropy** | **Merkle trees** (Dynamo/Cassandra-style) | nodes cheaply detect "are we out of sync?" and repair only the diff |
| **Leader election** | **Paxos** (single-decree) + a **lease** with a **fencing token** | exactly one leader, *never two* even in a split — the lease/PREPARE round elects safely; the fencing epoch stops a zombie ex-leader from acting |
| **Tunable consistency** | a per-key **dial** (eventual → causal → quorum → consensus) | you choose, *per key*, how strong it must be — cheap gossip for tags, Paxos only for leadership |
| **Self-healing quorum** | **single-member reconfiguration** through consensus (Raft-style) | the consensus group forms and repairs itself instead of being hand-listed |
| **Security** | a **schema'd binary codec** (no Java serialization) | nothing on the wire can trigger a deserialization-gadget RCE |
| **Proof** | **TLA+** model-checking + **deterministic simulation testing** (FoundationDB/TigerBeetle-style) | "never two leaders" is *checked*, not just claimed |

In short: **gossip + CRDTs make discovery cheap and always-available (AP); Paxos + leases + fencing
make leadership safe (CP); a per-key dial lets one cluster serve both.**

## Why another registry? Why an elector?

Every cluster has to answer two questions all the time: **"where is everyone?"** (discovery) and
**"who's in charge?"** (leadership). They're the same need — shared cluster state — at two different
consistency points, and that split is the whole reason prism exists.

**Why another service registry.** The usual options each force a compromise:

- *Eureka / Consul catalog / raw gossip (AP):* always-available, but the catalog is a best-effort blob
  — no per-key version (a late or duplicated update silently clobbers a newer one), no anti-entropy
  (two nodes can disagree forever), no read-your-writes, no way to make one key stronger.
- *ZooKeeper / etcd (CP):* correct, but you pay CP latency/availability for *all* data — even tags
  that don't need it — and it's a **second cluster to operate** beside your app.

prism's registry is **AP yet version-correct**: a per-key **CRDT** stamped by a **Hybrid Logical
Clock** (updates merge regardless of order/duplication), healed by **Merkle anti-entropy**, with
**read-your-writes** freshness tokens and a **per-key consistency dial** — on the membership layer you
already run, no second cluster.

**Why an elector.** Discovery says *who's there*; the next question is *who's in charge* — exactly one
active gateway/scheduler/owner, **never two**. That's a different, CP problem, and gossip **cannot do
it safely**: SWIM is an unreliable detector, so under a partition both sides can think they lead
(split-brain; FLP). Safe leadership needs consensus + a **lease** + a **fencing token** — which most
teams bolt on as ZooKeeper/etcd, *again a second system*. prism provides it on the **same fabric**:
a Paxos quorum lease with monotone fencing, behind a one-line API.

**Why together.** Because they're two halves of one job, and most stacks pay twice — an AP registry
**and** a CP coordinator, two clusters, glue, two failure models. prism puts both on **one gossip
substrate with a per-key dial**: eventual/causal for the 99% of discovery data, consensus only for the
few keys that are leadership or locks. One dependency, one failure model, one ops surface.

## Why it's different

- 🎚️ **One substrate, a whole spectrum.** Per-key tiers — eventual → causal → quorum → consensus —
  on a single gossip layer. Pay for strong consistency only on the keys that need it.
- 🛡️ **Safety you can *check*, not just trust.** "Never two leaders" rests on **single-decree Paxos**
  (a PREPARE/promise round), is **model-checked in TLA+**, and is **fuzzed by deterministic
  simulation** across hundreds of seeded partition/loss/churn scenarios.
- 🔒 **Secure by construction.** A schema'd binary wire codec — **no Java serialization**, so there is
  no deserialization-gadget RCE surface.
- 🧩 **Layered, never forked.** `scalecube-cluster` stays a clean, upgradable dependency underneath.

## Quickstart

```java
// 1) An ordinary scalecube cluster node.
ClusterImpl cluster = new ClusterImpl().transportFactory(TcpTransportFactory::new);

// 2) Wrap it. The registry is ready immediately; the elector needs a PrismConfig (a quorum).
Prism prism = new PrismImpl(cluster).startAwait();

// Advertise a service (this node only writes its own keys; versioned by a Hybrid Logical Clock).
prism.registry().register("orders", Map.of("weight", "100", "status", "passing")).block();

// Discover it anywhere — a local, always-available view that converges via gossip + anti-entropy.
for (ServiceEntry e : other.registry().lookup("orders")) {
  System.out.println(e.address() + " " + e.properties());
}
```

Elect a safe leader (partition-proof, with fencing):

```java
PrismConfig config = new PrismConfig(addr, quorum, TcpTransportFactory::new);
Prism prism = new PrismImpl(cluster, config).startAwait();

prism.elector().leadership("gateway").subscribe(l -> server.setActive(l.active(), l.epoch()));
prism.elector().campaign("gateway").block();   // at most one node goes active — ever
```

> **The fine print on "never two".** The elector guarantees at most one node holds a *valid* lease at
> a time — safety that rests on quorum intersection, independent of clocks. But because a lease is
> time-bounded, a deposed leader can briefly still *believe* it is active until it notices. The
> always-increasing **fencing epoch** (`l.epoch()`) is what closes that window: stamp it on every
> externally-visible action (writes, RPCs, storage) and have the downstream **reject a lower epoch**.
> Then two actors can never both take effect — "never two leaders" in the sense that matters. prism
> gives you the monotone token; honoring it at the resource is the caller's part of the contract.

→ Full walkthrough: **[getting-started](prism-docs/getting-started.md)** ·
**[user guide](prism-docs/user-guide.md)** (12 runnable examples).

## The consistency dial

The owner of each key declares the **weakest tier that is still correct** — one protocol serves all
four:

| Tier | Mechanism | Use for |
|------|-----------|---------|
| `EVENTUAL`  | gossip last-writer-wins | tags, labels, coarse health |
| `CAUSAL` *(default)* | gossip + causal context (session guarantees) | service properties, weight, version |
| `QUORUM`    | on-demand read-repair across *k* replicas | "is this current before I route?" |
| `CONSENSUS` | small elected quorum (the elector) | leader election, singleton ownership, locks |

```
available · fast · stale-tolerant   ─────────────▶   linearizable · safe · coordinated
```

## What's inside

- **Service registry** — per-key, HLC-versioned, single-writer **LWW-CRDT**; delta gossip + **Merkle-root
  anti-entropy**; membership-driven lifecycle; snapshot-then-stream `watch`; freshness tokens.
- **Singleton elector** — leadership is a **single-decree Paxos** lease: a PREPARE/promise round
  elects exactly one leader even under concurrent contention, with a **monotonic fencing epoch**
  (the Paxos ballot); sticky, safe handoff, per-group (independent leaders for `service-A` vs
  `service-B` on the same cluster).
- **Leader affinity** *(ADR-0016)* — preferred / sticky / **no-failback** leadership, plus
  controller-driven `promote`/`demote`. (The AZ-pinned-gateway pattern.)
- **Self-electing quorum** *(ADR-0015)* — a quorum that **forms and heals itself** by single-member
  reconfiguration through consensus; opt-in via `withDynamicQuorum(target)`.
- **Durability** — write-ahead lease journal + durable HLC high-water (versions/epochs never regress).
- **Observability** — a metrics SPI (NOOP / in-memory / Micrometer-OTel adapter).

## Architecture at a glance

```
  L4  Singleton elector      lease · epoch · fencing · affinity      prism-elector
  L3  Consensus engine       quorum lease · self-electing quorum     prism-consensus
  ───────── boundary: reactive above · deterministic below ─────────
  L2  Consistency router     per-key tier dispatch                   prism-registry
  L1  Service registry       versioned per-key CRDT map (reactive)   prism-registry
  L0  scalecube-cluster      SWIM + gossip + membership (AP)         (dependency)
```

Dependencies flow **downward** to `scalecube-cluster`; nothing points back up. Full treatment:
**[architecture.md](prism-docs/architecture.md)** (context · module · component · runtime · deployment
views, formal foundations, quality-attribute scenarios).

## Examples

Fourteen runnable examples in [`prism-examples`](prism-examples), simple → advanced: hello → register &
discover → watch → consistency tiers → instance selection → singleton elector + fencing → 3-node
partition-safe elector → durable fencing → metrics → independent-leader-per-group → self-electing
quorum → leader affinity → dynamic quorum (public API) → QUORUM read-repair (`lookupQuorum`). See the
[catalog](prism-examples/README.md).

## How it compares

| You want… | Common answer | prism |
|-----------|---------------|-------|
| Eventually-consistent discovery | Eureka, raw gossip | ✅ the `EVENTUAL`/`CAUSAL` tiers — with versioning + anti-entropy |
| Strongly-consistent coordination | ZooKeeper, etcd, Consul | ✅ the `CONSENSUS` elector — no second cluster to operate |
| Both, today | ZK **and** Eureka (two systems) | **one** system with a per-key dial |
| A raw membership layer | scalecube-cluster | prism is the **registry + elector** on top of it |

## Status

**Shipped & verified** (TLA+ model-checking + deterministic-simulation fuzzing + BDD tests + CI):
registry (`EVENTUAL`/`CAUSAL` + anti-entropy), the `CONSENSUS` singleton elector, leader affinity,
the self-electing dynamic quorum (opt-in), durability, the security codec, and observability.

**Remaining** (designed; tracked in the [roadmap](prism-docs/plan.md)): the `QUORUM` read-repair tier,
durable epoch-floor on the dynamic path, a gossip-pool-derived roster, and an EPaxos engine.

## Build

```
mvn clean install
```

Java 17+. Resolves `scalecube-cluster` from Maven Central / GitHub Packages.

## Documentation

Everything lives in [`prism-docs/`](prism-docs/README.md) (that page is the full index). The map:

**Learn it**
- [**Onboarding / learning path**](ONBOARDING.md) — **start here if you're new**: zero → can-change-it-safely, one study track per building block (theory + code)
- [Getting started](prism-docs/getting-started.md) — zero to a working registry + elector in minutes
- [User guide](prism-docs/user-guide.md) — feature-by-feature tour, each backed by a runnable example
- [Use cases](prism-docs/use-cases.md) — business scenarios as user stories → feature → validating test
- [Examples](prism-examples/README.md) — 12 runnable programs, simple → advanced

**Understand it**
- [Architecture](prism-docs/architecture.md) — context/module/component/runtime/deployment views, formal foundations
- [Context](prism-docs/context.md) — background & lineage (SWIM/gossip, the 90s P2P roots, Consul/Eureka/Aeron)
- [Guarantees](prism-docs/guarantees.md) — the safety/liveness/consistency contract + its evidence

**How each building block works** — one explainer per approach (what it is · how it maps to the code · what's out of scope)
- [Gossip + SWIM](prism-docs/gossip-swim.md) — membership & failure detection (ridden from scalecube-cluster)
- [CRDT + HLC](prism-docs/crdt-hlc.md) — the registry: per-key last-writer-wins map versioned by a Hybrid Logical Clock
- [Merkle anti-entropy](prism-docs/anti-entropy-merkle.md) — cheap drift detection + delta repair
- [Paxos](prism-docs/paxos.md) — leader election: single-decree Paxos + a fenced lease
- [Tunable consistency](prism-docs/tunable-consistency.md) — the per-key dial (eventual → causal → quorum → consensus)
- [Self-electing quorum](prism-docs/self-electing-quorum.md) — single-member reconfiguration through consensus
- [Codec & security](prism-docs/codec-security.md) — schema'd binary wire format, no Java serialization
- [Formal verification + DST](prism-docs/formal-verification-dst.md) — TLA+ model checking + deterministic simulation testing

**Operate it**
- [Config & API reference](prism-docs/config-reference.md) — every option, method, tier, tunable, metric
- [Persistence](prism-docs/persistence.md) — durability: the journals, what's stored, growth & risks
- [Troubleshooting](prism-docs/troubleshooting.md) — symptom → diagnosis → fix
- [Debugging](prism-docs/debugging.md) — reproduce any failure deterministically from a seed
- [Concurrency model](prism-docs/concurrency.md) — threads, shared state, invariants
- [Ops runbook](prism-docs/ops/runbook.md) — sizing, monitoring, failure modes
- [Module ownership map](prism-docs/ownership.md) — who understands what (bus-factor plan)

**Decide & prove it**
- [ADRs](prism-docs/decisions/) — the binding decisions and *why* (Paxos elector, dynamic quorum, affinity, …)
- [TLA+ specs](prism-docs/spec/) — machine-checked safety models
- [Roadmap](prism-docs/plan.md) — what's done, what's next, with evidence

## License

[Apache License 2.0](LICENSE) — same as the rest of the scalecube project.

---

<div align="center">
<sub><b>Strategy:</b> layer, don't fork. <code>scalecube-cluster</code> stays the AP foundation;
prism adds the registry and the safe elector on its public APIs. AP by default · strong consistency
opt-in.</sub>
</div>
