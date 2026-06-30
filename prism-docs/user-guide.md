# User guide

A feature-by-feature tour of prism, each section backed by a runnable example in
[`prism-examples`](../prism-examples). It is ordered simple → advanced; if you are brand new, start
with the [getting-started guide](getting-started.md) first.

| # | Topic | Example | Nodes |
|---|-------|---------|-------|
| 1 | Wrap a cluster | `HelloPrismExample` | 1 |
| 2 | Register & discover | `ServiceRegistryExample` | 3 |
| 3 | Watch the registry live | `RegistryWatchExample` | 3 |
| 4 | Consistency tiers & freshness | `ConsistencyTiersExample` | 2 |
| 5 | Discover many, pick one | `InstanceSelectionExample` | 3 |
| 6 | Singleton elector + fencing (1 JVM) | `GatewayElectionExample` | 1 |
| 7 | Partition-safe elector (3 nodes) | `PrismGatewayExample` | 3 |
| 8 | Durable, crash-safe fencing | `DurableLeaseExample` | 1 |
| 9 | Metrics & observability | `ObservabilityExample` | 1 |
| 10 | Independent leader per group | `MultiGroupElectionExample` | 1 |
| 11 | Self-electing / self-healing quorum (mechanism, in-process) | `SelfElectingQuorumExample` | 1 |
| 12 | Leader affinity (preferred / promote-demote) | `LeaderAffinityExample` | 1 |
| 13 | Dynamic quorum + durability via the public API (3 nodes) | `DynamicQuorumExample` | 3 |
| 14 | QUORUM read-repair (`lookupQuorum`) | `QuorumReadExample` | 3 |

---

## 1. Wrap a cluster

prism is a decorator over a scalecube `Cluster`: one wrapper, with `registry()` and `elector()` hanging
off it. `shutdown()` stops prism but not the cluster you handed it.

```java
Prism prism =
    new PrismImpl(new ClusterImpl().transportFactory(TcpTransportFactory::new)).startAwait();
ServiceRegistry registry = prism.registry();
```

The registry is ready immediately; the elector is only available when you pass a `PrismConfig` (§7).
→ `HelloPrismExample`.

## 2. Register & discover

The owner of a service advertises it with a property map. Other nodes discover it through gossip — an
**AP**, eventually-consistent, always-available local view.

```java
provider.registry().register("orders", Map.of("weight", "100", "status", "passing")).block();
Collection<ServiceEntry> found = consumer.registry().lookup("orders");
```

`register` defaults to the `CAUSAL` tier (§4). `lookup` returns every alive instance; `list()` returns
the whole catalog. → `ServiceRegistryExample`.

## 3. Watch the registry live

`watch()` first **replays a snapshot** of what's already known, then streams live changes
(`REGISTERED` / `UPDATED` / `DEREGISTERED`). Ideal for a router keeping a hot routing table.

```java
consumer.registry().watch().subscribe(ev ->
    System.out.println(ev.type() + " " + ev.entry().service() + " " + ev.entry().properties()));

provider.registry().register("orders", Map.of("weight", "100")).block();
provider.registry().update("orders", "weight", "0").block(); // drain
provider.registry().deregister("orders").block();
```

Only the **owner** mutates its keys (single-writer-per-key); updates are versioned by a Hybrid Logical
Clock so they apply in causal order regardless of arrival order. → `RegistryWatchExample`.

## 4. Consistency tiers & freshness

Each key carries the *weakest tier that is still correct* — the per-key
[consistency dial](../README.md):

| Tier | Mechanism | Use for |
|------|-----------|---------|
| `EVENTUAL` | gossip LWW | tags, labels, coarse health |
| `CAUSAL` (default) | gossip + causal context | service properties, weight, version |
| `QUORUM` | on-demand read-repair across *k* | "is this current before I route?" |
| `CONSENSUS` | elected group (the elector) | singleton ownership, locks |

```java
registry.register("cache", Map.of("region", "eu"), ConsistencyTier.EVENTUAL).block();
registry.register("orders", Map.of("weight", "100"), ConsistencyTier.CAUSAL).block();
FreshnessToken t = registry.freshness(ownerId); // upTo() advances monotonically with that owner's writes
```

A `FreshnessToken` lets a reader assert it has observed at least up to a version it wrote — the basis of
Bayou-style session guarantees (read-your-writes, monotonic reads). → `ConsistencyTiersExample`.

`QUORUM` read-repair is now implemented as `registry.lookupQuorum(service)` — a per-read,
majority-quorum fetch-and-repair that errors (`QuorumUnavailableException`) if a majority is
unreachable. `EVENTUAL`/`CAUSAL` and the `CONSENSUS` elector are live too. It is opt-in per read,
not yet auto-applied to every read of a `QUORUM`-tagged key. See the [roadmap](plan.md).

```java
// Fresh-at-read-time: ask a majority of members, repair locally, return the freshest instances.
Collection<ServiceEntry> fresh = registry.lookupQuorum("orders").block();
```

## 5. Discover many, pick one

`lookup` returns every alive instance (one per owner). Selection policy is the caller's — filter by
health and choose by weight, zone, latency, whatever you track in properties.

```java
ServiceEntry chosen = consumer.registry().lookup("orders").stream()
    .filter(ServiceEntry::alive)
    .filter(e -> "passing".equals(e.properties().get("status")))
    .max(Comparator.comparingInt(e -> Integer.parseInt(e.properties().getOrDefault("weight", "0"))))
    .orElse(null);
```

→ `InstanceSelectionExample`.

## 6. Singleton elector + fencing (one JVM)

Exactly one member is `active` per group at a time. Safety comes from a quorum lease; a monotonic
**fencing epoch** protects a downstream from a zombie former leader.

```java
elector.leadership("gateway").subscribe(server::apply); // active flips on/off here
elector.campaign("gateway").block();
elector.start(Duration.ofMillis(200));                  // renew + promote standbys
// ... if the active stops renewing, its lease expires and a standby is promoted at a higher epoch ...
```

A downstream resource that records the highest epoch it has seen will **reject** the old leader's lower
epoch — that is fencing, and it is what makes "never two actives" externally true.
→ `GatewayElectionExample` (in-memory consensus, single process).

## 7. Partition-safe elector (3 nodes)

The production shape: a `PrismConfig` declares this node's dedicated consensus address and the quorum it
belongs to. A majority lease guarantees one leader even under partition; a minority side cannot acquire
(it loses availability, never safety).

```java
List<String> quorum = List.of(host + ":7001", host + ":7002", host + ":7003");
PrismConfig config = new PrismConfig(host + ":7001", quorum, TcpTransportFactory::new);
Prism prism = new PrismImpl(cluster, config).startAwait();
prism.elector().campaign("gateway").block();
prism.elector().currentLeader("gateway"); // who leads right now
```

The consensus address **must equal** what the transport advertises (don't rewrite it). On ungraceful
death there is a bounded zero-leader window (≤ `leaseTtl`); graceful `resign` hands off near-instantly.
→ `PrismGatewayExample`. Tuning: [config-reference](config-reference.md).

## 8. Durable, crash-safe fencing

Enable durability with `PrismConfig.withPersistenceDir(dir)` and a **stable member id**. The acceptor
journals every acceptance write-ahead, so a restarted node never forgets the fencing high-water and can
never hand out a stale (lower) epoch.

```java
Acceptor recovered = new Acceptor(new FileLeaseJournal(journalPath)); // recovers prior acceptances
// an expired lease at epoch 5 still forces any new owner to use epoch > 5
```

→ `DurableLeaseExample` (shown at the consensus layer so it is fully self-contained). Without
durability, see the version-regression pitfall in [troubleshooting](troubleshooting.md).

## 9. Metrics & observability

Pass any `Metrics` implementation to the runtime (the SPI a Micrometer/OpenTelemetry adapter
implements). `InMemoryMetrics` is handy for tests and demos.

```java
InMemoryMetrics metrics = new InMemoryMetrics();
Prism prism = new PrismImpl(cluster, config, metrics).startAwait();
prism.elector().campaign("gateway").block();
metrics.count("prism.elector.granted"); // 1 after winning
```

Key signals: `prism.elector.granted` / `revoked`, and the registry anti-entropy counters
(`prism.registry.ae.*`). → `ObservabilityExample`. See [concurrency](concurrency.md) for the threading
model and [runbook](ops/runbook.md) for alert thresholds.

---

## 10. Independent leader per group

Election is scoped to a **group** key, so different services elect their own leader concurrently and
independently on the same machinery. Three nodes of service A elect one A-leader; three nodes of
service B elect one B-leader — at the same time, with no interference.

```java
for (LeaseElector e : groupA) { e.campaign("service-A").block(); e.start(tick); }
for (LeaseElector e : groupB) { e.campaign("service-B").block(); e.start(tick); }
// elector.currentLeader("service-A") and currentLeader("service-B") are distinct and independent
```

Killing service-A's leader triggers failover **only** within service A; service-B's leader is
untouched. Each group has its own lease, epoch, and fencing — one elector, any number of groups.
→ `MultiGroupElectionExample`.

## 11. Self-electing / self-healing quorum

Opt in with `PrismConfig.withDynamicQuorum(target)`: `quorumMembers` becomes a **candidate roster**,
and the quorum sizes itself to the target (odd) and **self-heals** by single-member reconfiguration
committed through consensus — no hand-listed fixed quorum (ADR-0015).

```java
PrismConfig config =
    new PrismConfig(addr, roster, TcpTransportFactory::new).withDynamicQuorum(3);
```

`SelfElectingQuorumExample` narrates the mechanics in-process: bootstrap `{n0}` → self-forms to
`{n0,n1,n2}` → n1 dies → self-heals to `{n0,n2,n3}`, leader stable, every step single-member (the
rule that keeps "never two leaders" safe across reconfiguration). The static quorum stays the default.

`DynamicQuorumExample` shows the same through the **public API over a real 3-node cluster**:
`withDynamicQuorum(3)` (each node advertises its consensus address as gossip metadata, so the roster
is derived from the live cluster) combined with `withPersistenceDir(dir)` (durable leases, HLC, and
the committed config chain) — it elects a leader, crashes it, and the quorum self-heals to a new
leader at a higher fencing epoch.

## 12. Leader affinity

Bias *which* member leads without breaking "never two." Two modes, same lease/fencing kernel
([ADR-0016](decisions/0016-leader-affinity.md)):

```java
// Mode A — autonomous, preference-biased (sticky, no automatic failback):
elector.affinity("gateway",
    () -> myZone.equals(anchor.activeZone()) ? Preference.PREFERRED : Preference.STANDBY,
    Duration.ofSeconds(8),  // STANDBY yields this long so a PREFERRED node wins first
    true);                  // hand off once if this leader becomes non-preferred

// Mode B — controller-driven (nodes passive; an external controller drives):
boolean won = elector.promote("gateway").block(); // cooperative: wins only if free
elector.demote("gateway").block();                // release + stay passive
```

A `PREFERRED` node wins the election regardless of who campaigned first; a returning preferred node
**never** preempts a healthy leader (no failback). → `LeaderAffinityExample`.

## Beyond the examples

- **Self-electing / dynamic quorum** and **leader affinity** are both shipped (above). Remaining
  hardening — durable epoch-floor on the dynamic path, a gossip-pool-derived roster, and the `force`
  promote variant — is tracked in [ADR-0015](decisions/0015-self-electing-quorum.md),
  [ADR-0016](decisions/0016-leader-affinity.md), and the [roadmap](plan.md).
