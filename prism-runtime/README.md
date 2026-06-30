# prism-runtime

**Status:** implemented · the `Prism` entry point

## What it does
Provides `PrismImpl`, the concrete `Prism` (from `prism-api`): a decorator over a scalecube
`Cluster` that wires the service registry and — when configured — the singleton elector.

## Goal
One small type a user learns. Build a normal `ClusterImpl`, wrap it, and get a `ServiceRegistry` and
optionally a `SingletonElector`.

## How
- `new PrismImpl(cluster)` — registry only. Installs the registry's handler at build time and starts
  the cluster (scalecube delivers gossip/membership through a build-time handler, so prism takes an
  *unstarted* `ClusterImpl`).
- `new PrismImpl(cluster, PrismConfig)` — also enables `elector()`: binds a **dedicated consensus
  transport** on the configured address, joins the configured static quorum, and runs a
  partition-safe `LeaseElector` (ADR-0012).
- `PrismConfig` declares this node's consensus address (must equal the transport's advertised
  address), the quorum members, the transport factory, and lease/tick/timeout tunables. Call
  `withPersistenceDir(dir)` to enable **durability**: the registry HLC uses a `FileClockJournal`
  (versions never regress on restart) and the quorum acceptor a `FileLeaseJournal` (crash-safe
  promises). Use with a stable `memberId`.
- `shutdown()` stops the elector, consensus transport, and cluster (prism owns what it started).

## Important
- The consensus transport is separate from the cluster's gossip transport (its own port).
- The quorum is a **configured static set** of real `host:port` addresses (no address rewriting).
- See `PrismGatewayExample` (in `prism-examples`) for the full one-line-API gateway across 3 nodes.

## Depends on
`prism-api`, `prism-registry`, `prism-elector`, `scalecube-cluster`, `scalecube-transport-api`
