# SWIM + gossip in prism — the membership substrate, and what it can't do

prism's bottom layer (L0) is **SWIM failure detection + epidemic gossip**, supplied whole by
`scalecube-cluster`. This page explains what SWIM actually is, how prism *rides* it (rather than
reimplements it), how membership events drive the registry — and, just as importantly, the one thing
this layer is structurally **forbidden** from doing: electing a leader.

> **One-line answer:** prism does **not** own a failure detector. It consumes scalecube-cluster's
> SWIM membership and gossip through their public APIs — `listenMembership`/`onMembershipEvent` and
> `spreadGossip`/`onGossip` — using membership as the **tombstone** for dead owners and gossip as the
> dissemination bus for the registry CRDT. SWIM here is an **eventually-strong (◇S) detector that
> *informs* failover, never *decides* it.** Decisions belong to consensus (see §6).

---

## 1. What SWIM is

**SWIM** — *Scalable Weakly-consistent Infection-style Membership* (Das, Gupta, Motwani, DSN 2002) —
splits cluster membership into two decoupled jobs: **detecting** that a member failed, and
**disseminating** that fact. Naively, an all-to-all heartbeat costs `O(n²)` and gets worse as the
cluster grows. SWIM makes both jobs cheap and roughly constant-cost-per-node:

- **Direct probing.** Each member, every protocol period, picks one peer at random and PINGs it.
  A timely ACK means "alive," and the cost per node per period is `O(1)`, not `O(n)`.
- **Indirect probing (ping-req).** If the direct PING times out, the prober asks *k* other members
  to ping the suspect on its behalf (PING_REQ). This is the crucial false-positive cure: a single
  dropped packet or a momentarily busy prober no longer condemns a healthy node — several
  independent paths must all fail. In scalecube this is the failure detector's `doPing` →
  `doPingReq` path, with the `sc/fdetector/pingReq` qualifier
  ([`FailureDetectorImpl.java`](https://github.com/scalecube/scalecube-cluster/blob/master/cluster/src/main/java/io/scalecube/cluster/fdetector/FailureDetectorImpl.java)).
- **Suspicion, not instant death.** A member that fails both direct and indirect probing is marked
  **SUSPECT**, not dead, for a bounded window. The suspicion is gossiped, giving the accused a chance
  to **refute** it.
- **Incarnation refutation.** Each member carries a monotonic **incarnation** number. When a member
  hears it is suspected, it re-broadcasts itself at a higher incarnation, which overrides the
  suspicion everywhere. Only if the suspicion outlives the window unrefuted does the member become
  **DEAD** (`MemberStatus`,
  [`MembershipProtocolImpl.java`](https://github.com/scalecube/scalecube-cluster/blob/master/cluster/src/main/java/io/scalecube/cluster/membership/MembershipProtocolImpl.java)).
- **Gossip dissemination.** Membership updates piggyback on an epidemic gossip protocol — fanout to
  a few random peers, `O(log n)` rounds to full coverage, sequence-id dedup to stop re-floods
  ([`GossipProtocolImpl.java`](https://github.com/scalecube/scalecube-cluster/blob/master/cluster/src/main/java/io/scalecube/cluster/gossip/GossipProtocolImpl.java)).

The deliberate non-feature: SWIM is *weakly consistent*. Two nodes may briefly hold different
membership views. That is by design — and it is the entire reason §6 exists.

> **Lifeguard.** HashiCorp's SWIM refinements (self-/peer-awareness, dynamic suspicion timeouts) cut
> false positives under load. They are referenced in prism's background as a desirable hardening but
> are **not** present in the scalecube-cluster core today (`grep` for "Lifeguard" finds it only in the
> docs, never in `cluster/src/main`). prism therefore inherits plain SWIM's accuracy, which is why
> ADR-0005's correctness caveat about false-positive `DEAD` matters in practice — see
> [`context.md`](context.md) and [`decisions/0005-membership-as-tombstone.md`](decisions/0005-membership-as-tombstone.md).

---

## 2. prism rides SWIM — it does not fork it

The architectural commitment is **layer, don't fork**
([`decisions/0001-keep-cluster-as-is-layer-dont-fork.md`](decisions/0001-keep-cluster-as-is-layer-dont-fork.md)).
SWIM/gossip is *mechanism* (disseminate, detect liveness); registry/elector are *policy* built on
top. By Parnas's modular-decomposition argument, those are distinct **secrets** that change at
different rates, so they live in distinct modules with a strictly one-way dependency. prism is built
only on scalecube-cluster's **public surface** — `listenMembership`, `spreadGossip`/`listenGossips`,
the `ClusterMessageHandler` callbacks — never its internals.

Concretely, the registry installs a `ClusterMessageHandler` at cluster-construction time and receives
both inbound gossip and membership events through it
([`GossipServiceRegistry.bind`](../prism-registry/src/main/java/io/scalecube/prism/registry/impl/GossipServiceRegistry.java)):

```java
public ClusterMessageHandler bind(Cluster cluster) {
  this.cluster = Objects.requireNonNull(cluster, "cluster");
  return new ClusterMessageHandler() {
    @Override public void onGossip(Message gossip) { /* registry deltas + AE beacons */ }
    @Override public void onMembershipEvent(MembershipEvent event) { handleMembership(event); }
  };
}
```

prism adds **zero** code to the failure detector. It is purely a consumer of the SWIM verdict.

---

## 3. How prism maps onto the cluster APIs

| SWIM / gossip concept (L0) | prism's use of it (L1) |
|---|---|
| **Membership event** (`MembershipEvent`, `isAdded`/`isRemoved`/`isLeaving`/`isUpdated`) | delivered to `onMembershipEvent` → `handleMembership` ([`GossipServiceRegistry.java`](../prism-registry/src/main/java/io/scalecube/prism/registry/impl/GossipServiceRegistry.java)). Today the registry acts on the **removal** edge only. |
| **Member identity** (`event.member().id()`) | the owner key the registry purges entries for — every registry entry is keyed by its owning member's id. |
| **Gossip spread** (`cluster.spreadGossip(message)`) | how the registry disseminates a versioned CRDT delta (`spread`, qualifier `sc/prism/registry`) and the Merkle anti-entropy digest beacon (`broadcastBeacon`, qualifier `sc/prism/registry/ae`). |
| **Inbound gossip** (`onGossip(Message)`) | `handleGossip` merges a remote delta by last-writer-wins version; `handleAntiEntropy` compares Merkle roots and re-advertises owned slices on mismatch. |
| **Epidemic dedup / `O(log n)` fanout** | inherited for free from `GossipProtocolImpl` — prism never re-implements flood control. |

The registry is the **sole writer** of its own member's keys and ignores echoes of its own gossip
(`delta.owner().equals(localId())`), so the LWW merge is conflict-free by construction.

---

## 4. Membership drives the registry — "membership *is* the tombstone"

Deletion is the hard part of any replicated set: a bare removal is not a join-semilattice operation,
so anti-entropy from a lagging replica can **resurrect** a deleted key. The general fix is a
versioned **tombstone** that dominates the add it cancels (OR-Set / 2P-Set; Shapiro et al., 2011) —
at the cost of tombstone accumulation and GC.

A service registry has a structural shortcut: the dominant deletion is *"the owner died,"* and
**SWIM already detects exactly that.** So prism binds each entry's lifecycle to its owner's
membership status
([`decisions/0005-membership-as-tombstone.md`](decisions/0005-membership-as-tombstone.md)):

```java
private void handleMembership(MembershipEvent event) {
  if (!event.isRemoved()) {
    return;
  }
  final String ownerId = event.member().id();
  synchronized (lock) {
    for (RegistryEvent e : store.purgeOwner(ownerId)) {
      emit(e);                       // EXPIRED for every entry the dead owner wrote
    }
  }
}
```

When SWIM declares a member `REMOVED`, prism **purges that owner's entries and emits `EXPIRED`** —
no per-key tombstone, no GC. Safety rests on the single-writer rule: only the dead owner could have
written those keys, so the purge contradicts no live writer. The full versioned-tombstone-plus-GC
machinery is paid only for the rarer **live deregistration** (`deregister` writes a higher-version
tombstone), where the host outlives the service and membership cannot help.

The honest cost: a consumer's lookup is a **hint, not a guarantee**. A dead owner's entries linger
for the failure-detection-plus-dissemination window, and a SWIM false-positive `DEAD` would purge a
live owner's entries until it re-advertises (anti-entropy heals it). That window is exactly SWIM's
accuracy — which is why Lifeguard (§1) would tighten this guarantee.

---

## 5. Anti-entropy: gossip's safety net

Gossip is best-effort; a delta can be missed. prism layers periodic **Merkle anti-entropy** over
the same gossip bus: every node broadcasts a 256-bucket Merkle **digest** of its catalog
(`broadcastBeacon`), and any peer whose tree differs re-advertises only the records it owns in the
**differing buckets** (`handleAntiEntropy` → `MerkleTree.diff` → `reAdvertiseBuckets`)
([`GossipServiceRegistry.java`](../prism-registry/src/main/java/io/scalecube/prism/registry/impl/GossipServiceRegistry.java),
[`MerkleTree.java`](../prism-registry/src/main/java/io/scalecube/prism/registry/impl/MerkleTree.java)).
Traffic is proportional to the delta and stops once the trees converge. This is the same soft-state + anti-entropy discipline the PARC
epidemic line established (Demers et al., 1987) — see [`context.md`](context.md).

---

## 6. The honest, load-bearing limit — gossip cannot elect a leader

This is the most important sentence on the page: **SWIM/gossip gives eventually-consistent
membership, and eventually-consistent membership cannot safely elect a singleton leader.**

The tempting cheap rule — *"the lowest-id alive member in my gossip view is the leader"* — is
**provably unsafe** ([`decisions/0006-consensus-not-gossip-for-election.md`](decisions/0006-consensus-not-gossip-for-election.md)):

- SWIM is an **unreliable** failure detector. In an asynchronous network you cannot distinguish a
  crashed node from a slow or partitioned one; perfect detection (`P`) is unimplementable, and FLP
  (1985) shows consensus is impossible without extra assumptions.
- A rule that maps the **local** membership view to a leader inherits that view's disagreements.
  Under a partition or a false-positive suspicion, the two sides hold different "lowest-id alive"
  members and **both elect** → split-brain. Two leaders is exactly the outcome prism exists to
  prevent.
- Election **is** consensus — agreement on a single value (the leader). The weakest detector that
  solves consensus is `◇W`, and it needs a **majority of correct processes** (a quorum). Gossip
  supplies dissemination and an `◇S`-style detector, but **not agreement**.

So prism keeps the two strictly separate. SWIM/Lifeguard provides **fast, reasonably accurate
detection** that *drives* handoff; the actual decision is made on the `CONSENSUS` tier by a Paxos
quorum lease with a monotonic **fencing epoch** — see [`paxos.md`](paxos.md). The slogan: SWIM
*informs* failover; consensus *decides* it. Shipping a gossip-only elector is an explicit guardrail
violation in ADR-0006.

---

## 7. What is explicitly *out* of scope

| Not in prism's L0 | Where it actually lives |
|---|---|
| **The failure detector itself** (ping, ping-req, suspicion, incarnation) | entirely scalecube-cluster's `FailureDetectorImpl` / `MembershipProtocolImpl`. prism reads the verdict; it never computes it. |
| **Lifeguard accuracy refinements** | not in the cluster core today; tracked as desirable hardening, not owned by prism (§1). |
| **Any leadership decision from the gossip view** | forbidden by ADR-0006 — leadership is consensus + lease + fencing, never gossip. |
| **Strong consistency on the gossip path** | the registry is AP by design; only the per-key `CONSENSUS` tier is strongly consistent. |
| **Transport / RPC plumbing** | the SWIM probe traffic and gossip ride scalecube's Netty/TCP transport; prism adds its own point-to-point transport for consensus (ADR-0012), not for membership. |

If you need a strongly-consistent membership log, this layer is the wrong place — but prism can elect
the single leader that safely *owns* such a log.

---

## 8. Evidence

The membership-driven purge and the LWW/anti-entropy convergence are exercised by the registry tests
and by the `prism-sim` deterministic fuzzer, which injects message loss, partitions, and churn against
the **real** `GossipServiceRegistry` and asserts convergence plus correct `EXPIRED` emission on owner
death. The "gossip cannot elect" claim is the negative space proved on the *other* side: the elector's
`AtMostOneLeader` invariant is model-checked in TLA+ ([`spec/LeaseElection.tla`](spec/LeaseElection.tla))
and fuzzed across hundreds of partition seeds — which is precisely the safety that a gossip-view
elector would forfeit. See [`guarantees.md`](guarantees.md) and [`context.md`](context.md) for the
full lineage and the AP-vs-CP boundary.
