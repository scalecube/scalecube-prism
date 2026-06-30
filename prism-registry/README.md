# prism-registry

**Layer:** L1 + L2 · **Phase:** 2 · **Status:** planned (impl package declared)

## What it does
The service registry itself: a fully-replicated, eventually-consistent catalog where every node
holds the complete view locally, and each node advertises (and solely owns) its own services.
Implements `prism-api`'s `ServiceRegistry`.

## Goal
Give applications a **local, always-available** lookup of "who offers service X and with what
properties," that **converges** across the cluster and exposes **per-property versions** so consumers
can route on `weight` / `status` / `version` safely.

## How
- **Data model:** per-owner, single-writer, per-key versioned property map (a per-source LWW-map
  CRDT). Versions come from `prism-versioning` (HLC).
- **Dissemination:** rides the existing scalecube `GossipProtocol` (`Cluster.spreadGossip` /
  `listenGossips`) with **delta + Merkle anti-entropy** — ship only what changed, reconcile by digest.
  The `MerkleTree` algorithm (range-based, Dynamo/Cassandra-style) compares two replicas' roots in
  O(1) and descends only differing branches, so reconciliation exchanges just the changed buckets;
  `RegistryStore.contentDigest()` feeds it.
- **Live anti-entropy (wired):** each node periodically gossip-broadcasts its catalog's Merkle root
  (`start(interval)`, driven by `PrismImpl`). A peer whose root differs re-advertises its owned slice,
  so any missed update heals via LWW — and the beacons go quiet once roots match. This is the
  bandwidth-aware "are we in sync?" use of the Merkle root, within the gossip (broadcast) transport.
- **Lifecycle bound to membership:** subscribes to `Cluster.listenMembership()`. A member going
  `DEAD` auto-purges its entries (membership *is* the tombstone). Explicit live deregistration emits a
  versioned tombstone with TTL + GC.
- **Consistency router (L2):** dispatches reads/writes by each key's `ConsistencyTier` — local LWW for
  `EVENTUAL`/`CAUSAL`, read-repair for `QUORUM`, consensus for `CONSENSUS`.

## Consistency contract (tell your users)
- **Can promise:** complete view, convergence, monotonic-per-key reads, read-your-own-writes for your
  own services, always-available local reads.
- **Cannot promise:** linearizability, cross-key atomicity, "exactly one instance," instant global
  visibility.
- **Rule of thumb:** a lookup is a *hint* — try the instance, fall back if it's gone.

## Important
- This module is **reactive** (it consumes scalecube's `Flux` streams). It must *not* call into the
  deterministic consensus loop directly — go through `prism-consensus`'s boundary adapter.
- Single-writer-per-key is a **hard invariant**: reject writes to services this node doesn't own.
- Scale envelope: full replication suits modest state (discovery/config/tags). If the catalog ever
  grows to a real data store, that's a different architecture (partition + replication factor).

## Depends on
`prism-api`, `prism-versioning`, `prism-codec`, `scalecube-cluster`, `slf4j-api`
