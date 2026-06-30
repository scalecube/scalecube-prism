# prism-elector

**Layer:** L4 · **Phase:** 4 · **Status:** implemented & verified end-to-end — **the headline deliverable**

## Implemented now
- `LeaseElector` implements `SingletonElector`: lease + monotonic fencing epoch + renewal + failover +
  resign. Deterministic core (`tick()`), with `start(Duration)` for periodic renewal.
- Backed by `prism-consensus`: with `InMemoryConsensusStore` for single-JVM use/tests, or the
  partition-safe `QuorumConsensusStore` + `QuorumNode` for real cross-node election.
- Verified: `LeaseElectorTest` (never-two, takeover, fencing, resign) and the scalecube-style
  `QuorumElectionIntegrationTest` (one Active over a 3-member quorum; partition the leader → standby
  takes over; **never two Active**).

## Wired into the facade
`new PrismImpl(cluster, PrismConfig)` stands up the dedicated consensus transport + quorum and exposes
`prism.elector()`. Verified across three real nodes by `PrismGatewayExample`. Direct construction
(`QuorumNode` + `LeaseElector`) is still available (see `QuorumElectionIntegrationTest`).

## What it does
Safe singleton election. For each group, **at most one member is `Active` at any time — never two** —
in a deterministic, sticky way: leadership switches only when the current holder genuinely loses its
lease. Implements `prism-api`'s `SingletonElector`.

## Goal
The concrete objective the whole project was built toward: "for group A (or B), exactly one node is
Active, safely, and it doesn't flap to another node unless the holder is really gone."

## How
Built on `prism-consensus` (the `CONSENSUS` tier), combining four mechanisms:
1. **Consensus-granted lease + monotonic epoch** — the quorum holds `{group, leader, epoch,
   leaseExpiry}`. At most one winner per epoch → **never two Actives**. (Safety.)
2. **Lease renewal** — the Active member renews via heartbeat; it stays leader as long as it can.
   (Stickiness — "don't switch unless it has to".)
3. **Fencing tokens** — every action while Active carries its `epoch`; downstream rejects lower
   epochs. A partitioned zombie leader's stale actions are harmless. (Safe to be wrong.)
4. **SWIM-driven handoff** — graceful `LEAVING` releases the lease instantly; ungraceful `DEAD` falls
   back to lease expiry. Lifeguard keeps detection accurate so failover isn't spurious.

## Why "really dead" is reframed
"Really dead" is undecidable in an async network (dead vs. partitioned is indistinguishable). So the
guarantee is: *switch when the lease expires and the quorum agrees, and use fencing so being wrong is
harmless.* You don't need certainty of death — you need wrong failovers to be safe.

## Do NOT
- Implement election over gossip alone. A deterministic "lowest-id-alive" rule splits into two leaders
  under partition/false-positive. Safe election **requires** consensus + fencing — that's why this
  module sits on `prism-consensus`, not directly on gossip.

## Important
- Reuse the proven recipe (Curator `LeaderLatch` / etcd / ZooKeeper election semantics) rigorously —
  the bugs (clock skew, fencing gaps, reconfiguration races) are subtle.
- Lifeguard (Phase 0 in `scalecube-cluster`) should land before this is trusted in production.

## Depends on
`prism-api`, `prism-consensus`, `slf4j-api`
