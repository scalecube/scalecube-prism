# Business use cases

What real problems teams reach for prism to solve — written as user stories, each mapped to the
feature that solves it and the test that proves it. The goal is full feature coverage: if a capability
exists, there is a use case here and a test behind it.

Legend for **Validated by**: `*E2eTest` = black-box test over a real cluster via the public API;
`*Test` = focused unit/integration test; `*Example` = runnable demo.

---

## A. Service discovery & routing (the AP registry)

### A1 — Find the services I depend on
> *As a microservice, I want to discover live instances of the services I call, so I don't hard-code
> addresses and can scale instances up/down freely.*

**Problem:** static address lists rot; instances come and go. **Prism:** `register` advertises an
instance; `lookup`/`list` return live instances from a local, always-available view that converges via
gossip. **Validated by:** `RegistryE2eTest.registerThenDiscoverAcrossNodes`, `ServiceRegistryExample`.

### A2 — Route to the healthiest / best instance (client-side load balancing)
> *As a gateway, I want to choose among instances by weight and health, so traffic favors capable,
> healthy nodes.*

**Problem:** a flat round-robin ignores capacity and health. **Prism:** each instance carries a
property map (`weight`, `status`, zone, …); the caller filters `alive()` + `status` and picks by
weight. **Validated by:** `UseCaseE2eTest.clientSideLoadBalancing`, `InstanceSelectionExample`.

### A3 — Keep my routing table hot, without polling
> *As a router, I want my routing table to update the instant topology changes, so I never route on a
> stale snapshot.*

**Problem:** polling is laggy and wasteful. **Prism:** `watch()` replays a snapshot then streams live
`REGISTERED`/`UPDATED`/`DEREGISTERED` events. **Validated by:** `RegistryE2eTest.watchObservesLifecycleEvents`,
`RegistryWatchExample`.

### A4 — Zero-downtime deploys (drain before shutdown)
> *As an operator, I want to drain an instance (stop new traffic, finish in-flight) before stopping
> it, so deploys cause no dropped requests.*

**Problem:** killing a node mid-request drops traffic. **Prism:** `update("svc","weight","0")` and/or a
`status=draining` property lets routers deprioritize, then `deregister` removes it cleanly.
**Validated by:** `UseCaseE2eTest.zeroDowntimeDrainBeforeShutdown`.

### A5 — Stop routing to crashed nodes automatically
> *As a consumer, I never want to send requests to a process that has died.*

**Problem:** a crashed provider can linger in a naive registry. **Prism:** membership is the
tombstone — when SWIM marks a member `DEAD`, its entries are purged everywhere. **Validated by:**
`RegistryE2eTest.deadProviderEntriesArePurged`.

### A6 — Run many instances of the same service
> *As a platform, I want N instances of a service to all be discoverable so callers can balance across
> them.*

**Problem:** a single-value registry hides replicas. **Prism:** one entry per (owner, service);
`lookup` returns them all. **Validated by:** `RegistryE2eTest.discoversMultipleInstancesOfAService`.

---

## B. Consistency control (the per-key dial)

### B1 — Don't read my own writes stale (session guarantees)
> *As a service that just updated its own metadata, I want my next read to reflect it (read-your-writes
> / monotonic reads).*

**Problem:** pure eventual consistency can show a client older data than it just wrote. **Prism:** the
`CAUSAL` tier (default) plus a `FreshnessToken` (`upTo()`), which advances monotonically per owner.
**Validated by:** `RegistryE2eTest.freshnessTokenIsMonotonic`, `ConsistencyTiersExample`.

### B2 — Pay only for the consistency each key needs
> *As an owner, I want cheap eventual consistency for coarse data (tags, labels) but stronger
> guarantees for critical properties — without running two systems.*

**Problem:** one-size consistency over-pays (strong-everywhere) or under-delivers (eventual-everywhere).
**Prism:** declare a `ConsistencyTier` per key (`EVENTUAL`/`CAUSAL`; `QUORUM` designed; `CONSENSUS` via
the elector) on one substrate. **Validated by:** `RegistryE2eTest.registersAtDifferentConsistencyTiers`.

---

## C. Leadership & coordination (the CP elector)

### C1 — Exactly one active instance, never two (active/passive HA)
> *As a stateful gateway or scheduler, I need exactly one active instance at a time — and never two,
> even during a network partition.*

**Problem:** gossip-only "lowest id" election splits under partition (FLP). **Prism:** a majority-quorum
lease — only one owner can be majority-backed at once. **Validated by:** `ElectorSafetyFuzzTest`,
`QuorumElectionIntegrationTest`, `LeaseElection.tla`; E2E `ElectorE2eTest` (failover, resign).

### C2 — Automatic, prompt failover
> *As an HA service, when the active dies I want a standby promoted quickly, so the role isn't vacant
> for long.*

**Problem:** manual failover is slow and error-prone. **Prism:** the lease expires (≤ `leaseTtl`) and a
standby acquires; graceful `resign` hands off near-instantly. **Validated by:**
`ElectorE2eTest.leaderFailoverPromotesAStandbyAtHigherEpoch`, `...resignHandsOffToAnotherNode`.

### C3 — A zombie old-leader must not corrupt anything (fencing)
> *As the owner of a shared resource, I must reject writes from a leader that has been superseded but
> doesn't know it yet.*

**Problem:** a partitioned old-active can keep acting. **Prism:** every grant carries a monotonic
**fencing epoch**; the downstream rejects a lower epoch. **Validated by:**
`ElectorE2eTest` (epoch strictly increases on failover), `GatewayElectionExample` (fencing demo),
`LeaseElection.tla` (fencing monotonicity).

### C4 — Independent leaders for different responsibilities
> *As a platform, service A and service B each need their own single leader, on the same cluster.*

**Problem:** one global leader is a bottleneck and a wrong abstraction. **Prism:** election is scoped to
a *group* key; each group elects independently. **Validated by:**
`ElectorE2eTest.independentLeaderPerGroup`, `MultiGroupChurnStressTest`, `MultiGroupElectionExample`.

### C5 — Pin leadership to a preferred location, stickily, with no auto-failback
> *As a latency-sensitive system, I want the leader co-located with an anchor (e.g. same AZ), sticky
> once elected, and NOT to fail back automatically when the old preferred node returns.*

**Problem:** naive preference causes flapping/failback. **Prism:** leader affinity Mode A — preference
biases the election only, never preempts a healthy leader; one controlled auto-move when the anchor
moves. **Validated by:** `AffinityE2eTest.preferredNodeWinsTheElection`, `LeaseElectorAffinityTest`,
`LeaderAffinityExample`.

### C6 — Let an external controller drive leadership
> *As an orchestrator that knows the topology, I want to explicitly promote/demote which node leads,
> with prism as the safety floor (never two, even on a racy command).*

**Problem:** baking topology knowledge into every node is brittle. **Prism:** Mode B `promote`
(cooperative) / `demote`; the quorum guarantees at-most-one regardless. **Validated by:**
`AffinityE2eTest.controllerPromoteIsCooperative`, `...controllerDemoteThenPromoteHandsOff`.

---

## D. Operations & platform

### D1 — A self-managing quorum (no hand-listed members)
> *As an operator, I don't want to hand-configure and babysit quorum membership; it should form itself
> and heal when a member dies permanently.*

**Problem:** static quorum config is toil and a failure mode. **Prism:** self-electing quorum
(`withDynamicQuorum`) — single-member reconfiguration through consensus, sized to a target, self-healing.
**Validated by:** `DynamicQuorumSmokeTest`, `DynamicQuorumTransportIntegrationTest`,
`ReconfigurationSafetyFuzzTest`, `SelfElectingQuorumExample`, `SelfElectingQuorum.tla`.

### D2 — Survive restarts without surprises
> *As an operator, after a crash/restart I must not get a regressed version or a stale leadership
> grant.*

**Problem:** in-memory state forgets across restarts. **Prism:** durable write-ahead lease journal +
durable HLC high-water (with a stable member id). **Validated by:** `FileLeaseJournalTest`,
`FileClockJournalTest`, `DurableLeaseExample`.

### D3 — See what the cluster is doing (observability)
> *As an SRE, I want metrics on elections and registry reconciliation so I can alert and debug.*

**Problem:** opaque coordination is unoperable. **Prism:** a `Metrics` SPI emits
`prism.elector.granted/revoked`, `prism.registry.ae.*`, `prism.registry.event.*`. **Validated by:**
`UseCaseE2eTest.electionMetricsAreRecorded`, `ObservabilityExample`.

### D4 — Don't get RCE'd by a malicious peer (secure wire)
> *As a security owner, I need the cluster not to deserialize arbitrary objects off the network.*

**Problem:** Java serialization on the wire is a deserialization-gadget RCE surface. **Prism:** a
schema'd, version-prefixed binary codec — no `ObjectInputStream`. **Validated by:** `LeaseCodecTest`,
`ConfigCodecTest` (round-trips; no object graph construction).

---

## Coverage matrix

| Feature | Use cases | Primary test |
|---------|-----------|--------------|
| Registry register/lookup/list | A1, A6 | `RegistryE2eTest` |
| Registry watch | A3, A4 | `RegistryE2eTest`, `UseCaseE2eTest` |
| Properties / selection | A2 | `UseCaseE2eTest` |
| Membership lifecycle | A5 | `RegistryE2eTest` |
| Consistency tiers | B2 | `RegistryE2eTest` |
| Freshness tokens | B1 | `RegistryE2eTest` |
| Elector (never two) | C1 | `ElectorE2eTest`, DST, TLA+ |
| Failover / resign | C2 | `ElectorE2eTest` |
| Fencing epoch | C3 | `ElectorE2eTest`, TLA+ |
| Per-group election | C4 | `ElectorE2eTest`, sim |
| Affinity (preference) | C5 | `AffinityE2eTest` |
| Affinity (promote/demote) | C6 | `AffinityE2eTest` |
| Dynamic quorum | D1 | smoke + transport IT + DST |
| Durability | D2 | journal tests |
| Observability | D3 | `UseCaseE2eTest` |
| Security codec | D4 | codec tests |

See the [user guide](user-guide.md) for how-to, [architecture](architecture.md) for why, and
[guarantees](guarantees.md) for the formal contract behind these claims.
