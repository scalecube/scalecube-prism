# Per-key tunable consistency in prism — one cluster, one dial

prism does not pick a single point on the consistency spectrum and impose it on everything. Instead
**each key declares the weakest tier that is still correct for its data**, and one membership
substrate serves all of them. This page explains the tier ladder, what each tier actually guarantees
and costs, the CAP/PACELC reasoning behind the default, and — honestly — which tiers are live code
today versus designed-but-not-yet-built.

> **One-line answer:** consistency is a **per-key attribute**, not a system-wide constant. The owner
> of a key chooses `EVENTUAL → CAUSAL → QUORUM → CONSENSUS`; AP is the default and strong consistency
> is opt-in *per key*, so the cheap-and-available registry and the never-two elector live behind a
> single dial on one gossip fabric — not two separately-operated systems.

---

## 1. The core idea — one substrate, a whole spectrum

Most systems make a global, early CP/AP commitment: Eureka/Serf are eventual (AP), Aeron Cluster is
linearizable (CP), and Consul cuts a hard seam between a gossip tier and a *separately operated* Raft
tier. prism rejects the binary. There is **one** SWIM/gossip fabric (`scalecube-cluster`), and on top
of it a per-key router decides how strong each key needs to be.

The licence for mixing levels is theoretical, not hand-waving. Because prism keys are
single-writer-per-key (ADR-0003), cross-key operations commute — they are RedBlue "blue" operations
(Li et al., OSDI 2012) and can run eventually-consistent safely. Only the rare "red" keys — singleton
ownership, locks — are non-commutative and must pay for coordination. So the *vast majority* of keys
ride cheap gossip, and consensus machinery is confined to where its cost is genuinely justified. See
[`decisions/0002-per-key-tunable-consistency.md`](decisions/0002-per-key-tunable-consistency.md).

---

## 2. The tier ladder

The dial is the enum
[`ConsistencyTier`](../prism-api/src/main/java/io/scalecube/prism/registry/ConsistencyTier.java),
ordered most-available → most-consistent. The owner stamps a key's tier at registration via
[`ServiceRegistry.register(service, props, tier)`](../prism-api/src/main/java/io/scalecube/prism/registry/ServiceRegistry.java);
the two-arg overload defaults to `CAUSAL`.

| Tier | Guarantees | Costs | Use it for |
|---|---|---|---|
| **`EVENTUAL`** | Convergence only: last-writer-wins per key, no ordering across reads. Local, always-available reads; never blocks. | Reads can be stale; no session guarantees. | tags, labels, coarse health — data where a brief stale value is harmless. |
| **`CAUSAL`** *(default)* | Everything in `EVENTUAL` **plus** Bayou session guarantees — read-your-writes and monotonic reads, tracked by a freshness token. | Still AP and local; the only extra cost is carrying causal/freshness context. | service properties, weight, version — the 99% of discovery data. |
| **`QUORUM`** *(via `lookupQuorum`)* | Read-time freshness: on-demand read-repair across a majority of members to answer "is this current *before* I route?" | A read now does network round-trips to a majority: real latency, and unavailable (errors) if a majority is unreachable. | the occasional "must be fresh at read time" lookup. |
| **`CONSENSUS`** | Linearizable — a value chosen by a majority; the *never-two* property holds in every execution, even a partition. | Full CP cost: a quorum round-trip per decision, and the minority side stops answering under partition. | singleton ownership, locks, leadership. |

```
available · fast · stale-tolerant   ─────────────▶   linearizable · safe · coordinated
   EVENTUAL          CAUSAL              QUORUM              CONSENSUS
```

The rule the owner applies is "**declare the weakest tier that is still correct**" — climbing the
ladder only buys guarantees you actually need, and every rung up the ladder is paid for in latency
and availability (CAP under partition, PACELC even without one).

---

## 3. CAP / PACELC — why AP is the default

`AP` and `CP` are the two horns of CAP (Gilbert & Lynch, 2002): when the network partitions you may
keep every node **A**vailable *or* keep the system **C**onsistent, not both. PACELC (Abadi, 2012)
adds the part that bites even with a healthy network: **E**lse, you still trade **L**atency for
**C**onsistency. Strong consistency is never free.

prism's stance follows directly:

- **Discovery is AP.** A slightly-stale service list beats no service list — a reader should always
  get *an* answer and retry on a miss. So `EVENTUAL`/`CAUSAL` keep reads local and never block; under
  partition every node still answers. This is the registry's contract: convergent and
  monotonic-per-key, explicitly **not** linearizable
  ([`ServiceRegistry`](../prism-api/src/main/java/io/scalecube/prism/registry/ServiceRegistry.java)
  Javadoc — "treat a lookup as a hint").
- **Leadership is CP.** Better *no* leader for a moment than **two**. So `CONSENSUS` accepts the CAP
  bargain: the minority side of a partition goes unavailable rather than risk a second leader.

The default tier is `CAUSAL`, not `EVENTUAL`: it is still fully AP and local, but it adds the session
guarantees most callers silently assume (you can read your own writes back), so the cheap default is
also the *least surprising* one.

---

## 4. How the dial unifies the AP registry and the CP elector

The dial is not a fifth subsystem bolted on — it is the framing that makes one cluster serve both
ends of the spectrum:

- **`EVENTUAL` and `CAUSAL` are the registry.** Both are served by the gossip CRDT: a per-key
  last-writer-wins map, HLC-versioned, healed by Merkle anti-entropy. The tier travels *with* each
  entry — it is a field on
  [`ServiceEntryImpl`](../prism-registry/src/main/java/io/scalecube/prism/registry/impl/ServiceEntryImpl.java),
  set at `register` and preserved across `update`
  ([`GossipServiceRegistry.register/update`](../prism-registry/src/main/java/io/scalecube/prism/registry/impl/GossipServiceRegistry.java)).
- **`CONSENSUS` is the elector.** The strong rung is realized by the single-decree Paxos lease in
  [`LeaseElector`](../prism-elector/src/main/java/io/scalecube/prism/elector/impl/LeaseElector.java),
  exposed as
  [`SingletonElector`](../prism-api/src/main/java/io/scalecube/prism/elector/SingletonElector.java).
  Electing a leader *is* choosing a key at the `CONSENSUS` tier; see
  [`paxos.md`](paxos.md) for the protocol.

So "AP registry" and "CP elector" are two settings of one dial, on one fabric — the structural payoff
of ADR-0002. The router that dispatches per tier lives in the L2 registry implementation
([`registry/impl/package-info.java`](../prism-registry/src/main/java/io/scalecube/prism/registry/impl/package-info.java)
— "houses the consistency router that dispatches reads/writes per `ConsistencyTier`").

| Concept | prism code |
|---|---|
| The dial (the four tiers) | [`ConsistencyTier`](../prism-api/src/main/java/io/scalecube/prism/registry/ConsistencyTier.java) |
| Pin a tier at key creation | [`ServiceRegistry.register(svc, props, tier)`](../prism-api/src/main/java/io/scalecube/prism/registry/ServiceRegistry.java); 2-arg overload defaults to `CAUSAL` |
| Tier carried on the entry | `ServiceEntry.tier()` → [`ServiceEntryImpl`](../prism-registry/src/main/java/io/scalecube/prism/registry/impl/ServiceEntryImpl.java) |
| `EVENTUAL` / `CAUSAL` mechanism | gossip CRDT in [`GossipServiceRegistry`](../prism-registry/src/main/java/io/scalecube/prism/registry/impl/GossipServiceRegistry.java) |
| `CONSENSUS` mechanism | the Paxos lease in [`LeaseElector`](../prism-elector/src/main/java/io/scalecube/prism/elector/impl/LeaseElector.java) |
| Worked example | [`ConsistencyTiersExample`](../prism-examples/src/main/java/io/scalecube/prism/examples/ConsistencyTiersExample.java) |

---

## 5. Concrete guidance — which tier for what

- **`EVENTUAL`** — coarse, stale-tolerant facts: region tags, free-form labels, a rough health hint.
  If a reader seeing yesterday's value would do no harm, use the cheapest rung.
- **`CAUSAL`** (default) — anything a caller might write and then immediately read back, or anything
  where "newer must not be hidden by older": service weight, status, config properties, version
  strings. When unsure, this is the right default — it is AP, local, and read-your-writes.
- **`QUORUM`** — a read that genuinely needs to be fresh *at the moment of routing* ("is this still
  current before I send traffic?"), where you will accept latency and possible unavailability for it.
- **`CONSENSUS`** — only the *never-two* keys: singleton ownership, distributed locks, leadership.
  Reach for it via the elector, not the registry. Paying CP cost on anything else is waste.

The full per-tier matrix is in [`config-reference.md`](config-reference.md) §6.

---

## 6. What's out of scope / honest caveats

| Item | Honest status |
|---|---|
| **`QUORUM` read-repair** | **Implemented** via [`ServiceRegistry.lookupQuorum(service)`](../prism-api/src/main/java/io/scalecube/prism/registry/ServiceRegistry.java). Because the registry is fully replicated, the read fans out to a **majority of live members** over the cluster's own transport, merges replies last-writer-wins, repairs the local view, and returns the freshest instances; it throws `QuorumUnavailableException` if a majority can't be reached (CAP). Note this is a **read-side** mechanism the caller opts into per lookup — it is not yet auto-applied to every read of a key whose stored tier is `QUORUM`. Covered by `RegistryReadCodecTest` + `QuorumReadE2eTest`. |
| **`CONSENSUS` *for arbitrary registry keys*** | The `CONSENSUS` tier is live **only via the elector** (leadership / singleton ownership). The registry does not run a per-key Paxos register for a `CONSENSUS`-tagged *service property*; if you need a linearized key, use `elector()`, not a `CONSENSUS`-tagged `register`. |
| **Automatic tier dispatch in one router method** | The L2 router framing is real (tier is pinned and travels with each entry), but `EVENTUAL` and `CAUSAL` share the same gossip CRDT path today; the visible behavioral difference is the session-guarantee/freshness handling, not two separate transports. |
| **Cost is real** | Climbing the ladder is not free: `QUORUM` and `CONSENSUS` trade away local-read latency and partition availability by design (CAP/PACELC). The dial lets you *avoid* that cost on the 99% — it does not abolish it on the few. |
| **Per-operation (vs per-key) tuning** | prism tunes per *key* (pinned at creation), not per *read request* (Dynamo-style R/W knobs) or per latency-SLA (Pileus/Tuba). That richer surface is acknowledged in ADR-0002's references but is not built. |

If you need a general per-request quorum store or a replicated command log, prism is the wrong layer.
prism's promise is narrower and honest: **AP by default, CP where you declare it, on one cluster.**

---

## 7. Evidence

The two **live** ends of the dial are the ones backed by tests and proof. `EVENTUAL`/`CAUSAL` are
exercised by the registry convergence and anti-entropy suites
(`prism-registry/src/test/.../RegistryConvergenceTest.java`, `AntiEntropyTest.java`) and the
round-trip [`ConsistencyTiersExample`](../prism-examples/src/main/java/io/scalecube/prism/examples/ConsistencyTiersExample.java),
which asserts read-your-writes monotonicity via freshness tokens. The `CONSENSUS` end is the most
heavily verified surface in the project: TLA+ model-checking and the `prism-sim` deterministic fuzzer
both check **never two leaders** ([`paxos.md`](paxos.md), [`guarantees.md`](guarantees.md)). The
middle rung, `QUORUM`, is now implemented as `lookupQuorum` and exercised by `RegistryReadCodecTest`
(wire round-trips) and `QuorumReadE2eTest` (majority fan-out, repair, and the single-node fast path)
over a real netty cluster. The dial is real where it is proven, and honest about what each rung
delivers today.
