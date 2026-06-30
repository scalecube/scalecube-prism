# 0015 — Self-electing / self-healing quorum

Status: **Accepted (implemented)** — shipped opt-in via `PrismConfig.withDynamicQuorum(target)`; the
static quorum remains the default. Verified by TLA+ (`AtMostOneLeader` + `NoTokenRegression`), DST,
and elector-driven E2E. Implementing it surfaced a real safety gap (below) that the model both found
and proved the fix for.

Companion spec: [`prism-docs/spec/SelfElectingQuorum.tla`](../spec/SelfElectingQuorum.tla).
Explainer: [`prism-docs/self-electing-quorum.md`](../self-electing-quorum.md).

> **Update (shipped).** Three things changed from the original design during implementation:
> 1. **§7.1 must carry the high-water to a *majority of the new config*, not just joiners.** Extending
>    the model with `NoTokenRegression` proved joiner-only transfer lets a single-member *shrink*
>    resurrect a stale lower-epoch lease (a fencing regression `AtMostOneLeader` does not catch). The
>    leader now re-establishes its lease on a majority of the new config before retiring the old one.
> 2. **The committed config chain is durable** (`config.journal`) — a restart resumes it, not C0.
> 3. **The roster is derived from cluster-gossip metadata** (each node advertises its consensus
>    address), so the quorum forms/heals from the live cluster. The configured members are the seed
>    C0 and a fallback. Open item: journal compaction (the journals grow unbounded).

---

## 1. Problem and goals

The consensus quorum is presently a hand-listed static set (ADR-0007, ADR-0012). We want it to:

- **G1 — form itself** from what the cluster already knows (no separate roster to maintain);
- **G2 — size itself** to a target `T` (odd; default 3), capped by cluster size, so a 10-node cluster
  runs a 3- or 5-node quorum, not a 10-node one;
- **G3 — heal itself** by replacing a permanently-failed quorum member with a healthy one;
- **G4 — never violate safety** — at most one leader per election group at any instant, across
  formation, growth, shrink, healing, and partition.

G4 dominates. Reconfiguration is the classic source of consensus safety violations (documented Raft
joint-consensus and single-server-change subtleties; Jepsen has found membership-change split-brains
in multiple production systems). The whole point of this document is to make G1–G3 *without*
sacrificing G4, and to prove it.

---

## 2. System model and assumptions

- **Asynchronous network** with message loss, reordering, duplication, and **partitions**. No bound on
  message delay (we assume *partial synchrony* only for liveness, §9).
- **Crash-recovery** processes with **stable storage** (the write-ahead lease journal and durable HLC,
  ADR-0013 / persistence): a recovered acceptor never forgets an accepted lease.
- **No Byzantine faults.** Members follow the protocol.
- **Loosely-synchronised clocks** with bounded drift used *only* for leases; safety never depends on
  clock accuracy — it depends on quorum intersection and the fencing epoch. Clock error widens only
  the window a stale leader can *believe* it leads, which fencing makes harmless.
- **Failure detector:** SWIM + Lifeguard, an eventually-strong (◇S-class, Chandra–Toueg) detector —
  it may produce false positives transiently but is eventually accurate; it informs but never decides
  reconfiguration.

---

## 3. Definitions

- **Roster `R`** — the configured candidate set; by default the seed list (§5).
- **Configuration `C ⊆ R`** — a committed quorum membership. `Majority(C) = ⌊|C|/2⌋ + 1`.
- **Lease** — `(owner, epoch, expiry)` stored at an acceptor; an acceptor holds at most one lease per
  group. A lease is *valid* at time `t` iff `t < expiry`.
- **Fencing epoch** — a monotonic ballot; every action a leader performs downstream carries it, and
  downstream rejects any epoch below the highest seen (Kleppmann fencing).
- **Certified under `C`**, `Cert(o, C)` — a majority of `C` currently hold a *valid* lease for `o`.
- **Leader** — an owner `o` such that `Cert(o, C)` for the current committed config `C` (or, during a
  transition, the immediately-previous committed config `C⁻`; in-flight leases span one reconfig).
- **Acceptor rule** (the safety kernel, identical to `Acceptor.handle`): accept `(o, e)` iff the cell
  is free, **or** it is a same-owner renewal with `e ≥ current.epoch`, **or** the current lease is
  expired **and** `e > current.epoch`. (A different owner may never preempt a *valid* lease, and a
  takeover must strictly increase the epoch.)

---

## 4. Design overview

Three invariant-preserving rules; everything else is policy.

1. **Bootstrap from the seed roster** (§5): the initial config `C0` is a deterministic subset of `R`,
   committed only once a majority of `R` is reachable.
2. **Membership changes are committed through consensus, one member at a time** (§7): the gossip view
   *suggests* changes; it never mutates the active config. Single-member changes guarantee adjacent
   configs have overlapping majorities (Lemma 1), which is what preserves G4 (Theorem 1).
3. **The current leader is the sole reconfigurator** (§6): only the lease-holder proposes changes,
   eliminating dueling proposers; SWIM (Lifeguard-stabilised) supplies the signal, a dwell timer and
   hysteresis damp churn.

Seeds carry **no runtime authority** — their only power is to seed `C0`. After bootstrap the quorum is
the committed config, evolving solely through consensus.

---

## 5. Bootstrap: the seed roster as `C0`

The seed list is the one configuration already present and intended-identical across nodes, so it is
the natural roster `R`. `C0 = the size-min(T, |R|) deterministic subset of R` (lowest stable-id hash).

**Why bootstrap must require a majority of `R`.** A naive "first node declares itself the quorum"
(`C0 = {self}`) is unsafe:

> **Proposition (lone-bootstrap split-brain).** If two disjoint subsets `A, B ⊆ R` may each
> independently commit a configuration with themselves as a majority, then two leaders with
> incomparable epochs can coexist, and merging the partitions cannot reconcile them.
>
> *Proof.* A lone bootstrapper `a` forms `C0 = {a}`, `Majority = 1`, elects itself at epoch 1.
> Symmetrically `b` forms `{b}`, epoch 1. The two never shared an acceptor, so neither epoch dominates
> the other; both are valid leaders. ∎

The fix is to require a **majority of the full roster `R`** to commit `C0`. Two majorities of the same
set intersect (Lemma 1 with `C = C' = R`), so two disjoint bootstrappers are impossible. The lone-node
case (`T = 1`) is therefore explicitly a **non-HA development mode**, not a partition-safe
configuration.

---

## 6. Sizing and self-heal policy

- **Target `T`** is configurable, **odd** (1, 3, 5, 7). Even sizes are forbidden: `|C| = 2` has
  `Majority = 2` and tolerates **zero** failures — strictly worse than `|C| = 1` — so the active size
  steps **1 → 3 → 5** as the cluster crosses thresholds and never settles on an even value (it may
  pass through one transiently mid-reconfiguration).
- **Active size** = `min(T, |healthy members|)`, adjusted with **hysteresis** (grow at `n ≥ T`, shrink
  only well below) to avoid oscillation around a threshold.
- **Self-heal** is leader-driven: when a quorum member is `DEAD` for longer than a **dwell** `τ`
  (Lifeguard-stabilised to suppress false positives), the leader commits *remove-dead* then
  *add-replacement* — each a single-member change (§7). **Candidate selection is deterministic**
  (lowest stable-id hash among healthy, longest-lived non-members) so proposers agree on *who*; the
  change is still *committed*, never applied from the gossip guess.
- **Rate limiting:** at most one reconfiguration per `τ`, bounding reconfig churn.

---

## 7. Reconfiguration protocol (single-member)

```
on leader L holding a valid lease (epoch e) for the consensus group:
  observe SWIM membership (Lifeguard)
  if a member m is DEAD for > τ  and  Majority(C) still reachable:
      propose-and-commit  Cnext = C \ {m}        # remove (single-member)
      pick replacement r deterministically
      propose-and-commit  Cnext = C ∪ {r}        # add    (single-member)
  if cluster grew and |C| < min(T, n):
      propose-and-commit one add toward target
  if cluster shrank past hysteresis low-water:
      propose-and-commit one remove toward target
```

Each `propose-and-commit` is an ordinary consensus log entry committed by `Majority(C)` (the *current*
config). The new config takes effect only once committed, so configurations form a **totally ordered,
single-step chain** `C0 → C1 → C2 → …`, each adjacent pair differing by one member.

### 7.1 Fencing high-water state transfer (mandatory)

A committing reconfiguration MUST also transfer the **fencing-epoch high-water** to the members of
the new config before they count toward a quorum:

```
on commit of Cnext:
  hw := highest-epoch lease record known to Majority(C)        # always visible: adjacent majorities overlap
  for each member m ∈ Cnext that is behind hw.epoch:
      m.adopt(hw)                                              # raise m's epoch floor (does not grant a live lease)
```

**Why it is required (a DST finding, see §13.2/§14).** Single-member reconfiguration guarantees *mutual
exclusion* (Theorem 1) on its own: adjacent configs' majorities overlap, so two owners can never both
be certified. It does **not**, by itself, guarantee *global fencing-token monotonicity*. If the nodes
that remember a high epoch are removed from the config over successive steps, a new leader can win a
majority of the new config on members that never saw that epoch — and so be granted a *lower* fencing
token than a previous leader. Mutual exclusion still holds, but a fencing token that can go backwards
is not a safe fence for external resources. Transferring the high-water on every config change keeps
the epoch floor inside the active config, restoring strict monotonicity. The deterministic simulator
reproduces both the regression (without the transfer) and its repair (with it).

---

## 8. Safety

> **Lemma 1 (single-member quorum intersection).** If `|C △ C'| ≤ 1`, then every `Q ⊆ C` with
> `|Q| ≥ Majority(C)` and every `Q' ⊆ C'` with `|Q'| ≥ Majority(C')` satisfy `Q ∩ Q' ≠ ∅`.
>
> *Proof.* WLOG `C' = C ∪ {x}` (add) or `C' = C \ {x}` (remove); both give `Q, Q' ⊆ C ∪ C'` with
> `|C ∪ C'| = |C| + [x added]`. Let `n = |C|`. Then
> `|Q| + |Q'| ≥ (⌊n/2⌋+1) + (⌊(n±1)/2⌋+1) ≥ n + 2 > |C ∪ C'|`.
> By inclusion–exclusion `|Q ∩ Q'| ≥ |Q| + |Q'| − |C ∪ C'| ≥ 1`. ∎

> **Theorem 1 (mutual exclusion across reconfiguration).** Under the acceptor rule with single-member
> reconfiguration, no two distinct owners are simultaneously certified under adjacent configurations.
>
> *Proof.* Suppose at instant `t`, `Cert(o1, C1)` via majority `Q1` and `Cert(o2, C2)` via majority
> `Q2`, with `o1 ≠ o2` and `|C1 △ C2| ≤ 1`. By Lemma 1 there is `n ∈ Q1 ∩ Q2`. Then `n` holds a valid
> lease for `o1` (∈ Q1) and for `o2` (∈ Q2) at `t`. But an acceptor holds at most one lease per group
> — contradiction. ∎

Because reconfigurations form a single-step chain (§7) committed by the current majority, the only
configs whose leaders' leases can overlap in time are **adjacent** ones: a leader certified under `Ci`
loses certification once the config advances two steps (its backers fall below `Majority(Ci+2)`),
*and* its lease must have been renewed only through `Ci`'s majority, which overlaps `Ci+1`'s — so the
next leader is forced strictly higher in epoch (Theorem 2). The companion TLA+ model checks exactly
the adjacent `(C, C⁻)` abstraction and confirms `AtMostOneLeader`; relaxing to multi-member jumps
(`ReconfigDelta > 1`) makes TLC produce a split-brain counterexample — demonstrating the single-member
rule is *necessary*, not stylistic.

> **Theorem 2 (fencing monotonicity across reconfiguration).** If `o2` becomes certified under `C2`
> while `o1` (≠ o2) was certified under adjacent `C1`, then `epoch(o2) > epoch(o1)`.
>
> *Proof.* By Lemma 1 some `n ∈ Q1 ∩ Q2` accepted `o1` at `epoch(o1)`. For `n` later to accept `o2`
> (different owner), the acceptor rule requires the lease to be expired **and** `epoch(o2) >
> n.epoch ≥ epoch(o1)`. ∎

Theorem 2 is why a stale leader from an old configuration is harmless: anything it does downstream
carries the smaller epoch and is fenced.

---

## 9. Liveness

By **FLP** (1985) no asynchronous consensus is simultaneously always-safe and always-live under
arbitrary failures. We choose safety. Liveness is conditional:

- **Progress condition.** A command (including a reconfiguration) commits iff a `Majority(C)` of the
  current config is up and mutually reachable within the partial-synchrony window. This is the
  ◇S/Chandra–Toueg setting; SWIM+Lifeguard approximates the detector.
- **Self-heal preserves liveness long-term:** while a majority survives, dead members are replaced, so
  the quorum does not erode toward unavailability.
- **Majority loss ⇒ safely unavailable.** If more than `⌊|C|/2⌋` members fail simultaneously, *no*
  majority exists to commit even the healing reconfiguration. The system **stops** (no leader, no
  progress) until members return. This is not a defect — committing a config change with less than a
  majority is precisely the unsafe override that causes split-brain. A separate, **operator-
  acknowledged `forceReconfigure`** is the only escape, and it is unsafe by construction (potential
  data/leadership loss) and must be explicit.
- **Anti-flapping bound:** with dwell `τ` and ≤1 reconfiguration per `τ`, the reconfiguration rate is
  bounded regardless of suspicion noise; Lifeguard further reduces false positives.

---

## 10. Failure-mode analysis

| Scenario | Outcome | Why safe |
|----------|---------|----------|
| 1 quorum member crashes (`T=3`) | auto-heal: remove+add via the surviving majority (2/3) | majority commits the change |
| 2 of 3 crash simultaneously | **stuck, unavailable** until one returns | no majority to commit anything |
| Quorum partitioned (minority side) | minority cannot elect or reconfigure | minority < majority (Lemma 1) |
| Stale leader from old config still believes it leads | its actions fenced | Theorem 2 (epoch strictly increases) |
| Two nodes try to bootstrap in a partition | impossible to both commit `C0` | majority-of-`R` rule (§5) |
| Dueling reconfigurators | only the lease-holder proposes | leader-driven (§6) |
| Flapping member (suspect↔alive) | no reconfiguration until dwell `τ` elapses | dwell + hysteresis + Lifeguard |
| Multi-member config jump (bug) | rejected by design; TLC catches it | single-member rule + Theorem 1 |

---

## 11. Alternatives considered

- **Derive the active quorum directly from the gossip view** — *rejected.* Membership is eventually
  consistent; two nodes disagreeing on the set can form disjoint quorums → split-brain. Violates G4.
- **Joint consensus `Cold ∪ Cnew`** (Raft §6 / Lamport–Malkhi–Zhou Vertical Paxos) — *viable,* more
  general (multi-member jumps in one step), but more state and more model-checking surface.
  Single-member changes give the same safety for our needs with far less complexity; we keep joint
  consensus in reserve for batch reconfiguration if ever required.
- **External coordinator (ZooKeeper/etcd) for membership** — *rejected* for an embeddable library: it
  reintroduces the dependency we set out to avoid (ADR-0001) and a separate operational surface.
- **Static quorum only (status quo)** — the supported default; this design ships strictly opt-in on
  top of it.
- **Flexible Paxos quorums** (Howard et al., 2016) — relax the intersection requirement to phase-1/2;
  orthogonal optimisation, not needed for correctness here, noted for future quorum-size tuning.

---

## 12. CAP / FLP positioning

The consensus tier is **CP**: under partition it sacrifices availability on the minority side to keep
"never two leaders." The registry tier remains **AP** (ADR-0002). Self-electing membership does not
move the CAP point — it only automates *which* nodes constitute the CP core, never relaxing the
majority requirement. Liveness is the FLP-mandated casualty, recovered under partial synchrony with a
surviving majority (§9).

---

## 13. Verification plan (must pass before implementing)

1. **Model checking.** ✅ **DONE (2026-06-28, tla2tools 1.7.4).** `SelfElectingQuorum.tla`:
   `SelfElectingQuorum.cfg` (`ReconfigDelta=1`) ⇒ `AtMostOneLeader` & `TypeOK` hold (86,298,140 states,
   4,865,740 distinct, depth 21, no error);
   `SelfElectingQuorum_unsafe.cfg` (`ReconfigDelta=5`) ⇒ TLC returns a split-brain counterexample at
   depth 4 (two owners each majority-backed under disjoint configs). Full trace and the one modeling
   fix it forced (`NONE` as a model-value constant rather than an unbounded `CHOOSE`) are recorded in
   `prism-docs/spec/README.md`. *Still open (§14): extend to `EpochAgreement` and a `prevConfig`-chain
   of depth > 1 to probe non-adjacent overlap.*
2. **Deterministic simulation testing.** ✅ **DONE (2026-06-28).** `ReconfigSimCluster` +
   `ReconfigurationSafetyFuzzTest` add a reconfiguration/auto-heal fault mode to `prism-sim`: seeded
   interleavings of single-member growth/shrink, partition, 20 % link loss and clock jumps over the
   **real** `Acceptor`/`QuorumConsensusStore`/`LeaseElector` kernel.
   - **Never two leaders:** holds across 300 seeds × 200 steps (config-aware oracle: certified under
     current *or* previous config).
   - **Self-formation** from a single-node bootstrap and **self-heal** after the leader is removed:
     stay single-leader.
   - **Finding:** the first DST run exposed a *fencing-epoch regression* under churn (mutual exclusion
     intact, but the token went backwards when a config change evicted the high-epoch carriers). This
     drove the **high-water state-transfer** rule now in §7.1; with it, fencing-epoch monotonicity
     holds across all seeds. A negative-control test reconstructs the multi-member split-brain at the
     raw acceptors, confirming the oracle detects violations (it is not vacuously green).
3. **Only then** expose `PrismConfig.dynamicQuorum = true`; the static quorum stays the default.
   Implementation must include §7.1 high-water transfer and persist the epoch floor (durable HLC).
   ✅ **DONE (2026-06-28).** Shipped opt-in behind `PrismConfig.withDynamicQuorum(target)`:
   `QuorumConfig` (single-member commit + policy), `ReconfigurationManager` (leader-driven, §7.1
   transfer), config replication over the transport (`ConfigRecord`/`ConfigCodec`/
   `TransportConfigReplicator`, `QuorumNode.attachDynamic`), and the `PrismImpl` control-group loop
   with a consensus-transport liveness probe. Verified by unit tests, a real-transport integration
   test (config dissemination + §7.1 transfer), a PrismImpl smoke test, and the §13.2 DST. The static
   quorum remains the default and supported production path. *Remaining hardening: durable epoch-floor
   persistence across the dynamic path, and a gossip-pool-derived roster (today the roster is the
   configured candidate list).*

---

## 14. Threats to validity / open problems

- The TLA+ abstraction tracks one previous config (`C⁻`); the full chain argument (§8) relies on lease
  expiry plus sequential single-step commits. A bounded multi-`prevConfig` history check, or a refined
  spec that models the consensus log of config entries explicitly, would strengthen the proof.
- **Fencing-token monotonicity across reconfiguration is *not* free** from the single-member rule
  alone — DST (§13.2) showed a multi-step config sequence can evict the high-epoch carriers and let a
  new leader regress the token (mutual exclusion still holds). It is recovered by the §7.1 high-water
  state transfer, which the simulator validates. The TLA+ model does **not** yet encode §7.1 or an
  `EpochAgreement`-across-reconfig invariant; adding both (and checking the token never regresses)
  would lift this from "validated by simulation" to "model-checked." Tracked for the next spec pass.
- Lease safety is modulo clock drift; the formal model abstracts time as an `Expire` action. A
  real-time/timed-automata model (or explicit max-clock-drift parameter à la Spanner TrueTime) would
  quantify the fencing window.
- Deterministic candidate selection must itself converge under churn; pathological membership
  oscillation could starve healing despite the dwell timer — needs DST coverage.
- `forceReconfigure` is a correctness escape hatch; its blast radius (possible lost leadership/acks)
  must be documented and guarded operationally.

---

## 15. Decision & consequences

Adopt seed-bootstrapped, consensus-evolved, **single-member** self-electing membership with an
**odd target size**, **leader-driven self-heal while a majority survives**, and **safe-unavailability
on majority loss** — gated behind the static-quorum default until §13 passes.

- **+** Automatic formation and healing (G1–G3) with a proven safety floor (G4).
- **+** Reuses the seed list as the roster — no new config concept.
- **−** The most intricate, safety-critical subsystem in the project; ships last and opt-in.
- **−** Majority loss is unavailable by design; operators must understand this is the price of never
  two leaders.

---

## References

1. Lamport. *The Part-Time Parliament.* ACM TOCS, 1998. / *Paxos Made Simple.* 2001.
2. Ongaro & Ousterhout. *In Search of an Understandable Consensus Algorithm (Raft).* USENIX ATC, 2014;
   Ongaro, *Consensus: Bridging Theory and Practice* (PhD thesis), 2014 — single-server membership.
3. Lamport, Malkhi, Zhou. *Vertical Paxos and Primary-Backup Replication.* PODC, 2009.
4. Howard, Malkhi, Spiegelman. *Flexible Paxos: Quorum Intersection Revisited.* OPODIS, 2016.
5. Fischer, Lynch, Paterson. *Impossibility of Distributed Consensus with One Faulty Process.* JACM,
   1985.
6. Chandra & Toueg. *Unreliable Failure Detectors for Reliable Distributed Systems.* JACM, 1996.
7. Das, Gupta, Motwani. *SWIM.* DSN, 2002. / Dadgar, Phillips, Currey. *Lifeguard.* 2017.
8. Kulkarni et al. *Logical Physical Clocks (HLC).* OPODIS, 2014.
9. Kleppmann. *How to do distributed locking* (fencing tokens). 2016.
10. Kingsbury. *Jepsen* analyses — membership-change safety failures in production systems.
