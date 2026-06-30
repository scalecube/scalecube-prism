# Phase 2 — AP service registry (`prism-versioning` + `prism-registry`)

**Depends on:** `scalecube-cluster` (gossip + membership APIs); Phase 1 for tests.
**Goal:** a fully-replicated, versioned, causally-consistent registry — local reads, converging
writes, per-property versions. Already beyond Eureka at completion.

## Part A — `prism-versioning` (foundational)

### 2.1 — `HybridLogicalClock implements Version` · M
- `now()` (advance using max(physical, last)+logical rules), `update(remote)` (merge on receive),
  total-order `compareTo`.
- Compact, codec-friendly representation (`logical:long`, `physical:long`).
- **Tests:** monotonicity under rapid calls; correct merge with a remote clock ahead/behind; never
  regresses across `update`.

### 2.2 — `FreshnessToken` impl · S
- Build `(ownerId, upTo:Version)` from the local view; returned alongside reads.

## Part B — `prism-registry` (L1 + L2)

### 2.3 — Entry & store model · S
- `ServiceEntryImpl`; internal key `(ownerId, service)`; store
  `Map<ownerId, Map<service, VersionedEntry>>`.
- **Single-writer enforcement:** reject `register/update/deregister` for services this node doesn't own.

### 2.4 — Dissemination over gossip · M
- Encode entry deltas (`prism-codec`) and `Cluster.spreadGossip`; on `listenGossips`, decode and apply
  **LWW** (`version > stored`) → emit `RegistryEvent`.
- Idempotent + reorder-safe by construction (the version guard).

### 2.5 — Anti-entropy (delta + Merkle) · M
- Per-owner digest (highest version held); Merkle root over the owned keyspace.
- Periodic digest exchange (own message or piggyback on a sync tick); mismatches pull only the missing
  deltas. Closes the gossip tail.

### 2.6 — Lifecycle bound to membership · M
- Subscribe `Cluster.listenMembership()`.
- Owner `DEAD`/`REMOVED` → purge its entries, emit `EXPIRED` (membership *is* the tombstone — ADR-0005).
- Live `deregister` → versioned tombstone + TTL; GC sweep after the dissemination window.

### 2.7 — Consistency router (L2) · S
- Dispatch by `ConsistencyTier`: implement `EVENTUAL` and `CAUSAL` (causal adds session-guarantee
  read tracking); `QUORUM`/`CONSENSUS` route to Phase 5 / Phase 3 (stub → clear `Unsupported` for now).

### 2.8 — Public API (`GossipServiceRegistry`) · S
- Implement `ServiceRegistry`: `register/update/deregister`, local `lookup/list`, `watch()` (`Flux`),
  `freshness(ownerId)`.

## Cross-cutting added here
- **Codec:** registry-delta + tombstone schemas in `prism-codec` (ADR-0009).
- **Persistence:** local slice + HLC version, WAL, stable-id resume (`prism-persistence`).
- **Observability:** dissemination latency, anti-entropy volume, tombstone counts.

## Definition of done (verified in `prism-sim`)
- **Convergence:** all nodes reach identical catalog after churn.
- **Monotonicity:** no observer ever sees a property version regress.
- **Session guarantees** for `CAUSAL` keys: read-your-writes, monotonic reads.
- **Tombstones:** a deleted/expired key never resurrects via anti-entropy.
- **Restart:** a stable-id node resumes at `version+1` and isn't rejected as stale.

## Risks
- **Anti-entropy cost** at scale → ship deltas not full state; cap Merkle granularity; `log()` any
  truncation.
- **Causal-context growth** → causal-stability pruning; keep `CAUSAL` opt-in.
