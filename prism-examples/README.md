
# prism-examples

Scalecube-style, **runnable** examples — and the original design driver for the public API. Each class
below has a `public static void main` you can run directly; they all compile and run today against the
current API. They are the spine of the [getting-started guide](../prism-docs/getting-started.md) and
the [user guide](../prism-docs/user-guide.md).

## Runnable examples (simple → advanced)

| # | Class | Shows | Nodes |
|---|-------|-------|-------|
| 1 | `HelloPrismExample` | wrap a cluster, register & look up locally | 1 |
| 2 | `ServiceRegistryExample` | advertise on one node, discover on another | 3 |
| 3 | `RegistryWatchExample` | snapshot-then-stream `watch()`; update/drain/deregister | 3 |
| 4 | `ConsistencyTiersExample` | `EVENTUAL`/`CAUSAL` tiers + monotonic freshness tokens | 2 |
| 5 | `InstanceSelectionExample` | discover many instances, pick a healthy one by weight | 3 |
| 6 | `GatewayElectionExample` | singleton elector + failover + fencing (one JVM) | 1 |
| 7 | `PrismGatewayExample` | partition-safe elector over a real 3-node quorum | 3 |
| 8 | `DurableLeaseExample` | crash-safe, non-regressing fencing (journaled acceptor) | 1 |
| 9 | `ObservabilityExample` | wire a `Metrics` sink; read election counters | 1 |
| 10 | `MultiGroupElectionExample` | independent leader per group (service A vs B); isolated failover | 1 |
| 11 | `SelfElectingQuorumExample` | dynamic quorum *mechanism*, in-process: self-forms 1→3 and self-heals, single-member steps (ADR-0015) | 1 |
| 12 | `LeaderAffinityExample` | leader affinity: preferred/sticky/no-failback + controller promote/demote (ADR-0016) | 1 |
| 13 | `DynamicQuorumExample` | dynamic quorum + durability via the **public API**: `withDynamicQuorum` (self-healing, gossip-metadata roster) + `withPersistenceDir` (durable leases/HLC/config); crash the leader → self-heal | 3 |
| 14 | `QuorumReadExample` | the `QUORUM` tier: `lookupQuorum` — a fresh-at-read-time, majority read-repair (vs. the local `lookup`); reflects the latest write | 3 |

Run one with the module on the classpath:

```
mvn -pl prism-examples dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "prism-examples/target/classes;$(cat prism-examples/cp.txt)" \
  io.scalecube.prism.examples.HelloPrismExample
```

---

The snippets further below are illustrative API patterns (some are composites, not 1:1 with a class).
The numbered classes above are the authoritative, runnable set.

## The entry point (proposed)

prism is a **layer over a scalecube `Cluster`** — you build a normal cluster node, then wrap it. One
object to manage; registry and elector hang off it.

```java
// L0: an ordinary scalecube cluster node (unchanged)
Cluster cluster =
    new ClusterImpl()
        .config(opts -> opts.memberAlias("orders-1"))
        .transportFactory(TcpTransportFactory::new)
        .membership(opts -> opts.seedMembers(Address.from("seed-host:4801")))
        .startAwait();

// L1+: wrap it with prism
Prism prism = new PrismImpl(cluster).startAwait();

ServiceRegistry registry = prism.registry();
SingletonElector elector = prism.elector();

// ... use them ...

prism.shutdown().block();   // stops prism; cluster lifecycle stays yours
```

`Prism` is the only new top-level type a user learns; everything else is `ServiceRegistry` /
`SingletonElector` from `prism-api`.

---

## Example 1 — Advertise & discover

```java
public final class ServiceRegistryExample {

  public static void main(String[] args) {
    Cluster cluster =
        new ClusterImpl()
            .config(opts -> opts.memberAlias("orders-1"))
            .transportFactory(TcpTransportFactory::new)
            .membership(opts -> opts.seedMembers(Address.from("seed:4801")))
            .startAwait();

    Prism prism = new PrismImpl(cluster).startAwait();
    ServiceRegistry registry = prism.registry();

    // Advertise THIS node's service. Single-writer: a node only registers its own services.
    registry
        .register(
            "orders",
            Map.of("weight", "100", "status", "passing", "version", "1.4.0"),
            ConsistencyTier.CAUSAL)
        .block();

    // Discover locally — no network hop, always available, possibly slightly stale.
    for (ServiceEntry e : registry.lookup("orders")) {
      System.out.printf(
          "%s @ %s  weight=%s status=%s%n",
          e.owner(), e.address(), e.properties().get("weight"), e.properties().get("status"));
    }
  }
}
```

---

## Example 2 — Watch the topology & update properties at runtime

```java
public final class RegistryWatchExample {

  public static void main(String[] args) {
    Prism prism = new PrismImpl(/* cluster */).startAwait();
    ServiceRegistry registry = prism.registry();

    // React live — keep a load-balancer pool in sync instead of polling.
    registry
        .watch()
        .subscribe(
            ev -> {
              switch (ev.type()) {
                case REGISTERED -> pool.add(ev.entry());
                case UPDATED -> pool.update(ev.entry()); // e.g. weight or status changed
                case DEREGISTERED, EXPIRED -> pool.remove(ev.entry());
                default -> {}
              }
            });

    // Later: gracefully drain THIS node before a deploy (only my own service).
    registry.update("orders", "status", "draining").block();
    registry.update("orders", "weight", "0").block();
    // ... let in-flight requests finish ...
    registry.deregister("orders").block();
  }
}
```

> **Consumer contract:** a lookup is a *hint*. The registry is complete, convergent, and
> monotonic-per-key — **not** linearizable. Always be ready for "I got an address, it didn't answer":
> retry, circuit-break, try the next instance. A dead node may linger briefly; a new one may be
> momentarily invisible.

---

## Example 3 — Safe singleton (only one node Active), with fencing

```java
public final class SingletonElectorExample {

  public static void main(String[] args) {
    Prism prism = new PrismImpl(/* cluster */).startAwait();
    SingletonElector elector = prism.elector();

    // React to leadership changes for the "scheduler" group.
    elector
        .leadership("scheduler")
        .subscribe(
            lead -> {
              if (lead.active()) {
                runScheduler(lead.epoch()); // pass the fencing epoch into every side effect
              } else {
                stopScheduler(); // revoked — stop acting immediately
              }
            });

    // Join the race. At most one member is Active for "scheduler" at any time — never two.
    elector.campaign("scheduler").block();
  }

  // Every external action carries the fencing epoch; the resource rejects stale epochs, so a
  // partitioned old leader that hasn't noticed it lost the lease can do no damage.
  static void persistJob(long epoch, Job job) {
    db.execute(
        "INSERT INTO jobs(...) SELECT ... WHERE :epoch >= (SELECT COALESCE(MAX(fence),0) FROM jobs)",
        epoch);
  }
}
```

---

## Example 4 — Run work *only* while leader (registry + elector together)

```java
public final class ActivePassiveServiceExample {

  public static void main(String[] args) {
    Prism prism = new PrismImpl(/* cluster */).startAwait();

    // Always advertise the service so consumers can find whoever is Active.
    prism.registry().register("cron", Map.of("role", "standby"), ConsistencyTier.CAUSAL).block();

    prism
        .elector()
        .leadership("cron")
        .subscribe(
            lead -> {
              if (lead.active()) {
                prism.registry().update("cron", "role", "active").block();
                startCronLoop(lead.epoch());
              } else {
                prism.registry().update("cron", "role", "standby").block();
                stopCronLoop();
              }
            });

    prism.elector().campaign("cron").block();
  }
}
```

---

## Example 5 — Active/passive gateway (exactly one Active)

A network gateway that accepts client connections: **at most one instance is Active at any time —
never two** — and when the Active dies, the standby is promoted. This is the canonical reason the
elector exists.

```java
public final class ActivePassiveGatewayExample {

  public static void main(String[] args) {
    Prism prism = new PrismImpl(/* unstarted ClusterImpl */).startAwait();

    GatewayServer server = new GatewayServer(); // your listener; starts CLOSED (passive)

    prism
        .elector()
        .leadership("gateway")
        .subscribe(
            lead -> {
              if (lead.active()) {
                // We won the lease: open the listener and start accepting connections.
                server.activate(lead.epoch()); // epoch fences every downstream action
                prism.registry().update("gateway", "role", "active").block();
              } else {
                // Revoked (lost the lease / partitioned away): stop accepting immediately.
                server.deactivate();
                prism.registry().update("gateway", "role", "standby").block();
              }
            });

    // Advertise the gateway so clients can discover whoever is currently active.
    prism.registry().register("gateway", Map.of("role", "standby")).block();

    // Join the race. If we don't win, we stay passive, ready to be promoted.
    prism.elector().campaign("gateway").block();
  }
}
```

**Guarantees and the one unavoidable tradeoff:**

- **Never two serving.** At most one member holds the `gateway` lease per epoch (consensus). Even
  during a network partition, a superseded old-Active is *fenced*: its epoch is stale, so any
  connection it still accepts is rejected downstream. You cannot end up with two authoritative
  gateways.
- **Not stuck with zero.** When the Active dies, its lease expires (or it releases instantly via a
  graceful `LEAVING`), the standby's campaign wins, and it is promoted. Eventually exactly one.
- **The failover gap is mandatory.** Between a hard crash and the standby's promotion there is a brief
  window with *zero* Actives — that is the price of "never two": you must be sure the old lease cannot
  still be valid before activating the new one. Graceful shutdown shrinks this to near-zero; only an
  ungraceful crash incurs the lease-timeout gap. **You cannot have both zero-gap failover and
  never-two** in a partitionable system — and for a gateway that must not double-serve, briefly-zero
  is the correct, safe choice. During a consensus quorum loss the system also prefers zero over two.

---

## API decisions (resolved — see ADR-0011)
1. **Entry point:** `new PrismImpl(cluster).startAwait()` — decorator over `Cluster`; prism does not
   own the cluster's lifecycle.
2. **Registration tier:** `register(service, props)` defaults to `CAUSAL`; the 3-arg overload opts
   into another tier.
3. **Reactive vs blocking:** mutating ops return `Mono<Void>`; `*Await()` variants block, like
   scalecube's `startAwait`.
4. **Watch semantics:** snapshot-then-stream — a new subscriber gets the current catalog as
   `REGISTERED` events, then live changes.
