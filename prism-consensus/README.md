# prism-consensus

**Layer:** L3 · **Phase:** 3 · **Status:** quorum lease implemented & partition-tested (full Raft engine still future)

## Implemented now
A **single-decree, majority-quorum lease** — the minimal consensus the singleton elector needs
(ADR-0012), not yet a full Raft command log:
- `Acceptor` — the safety kernel (accept iff absent/expired/same-owner; reject equal-epoch from
  another owner).
- `QuorumConsensusStore` — proposer: a write needs a **majority** of acceptances; a quorum read
  returns the highest-epoch majority-backed lease. Minority partitions lose availability, never
  safety.
- `LeaseRecord` / `ConsensusStore` (seam) · `InMemoryConsensusStore` (single-JVM/tests).
- `QuorumNode` + `TransportPeerCaller` — binds an acceptor to a scalecube `Transport` (a **dedicated
  consensus transport per node**) and answers peer requests; `PeerCaller` is the RPC seam.
- Verified by `QuorumConsensusStoreTest` (partition/minority/duel) and, end-to-end with the elector,
  `QuorumElectionIntegrationTest` over `NetworkEmulatorTransport`.

## Still future (the original Phase-3 scope)
A full Raft replicated **log** (for richer `CONSENSUS`-tier state beyond a single lease), snapshotting,
and dynamic reconfiguration — gated by the Phase-1 simulator (ADR-0010).

## What it does
A small, embedded consensus engine that provides a linearizable replicated log over a stable subset
of cluster members. It backs the `CONSENSUS` tier and the singleton elector.

## Goal
Supply the **safety** that gossip fundamentally cannot: agreement. SWIM is an *unreliable* failure
detector (it can't distinguish dead from slow/partitioned), so anything requiring "exactly one" or a
single linearizable answer needs consensus, not gossip.

## How
- **Raft** to start — understandable, proven, and the same choice Aeron Cluster made. Implemented on a
  **single-threaded, allocation-disciplined loop** — deliberately **not** reactive — so the state
  machine is deterministic (and testable/replayable).
- **EPaxos-shaped interface**: the public surface is designed so a *leaderless* engine (EPaxos) can be
  dropped in later — the variant that keeps the strong tier decentralized, matching gossip's ethos.
- **SWIM-fed failure detection:** scalecube's Lifeguard-stabilized membership feeds leader-election /
  reconfiguration — faster and more accurate than Raft's blind timeouts. Reactive cluster events are
  adapted to the deterministic loop **only at the module boundary**.
- **Membership:** start with a *configured* small seed-quorum (Consul-servers style) embedded in the
  same library. A self-electing quorum from the gossip pool is a later, riskier phase.

## Critical design rules
- **Never** let reactive operators (`publishOn`, scheduler hops) into the loop — they break the
  deterministic ordering that correctness depends on. Bridge at the edge only.
- Reconfiguration must be ordered through the log (Raft joint consensus), not improvised from the
  gossip view.

## Important
- This is the heaviest, most safety-critical module. Do **not** build it before `prism-sim` (Phase 1)
  exists — you can't verify safety without reproducible partition/chaos simulation.
- Could later graduate to its own repo (a generic embedded Raft is broadly useful).

## Depends on
`prism-api`, `prism-codec`, `scalecube-cluster`, `slf4j-api`
