# 0001 — Keep scalecube-cluster as-is; layer, don't fork

Status: **Accepted** (rationale expanded to research grade; decision unchanged)

## Context
prism needs far more than membership/gossip (registry, tiered consistency, consensus, elector), but
`scalecube-cluster` is a faithful, focused SWIM + gossip implementation. The question is *where* the
new capability lives: forked into the core, or layered above it.

## The principle: mechanism/policy separation and information hiding
This is a textbook **modular decomposition** problem (Parnas, 1972): decompose by *what is likely to
change independently*, hiding each decision behind a stable interface. Membership/failure-detection
(L0) changes for different reasons and at a different rate than registry semantics or consensus
(L1–L4). They are distinct **secrets**, so they belong in distinct modules with a one-way dependency.

It is also the classic **mechanism vs. policy** split: SWIM/gossip is *mechanism* (disseminate, detect
liveness); registry/consensus/elector are *policy* built on that mechanism. Keeping policy out of the
mechanism is exactly why memberlist (mechanism) → Serf (gossip events) → Consul (catalog/consensus)
is layered, not monolithic — the precedent we follow deliberately.

## Decision
Treat `scalecube-cluster` as the stable **L0 dependency**. Build everything new in a separate repo
(`scalecube-prism`) on its **public** APIs (`listenMembership`, `spreadGossip`/`listenGossips`,
`send`/`requestResponse`/`listen`, `NetworkEmulator`). Only **small, surgical correctness fixes**
(Phase 0) go into the core, and ideally upstream. Dependencies flow strictly downward to L0; nothing
points back up.

## Why not fork
A fork creates a **maintenance divergence cost** that grows monotonically: every upstream
security/bug fix must be re-merged, and the fork's invariants drift from upstream's. Depending on a
*released artifact* (with at most a thin, upstreamable patch) preserves the upgrade path and keeps the
blast radius of our changes contained. This is the dependency-inversion discipline: depend on a
published contract, not a vendored copy you now own forever.

## Consequences
- A clean, upgradable dependency; no fork burden.
- A hard architectural rule (downward dependencies) that the module graph enforces.
- Phase-0 hardening (Lifeguard, batching) is contributed upstream rather than vendored.
- Risk: we are bound by L0's public surface; where it is insufficient (e.g. no point-to-point RPC on
  the `Cluster` facade) we add our own transport (ADR-0012) rather than reach into internals.

## References
1. Parnas. *On the Criteria To Be Used in Decomposing Systems into Modules.* CACM, 1972.
2. Parnas, Clements, Weiss. *The Modular Structure of Complex Systems.* IEEE TSE, 1985.
3. HashiCorp memberlist → Serf → Consul layering (design docs).
