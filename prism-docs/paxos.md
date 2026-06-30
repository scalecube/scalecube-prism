# Paxos in prism — what's implemented, and what isn't

prism elects its singleton leader with **single-decree Paxos** (the *Synod* protocol) — the core of
Paxos that drives a quorum to agree on **one** value. This page explains exactly which parts of
Paxos are implemented, how each maps to the code, where prism deliberately specializes the protocol
for leases, and the one textbook detail it simplifies.

> **One-line answer:** all of *single-decree* Paxos (both phases, ballots, promises, quorum
> intersection, the promise guard), specialized to leader election with fenced, expiring leases.
> It is **not** Multi-Paxos: there is no replicated command log, no slots, no state-machine
> replication, and no general "clients write arbitrary values" consensus.

---

## 1. Why Paxos at all?

Leader election is *agreement on a single value* — "who is the leader for this epoch?" — under an
asynchronous network with crashes and partitions. That is precisely the problem single-decree Paxos
solves, and it solves it with the only property that actually matters here: **safety holds in every
execution**. Two majorities always intersect, an acceptor holds a single lease, so two owners can
never both be majority-backed at once — *never two leaders*, even in a split brain. Gossip and
timeouts cannot give you that; consensus can. (See [`decisions/0006-consensus-not-gossip-for-election.md`](decisions/0006-consensus-not-gossip-for-election.md).)

prism runs **one independent single-decree instance per election group**, keyed by the group name.
Group `"gateway-leader"` and group `"compaction-owner"` are entirely separate Paxos instances on the
same cluster — the group is a logical partition, not a separate deployment.

---

## 2. Single-decree Paxos in 60 seconds

Three roles (here every node plays all three):

- **Proposer** wants to get a value chosen. It picks a **ballot** `b` — a unique, totally-ordered
  proposal number — and runs two phases.
- **Acceptor** is the durable voter. It keeps two things: the highest ballot it has **promised**, and
  the value it has **accepted** (with that value's ballot).
- **Learner** discovers which value was chosen.

The two phases:

1. **Phase 1 — PREPARE(b) / PROMISE.** The proposer asks a majority of acceptors to *promise* ballot
   `b`. An acceptor promises iff `b ≥` its highest promised ballot; it then refuses any future ballot
   below `b`, and reports back the highest value it has already accepted.
2. **Phase 2 — ACCEPT(b, v) / ACCEPTED.** Having a majority of promises, the proposer asks them to
   accept value `v` at ballot `b`. An acceptor accepts iff it has not promised a *higher* ballot in
   the meantime. A value accepted by a **majority** is **chosen**.

The whole safety argument rests on **quorum intersection**: any Phase-1 majority and any later
Phase-2 majority share at least one acceptor, which carries the constraint forward.

---

## 3. How it maps to prism's code

The proposer lives in the elector
([`prism-elector/.../LeaseElector.java`](../prism-elector/src/main/java/io/scalecube/prism/elector/impl/LeaseElector.java),
`tryAcquire`); the acceptor and the quorum fan-out live in `prism-consensus`
([`Acceptor.java`](../prism-consensus/src/main/java/io/scalecube/prism/consensus/Acceptor.java),
[`QuorumConsensusStore.java`](../prism-consensus/src/main/java/io/scalecube/prism/consensus/QuorumConsensusStore.java)).

| Paxos concept | prism code |
|---|---|
| **Ballot** (totally ordered; *near*-unique) | `nextBallot()` = `(counter << 20) \| nodeTag` — the counter makes it monotone; the 20-bit hashed-node-id tag in the low bits makes a same-counter collision between two nodes *improbable*, **not impossible**. This is best-effort uniqueness **for liveness only** (distinct ballots help dueling proposers converge); **safety never depends on it.** "Never two leaders" rests on quorum intersection + the promise guard + the fencing epoch — so even if two ballots tied, the system would still be safe. (Classic Paxos assumes globally-unique ballots; prism does not need that assumption to be sound.) |
| **Phase 1a — PREPARE(b)** | `store.prepare(group, ballot)` → fans the ballot out to every acceptor (`QuorumConsensusStore.prepare`). On the wire, a PREPARE is a GET that carries a `prepareBallot`. |
| **Phase 1b — PROMISE** | `Acceptor.handle`, PREPARE branch: promises iff `ballot ≥ promised[group]`, records the new floor, and returns *both* the floor (so a loser knows how high to retry) and its currently-accepted lease. |
| **Majority of promises** | `promisedCount ≥ majority` in `QuorumConsensusStore.prepare`; `majority = n/2 + 1`. |
| **"Take the highest accepted value"** | the proposer reads `prepare.highestAccepted()` and decides from it (§4, point 2). |
| **Phase 2a — ACCEPT(b, v)** | `store.compareAndSet(group, cur, next)` at the prepared ballot (`tryAcquire`). |
| **Phase 2b — ACCEPTED** | `Acceptor.handle`, ACCEPT branch: accepts iff the **lease rule** holds *and* the **promise guard** (`epoch ≥ promised`) holds; on accept it promotes `promised` to the committed ballot. |
| **Chosen value / Learner** | `QuorumConsensusStore.get()` returns the lease backed by a **majority** (a minority value is never reported). |
| **Fencing token** | the ballot **is** the fencing epoch — it travels with the lease so a stale ex-leader is detected and rejected by anyone honoring epochs. |

The ballot/epoch unification is the neat part: the Paxos proposal number and the fencing token are
the *same monotonically-increasing integer*.

---

## 4. Where prism specializes the protocol

Two deliberate departures from the textbook acceptor — both narrow the protocol to *leases* without
weakening safety.

**(1) The acceptor enforces lease rules on top of the ballot rule.** A textbook acceptor checks only
the ballot/promise. prism's acceptor also applies the lease rule (`Acceptor.handle`, ACCEPT branch):

- a **free** acceptor (no lease) accepts anything;
- the **same owner** may renew at `epoch ≥ current`;
- a **different owner** may take over **only if the lease is expired *and* the epoch is strictly
  higher**.

That last clause is what gives fencing-epoch monotonicity and stops a higher epoch from preempting a
*still-valid* lease.

**(2) The Phase-2 value rule is adapted for leadership.** Textbook Paxos says: if any acceptor in
your promise quorum already accepted a value, you **must** re-propose *that* value. prism instead: if
the highest accepted value is a **still-valid** lease of **another** owner, the proposer **yields**
(backs off) rather than proposing itself; it proposes itself only when the slot is **free or
expired**:

```java
// LeaseElector.tryAcquire — "Respect a still-valid claim of another owner"
final LeaseRecord highest = prepare.highestAccepted().orElse(null);
if (highest != null && !highest.isExpired(now) && !highest.owner().equals(memberId())) {
  backoff(group, now);
  return;
}
```

This is safe because a leader is "chosen" **only while a majority hold an unexpired lease**. Once the
lease expires, that value is no longer chosen, so taking over at a higher epoch is a *new decree*, not
an overwrite of a decided value. Complementing this, the promise guard carries a
`validSameOwnerRenewal` exemption (`Acceptor.handle`) so a challenger's PREPARE can never knock a
healthy leader off its own renewal — the anti-stickiness rule.

---

## 5. Liveness — breaking dueling proposers

Paxos cannot *guarantee* liveness (FLP impossibility), and naive Paxos can livelock: two proposers
keep out-balloting each other in lockstep, neither ever reaching Phase 2. prism uses the standard
practical fixes:

- **Randomized backoff `[T, 2T]`** after a contended round (`backoff()` + `acquireBackoffMillis`), so
  one proposer pulls ahead instead of all preempting each other — the same idea as Raft's randomized
  election timeout.
- **Ballot leap-frogging:** on a lost round the proposer floors its counter *above* the highest
  ballot any acceptor reported (`bumpBallotCounter` from both `highestPromised` and `highestAccepted`),
  so its next attempt is strictly higher than the floor instead of climbing by one forever.

This is the fix for the dueling-proposer livelock that an earlier single-phase design could not avoid
(see [`decisions/0012-distributed-quorum-lease-elector.md`](decisions/0012-distributed-quorum-lease-elector.md)).

---

## 6. The one honest caveat — durable `promised`

Textbook crash-safe Paxos keeps **both** `promised` and `accepted` on stable storage. prism journals
`accepted` (the durable acceptor recovers its accepted leases on construction —
`Acceptor(LeaseJournal)`), but **`promised` is in-memory only**. After a crash + restart an acceptor
forgets its Phase-1 promises.

In practice this is largely reconstructed and does **not** compromise the headline safety property:
the *accepted* epoch floor is durable, and a proposer always proposes strictly above the highest
accepted epoch it reads in Phase 1, so fencing monotonicity survives a restart. The lost `promised`
map mainly affects dueling-livelock avoidance and a single in-flight PREPARE, not the committed floor.
Still, it is a real simplification versus the textbook, and it is tracked on the roadmap as a durable
epoch-floor for the dynamic path ([`plan.md`](plan.md)).

---

## 7. What is explicitly *out* of scope

| Not implemented | Why it's not needed here |
|---|---|
| **Multi-Paxos** (a leader runs many decrees, skipping Phase 1 for subsequent ones) | prism agrees on *who leads*, then the leader just renews its lease — there is no stream of commands to order. |
| **Replicated log / slots / state-machine replication** | the "decided value" is a single register (owner + epoch + TTL), not a command history. Use the elected leader to drive your own state machine if you need one. |
| **General client-value consensus** | the value is always a lease; the acceptor rule is purpose-built for it. prism is an *elector*, not a general consensus library. |
| **EPaxos / leaderless multi-command** | listed as possible future work; today's scope is single-decree election. |

If you genuinely need a replicated command log, prism is the wrong layer — but prism can elect the
single leader that *owns* such a log safely.

---

## 8. Proof

The protocol on this page is modelled in TLA+ at
[`spec/LeaseElection.tla`](spec/LeaseElection.tla): the `promised` variable, the `Prepare` action
(promise iff `b > promised`), and the `Accept` action gated by both the lease rule and the promise
guard (with the same-owner-renewal exemption). TLC checks **`AtMostOneLeader`** (mutual exclusion)
and **`AgreementPerEpoch`** (the single-decree property) over the complete bounded state graph — and
this runs in CI (the `spec` job). The `prism-sim` deterministic fuzzer checks the same invariants on
the *real* implementation across hundreds of seeds with message loss, partitions, and clock skew.
Model checking and simulation as complementary evidence — see [`guarantees.md`](guarantees.md) and
[`spec/README.md`](spec/README.md).
