# Phase 4 — Safe singleton elector (`prism-elector`) — the goal

**Depends on:** Phase 3 (consensus), Phase 1 (sim). Lifeguard (Phase 0) before prod.
**Goal:** for each group, **at most one Active member, ever**, switching only on real loss of the
lease — deterministic and sticky, with split-brain made impossible by consensus + fencing (ADR-0006).

## Scope
**In:** lease/epoch/fencing on consensus, campaign/resign/leadership API, SWIM-driven handoff.
**Out:** anything attempting election over gossip alone (forbidden).

## Work items

### 4.1 — Lease state machine on consensus · M
- Per-group replicated record `{group, leader, epoch, leaseExpiry}` committed via `prism-consensus`.
- Campaign wins **only if** no valid (unexpired) lease exists; winner gets `epoch + 1`.
- **At most one winner per epoch ⇒ never two Actives.**

### 4.2 — Campaign / resign API · S
- Implement `SingletonElector#campaign/resign`; surface grants/revocations on `leadership(group)`
  (`Flux<Leadership>` carrying `epoch` + `active`); `currentLeader(group)`.

### 4.3 — Lease renewal & expiry · M
- Active member renews via periodic heartbeat (a committed/lightweight lease extension). Failure to
  renew → lease expires → another node may campaign. Tune renewal vs. expiry for stickiness.

### 4.4 — Fencing tokens · S
- Expose the monotonic `epoch` with every grant; provide a `Fenced<T>` helper and the contract that
  downstream resources reject actions bearing an epoch lower than the highest seen. Makes an
  early/mistaken failover harmless.

### 4.5 — SWIM-driven fast handoff · S
- Graceful `LEAVING` → proactively release the lease (instant, safe handoff).
- Ungraceful `DEAD`/lease-expiry → the slower fallback path. Lifeguard keeps detection accurate so
  failover isn't spurious.

## Cross-cutting added here
- **Observability:** election events, leadership churn, lease renewals, and **fencing rejections**
  (non-zero = zombie leaders correctly fenced — alert on sustained rate).

## Definition of done (verified in `prism-sim`, critical)
- **Never two Actives** across partitions, false-positive suspicions, and chaos (`SingleLeaderChecker`
  holds for the entire run over many seeds).
- **Stickiness:** leadership does not move while the holder keeps renewing.
- **Fencing:** a partitioned old leader's stale-epoch actions are rejected.
- **Handoff timing:** graceful leave hands off near-instantly; ungraceful death within the lease bound.

## Risks
- **Subtle correctness bugs** (clock skew in leases, fencing gaps, reconfiguration races) → reuse
  Curator/etcd/ZooKeeper election semantics rigorously; exhaustive sim coverage; never relax to a
  gossip shortcut.
