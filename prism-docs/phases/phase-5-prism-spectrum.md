# Phase 5 — Full Prism spectrum (stretch / research)

**Depends on:** Phase 2 (registry/router), Phase 3 (consensus).
**Goal:** complete the per-key consistency dial — `QUORUM` reads and a self-forming `CONSENSUS`
tier — so the whole spectrum is available with no separate cluster to operate.

## Scope
**In:** quorum tier, full per-key tier routing, self-electing quorum, EPaxos engine.
**Out / gated:** ship nothing here until Phases 2–4 are proven; the self-electing quorum is the
riskiest item and goes last.

## Work items

### 5.1 — `QUORUM` tier (read-repair) · M
- On a quorum read, query *k* replicas for their version of the key, take the highest, and
  **read-repair** stragglers. Tunable *R*. Leverages full replication — no central tier.
- **Tests (sim):** a quorum read returns the latest acknowledged write even when some replicas lag.

### 5.2 — Full per-key tier routing · S
- Wire `ConsistencyTier` end to end so a single key can be `EVENTUAL`/`CAUSAL`/`QUORUM`/`CONSENSUS`;
  pin the tier at key creation; make the tag unforgeable in the codec.

### 5.3 — Self-electing consensus quorum · L (research-grade)
- Derive the consensus group from the gossip membership (e.g. *k* longest-lived/healthiest by hashed
  id) instead of a static config; reconfigure as the pool churns.
- **Hard problem:** membership is *eventually* consistent, so nodes can transiently disagree on who's
  in the quorum → split-brain risk. Safeguards: change the group only via committed reconfiguration;
  require overlap (single-member steps) between old/new; hysteresis to avoid churn-driven flapping.
- **Tests (sim):** under churn and partition, the quorum never splits into two committing groups.

**Status — design + formal verification ✅ (the §13 gate); implementation ⬜.**
- ✅ ADR-0015 (research-grade design: system model, mutual-exclusion theorem, fencing, bootstrap,
  sizing, self-heal, §7.1 high-water transfer).
- ✅ TLA+ `SelfElectingQuorum.tla` model-checked: safe config holds (86M states); unsafe multi-member
  config yields the split-brain counterexample.
- ✅ DST `ReconfigSimCluster` + `ReconfigurationSafetyFuzzTest`: never-two-leaders + monotone fencing
  across reconfiguration (300 seeds), self-formation, self-heal, oracle-has-teeth negative control.
  DST surfaced and validated the §7.1 high-water state-transfer requirement.
- ⬜ Implementation: `PrismConfig.dynamicQuorum`, seed-bootstrap, single-member reconfiguration over
  consensus, odd-sizing, self-heal, durable epoch floor (must include §7.1).

### 5.4 — EPaxos engine · L
- Implement a leaderless, commutativity-aware engine behind the Phase 3 EPaxos-shaped interface.
  Non-conflicting (single-writer ⇒ usually non-conflicting) commands commit in one round trip,
  keeping the strong tier decentralized — aligned with the gossip ethos (ADR-0007).

## Definition of done
- A single key can move across the full tier spectrum and behaves correctly at each level (sim-proven).
- The self-electing quorum survives churn/partition with **no split** over many seeds.
- EPaxos passes the same linearizability suite as Raft behind the shared interface.

## Risks
- **Self-electing quorum** is the project's deepest risk → keep the configured seed-quorum (Phase 3)
  as the supported default; treat self-election as opt-in/experimental until sim coverage is
  exhaustive.
- **EPaxos complexity** → only pursue once Raft is solid and a concrete need for leaderless exists.
