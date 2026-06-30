# Operations runbook

Practical guidance for deploying and operating scalecube-prism in production.

## Topology
- **Every node** runs the gossip membership (L0) + the registry (L1/L2). Registry reads are local and
  always available; the catalog converges via gossip + Merkle-root anti-entropy.
- A **configured quorum** of 3 or 5 nodes runs the consensus tier (a dedicated transport per node)
  for the singleton elector. Non-quorum nodes can still campaign by talking to the quorum.

## Sizing the quorum
| Quorum size | Tolerates failures | Notes |
|-------------|--------------------|-------|
| 3 | 1 | minimum for safety; common |
| 5 | 2 | better availability; more messages |
- Use an **odd** size (clean majority). Majority = ⌊N/2⌋ + 1.
- Larger quorums do **not** improve safety, only failure tolerance, at higher latency. Don't exceed 5
  unless you have a specific reason.

## Configuration (`PrismConfig`)
- `consensusAddress` — this node's consensus transport address; **must equal** what the transport
  advertises (real `host:port`, not `localhost` unless that's what it binds).
- `quorumMembers` — the exact consensus addresses of all quorum members (including self).
- `leaseTtl` (default 5s) — how long a leader holds leadership without renewing. Trade-off:
  - shorter ⇒ faster failover, more renewal traffic, more sensitivity to pauses;
  - longer ⇒ slower failover, more tolerance of transient slowness.
- `tickInterval` (default 1s) — renewal/acquisition cadence; keep well below `leaseTtl` (≤ TTL/3).
- `callTimeout` (default 1s) — per-peer consensus RPC timeout.

## Consistency contract (tell consumers)
- The registry is **complete, convergent, monotonic-per-key — not linearizable**. A lookup is a
  **hint**: try the instance, fall back if it's gone. Retry on `ServiceUnavailable`.
- The elector is **safe** (never two Active) at the cost of a brief **zero-Active failover gap** on
  ungraceful death (bounded by `leaseTtl`); graceful `resign`/`LEAVING` makes handoff near-instant.
- **Always fence**: every action a leader takes downstream must carry its epoch; the resource rejects
  stale epochs. This is what makes an early/mistaken failover harmless.

## Monitoring (metrics)
Wire a `Metrics` adapter (Micrometer/OTel). Watch:
- `prism.elector.granted` / `prism.elector.revoked` — leadership churn. Sustained churn ⇒ TTL too
  short, or an unstable node; investigate.
- `prism.registry.ae.beacon` / `prism.registry.ae.readvertise` — anti-entropy activity. Persistent
  re-advertise ⇒ a node is chronically behind (network/GC).
- `prism.registry.event.*` — registration/update/expiry rates.
- Fencing rejections at your downstream resource — a non-zero rate means zombie leaders are being
  correctly fenced (expected during failovers; sustained ⇒ investigate partitions).

## Durability
- Quorum acceptors use a **write-ahead lease journal** (`FileLeaseJournal`) — point it at fast local
  storage. Recovery preserves promises (crash-safe).
- The registry's HLC uses a **durable high-water** (`FileClockJournal`) so versions never regress
  across restart — required if you run with a **stable `memberId`**.

## Failure modes & responses
| Symptom | Likely cause | Response |
|---------|-------------|----------|
| `ServiceUnavailable` on first calls | discovery lag (stale-negative) | retry with backoff; await discovery on startup |
| Service lingers after a node dies | failure-detection + dissemination window | consumers must fail over; tune membership |
| No leader for a few seconds | ungraceful leader death (failover gap) | expected; bounded by `leaseTtl`; prefer graceful shutdown |
| Minority partition can't elect | by design (CP) — safety over availability | restore quorum connectivity |
| Persistent leadership churn | `leaseTtl`/`tickInterval` too tight, or slow node | raise TTL; Lifeguard on the FD (Phase 0) |

## Security
- Prism's wire messages use a schema'd binary codec (no Java serialization) — no deserialization-RCE
  surface. Before exposing to an untrusted network, also enable transport TLS and gossip-message
  authentication (Phase-0 item).

## Verifying a release
- CI runs `mvn verify` (all BDD tests + DST fuzzer + fault injection + real-transport integration)
  and model-checks the TLA+ spec. Treat a fuzzer/spec failure as a release blocker; save the failing
  seed for reproduction.
