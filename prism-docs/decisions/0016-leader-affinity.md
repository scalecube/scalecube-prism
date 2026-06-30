# 0016 — Leader affinity: preference-biased, sticky, no-failback election

Status: **Accepted — implemented (2026-06-28).** Extends the lease elector (ADR-0012) with an
election-time preference policy (Mode A) and a controller-driven promote/demote API (Mode B). Does not
change the safety kernel. Shipped on `SingletonElector`/`LeaseElector`: `affinity(group, preference,
yieldWindow, autoMove)`, `promote(group)` (cooperative), `demote(group)`; verified by
`LeaseElectorAffinityTest` (8 BDD tests: preferred-wins-regardless-of-order, standby-after-yield,
ineligible-never, no-failback, auto-move handoff, cooperative promote, demote, promoted-no-reacquire)
and demoed by `LeaderAffinityExample`. The `force` promote variant (§4a) is not yet implemented.

---

## 1. Problem and goals

Some deployments want leadership to sit on a **specific, preferred** member rather than whichever node
happens to win — for example a set of replicas spread across failure domains (availability zones), where
the leader should be the replica **co-located with some external anchor** (a dependency whose primary
lives in one domain and can fail over to another). Requirements, stated generically:

- **G1 — preferred start.** At boot, the member co-located with the active anchor is the leader.
- **G2 — follow the anchor on failure.** If the anchor's domain fails (anchor *and* the local member
  die), the new leader should be the member in the domain the anchor **fails over to**.
- **G3 — survive a lone member failure.** If only the leader member dies (anchor healthy), any other
  member may lead.
- **G4 — no automatic failback.** When the previous preferred member returns, the incumbent **keeps**
  leadership until an operator (or the controlling authority) rearranges things.
- **G5 — safety unchanged.** Exactly one leader at any instant; stale leaders fenced.

This is the **Patroni** model (`failover_priority` + `nofailover` tags, manual failback), *not*
CockroachDB lease preferences (which auto-fail-back to preference — explicitly unwanted here).

---

## 2. The catch

The naïve reading — "leadership tracks the preferred node" — is a **continuous controller**, and it
violates G4: the returning preferred member would immediately steal leadership back. The resolution:

> **Preference is an election-time gate, not a running comparison.** It influences *who wins when an
> election happens*; it never preempts a healthy leader.

Our elector already **never preempts a valid lease** (ADR-0012), so G4 is free once preference is
confined to campaign timing. Re-appointment therefore happens only on a real **vacancy** (the leader's
lease actually expired) — with one explicit, operator-equivalent exception in §4.3.

Two further catches:

- **C1 — the anchor-failover vs election race.** On a full-domain failure the lease frees in
  ≈`leaseTtl`, but the anchor needs its own time to re-establish a primary elsewhere. If the election
  resolves *first*, "preferred" is unknown, a standby wins, and — with no failback — it stays in the
  wrong domain. **Decision: affinity-strict** (§4.2).
- **C2 — fencing must be honored downstream.** "Never two active leaders" is only externally true if
  the **protected resource rejects a stale leader's fencing epoch**. Leadership moves bump the epoch
  monotonically (ADR-0012/0013); the resource side must check it. This is a contract on the resource,
  not on prism.

---

## 2a. Two modes: autonomous vs. controller-driven

There are two ways to use affinity; both ride the same lease/fencing kernel and may be mixed per group.

- **Mode A — autonomous (declarative).** The application supplies a *preference signal* and Prism
  self-elects with a bias toward the preferred candidate (§3–§4). Prism owns the *when*.
- **Mode B — controller-driven (imperative).** Nodes are **passive** (they do not self-campaign). An
  external controller explicitly **promotes** and **demotes** members; Prism is the *safe mechanism
  only*, guaranteeing at-most-one and monotone fencing even if the controller issues a racy or mistaken
  command. The controller owns the *when*; Prism owns the *safety*. (§4a.)

Mode B is the cleaner fit when an authority already exists that knows the topology (it knows which
domain it is in and when it fails over). It externalizes policy entirely and keeps Prism a guardrail.
This is the etcd/Consul-session pattern: an orchestrator drives, the lock service guarantees exclusion.

---

## 3. Model: preference levels (Mode A)

Each candidate exposes a **preference level** for a group, re-evaluated continuously but consulted only
at campaign time:

| Level | Meaning | Campaign behavior |
|-------|---------|-------------------|
| `PREFERRED` | co-located with the active anchor | campaigns immediately for a free/expired lease |
| `STANDBY` | eligible but not preferred | campaigns only after a **yield window** with no preferred winner |
| `INELIGIBLE` | must never lead (e.g. drained) | never campaigns (the `nofailover` analog) |

The signal is **app-provided** (decided): the application supplies a `Supplier<Preference>` per group,
e.g. `() -> localDomain.equals(anchor.activeDomain()) ? PREFERRED : STANDBY`. Prism stays decoupled
from how the anchor's location is discovered.

---

## 4. Policy (Mode A)

### 4.1 Election-time bias (the core)
On a tick, when the lease is free or expired:
- `PREFERRED` candidates attempt acquisition immediately.
- `STANDBY` candidates attempt acquisition only after the **yield window** `Y` has elapsed since the
  lease became acquirable, and only if no `PREFERRED` candidate has taken it.
- The acquisition itself is the unchanged quorum CAS (safety intact regardless of who attempts).

Sticky tenure (ADR-0012) means whoever wins keeps it until its lease is lost. G4 holds automatically.

### 4.2 Affinity-strict on vacancy (resolves C1)
The yield window `Y` is sized **≥ the anchor's worst-case failover time**. So after a full-domain
failure, the co-located member in the *new* anchor domain wins within `Y`; only if no `PREFERRED`
candidate appears within `Y` does a `STANDBY` take over (the availability escape hatch). This trades a
bounded extra leadership gap (`Y`) for the co-location guarantee.

### 4.3 Auto-move once on preferred-locality change (decided)
If the anchor's active domain changes while the leader is healthy but now in the **wrong** domain, prism
performs **exactly one controlled handoff** to the co-located member: the incumbent resigns (releasing
its lease) and the preferred member acquires at a strictly higher epoch. This is a *single deliberate
preemption*, not continuous failback — it fires on the **preferred-locality-change edge**, never on a
preferred node merely returning. Guard: debounced and rate-limited; if no co-located member is live, no
move.

> This is the one place a healthy leader is preempted. It is justified because the *purpose* of the
> leader (co-location with the anchor) has objectively changed. Returning nodes do not trigger it (G4).

### 4.4 Manual rebalance
An operator command (`resign` on the current leader, or a `rebalance` that resigns + lets preference
re-elect) is always available, for the cases policy intentionally leaves alone.

---

## 4a. Mode B — controller-driven imperative control

In Mode B the application does **not** call `campaign`; nodes are passive. An external controller drives
leadership with two verbs on a specific member:

| Verb | Meaning | Mechanism |
|------|---------|-----------|
| `promote(group)` | "make this member the leader" | acquire the lease for self |
| `demote(group)` | "retire this member as leader" | release the lease (= `resign`); stay passive |

The controller decides *when* to elect or retire; Prism guarantees the *safety floor*: even if it
promotes two members concurrently (bug, retry, partition), the quorum CAS lets **at most one** win, and
the loser's `promote` simply fails. The downstream remains protected by the monotone fencing epoch (C2).

**The one real decision — `promote` against a live incumbent:**

- **Cooperative (recommended default).** `promote` acquires only if the lease is free or expired; if
  another member holds a valid lease it **fails** (returns false). To move leadership the controller
  issues an explicit `demote(old)` then `promote(new)`. Nothing is ever preempted without an explicit
  command, and there is no window where Prism preempts on its own. Safest and most predictable.
- **Forced (opt-in, `promote(group, force=true)`).** A single controlled handoff: the incumbent is
  asked to step down and the new member acquires at a strictly higher epoch, fencing the old. One call
  instead of two, at the cost of relying on the resource honoring fencing (C2) during the brief overlap.

Recommendation: ship **cooperative** `promote` + explicit `demote`; add `force` only if the two-step
control loop proves awkward. Either way, a forced or cooperative promote is just the proven
acquire/resign path at a higher epoch — **no new safety surface**.

`demote` leaves the node passive (it does not auto-recampaign), so "the controller decides when to
elect or retire" holds literally: leadership stays vacant until the controller promotes someone.

---

## 5. Safety argument

The safety kernel is untouched: every leadership change is still a quorum lease CAS with monotone
fencing epochs (proven in `LeaseElection.tla`, DST-fuzzed). Preference (Mode A) and promote/demote
(Mode B) only change **which candidate calls `tryAcquire` and when** — they cannot make two
acquisitions both succeed (that is the acceptor's job, unchanged). Therefore:

- **Never two leaders** — inherited from ADR-0012; neither mode can weaken it.
- **No failback** — preference is consulted only on vacancy (§4.1) or the locality edge (§4.3), never
  on a node returning; Mode B moves only on explicit command.
- **Liveness** — the yield window guarantees a `STANDBY` eventually wins if no `PREFERRED` does, so a
  lost preferred domain never blocks leadership beyond `Y` (availability escape hatch).

The §4.3 auto-move and a forced Mode-B promote are both a `resign`+`acquire` at a higher epoch — they
reuse the proven path and add no new safety surface.

---

## 6. API sketch (non-binding)

Mode A — autonomous with affinity bias:

```java
ElectorConfig.forGroup("gateway")
    .preference(() -> localDomain.equals(anchor.activeDomain()) ? PREFERRED : STANDBY)
    .yieldWindow(Duration.ofSeconds(8))      // >= anchor failover time (affinity-strict)
    .autoMoveOnPreferenceChange(true);       // §4.3
```

`PREFERRED | STANDBY | INELIGIBLE`. Defaults: everyone `STANDBY`, `yieldWindow = 0` (pure
first-come stickiness, today's behavior), `autoMoveOnPreferenceChange = false`. The feature is fully
opt-in and changes nothing for existing users.

Mode B — controller-driven imperative control (nodes passive; the controller calls these):

```java
SingletonElector elector = prism.elector();
boolean won = elector.promote("gateway").block();   // acquire iff free/expired (cooperative)
elector.demote("gateway").block();                  // release + stay passive (= resign)
// elector.promote("gateway", /*force*/ true)        // opt-in single controlled handoff
```

`promote`/`demote` are additive to the existing `campaign`/`resign`; a node uses Mode A *or* Mode B per
group, not both. In Mode B nodes never auto-campaign, so leadership stays exactly where the controller
puts it.

---

## 7. Relationship to other ADRs

- **ADR-0012 (quorum-lease elector):** this is a policy layer above it; the lease/fencing kernel is
  unchanged.
- **ADR-0015 (self-electing quorum):** orthogonal. A fixed set of members (e.g. one per domain) uses
  the **static** quorum; affinity is about *which member leads*, not *which members form the quorum*.
  They compose but neither requires the other.
- **ADR-0013 (formal verification + DST):** the standing standard applies — the yield-window/auto-move
  policy and the promote/demote verbs get DST coverage (preferred-wins-on-vacancy;
  no-failback-on-return; single-move-on-locality-edge; at-most-one-under-racy-promote; availability
  after `Y`) before they ship.

---

## 8. Consequences

- **+** Meets a concrete need (domain-pinned leadership with controlled failover) with no change to the
  safety kernel, in either an autonomous or a controller-driven style.
- **+** Reuses sticky tenure for "no failback" — almost free.
- **−** Requires the downstream protected resource to honor fencing epochs (C2) for end-to-end safety.
- **−** Affinity-strict adds a bounded leadership gap (`Y`) on full-domain failover — the cost of
  guaranteeing co-location.
- **−** The §4.3 auto-move is the sole autonomous healthy-leader preemption; must be debounced and
  well-tested to avoid edge-triggered churn.

---

## 9. References

- Patroni — `failover_priority`, `nofailover`, and manual failback semantics.
- CockroachDB — lease preferences (the auto-failback model we deliberately avoid).
- Kubernetes — node affinity + tolerations (the "prefer, with an escape hatch" pattern); leases driven
  by an external controller.
- ADR-0012, ADR-0013, ADR-0015.
