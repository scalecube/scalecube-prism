# Troubleshooting

Symptom → diagnosis → fix. (Mirrors the dpdk repo's `troubleshooting.md` style.)

## Quick table
| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| `ServiceUnavailable` / "no route" on first calls | discovery lag (stale-negative) | retry with backoff; await discovery on startup |
| A dead service still appears in lookups | failure-detection + dissemination window (stale-positive) | consumers must fail over; tune membership FD |
| No leader for a few seconds after a node dies | ungraceful-death failover gap (≤ `leaseTtl`) | expected (CP); prefer graceful `resign`/shutdown |
| Leadership keeps flapping | `leaseTtl`/`tickInterval` too tight, or an unstable node | raise `leaseTtl`; ensure `tickInterval ≤ TTL/3`; Lifeguard (Phase 0) |
| Elector stuck, no leader at all | **majority of quorum lost** | restore a quorum member; CP = unavailable until then |
| Minority partition can't elect | by design (safety over availability) | restore connectivity to the majority side |
| Registry diverges between nodes | missed gossip not yet reconciled | wait one anti-entropy beacon; check `prism.registry.ae.*` |
| Versions "go backward" after restart | durability off, or unstable `memberId` | set `persistenceDir` **and** a stable `memberId` |
| `elector()` throws `UnsupportedOperationException` | constructed without `PrismConfig` | use `new PrismImpl(cluster, prismConfig)` |
| Consensus RPC never returns | `consensusAddress` ≠ advertised address | make them equal; don't use an `addressMapper` that rewrites it |

## Detailed

### "Service not available" / `ServiceUnavailable`
The router found no instance for the requested service in its **local** view. Causes:
1. **Stale-negative:** a provider registered but the caller hasn't received its gossip+membership yet
   (converges in a round or two). → **retry with backoff**, or await discovery before first call.
2. **Mid-failover / stale-positive:** provider just died; until `DEAD` propagates, the route is gone.
   → consumers must retry/fail over.
3. **Metadata-fetch fragility (scalecube L0):** a member is added only after its metadata pull
   succeeds; a timed-out pull hides the provider until the next sync. → check `metadataTimeout`,
   network/GC. (prism's own registry uses versioned deltas, not the single blob — Phase-0 fixes this.)
4. **Namespace/seed mismatch:** caller and provider not in the same view. → verify config.
**Rule:** discovery is a *hint*; treat `ServiceUnavailable` as retryable, not fatal.

### No leader / failover gap
On ungraceful death there is a bounded **zero-leader window** (≤ `leaseTtl`) while the standby waits
for the lease to expire — this is the price of "never two leaders." Minimize it with graceful
shutdown (`resign`/`LEAVING`, near-instant handoff) and a smaller `leaseTtl` your renewals can sustain.

### Majority loss (the important one)
If more than ⌊|quorum|/2⌋ members are down at once, **no majority exists to commit anything** —
including the healing reconfiguration. The quorum is **safely unavailable** until a member returns.
This is CAP, not a bug. The only override is an operator-acknowledged `forceReconfigure` (unsafe;
potential lost leadership). → restore a quorum member; size the quorum (5 tolerates 2) for resilience.

### Leadership flapping
Each spurious `DEAD` can trigger a failover. Causes: `leaseTtl` too short relative to renewal RTT/GC
pauses; an overloaded node; noisy network. → raise `leaseTtl`, ensure `tickInterval ≤ TTL/3`, enable
Lifeguard (Phase 0) to suppress false suspicions. Watch `prism.elector.{granted,revoked}` for churn.

### Versions regress / updates ignored after restart
Without durability, a restarted node with a **stable id** resumes at version 0 while the cluster holds
higher versions → its updates are LWW-rejected. → set `persistenceDir` (durable HLC) **and** a stable
`memberId`. With an ephemeral id, a restart is a new member and this doesn't arise.

### Verifying safety locally
Run the simulator and integration tests: `mvn -q -pl prism-sim -am test` (fuzzer + fault injection)
and `mvn -q -pl prism-elector -am test` (real-transport partition). A failing fuzz seed is printed —
save it; it reproduces deterministically.
