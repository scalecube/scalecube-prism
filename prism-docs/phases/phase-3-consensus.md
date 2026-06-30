# Phase 3 — Consensus engine (`prism-consensus`)

**Depends on:** **Phase 1** (mandatory — ADR-0010), `scalecube-cluster`, `prism-codec`.
**Goal:** an embedded, deterministic, linearizable replicated log over a stable subset of members —
the safety substrate for the `CONSENSUS` tier and the elector.

## Scope
**In:** Raft on a deterministic loop, transport bridge, SWIM-fed FD, configured seed-quorum,
replicated-state-machine API. **Out:** self-electing/dynamic quorum and EPaxos (Phase 5).

## Work items

### 3.1 — Deterministic event loop · M
- Single-threaded command/event loop: inbound queue, timers as logical events, no reactive operators
  inside (ADR-0004). All inputs (messages, ticks, proposals) are ordered through one queue.
- **Tests:** identical input sequence ⇒ identical state (determinism harness).

### 3.2 — Raft core · L
- Leader election (terms, votes), log replication, commit index, apply to state machine.
- **Durable state:** `currentTerm`, `votedFor`, and the log — persisted (via `prism-persistence`).
- Snapshotting to bound log growth and replay time.
- **Tests (sim):** leader uniqueness per term; log convergence; minority partition makes no progress;
  majority continues; recovery after restart.

### 3.3 — Transport bridge · M
- Adapter: scalecube `send`/`requestResponse`/`listen` ↔ the deterministic loop (decode inbound to
  loop events; encode outbound). Reactive lives **only** in this adapter.
- Codec: Raft message schemas in `prism-codec`.

### 3.4 — SWIM-fed failure detection · S
- Consume `MembershipEvent` to inform election/step-down (a member SWIM-reports DEAD → don't wait for
  a full election timeout). Lifeguard keeps this accurate.

### 3.5 — Configured seed-quorum · M
- Static membership config (3 or 5 members) embedded in the same library.
- Reconfiguration via Raft joint consensus, ordered through the log (not from the gossip view).
- **Tests (sim):** add/remove a member without losing linearizability.

### 3.6 — Replicated state machine API · S
- `propose(command) → committed`; linearizable read (leader lease or read-index); `stale` read option.
- EPaxos-shaped interface so the engine can be swapped in Phase 5.

## Cross-cutting added here
- **Persistence:** the Raft log + term/vote (distinct from the registry slice).
- **Observability:** term changes, election count, commit latency, replication lag.

## Definition of done (verified in `prism-sim`)
- **Linearizability** of the committed log under partitions and message loss.
- **At most one leader per term**, always.
- **No progress in a minority**; clean catch-up on heal.
- **Restart** recovers committed state from persisted log + snapshot.
- **Reconfiguration** preserves safety.

## Risks
- **Highest-risk module.** Mitigate: sim-first; borrow proven Raft semantics exactly; small,
  property-tested increments; snapshot early to keep replay bounded.
- **Determinism leaks** from the reactive boundary → keep the adapter thin and audited.
