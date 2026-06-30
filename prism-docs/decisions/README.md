# Architecture Decision Records (ADRs)

Each ADR records one binding decision: its context, the choice, and the consequences. ADRs are
numbered, append-only, and immutable once `Accepted`. To reverse one, add a new ADR that supersedes
it — never edit history.

| # | Decision | Status |
|---|----------|--------|
| [0001](0001-keep-cluster-as-is-layer-dont-fork.md) | Keep scalecube-cluster as-is; layer, don't fork | Accepted |
| [0002](0002-per-key-tunable-consistency.md) | Per-key tunable consistency over one substrate (Prism) | Accepted |
| [0003](0003-single-writer-lww-versioning.md) | Single-writer-per-key + monotonic HLC version + LWW | Accepted |
| [0004](0004-reactive-vs-deterministic-boundary.md) | Reactive plumbing vs. deterministic consensus — hard boundary | Accepted |
| [0005](0005-membership-as-tombstone.md) | Membership lifecycle drives registry entry removal | Accepted |
| [0006](0006-consensus-not-gossip-for-election.md) | Safe singleton election needs consensus + fencing, not gossip | Accepted |
| [0007](0007-raft-first-epaxos-shaped.md) | Raft first, EPaxos-shaped interface; configured quorum before self-election | Accepted |
| [0008](0008-transport-tcp-stays-no-aeron-core.md) | TCP stays; no UDP/Aeron in the core until measured | Accepted |
| [0009](0009-schema-codec-no-jdk-serialization.md) | Schema'd wire codec; no JDK serialization | Accepted |
| [0010](0010-sim-before-consensus.md) | Build the deterministic simulator before consensus/elector | Accepted |
| [0011](0011-public-api-shape.md) | Public API shape: decorator entry point, snapshot-then-stream watch, default CAUSAL tier | Accepted |
| [0012](0012-distributed-quorum-lease-elector.md) | Distributed singleton elector via a configured-quorum majority lease on a dedicated transport | Accepted |
| [0013](0013-formal-verification-and-dst.md) | Formal verification (TLA+) + deterministic simulation testing for the safety kernel | Accepted |
| [0014](0014-observability-metrics-spi.md) | Pluggable metrics SPI (NOOP default, in-memory impl, Micrometer/OTel adapters) | Accepted |
| [0015](0015-self-electing-quorum.md) | Self-electing / dynamic quorum via single-member reconfiguration through consensus | Proposed |
| [0016](0016-leader-affinity.md) | Leader affinity: preference-biased, sticky, no-failback election (AZ-pinned gateway) | Accepted (impl) |

## Template
```
# NNNN — Title
Status: Proposed | Accepted | Superseded by NNNN
## Context
## Decision
## Consequences
```
