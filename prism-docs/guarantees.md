# Guarantees

The authoritative statement of what scalecube-prism guarantees, under what assumptions, and what
evidence backs each claim. This is the contract; the ADRs argue *why*, the specs/tests *prove*.

## System model (assumptions)
- **Asynchronous network** — arbitrary message delay, loss, reordering, duplication, and partitions.
- **Crash-recovery processes with stable storage** — acceptors and the HLC are durable (ADR-0012,
  ADR-0013, `prism-persistence`); a recovered process never forgets an accepted lease or re-issues a
  version.
- **Non-Byzantine** — members follow the protocol.
- **Loosely-synchronised clocks** — used only for lease *liveness*; safety never depends on clock
  accuracy (it depends on quorum intersection + fencing epochs).
- **Failure detector** — SWIM + Lifeguard, an eventually-strong (`◇S`) detector; it informs decisions,
  it never *is* the decision.

---

## Safety guarantees

### Registry — Strong Eventual Consistency (AP)
- **Convergence (SEC):** replicas that have delivered the same set of updates hold identical live
  state, regardless of delivery order or duplication. *Why:* per-source LWW-map CRDT over a
  join-semilattice with `max`-by-version merge (ADR-0003). *Evidence:* proof sketch in ADR-0003 +
  `RegistryConvergenceTest` (200 seeds, reordering + duplicates).
- **Monotonic-per-key reads:** an observer never sees a key's version go backward (LWW `>` guard).
- **No delete resurrection:** a tombstoned key is not revived by anti-entropy (versioned tombstone /
  membership-as-tombstone, ADR-0005). *Evidence:* `RegistryStoreTest`.

### Elector — mutual exclusion under partition (CP)
- **At most one leader, ever** per group. *Why:* a leader needs a majority of the quorum; any two
  majorities intersect; an acceptor holds one lease — so two owners cannot both be certified
  (Lemma + Theorem 1, ADR-0012; extended across reconfiguration in ADR-0015). *Evidence:*
  `LeaseElection.tla` (`AtMostOneLeader`, model-checked), `QuorumConsensusStoreTest`,
  `QuorumElectionIntegrationTest` (real transport + partition), and the seeded fuzzer
  `ElectorSafetyFuzzTest` / `FaultInjectionTest` / `SkewedClockSafetyFuzzTest` (god-view oracle;
  the last with per-acceptor clock skew, since safety is clock-independent).
- **Fencing monotonicity:** a later leader's epoch strictly exceeds any concurrently-valid earlier
  leader's, so a stale (zombie) leader is rejected downstream (Theorem 2, ADR-0012). *Evidence:* spec
  + fuzzer monotonicity assertion.
- **Crash-safe:** an acceptor that crashes and recovers preserves its promises (write-ahead journal,
  ADR-0013). *Evidence:* `FileLeaseJournalTest`.
- **Affinity preserves it:** leader affinity (preference / yield window / promote / demote, ADR-0016)
  only changes *which candidate calls acquire and when* — it never weakens mutual exclusion or fencing,
  and never preempts a healthy leader (no automatic failback). *Evidence:* `LeaseElectorAffinityTest`.

### Versioning — no regression across restart
- A node with a stable id resumes versions **above** any it issued before a crash, so LWW keeps
  accepting it (durable HLC high-water, ADR-0003). *Evidence:* `FileClockJournalTest` (verified even
  with the wall clock stepped backward).

### Security — no deserialization-RCE surface
- prism wire messages are schema-decoded (`byte[]` + `WireReader`), never `ObjectInputStream`, so a
  malicious payload cannot drive a gadget chain (ADR-0009). *Evidence:* codec round-trip tests; DTOs
  are plain classes.

---

## Consistency contract per tier (session guarantees)
For a single client against its local replica (Terry et al. session-guarantee terms):

| Tier | Read-your-writes | Monotonic reads | Monotonic writes | Writes-follow-reads | Linearizable |
|------|:----------------:|:---------------:|:----------------:|:-------------------:|:------------:|
| `EVENTUAL`  | own keys | ✓ | ✓ | — | ✗ |
| `CAUSAL`    | own keys | ✓ | ✓ | ✓ (HLC causality) | ✗ |
| `QUORUM`    | ✓ (read-repair) | ✓ | ✓ | ✓ | ✗ (fresh, not linearizable) |
| `CONSENSUS` | ✓ | ✓ | ✓ | ✓ | ✓ |

Default tier is `CAUSAL` (ADR-0011). `QUORUM` and the full `CONSENSUS` log are roadmap (ADR-0007).

---

## Liveness guarantees and limits
- **FLP:** no asynchronous consensus is both always-safe and always-live; prism chooses **safety**.
  Liveness holds under partial synchrony with a surviving majority and the `◇S` detector (ADR-0006).
- **Registry:** always locally available; converges within the gossip dissemination + anti-entropy
  window.
- **Elector progress** needs a **majority of the quorum** reachable. Self-heal replaces dead members
  while a majority survives (ADR-0015).
- **Bounded failover gap:** on ungraceful leader death there is a brief **zero-leader** window
  (≤ lease TTL); graceful `resign`/`LEAVING` makes handoff near-instant.
- **Majority loss ⇒ safely unavailable** (CP): the quorum stops rather than risk two leaders; recovery
  needs a returning member or an explicit, unsafe operator `forceReconfigure` (ADR-0015).

---

## What prism does NOT guarantee
- **Not a linearizable registry.** Registry reads (below `CONSENSUS`) are AP — a lookup is a *hint*;
  consumers must retry / fail over (stale-positive and stale-negative windows exist).
- **No cross-key or cross-node atomicity / transactions.**
- **No instantaneous global visibility** — updates converge within a bounded window, not instantly.
- **No safety under Byzantine faults or unbounded clock error** (clock error only widens the fenced
  zombie window, which fencing makes harmless — directly exercised by `SkewedClockSafetyFuzzTest`,
  where per-acceptor skew never produces two leaders).

---

## Evidence map
| Guarantee | Spec / test |
|-----------|-------------|
| Registry SEC | ADR-0003 proof · `RegistryConvergenceTest` |
| No tombstone resurrection | `RegistryStoreTest` |
| At-most-one-leader (static) | `LeaseElection.tla` · `QuorumConsensusStoreTest` · `QuorumElectionIntegrationTest` |
| At-most-one-leader (dynamic) | `SelfElectingQuorum.tla` (safe vs unsafe configs) |
| Never-two under faults | `ElectorSafetyFuzzTest`, `FaultInjectionTest` (god-view, 500 seeds total) |
| Never-two under per-acceptor clock skew | `SkewedClockSafetyFuzzTest` (±1 TTL skew, 300 seeds) |
| Renewal I/O off the elector lock; resign-vs-acquire safe | `LeaseElectorConcurrencyTest` |
| Fencing monotonicity | spec + fuzzer assertion |
| Crash-safe acceptor | `FileLeaseJournalTest` |
| Version no-regression | `FileClockJournalTest` |
| Wire security | codec round-trip tests (no `ObjectInputStream`) |
| Performance (sub-linear anti-entropy) | `prism-bench` |

See [`decisions/`](decisions/) for rationale, [`spec/`](spec/) for the TLA+ models, and
[`ops/runbook.md`](ops/runbook.md) for operating within these guarantees.
