# 0014 — Pluggable metrics SPI

Status: **Accepted** (rationale expanded to research grade; decision unchanged)

## Context
A production registry/elector must be observable, but an embeddable library must not *impose* a metrics
backend (Micrometer/OTel) on its consumers (ADR-0001 economy of dependencies).

## Theory: what to measure, and how to decouple it
- **Decoupling — dependency inversion (SPI):** the library depends on a tiny abstraction it owns
  (`Metrics`), and consumers inject an implementation. The default is a `NOOP` (zero overhead unless
  wired) — the same inversion that keeps `prism-codec`/`prism-api` backend-agnostic.
- **What is worth measuring — USE and RED:** Brendan Gregg's **USE** method (Utilization, Saturation,
  Errors) characterizes *resources*; Tom Wilkie's **RED** method (Rate, Errors, Duration)
  characterizes *request-driven services*. For a membership/consensus system the high-signal series
  are the protocol's *rates and state transitions*, not CPU gauges. We emit, by RED/USE lens:
  - **Rate / transitions:** `prism.registry.event.{registered,updated,deregistered,expired}`,
    `prism.elector.{granted,revoked}` (leadership churn), `prism.registry.ae.{beacon,readvertise}`
    (anti-entropy activity).
  - **Errors / safety signals:** fencing-token rejections at the downstream resource (a non-zero rate
    means zombie leaders are being correctly fenced — expected during failovers, alarming if
    sustained).
  - **Saturation (future):** sink/queue depths and drop counters; convergence-time actual vs. the
    `ClusterMath` theoretical baseline.

These are the operational signals the runbook (`prism-docs/ops/runbook.md`) turns into alerts.

## Decision
A minimal `Metrics` SPI in `prism-api` (`increment(name)`, `gauge(name, value)`) defaulting to `NOOP`.
`prism-observability` provides a thread-safe, readable `InMemoryMetrics` (tests/introspection and a
reference adapter); deployments plug a Micrometer/OTel adapter. Components take an optional `Metrics`
(overloaded constructors) and `PrismImpl` threads one through to registry and elector.

## Consequences
- No hard metrics dependency; default is free.
- The signals chosen (RED/USE-aligned) make leadership churn, anti-entropy activity, and
  fencing/change rates visible — the actual operational concerns of this system.
- Logs and traces (the other two observability pillars) are out of scope here; a structured
  per-member decision log ("why was X marked dead / leadership moved") is a noted follow-up.

## References
1. Gregg. *The USE Method.* (utilization/saturation/errors), 2012.
2. Wilkie. *The RED Method* (rate/errors/duration for services), 2018.
3. Majors, Fong-Jones, Miranda. *Observability Engineering* (the three pillars), 2022.
4. OpenTelemetry / Micrometer — the adapter targets.
