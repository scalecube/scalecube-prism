# Phase 0 — Harden the core

**Where:** `scalecube-cluster` (the L0 repo) — branch/fork, ideally upstreamed.
**Depends on:** nothing. Runs as a **parallel** stream; not a gate for Prism.
**Goal:** make the AP foundation correct and stable enough to build safety-critical layers on.

## Scope
**In:** four surgical fixes to existing behavior. **Out:** any new responsibility (registry,
consensus) — those live in Prism, not here.

## Work items

### 0.1 — Metadata versioning (LWW) · S
- Add a version field (`long`, HLC-friendly) to `GetMetadataResponse`.
- Stamp it on the response in `MetadataStoreImpl#onMetadataRequest` from a monotonic local counter.
- Reader applies fetched metadata iff `version > stored`; store `(bytes, version)` together.
- Drive the `Updated` event off version change, not byte-equality, in `MembershipProtocolImpl`.
- **Files:** `GetMetadataResponse.java`, `MetadataStoreImpl.java`, metadata apply path in
  `MembershipProtocolImpl.java`.
- **Tests:** out-of-order pull responses; rapid successive updates; restart with stable id.

### 0.2 — Lifeguard: Local Health Multiplier (LHM) · M
- Track a local health score from recent probe outcomes (missed acks, late acks).
- Scale `pingTimeout` and suspicion timeout by the multiplier so an unhealthy local node stops
  aggressively suspecting others.
- **Files:** `FailureDetectorImpl.java`, `FailureDetectorConfig.java`, `ClusterMath#suspicionTimeout`.
- **Tests (sim):** a GC-paused/slow node does **not** cause a cascade of false suspicions.

### 0.3 — Lifeguard: suspicion confirmation · M
- Require *k* independent suspicion confirmations before declaring DEAD; shrink the timeout as
  confirmations accrue; prioritize gossiping a suspicion so the target can refute fast.
- **Files:** suspicion handling in `MembershipProtocolImpl.java`, `MembershipRecord.java`.
- **Tests (sim):** single false suspicion never kills a healthy node; real death still detected fast.

### 0.4 — Gossip batching · S
- Coalesce multiple gossips destined for one peer into a single `GossipRequest` up to a size cap
  (MTU-aware) in `GossipProtocolImpl#spreadGossipsTo` (today: one message per gossip).
- **Files:** `GossipProtocolImpl.java`, `GossipConfig.java` (cap setting).
- **Tests:** message count per round drops; convergence unchanged.

### 0.5 — Bounded backpressure · S
- Replace unbounded `onBackpressureBuffer()` on the event sinks with a bounded buffer + drop counter.
- **Files:** sinks in `FailureDetectorImpl`, `GossipProtocolImpl`, `MembershipProtocolImpl`.
- **Tests:** under flood, memory stays bounded; drop metric increments.

## Definition of done
- All four fixes merged (upstream PRs or a maintained patch branch).
- In `prism-sim` (once available): no false-positive deaths under slow-node chaos; metadata converges
  by version; gossip message count reduced by batching.

## Risks
- **Upstream acceptance latency** → mitigate with a thin patch branch consumed via a pinned version;
  drop the patch when upstream merges.
- **Lifeguard tuning** is subtle → validate purely in the simulator before any real deployment.
