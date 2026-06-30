# prism-observability

**Layer:** cross-cutting · **Status:** metrics SPI + in-memory impl implemented; instrumentation wired

## What it does
Surfaces metrics and structured decision logs for the whole stack, so prism is operable in
production — not just correct in tests.

## Goal
Turn the protocol's internal behavior into signals an operator can see and alert on. A
gossip/consensus system that can't be observed can't be trusted at scale.

## How
- Pluggable metrics adapters (Micrometer / OpenTelemetry) — no hard dependency forced on consumers.
- Structured per-member decision logs ("why was X marked dead at 14:32", "why did leadership move").

## Key signals
- **Membership/FD:** suspicion rate, ping-req fallback rate, false-positive rate, and **actual vs.
  theoretical convergence time** (scalecube's `ClusterMath` gives the theoretical baseline).
- **Registry:** dissemination latency, anti-entropy volume, tombstone counts, queue depths / drops.
- **Elector:** election events, leadership churn, lease renewals, and **fencing-token rejections**
  (a non-zero rate means zombie leaders are being correctly fenced — watch it).

## Important
- Bound any internal buffers and **expose the drop rate** — silent unbounded backpressure is an OOM
  waiting to happen.
- Keep this passive/observational; it must never alter protocol behavior.

## Depends on
`prism-api`, `slf4j-api`
