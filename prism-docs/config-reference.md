# Configuration & API reference

The complete reference: **every** configuration option, API method, consistency tier, internal
tunable, metric, and on-disk artifact in prism — with types, defaults, ranges, and effects. For the
conceptual walkthrough see the [user guide](user-guide.md); for *why* a default is what it is, the
[ADRs](decisions/).

**Contents:** [PrismConfig](#1-prismconfig) · [construction](#2-construction--fluent-copies) ·
[dynamic quorum](#3-dynamic-quorum-options) · [registry API](#4-registry-api) ·
[elector API](#5-elector-api) · [consistency tiers](#6-consistency-tiers) ·
[data types](#7-data-types) · [internal tunables](#8-internal-tunables) ·
[persistence](#9-persistence-on-disk) · [metrics](#10-metrics) · [L0 settings](#11-l0-cluster-settings) ·
[wire qualifiers](#12-transport-wire-qualifiers) · [defaults summary](#13-defaults-at-a-glance) ·
[sizing](#14-sizing-rules-of-thumb)

---

## 1. `PrismConfig`

Supplying a `PrismConfig` to `PrismImpl` enables the elector (`prism-runtime`). Immutable; tunables are
set with `withX` copies (§2). Without a `PrismConfig`, the registry still works and `elector()` throws.

| Option | Type | Default | Req? | Effect / tuning |
|--------|------|---------|:----:|-----------------|
| `consensusAddress` | `String` | — | ✅ | This node's consensus-transport address `host:port`. **Must equal** what the transport advertises (no rewriting) and be one of `quorumMembers`. |
| `quorumMembers` | `List<String>` | — | ✅ | All quorum members' consensus addresses, including self. Static mode: the exact quorum (use an **odd** count). Dynamic mode (§3): the **candidate roster**. |
| `transportFactory` | `Supplier<TransportFactory>` | — | ✅ | Factory for the dedicated consensus transport (e.g. `TcpTransportFactory::new`). Separate from the gossip transport. |
| `leaseTtl` | `Duration` | `5s` | — | Leadership validity before renewal. Shorter ⇒ faster failover, more renewal traffic, more pause-sensitivity. Longer ⇒ slower failover, more slack. Worst-case zero-leader gap on ungraceful death ≈ this. |
| `tickInterval` | `Duration` | `1s` | — | Renewal/acquisition cadence. Keep **≤ `leaseTtl` / 3** so a couple of missed ticks don't drop a valid lease. |
| `callTimeout` | `Duration` | `1s` | — | Per-peer consensus RPC timeout. Set above your p99 consensus-transport RTT and **comfortably below `leaseTtl − tickInterval`** (the renewal margin — see §14 for why). The `1s` default is correct for the default `5s`/`1s` TTL/tick. |
| `persistenceDir` | `Path` | `null` (off) | — | Enables durability: write-ahead lease journal + durable HLC high-water (§9). **Use with a stable member id.** |
| `dynamicQuorum` | `boolean` | `false` | — | Opt into the self-electing/self-healing quorum (§3, ADR-0015). Off ⇒ the static quorum (safe default). |
| `targetQuorumSize` | `int` | `3` | — | Target quorum size when `dynamicQuorum` is on; rounded down to odd, capped by what is live. Only meaningful with `dynamicQuorum`. |

Defaults are exposed as constants: `PrismConfig.DEFAULT_LEASE_TTL` (5s), `DEFAULT_TICK_INTERVAL` (1s),
`DEFAULT_CALL_TIMEOUT` (1s), `DEFAULT_TARGET_QUORUM_SIZE` (3).

---

## 2. Construction & fluent copies

```java
// Required args only (default timings, durability off, static quorum):
PrismConfig c = new PrismConfig(consensusAddress, quorumMembers, transportFactory);

// Fluent copies (each returns a new immutable instance):
c.withLeaseTtl(Duration.ofSeconds(8))
 .withTickInterval(Duration.ofSeconds(2))
 .withCallTimeout(Duration.ofMillis(800))
 .withPersistenceDir(Path.of("/var/lib/prism"))
 .withDynamicQuorum(3);                          // enable §3 with target 3
```

| Method | Returns a copy with… |
|--------|----------------------|
| `withLeaseTtl(Duration)` | a different lease TTL |
| `withTickInterval(Duration)` | a different renewal cadence |
| `withCallTimeout(Duration)` | a different per-peer RPC timeout |
| `withPersistenceDir(Path)` | durability enabled under `dir` |
| `withDynamicQuorum(int target)` | the dynamic quorum enabled with the given target |

Accessors mirror the fields: `consensusAddress()`, `quorumMembers()`, `transportFactory()`,
`leaseTtl()`, `tickInterval()`, `callTimeout()`, `persistenceDir()`, `dynamicQuorum()`,
`targetQuorumSize()`.

---

## 3. Dynamic quorum options

When `dynamicQuorum = true` (ADR-0015), `quorumMembers` is the **candidate roster** rather than a fixed
quorum. The quorum **sizes itself** to `targetQuorumSize` (odd, capped by live members) and
**self-heals** by single-member reconfiguration committed through consensus, with the mandatory §7.1
fencing high-water transfer.

| Behavior | Rule |
|----------|------|
| Desired size | largest odd value `≤ min(targetQuorumSize, liveCount)`, `≥ 1`. Target 3: 1 live→1, 2→1, 3→3, 10→3. Target 5: 4→3, 5→5. |
| Reconfiguration steps | exactly one member added/removed per committed step (the load-bearing safety rule) |
| Self-heal | a dead member is replaced one step at a time while a majority survives |
| Leader protection | a sizing-shrink never removes the current leader |
| Majority loss | safely **unavailable** until a member returns (CAP, by design) |

Status: implemented and opt-in via `withDynamicQuorum(target)` — policy, leader-driven
reconfiguration, §7.1 transfer, transport config replication, and the PrismImpl control loop are
wired and verified (TLA+ + DST + real-transport integration + smoke). The **static quorum remains the
default and the supported production path**; remaining hardening (durable epoch-floor on the dynamic
path, gossip-pool-derived roster) is tracked in [`plan.md`](plan.md) and ADR-0015 §13.

---

## 4. Registry API

`prism.registry()` → `ServiceRegistry`. Mutating ops return `Mono<Void>` (call `.block()` for blocking
use). A node only writes **its own** keys (single-writer-per-key).

| Method | Signature | Semantics |
|--------|-----------|-----------|
| register (tiered) | `register(String service, Map<String,String> props, ConsistencyTier tier)` | Advertise this node's service at an explicit tier. Stamps an HLC version. |
| register (default) | `register(String service, Map<String,String> props)` | As above with tier = **`CAUSAL`** (the default). |
| update | `update(String service, String key, String value)` | Change one property of an owned service; bumps the version. |
| deregister | `deregister(String service)` | Versioned tombstone (then GC) for an owned service while alive. |
| lookup | `Collection<ServiceEntry> lookup(String service)` | Local, always-available read of all alive instances of a service (one per owner). Possibly stale. |
| list | `Collection<ServiceEntry> list()` | The whole local catalog. |
| watch | `Flux<RegistryEvent> watch()` | **Snapshot then stream:** a new subscriber first gets the current catalog as `REGISTERED` events, then live changes. |
| freshness | `FreshnessToken freshness(String ownerId)` | The highest version observed from `ownerId` — for session guarantees (read-your-writes / monotonic reads). |

`RegistryEvent.type()` ∈ `{REGISTERED, UPDATED, DEREGISTERED, EXPIRED}`; `RegistryEvent.entry()` is the
`ServiceEntry`. Treat a lookup as a **hint** — be ready to retry/fail over (it is convergent and
monotonic-per-key, not linearizable).

---

## 5. Elector API

`prism.elector()` → `SingletonElector` (requires a `PrismConfig`). A *group* is an independent election
(e.g. `"gateway"`, `"scheduler"`).

| Method | Signature | Semantics |
|--------|-----------|-----------|
| campaign | `Mono<Void> campaign(String group)` | Join the race for a group; the elector then acquires/renews on each tick. Sticky — once won, held until the lease is lost. |
| resign | `Mono<Void> resign(String group)` | Step down: release the lease and stop contending for the group. |
| leadership | `Flux<Leadership> leadership(String group)` | Stream of leadership changes for the group. |
| currentLeader | `Optional<Member> currentLeader(String group)` | The current (unexpired) leader, if known. |

`Leadership` carries `group()`, `member()`, `epoch()` (the fencing token), and `active()`.

### Leader affinity (ADR-0016)

Opt-in policy on top of the same lease/fencing kernel (the kernel is unchanged):

| Method | Signature | Semantics |
|--------|-----------|-----------|
| affinity | `void affinity(String group, Supplier<Preference> preference, Duration yieldWindow, boolean autoMove)` | **Mode A** — bias *who* wins: `PREFERRED` acquires a free lease immediately; `STANDBY` waits `yieldWindow`; `INELIGIBLE` never campaigns. With `autoMove`, a leader that becomes non-preferred hands off once. Never preempts a healthy leader (no failback). |
| promote | `Mono<Boolean> promote(String group)` | **Mode B** — cooperatively acquire iff the lease is free/expired and hold it; returns `false` (no preemption) if another node holds a valid lease. |
| demote | `Mono<Void> demote(String group)` | **Mode B** — release the lease and stay passive (does not re-campaign). |

`Preference` ∈ `{PREFERRED, STANDBY, INELIGIBLE}`. A node uses Mode A *or* Mode B per group. Defaults:
no affinity (plain election); `yieldWindow = 0`. The `force` promote variant (ADR-0016 §4a) is not yet
implemented. See `LeaderAffinityExample`.

---

## 6. Consistency tiers

`ConsistencyTier` (the per-key dial; ADR-0002). Ordered most-available → most-consistent:

| Tier | Mechanism | Use for | Status |
|------|-----------|---------|--------|
| `EVENTUAL` | pure gossip LWW | tags, labels, coarse health | ✅ live |
| `CAUSAL` | gossip + causal context (Bayou session guarantees) | service properties, weight, version | ✅ live (**default**) |
| `QUORUM` | on-demand read-repair across *k* replicas | "is this current before I route?" | ⬜ designed |
| `CONSENSUS` | small elected quorum (the elector) | singleton ownership, locks | ✅ live (via elector) |

---

## 7. Data types

| Type | Fields / methods | Notes |
|------|------------------|-------|
| `ServiceEntry` | `service()`, `owner()`, `address()`, `properties()`, `version()`, `tier()`, `alive()` | one per (owner, service) |
| `Version` (HLC) | `logical()`, `physical()`; `Comparable<Version>` | total order; greater = newer; restart-safe |
| `FreshnessToken` | `ownerId()`, `upTo()` (a `Version`) | monotonic; basis of session guarantees |
| `Leadership` | `group()`, `member()`, `epoch()`, `active()` | `epoch` is the fencing token |
| `Member` (L0) | `id()`, `address()`, … | scalecube member; resolve via `currentLeader` |

---

## 8. Internal tunables

These are currently fixed constants (candidates for future config). Values as shipped:

| Constant | Where | Value | Effect |
|----------|-------|-------|--------|
| Anti-entropy beacon interval | `PrismImpl.ANTI_ENTROPY_INTERVAL` | `5s` | Merkle-root beacon cadence; lower ⇒ faster heal, more traffic |
| Merkle depth | `GossipServiceRegistry.MERKLE_DEPTH` | `8` (256 buckets) | diff granularity; deeper ⇒ finer buckets, larger tree |
| Default registration tier | `ServiceRegistry.register(svc, props)` | `CAUSAL` | override with the 3-arg `register(..., tier)` |
| Default lease TTL / tick / call timeout | `PrismConfig.DEFAULT_*` | `5s / 1s / 1s` | see §1 |
| Default target quorum size | `PrismConfig.DEFAULT_TARGET_QUORUM_SIZE` | `3` | see §3 |
| HLC persist-ahead window | `HybridLogicalClock` | `1000ms` | how far ahead the durable clock high-water is pre-persisted |

---

## 9. Persistence (on disk)

Enabled by `withPersistenceDir(dir)`. Files written under `dir` (write-ahead, fsync'd):

| File | Written by | Contents | Recovery |
|------|-----------|----------|----------|
| `dir/lease.journal` | acceptor (`FileLeaseJournal`) | append-only accepted lease records | the acceptor reloads them on construction ⇒ the fencing epoch never regresses across a crash |
| `dir/clock.journal` | HLC (`FileClockJournal`) | the clock high-water (single value, truncate+write+fsync) | the HLC resumes ahead of any version it had handed out ⇒ versions never regress |

**Requires a stable member id.** With an ephemeral id, a restart is a *new* writer and durability is
meaningless for resuming leadership/versions (see [troubleshooting](troubleshooting.md)).

---

## 10. Metrics

A `Metrics` SPI (NOOP by default; `InMemoryMetrics` for tests; Micrometer/OTel via an adapter). Counter
names emitted today:

| Metric | Kind | Incremented when |
|--------|------|------------------|
| `prism.elector.granted` | counter | this node becomes active for a group |
| `prism.elector.revoked` | counter | this node loses/releases leadership for a group |
| `prism.registry.ae.beacon` | counter | a Merkle-root anti-entropy beacon is sent |
| `prism.registry.ae.readvertise` | counter | this node re-advertises its owned slice to heal a divergence |
| `prism.registry.event.registered` | counter | a `REGISTERED` event is emitted |
| `prism.registry.event.updated` | counter | an `UPDATED` event is emitted |
| `prism.registry.event.deregistered` | counter | a `DEREGISTERED` event is emitted |
| `prism.registry.event.expired` | counter | an `EXPIRED` event is emitted |

`InMemoryMetrics` exposes `count(name)` and `gaugeValue(name)` for assertions. Alert thresholds:
[`ops/runbook.md`](ops/runbook.md).

---

## 11. L0 (`scalecube-cluster`) settings that affect prism

prism rides the cluster you configure; these L0 options materially affect it:

| Option | Why it matters to prism |
|--------|-------------------------|
| `ClusterConfig.memberId` / `memberAlias` | a **stable id** is required for durability to be meaningful (an ephemeral UUID makes a restart a new writer) |
| `membership.seedMembers` | bootstrap/discovery; also the natural quorum-roster anchor (ADR-0015) |
| failure detector `pingInterval` / `pingTimeout` / `pingReqMembers` | detection speed vs. false positives; drives elector handoff latency and registry purge |
| `membership.suspicionMult` | suspicion timeout; how fast `DEAD` (registry purge / elector failover) fires |
| `membership.syncInterval` | membership-level anti-entropy; complements registry anti-entropy |
| transport `port` / `transportFactory` | the **gossip** transport — separate from the consensus transport prism binds |

---

## 12. Transport wire qualifiers

Message qualifiers prism uses (for debugging/observability of raw traffic):

| Qualifier | Channel |
|-----------|---------|
| `sc/prism/registry` | registry delta gossip |
| `sc/prism/registry/ae` | registry Merkle-root anti-entropy gossip |
| `sc/prism/consensus/lease/req` | lease request (proposer → acceptor) |
| `sc/prism/consensus/lease/resp` | lease reply (acceptor → proposer) |

All payloads are encoded by `prism-codec` (schema'd, version-byte-prefixed; no Java serialization).

---

## 13. Defaults at a glance

| Setting | Default |
|---------|---------|
| Registration tier | `CAUSAL` |
| `leaseTtl` | 5 s |
| `tickInterval` | 1 s |
| `callTimeout` | 1 s |
| `persistenceDir` | none (durability off) |
| `dynamicQuorum` | false (static quorum) |
| `targetQuorumSize` | 3 |
| Anti-entropy beacon | 5 s |
| Merkle depth | 8 (256 buckets) |
| Metrics | NOOP |

---

## 14. Sizing rules of thumb

- **Quorum size:** odd; 3 (tolerates 1 failure) or 5 (tolerates 2). Larger improves failure tolerance,
  not safety, at higher latency. Rarely exceed 5.
- **`leaseTtl` vs failover:** worst-case zero-leader gap on ungraceful death ≈ `leaseTtl`. Pick the
  smallest TTL your renewal reliability (RTT + GC pauses) sustains; keep `tickInterval ≤ leaseTtl/3`.
- **`callTimeout` (renewal-margin constraint — important):** above the consensus-transport p99 RTT,
  and **comfortably below `leaseTtl − tickInterval`**. The renewal round no longer blocks while
  holding the elector's lock — it runs plan→I/O→commit with the lock released, so a slow peer can no
  longer stall `campaign`/`leadership`/other groups (backlog #9, done). But a *slow* (not dead) peer
  can still delay **that group's own** renewal by up to `callTimeout` (an unreachable peer fails its
  leg promptly and the round completes on the reachable majority). If `callTimeout` is not well under
  the renewal margin (`leaseTtl − tickInterval`), a partial partition with one hanging peer could
  make a healthy leader miss a renewal and self-expire — a spurious failover (never two leaders;
  safety is unaffected). Rule of thumb with the defaults (`leaseTtl 5s`, `tickInterval 1s`): keep
  `callTimeout` ≈ 1s, well below the ~4s margin.
- **Registry scale:** full replication suits catalogs of KB–low-MB/node across thousands of nodes.
  Beyond that, the registry is the wrong model — partition the data instead.
- **Anti-entropy interval:** lower heals faster but costs steady gossip; 5 s suits most clusters.
