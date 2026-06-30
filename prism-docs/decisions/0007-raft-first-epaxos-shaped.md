# 0007 — Consensus engine: lease-first, Raft-log-next, EPaxos-shaped interface

Status: **Accepted** (rationale expanded to research grade; the decision is unchanged)

Related: ADR-0004 (deterministic loop), ADR-0006 (consensus for election), ADR-0012 (quorum lease),
ADR-0015 (self-electing quorum), ADR-0013 (verification).

---

## 1. What the `CONSENSUS` tier must provide

A **linearizable replicated state machine**: commands submitted at any member take effect in a single
total order, surviving a minority of crashes. Two demands sit on top of it:

- **Today — mutual exclusion (the elector):** "at most one leader per group," which needs only a
  *single-decree* agreement per leadership term (a lease + fencing epoch, ADR-0012).
- **Tomorrow — replicated state (the `CONSENSUS` registry tier):** a *multi-decree* log for a small
  set of strongly-consistent keys (locks, singleton ownership, authoritative config).

The engine choice must serve the cheap case now without foreclosing the general case, and must do so
behind one stable interface.

---

## 2. System model and the weakest failure detector

Asynchronous network (loss, reorder, partition), crash-recovery with stable storage, non-Byzantine
(see ADR-0015 §2). By **FLP (1985)** consensus is impossible in a pure asynchronous model with even
one crash; every practical algorithm therefore assumes a **failure detector** and/or partial
synchrony.

**Chandra–Toueg–Hadzilacos (1996):** `◇W` (eventually-weak) is the **weakest failure detector** that
solves consensus, given a majority of correct processes. Every leader-based engine embeds *some*
detector:

- Raft/Multi-Paxos/VR/ZAB use **randomized election timeouts** — an ad-hoc, noisy `◇S`.
- prism feeds the engine **SWIM + Lifeguard**, a purpose-built `◇S`-class detector: eventually
  accurate, with Lifeguard suppressing the false positives that cause needless leader churn.

So "SWIM-fed, not blind timeouts" is not a convenience — it is supplying a *better realization of the
exact theoretical object consensus requires*, yielding fewer spurious elections and more stable
leadership.

---

## 3. Design space (rigorous comparison)

| Engine | Leadership | Commit latency (stable) | Quorum | Reconfiguration | Note |
|--------|------------|-------------------------|--------|-----------------|------|
| Single-decree Paxos | proposer (no stable leader) | 2 RTT (prepare + accept) | majority (phases intersect) | — | agrees one value |
| Multi-Paxos | stable leader | **1 RTT** (phase-2 only) | majority | aux master / joint | leader is throughput bottleneck |
| **Raft** | strong leader | **1 RTT** to majority | majority | **single-server** / joint | most implementable; Aeron Cluster's choice |
| Viewstamped Replication | primary + view changes | 1 RTT | majority | view change | ≈ Multi-Paxos (Oki–Liskov ’88; ’12) |
| ZAB (ZooKeeper) | leader, primary order | 1 RTT | majority | dynamic (≥3.5) | FIFO client order |
| **EPaxos** | **leaderless** | **1 RTT fast** (non-interfering) / 2 RTT slow | fast-quorum ≈ ⌈3N/4⌉; majority slow | per-instance | commutativity-aware, WAN-friendly (Moraru ’13) |
| Flexible Paxos | leader | 1 RTT | only `Q1 ∩ Q2 ≠ ∅` | — | tunable (smaller phase-2) quorums (Howard ’16) |
| Mencius / WPaxos | rotating / multi-leader | 1 RTT | majority / per-zone | — | geo / multi-leader |
| **prism lease (today)** | lease-holder | **1 RTT** (accept to majority) | majority | single-member (ADR-0015) | single-decree mutual exclusion + fencing |

**Equivalences.** Raft is Multi-Paxos repackaged for understandability (Ongaro & Ousterhout state this
explicitly); VR and ZAB are the same leader-based-log family. They differ in pedagogy and
reconfiguration ergonomics, not in power or fault tolerance.

**Our lease is single-phase.** Mutual exclusion does not need Paxos's prepare phase: the acceptor rule
uses the *epoch as ballot* and rejects an equal-epoch proposal from a different owner, so concurrent
same-epoch contenders simply fail and retry (no double-grant). One round trip to a majority
(ADR-0012). The general multi-decree case will need the full log.

---

## 4. Latency and quorum analysis

- **Stable-leader engines** (Multi-Paxos/Raft/VR/ZAB) commit in **one round trip** to a majority once
  a leader is established; the cost is funneling all commands through that leader (throughput ceiling,
  and an extra WAN hop for clients far from it).
- **EPaxos** removes the leader: a command commits at the **nearest** fast-quorum in **one** round
  trip *iff* it does not interfere with concurrent commands; interfering commands take a second round
  to order. No single bottleneck; commit latency tracks the closest quorum — a real WAN/throughput
  win.
- **Quorum intersection** (Lamport; Howard et al.): safety needs any two decision quorums to
  intersect. Classic engines use majorities (always intersect). **Flexible Paxos** shows only the
  phase-1 and phase-2 quorums must intersect, permitting smaller (faster) steady-state quorums — an
  optimization we keep in reserve for tuning, not correctness.

---

## 5. Decision

1. **Engine, staged.**
   - *Now:* a **single-decree majority-quorum lease** (ADR-0012) — the minimal consensus the elector
     needs, single-phase, 1 RTT, crash-durable (ADR-0013).
   - *Next:* a **multi-decree Raft log** for the strongly-consistent `CONSENSUS` registry tier. Raft
     because it is the most implementable, debuggable, well-specified member of the leader-based-log
     family, and is the choice Aeron Cluster validated.
2. **Interface: EPaxos-shaped.** The public contract is `propose(command) → committed/executed`
   (linearizable), deliberately *not* leader-centric, so a **leaderless EPaxos** engine can replace
   the Raft log without changing callers.
3. **Deterministic execution loop** (ADR-0004) — single-threaded, no reactive scheduler hops, so the
   state machine is testable and replayable.
4. **SWIM/Lifeguard-fed failure detection** — the engineered `◇S` of §2, not randomized timeouts.
5. **Configured quorum first; self-electing later** (ADR-0015) — reconfiguration is always ordered
   *through the log*, never improvised from the eventually-consistent gossip view.

---

## 6. Why EPaxos is the target (commutativity ↔ single-writer)

prism's data model is **single-writer-per-key** (ADR-0003): operations on different keys *commute*.
EPaxos's entire advantage is committing **non-interfering (commutative) commands in one round without
imposing an order**. So a registry/elector workload — overwhelmingly independent per-key operations —
hits EPaxos's fast path almost always, *and* stays **leaderless**, which is congruent with the
decentralized gossip substrate (no elected bottleneck, commit at the nearest quorum). Raft funnels the
same workload through one leader needlessly. EPaxos is thus not a stylistic preference; it is the
consensus design whose performance model matches our commutativity structure. We adopt the
EPaxos-shaped interface now so the upgrade is a drop-in.

---

## 7. Failure-detector argument, concretely

A leader-based engine re-elects whenever its detector suspects the leader. With randomized timeouts,
transient slowness (GC pause, brief congestion) triggers spurious elections — each a liveness hiccup
and, on flaky networks, a source of churn. SWIM gives a faster, cluster-wide-corroborated death
signal; **Lifeguard** scales suspicion by local health so a struggling observer doesn't wrongly
condemn healthy peers. Feeding this into the engine reduces false elections precisely where naive
timeouts fail — and it is the *same* `◇S` object the theory says we need (§2). For the elector, this
also keeps leadership **sticky** (fewer needless failovers), which is a product requirement, not just
an optimization.

---

## 8. Reconfiguration

Membership changes are **log entries**, committed by the current quorum, applied in log order — never
read from the gossip view at runtime. The safety of single-member changes (overlapping majorities) and
the self-electing policy are specified and proven in **ADR-0015** and `SelfElectingQuorum.tla`.

---

## 9. Alternatives considered

- **Multi-Paxos directly** — equivalent power, but notoriously underspecified in practice ("Paxos Made
  Live" documents the gap); Raft's explicit leader/log/membership rules lower implementation risk.
- **Viewstamped Replication / ZAB** — same family; no advantage over Raft for an embeddable library,
  and less familiar tooling.
- **EPaxos as the *first* engine** — its performance model is ideal (commutativity), but it is harder
  to implement and verify; we encode it as the *target behind the interface*, not the starting point.
- **Mencius / WPaxos (multi-leader, geo)** — relevant only at multi-region scale we are not targeting
  yet; revisitable behind the same interface.
- **Use an external consensus service (etcd/ZooKeeper/Ratis)** — rejected for the embeddable-library
  goal (ADR-0001): a heavy dependency and a separate operational surface. The seam (`ConsensusStore`)
  could nonetheless be implemented over Ratis if a team wanted to.

---

## 10. CAP / FLP positioning

The tier is **CP** (minority unavailable under partition). FLP forbids guaranteed liveness in pure
asynchrony; we recover liveness under partial synchrony with a surviving majority and a `◇S` detector
(§2). The engine choice does not move the CAP point — it only changes *how* the single agreed order is
produced (one leader vs. leaderless dependency graphs).

---

## 11. Threats / open problems

- The single-decree lease and the multi-decree log share an interface but not an implementation;
  introducing the log must re-use the *same* acceptor-rule/quorum invariants (ADR-0012/0015) to avoid
  a second, divergent safety argument.
- EPaxos's dependency-tracking and execution protocol have their own subtle liveness/correctness
  pitfalls (livelock under heavy conflicts, execution-order edge cases); adopting it requires its own
  TLA+ model and DST pass before replacing Raft.
- Flexible-Paxos quorum tuning interacts with reconfiguration (changing quorum sizes mid-flight);
  out of scope until both the log and dynamic membership are proven.

---

## 12. Consequences

- **+** An implementable, debuggable path (lease → Raft log) with a clean, leader-agnostic interface
  and a principled (`◇S`) failure detector.
- **+** The leaderless, commutativity-exploiting endgame (EPaxos) is reachable without API churn.
- **−** Two implementations will exist transiently (lease now, log later); they must be unified under
  the shared invariants.
- **−** Realizing the EPaxos target is significant future work, gated by its own verification.

---

## References

1. Lamport. *The Part-Time Parliament* (1998); *Paxos Made Simple* (2001).
2. Chandra, Hadzilacos, Toueg. *The Weakest Failure Detector for Solving Consensus.* JACM, 1996;
   Chandra & Toueg. *Unreliable Failure Detectors for Reliable Distributed Systems.* JACM, 1996.
3. Fischer, Lynch, Paterson. *Impossibility of Distributed Consensus with One Faulty Process.* 1985.
4. Ongaro & Ousterhout. *In Search of an Understandable Consensus Algorithm (Raft).* USENIX ATC, 2014.
5. Chandra, Griesemer, Redstone. *Paxos Made Live.* PODC, 2007.
6. Oki & Liskov. *Viewstamped Replication.* PODC, 1988; Liskov & Cowling. *VR Revisited.* 2012.
7. Junqueira, Reed, Serafini. *ZAB.* DSN, 2011.
8. Moraru, Andersen, Kaminsky. *There Is More Consensus in Egalitarian Parliaments (EPaxos).* SOSP,
   2013.
9. Howard, Malkhi, Spiegelman. *Flexible Paxos.* OPODIS, 2016.
10. Mao, Junqueira, Marzullo. *Mencius.* OSDI, 2008; Ailijiang et al. *WPaxos.* 2017.
