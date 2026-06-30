# prism-versioning

**Layer:** foundational · **Phase:** 2 · **Status:** planned (package declared)

## What it does
Provides the version primitives that make eventually-consistent data converge *safely*: a
restart-safe, causality-respecting stamp for every key, plus freshness tokens for reads.

## Goal
Replace scalecube's incarnation `int` — which conflates liveness-refutation with data-versioning and
resets on restart — with a proper version that:
1. is **monotonic** per key,
2. **survives restart** (resume, don't reset),
3. respects **causality** without requiring synchronized clocks, and
4. gives a **total order** so last-writer-wins is sound.

## How
- **Hybrid Logical Clock (HLC)** — a logical counter fused with physical wall-clock time. Bounds
  divergence from real time while guaranteeing monotonicity and a causal-friendly total order.
  Implements `version.Version`.
- **Interval Tree Clocks (ITC)** — for the `CAUSAL` tier, a causal-context representation built for
  *dynamic* membership (fork/join), unlike fixed version vectors. Used where session guarantees are
  required.
- **Freshness tokens** — constructed here; bind a read to "all updates up to version V from owner O."

## Why LWW is enough (no CRDTs/consensus needed for the common case)
Each key has a **single writer** (its owning member), so there are never concurrent writes to the
same key. A single-writer register reaches strong eventual consistency with nothing more than a
monotonic version + max-wins on readers. That's the whole reason this module is small.

## Important
- HLC needs the physical component persisted/forwarded carefully; pair with `prism-persistence` for
  restart correctness under a **stable `memberId`**.
- Keep the wire form of versions in `prism-codec`, not here.

## Depends on
`prism-api`
