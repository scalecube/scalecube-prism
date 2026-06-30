# Module ownership & bus-factor map

## Why this exists

A system nobody understands is a liability, no matter how well it runs today. This map names a
human **owner** for every module — the person whose job is to *understand* it, explain it, and
review changes to it. Ownership is about comprehension, not authorship: the owner is not
necessarily who wrote the code, but whoever currently carries its model in their head.

The bar this enforces: **nothing merges that no human understands.** Every PR into a module must
have an owner (or a second conversant human) who can explain *why* it is correct. That pressure is
the point — owning a module forces you to learn it, and a learned module is no longer a black box.

How to use this map:
- **Find the owner** of the code you're touching; get their review and have them explain the
  invariants before you change them.
- **Becoming an owner?** Walk the study column, then the learning path in
  [`../ONBOARDING.md`](../ONBOARDING.md). Ownership is claimed by demonstrating understanding, not
  assigned by tenure.
- Owner cells are `_TBD_` — the team fills them in. Treat an empty owner as a risk to close, not a
  blank to ignore.

## The ownership table

Each module's `What it does` is its `pom.xml` `<description>` (or README) summary. `Key files` is
the single class a new reader should open *first*. `Study` lists the explainer(s) and ADR(s) that
explain the module's design.

| Module | What it does (one line) | Owner | Key files / entry points | Study: explainer + ADR(s) |
|--------|-------------------------|-------|--------------------------|---------------------------|
| **prism-api** | Public surface: `Prism`, `ServiceRegistry`, `SingletonElector`, tiers, versions. | _TBD_ | [`Prism.java`](../prism-api/src/main/java/io/scalecube/prism/Prism.java), [`ServiceRegistry.java`](../prism-api/src/main/java/io/scalecube/prism/registry/ServiceRegistry.java) | [`architecture.md`](architecture.md) · [0011](decisions/0011-public-api-shape.md) |
| **prism-versioning** | HLC / interval-tree clocks and freshness tokens. | _TBD_ | [`HybridLogicalClock.java`](../prism-versioning/src/main/java/io/scalecube/prism/versioning/HybridLogicalClock.java) | [`crdt-hlc.md`](crdt-hlc.md) · [0003](decisions/0003-single-writer-lww-versioning.md) |
| **prism-codec** | Schema'd wire codec (msgpack/protobuf); no JDK serialization. | _TBD_ | [`WireReader.java`](../prism-codec/src/main/java/io/scalecube/prism/codec/WireReader.java), [`WireWriter.java`](../prism-codec/src/main/java/io/scalecube/prism/codec/WireWriter.java) | [`codec-security.md`](codec-security.md) · [0009](decisions/0009-schema-codec-no-jdk-serialization.md) |
| **prism-registry** | L1+L2: per-key CRDT map, delta+Merkle anti-entropy, consistency router, watch. | _TBD_ | [`GossipServiceRegistry.java`](../prism-registry/src/main/java/io/scalecube/prism/registry/impl/GossipServiceRegistry.java), [`MerkleTree.java`](../prism-registry/src/main/java/io/scalecube/prism/registry/impl/MerkleTree.java) | [`crdt-hlc.md`](crdt-hlc.md), [`anti-entropy-merkle.md`](anti-entropy-merkle.md), [`tunable-consistency.md`](tunable-consistency.md), [`gossip-swim.md`](gossip-swim.md) · [0002](decisions/0002-per-key-tunable-consistency.md), [0005](decisions/0005-membership-as-tombstone.md) |
| **prism-persistence** | Durable owner-slice + version: WAL, stable-id resume. | _TBD_ | [`FileClockJournal.java`](../prism-persistence/src/main/java/io/scalecube/prism/persistence/FileClockJournal.java), [`FileLeaseJournal.java`](../prism-persistence/src/main/java/io/scalecube/prism/persistence/FileLeaseJournal.java) | [`crdt-hlc.md`](crdt-hlc.md) · [0003](decisions/0003-single-writer-lww-versioning.md) |
| **prism-consensus** | L3: deterministic Raft engine (EPaxos-shaped), SWIM-fed failure detection. | _TBD_ | [`QuorumNode.java`](../prism-consensus/src/main/java/io/scalecube/prism/consensus/QuorumNode.java), [`Acceptor.java`](../prism-consensus/src/main/java/io/scalecube/prism/consensus/Acceptor.java), [`ReconfigurationManager.java`](../prism-consensus/src/main/java/io/scalecube/prism/consensus/ReconfigurationManager.java) | [`paxos.md`](paxos.md), [`self-electing-quorum.md`](self-electing-quorum.md) · [0004](decisions/0004-reactive-vs-deterministic-boundary.md), [0006](decisions/0006-consensus-not-gossip-for-election.md), [0007](decisions/0007-raft-first-epaxos-shaped.md), [0015](decisions/0015-self-electing-quorum.md) |
| **prism-elector** | L4: safe singleton elector (lease + epoch + fencing) on the consensus engine. | _TBD_ | [`LeaseElector.java`](../prism-elector/src/main/java/io/scalecube/prism/elector/impl/LeaseElector.java) | [`paxos.md`](paxos.md), [`self-electing-quorum.md`](self-electing-quorum.md) · [0006](decisions/0006-consensus-not-gossip-for-election.md), [0012](decisions/0012-distributed-quorum-lease-elector.md), [0016](decisions/0016-leader-affinity.md) |
| **prism-sim** | Phase 1: deterministic discrete-event simulator and property/chaos tests. | _TBD_ | [`SimCluster.java`](../prism-sim/src/main/java/io/scalecube/prism/sim/SimCluster.java), [`ReconfigSimCluster.java`](../prism-sim/src/main/java/io/scalecube/prism/sim/ReconfigSimCluster.java) | [`formal-verification-dst.md`](formal-verification-dst.md) · [0010](decisions/0010-sim-before-consensus.md), [0013](decisions/0013-formal-verification-and-dst.md) |
| **prism-observability** | Metrics adapters (Micrometer/OTel) and per-member decision logs. | _TBD_ | [`InMemoryMetrics.java`](../prism-observability/src/main/java/io/scalecube/prism/observability/InMemoryMetrics.java) | [`config-reference.md`](config-reference.md) · [0014](decisions/0014-observability-metrics-spi.md) |
| **prism-runtime** | The Prism entry point: a decorator over a scalecube Cluster wiring registry + elector. | _TBD_ | [`PrismImpl.java`](../prism-runtime/src/main/java/io/scalecube/prism/runtime/PrismImpl.java), [`PrismConfig.java`](../prism-runtime/src/main/java/io/scalecube/prism/runtime/PrismConfig.java) | [`architecture.md`](architecture.md), [`gossip-swim.md`](gossip-swim.md) · [0001](decisions/0001-keep-cluster-as-is-layer-dont-fork.md), [0008](decisions/0008-transport-tcp-stays-no-aeron-core.md), [0011](decisions/0011-public-api-shape.md) |
| **prism-examples** | Runnable registry and elector examples. | _TBD_ | [`HelloPrismExample.java`](../prism-examples/src/main/java/io/scalecube/prism/examples/HelloPrismExample.java) | [`getting-started.md`](getting-started.md), [`user-guide.md`](user-guide.md) |
| **prism-bench** | Throughput/latency harness for the core algorithms (run its main manually). | _TBD_ | [`Benchmarks.java`](../prism-bench/src/main/java/io/scalecube/prism/bench/Benchmarks.java) | [`architecture.md`](architecture.md) · [0008](decisions/0008-transport-tcp-stays-no-aeron-core.md) |
| **prism-docs** | Architecture, ADRs, roadmap, context. Documentation only — no code artifact. | _TBD_ | [`README.md`](README.md) | (this folder) |

## Cross-cutting owners

Some assets span every module. They need owners too, or they rot.

| Concern | Owner | What it covers | Anchor |
|---------|-------|----------------|--------|
| **Formal specs** | _TBD_ | The TLA+ models that prove the safety kernel; spec must track the code. | [`spec/README.md`](spec/README.md), [`spec/LeaseElection.tla`](spec/LeaseElection.tla), [`spec/SelfElectingQuorum.tla`](spec/SelfElectingQuorum.tla) · [0013](decisions/0013-formal-verification-and-dst.md) |
| **CI / build** | _TBD_ | The Maven reactor, release plugin, and build scripts; keeps the tree green. | [`../pom.xml`](../pom.xml), [`../scripts/`](../scripts) |
| **Docs** | _TBD_ | The docs index, explainers, and ADR discipline (append-only, supersede don't edit). | [`README.md`](README.md), [`decisions/README.md`](decisions/README.md) |

## Bus-factor rules

The whole point of this map is that no module dies with one person leaving.

1. **Two-deep on the safety kernel.** `prism-consensus` and `prism-elector` are critical: a minimum
   of **two humans** must be conversant in each at all times. A change to either needs a review from
   someone other than the author who can independently explain the invariant it preserves. The same
   two-deep rule applies to the [`spec/`](spec/) models that prove them.
2. **Rotate and pair.** Rotate the second seat on each critical module periodically, and pair on the
   first non-trivial change so the knowledge transfers by doing, not by reading. Owning a module for
   a quarter and then handing it off is a feature, not a failure.
3. **Onboarding is a tested artifact.** Track **days-to-first-PR** for each new contributor; a rising
   number is a regression in the onboarding path, not in the person. Every point of confusion an
   onboardee hits becomes a documentation fix in the same week — the docs are owned (see above), so
   that fix has a home and a reviewer.
4. **No silent single owners.** A module with exactly one conversant human is a logged risk. Closing
   it (a second human, a doc, a recorded walkthrough) is normal backlog work, not heroics.

## How to take ownership

Ownership is earned by demonstrating you understand a module — not granted by seniority. The route:

1. Walk the learning path in [`../ONBOARDING.md`](../ONBOARDING.md) (repo root) until you can build,
   run, and explain the system end to end.
2. For the module you want, read its `Study` column above: the explainer(s) for *how it works* and
   the ADR(s) for *why it is shaped this way*. Then open the `Key files` entry point and trace it.
3. Make a real change — fix a bug, add a test, sharpen a doc — and have the current owner (or a
   conversant human) confirm in review that you can explain why it's correct.
4. Put your name in the owner cell and announce it. You are now the human who understands this
   module, and the person the next learner studies with.
