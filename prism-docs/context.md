# Context & Background

Why this project exists and where its ideas come from. Read this first.

## The problem
We use `scalecube-cluster` as a **service registry**: services advertise themselves with metadata
(weight, status, version, zone), and consumers discover them. We want a **consistent, safe view** of
that registry, plus the ability to elect a **single Active node** per group — deterministically, with
no split-brain.

## What scalecube-cluster gives us (L0)
A solid AP membership library:
- **SWIM** failure detection (round-robin probing, ping-req indirection, suspicion + incarnation
  refutation — faithfully implemented).
- **Gossip** epidemic dissemination (fanout, O(log n) spread, sequence-id dedup).
- **Anti-entropy SYNC** + membership events, all over a reactive (`Mono`/`Flux`) Netty/TCP transport.

What it lacks for our goal: per-key versioned metadata (today it's a single unversioned blob,
pull-fetched), stable failure detection under load (no Lifeguard), durability, and any notion of
strong consistency.

## The core realization
You don't have to choose AP *or* CP globally. Make consistency a **per-key dial over one gossip
substrate** (the "Prism" model): most data stays cheap and eventually-consistent; the few keys that
need it opt into causal, quorum, or linearizable guarantees — without deploying a separate cluster.

## The intellectual lineage (this is not new — and that's good)
What we're building descends from the **Xerox PARC line**, not the flashy file-sharing P2P:
- **Grapevine / Clearinghouse** (PARC, ~1982) + **Demers et al., "Epidemic Algorithms"** (1987) — a
  replicated *directory* maintained by gossip. That's a service registry, 40 years early.
- **Bayou** (PARC, ~1994) — eventually-consistent replication with **session guarantees**
  (read-your-writes, monotonic reads/writes, writes-follow-reads) — the exact contract we expose.
- **Usenet/NNTP** — flood-fill with message-id dedup (≈ scalecube's `SequenceIdCollector`).
- **DNS** — the most successful eventually-consistent registry; TTL-bounded staleness = "a lookup is
  a hint."

What the 90s **got right** and we keep: soft state + TTL, anti-entropy as the safety net,
directory-not-database framing, hints-not-guarantees. What they **lacked** and we add: **SWIM** (2002)
for stable failure detection, and **CRDTs / formal eventual consistency** (2011) for *provable*
convergence. Our single-writer-per-key LWW map *is* a CRDT — the formalization of the old instinct.

The mistake we deliberately avoid: **pure flooding** (Gnutella's broadcast storm). We fanout-gossip
with dedup, which is exactly what killed unstructured P2P at scale.

## How the industry solves "service registry" — and where we sit
| System | Membership | Catalog | Bet |
|--------|-----------|---------|-----|
| **scalecube / Serf / Eureka** | gossip (SWIM) | gossip (eventual) | **AP** — available, eventually consistent |
| **Consul** | gossip (SWIM) | **Raft (CP)** | hybrid — gossip liveness + consistent catalog |
| **Aeron Cluster** | **Raft (CP)** | **Raft (CP)** | **CP all the way** — deterministic, no gossip |

- **Consul** = memberlist (SWIM) → Serf (gossip) → Consul (adds Raft for the catalog). It keeps the
  registry strongly consistent and uses gossip only for liveness. Offers tunable read consistency
  (`consistent` / `default` / `stale`).
- **Aeron Cluster** went *fully* consensus — it even removed a custom dynamic-join mechanism in favor
  of putting membership changes through the Raft log. Reliable multicast + Raft instead of gossip.
- **Prism** starts in the AP row (like Serf/Eureka) and lets *individual keys* climb toward the
  Consul/Aeron guarantees on demand — one substrate, opt-in tiers.

## Why not just use Aeron / Aeron Archive?
Aeron is a superb transport for *low-cardinality, high-throughput, reliable* streams — the opposite
of SWIM's *high-cardinality, low-volume, loss-tolerant* probe traffic, and its media-driver footprint
breaks the "embeddable library" property. Aeron Archive (event-log replay) is the wrong tool for
*membership* (the live cluster is always fresher than a replayed log) but the *right* shape for a
durable, ordered config/metadata log or a CP control tier. So: not for the SWIM core; possibly for a
future CP tier. See ADR-0008.

## Glossary
- **AP / CP** — availability+partition-tolerance vs. consistency+partition-tolerance (CAP).
- **SWIM** — Scalable Weakly-consistent Infection-style Membership (Das/Gupta/Motwani, 2002).
- **Lifeguard** — HashiCorp's SWIM extensions that cut false-positive failure detection.
- **CRDT** — Conflict-free Replicated Data Type; converges without coordination.
- **HLC** — Hybrid Logical Clock; monotonic, physical-time-correlated, causality-respecting.
- **LWW** — Last-Writer-Wins (safe here because each key has a single writer).
- **Fencing token** — a monotonic epoch that makes a stale/zombie leader's actions rejectable.
