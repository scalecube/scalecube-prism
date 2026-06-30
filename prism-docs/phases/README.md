# Detailed implementation plan

`plan.md` (one level up) is the overview. This folder breaks each phase into concrete work items,
deliverables, tests, and a definition of done. Sizes are **relative T-shirt** estimates
(S ≈ a few days, M ≈ 1–2 weeks, L ≈ several weeks), not commitments.

| Phase | Doc | Outcome | Depends on |
|-------|-----|---------|------------|
| 0 | [phase-0-harden-core.md](phase-0-harden-core.md) | Correct, stable L0 (in scalecube-cluster) | — (parallel) |
| 1 | [phase-1-simulator.md](phase-1-simulator.md) | Deterministic, seed-reproducible test harness | scalecube-cluster |
| 2 | [phase-2-registry.md](phase-2-registry.md) | AP versioned service registry | Phase 1 (for tests) |
| 3 | [phase-3-consensus.md](phase-3-consensus.md) | Deterministic Raft engine | **Phase 1** |
| 4 | [phase-4-elector.md](phase-4-elector.md) | Safe singleton elector *(the goal)* | Phase 3 |
| 5 | [phase-5-prism-spectrum.md](phase-5-prism-spectrum.md) | Full per-key consistency dial | Phase 2, 3 |

## Critical path & parallelism
```
Phase 0 ───────────────────────────────────────────────►  (parallel, merges opportunistically)

Phase 1 ──► Phase 2 ─────────────────────────────► Phase 5
        └─► Phase 3 ──► Phase 4 ──────────────────►
```
- **Start now, in parallel:** Phase 1 and Phase 2 (Phase 2 against today's scalecube-cluster release).
- **Hard gate:** Phase 1 (sim) before Phase 3/4 — safety can't be verified without it (ADR-0010).
- **Phase 0** is an independent hardening stream; Lifeguard must land before Phase 4 ships to prod.

## Cross-cutting tracks (woven through all phases)
Each has a checklist inside the phase where it first appears, and continues thereafter:
- **Versioning** (`prism-versioning`) — lands with Phase 2.
- **Codec / security** (`prism-codec`) — lands with Phase 2, hardened before any UDP.
- **Persistence** (`prism-persistence`) — lands with Phase 2 (registry) and Phase 3 (consensus log).
- **Observability** (`prism-observability`) — metrics added per phase as signals appear.
