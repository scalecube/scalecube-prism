# The CRDT registry in prism — what converges, and how HLC versions it

prism's service registry is **AP**: every node may read and write its local replica with no
coordinator, yet all replicas converge to the same catalog. It does this with a **per-key
last-writer-wins (LWW) map CRDT**, versioned by a **Hybrid Logical Clock (HLC)**. This page explains
exactly why that converges, how each piece maps to the code, the single-writer discipline that makes
LWW trivially correct, and what the design deliberately does *not* promise.

> **One-line answer:** the registry is a join-semilattice of per-key LWW-registers merged by
> `max`-by-version; the version is a strictly-monotonic HLC timestamp; merge is commutative,
> associative, and idempotent, so replicas reach **Strong Eventual Consistency** regardless of
> delivery order or duplication. It is **not** linearizable, **not** a multi-value register, and gives
> no cross-node read-after-write below the `CONSENSUS` tier.

---

## 1. Why a CRDT at all?

Discovery is the question *"who offers service X, where, and with what properties right now?"*
([`README.md`](../README.md), §Discovery). The honest constraint is that this answer must stay
**available during partitions** — a routing layer that blocks because a coordinator is unreachable is
worse than one that serves slightly stale hints. So the registry is AP, and AP means no consensus on
the write path: any node writes locally and gossips the change.

The hazard of uncoordinated writes is *divergence*: two nodes that saw the same updates in a different
order end up disagreeing. A **CRDT** (Conflict-free Replicated Data Type; Shapiro et al., 2011)
eliminates that hazard *by construction*. If replica state forms a **join-semilattice** and merge is
the least-upper-bound, then merge is commutative, associative, and idempotent — and any two replicas
that have delivered the same **set** of updates hold identical state, with no agreement on order. That
property is **Strong Eventual Consistency (SEC)**, and it is exactly what the registry guarantees
([`guarantees.md`](guarantees.md), §Registry).

---

## 2. The data type — a per-key LWW map as a join-semilattice

The convergent core is `RegistryStore`
([`../prism-registry/src/main/java/io/scalecube/prism/registry/impl/RegistryStore.java`](../prism-registry/src/main/java/io/scalecube/prism/registry/impl/RegistryStore.java)),
deliberately pure: no I/O, no gossip, fully deterministic and unit-testable. Its shape is a nested map
`owner → (service → Record)`, where each `Record` is a versioned `ServiceEntryImpl` plus a tombstone
flag. Each key `(owner, service)` is therefore a single **LWW-register**.

The whole semilattice lives in one method, `RegistryStore.apply`:

```java
// RegistryStore.apply — last-writer-wins by version
if (existing != null && entry.version().compareTo(existing.version()) <= 0) {
  return Optional.empty(); // not newer — last-writer-wins rejects it
}
services.put(service, new Record(entry, tombstone));
```

Read this as `merge(a, b) = argmax(version)`. Over a **totally ordered** version domain (§3), `max`
is:

- **commutative** — `max(a, b) = max(b, a)`, so the order two deltas arrive in is irrelevant;
- **associative** — grouping of merges does not matter, so any reconciliation tree converges;
- **idempotent** — `max(a, a) = a`, and the `<= 0` guard rejects a re-delivered or equal update, so
  duplicates are no-ops.

The map as a whole is the **product** of these per-key lattices, which is itself a join-semilattice.
Commutativity + idempotence is *precisely* order- and duplicate-independence, which is SEC. This is
the proposition proved in [`decisions/0003-single-writer-lww-versioning.md`](decisions/0003-single-writer-lww-versioning.md)
and mechanically checked by `RegistryConvergenceTest` over 200 seeds with reordering and duplicates.

Deletes are **tombstones** — a versioned "removed" marker, never a bare map removal — so anti-entropy
can never resurrect a deleted key (a later re-advertisement of the old entry loses the `max`). The
tombstone is just another value in the same lattice.

---

## 3. The version — a Hybrid Logical Clock, and why not wall-clock or vector clocks

`max`-by-version only converges if the version domain is **totally ordered** and a causally-later
write always carries a **strictly greater** version. A bare wall clock fails the second requirement:
under clock skew a causally-later write can carry a *smaller* millisecond and be wrongly dropped. A
pure Lamport clock fixes causality but loses the human-meaningful link to real time (useful for lease
TTLs and observability).

prism uses a **Hybrid Logical Clock** (Kulkarni et al., 2014), which fuses both. A
`HybridTimestamp`
([`../prism-versioning/src/main/java/io/scalecube/prism/versioning/HybridTimestamp.java`](../prism-versioning/src/main/java/io/scalecube/prism/versioning/HybridTimestamp.java))
is a pair `(physical, logical)` ordered **lexicographically** — exactly the total order LWW needs:

```java
// HybridTimestamp.compareTo — total order, greater means newer
int byPhysical = Long.compare(physical, other.physical());
return byPhysical != 0 ? byPhysical : Long.compare(logical, other.logical());
```

It implements the `Version`
([`../prism-api/src/main/java/io/scalecube/prism/version/Version.java`](../prism-api/src/main/java/io/scalecube/prism/version/Version.java))
interface — the same `compareTo` `RegistryStore.apply` calls. The clock that issues these stamps,
`HybridLogicalClock`
([`../prism-versioning/src/main/java/io/scalecube/prism/versioning/HybridLogicalClock.java`](../prism-versioning/src/main/java/io/scalecube/prism/versioning/HybridLogicalClock.java)),
has two mutating operations, and **both always advance**, so successive stamps are strictly
increasing:

- `now()` — stamp a local write. It takes `physical = max(prevPhysical, wallClock)` and bumps the
  `logical` counter when the physical component did not advance, resetting it to `0` when it did. The
  physical component **never goes backward**, even if the wall clock does.
- `update(remote)` — merge a timestamp seen on an inbound gossip delta, advancing local state past
  *both* the local clock and the remote one: `physical = max(local, remote, wallClock)`, then the
  logical counter **follows whichever clock the new physical matched** — `max(localLogical,
  remoteLogical) + 1` if local and remote tied, `localLogical + 1` or `remoteLogical + 1` if it
  matched just one, and `0` when the wall clock jumped ahead of both. (The single `max(…) + 1` is
  only the tie case; the four-way rule is what guarantees the result strictly exceeds *both* inputs.)

That second operation is what keeps the whole cluster's clocks within bounded drift: every received
delta drags a lagging clock forward (`GossipServiceRegistry.handleGossip` calls `clock.update(remote)`
before applying the delta —
[`../prism-registry/src/main/java/io/scalecube/prism/registry/impl/GossipServiceRegistry.java`](../prism-registry/src/main/java/io/scalecube/prism/registry/impl/GossipServiceRegistry.java)).

**No vector clocks.** Vector clocks *detect concurrent writes*; a scalar HLC cannot. prism does not
need detection because there is **no concurrency to detect** (§4) — so it pays for a single scalar
instead of an O(N) vector. Were multi-writer keys ever required, the design would need MV-registers or
interval-tree clocks plus explicit conflict resolution; the single-writer rule is exactly how prism
avoids that cost (ADR-0003, references 1 & 4).

**Durable high-water.** The clock can be constructed with a `ClockJournal` so it resumes **above** the
last persisted physical time on restart (`persistAhead`, `highWater`). A node with a stable id thus
never re-issues a version it already used, so LWW keeps accepting it after a crash — verified by
`FileClockJournalTest` even with the wall clock stepped backward ([`guarantees.md`](guarantees.md),
§Versioning).

---

## 4. Single-writer-per-key — why LWW is *trivially* correct here

LWW is famous for being lossy under concurrent writes: two writers update the same key "at the same
time" and one silently overwrites the other. prism sidesteps that entirely with a hard invariant from
[`decisions/0003-single-writer-lww-versioning.md`](decisions/0003-single-writer-lww-versioning.md):
**each key `(owner, service)` has exactly one writer — its owner.** A node only ever writes its own
keys (`register`/`update`/`unregister` stamp with the local `clock.now()` in
`GossipServiceRegistry`), and it **ignores echoes of its own data** on the gossip path:

```java
// GossipServiceRegistry.handleGossip — we are the sole writer of our keys
if (delta.owner().equals(localId())) {
  return; // our own data — we are the sole writer, ignore echoes
}
```

With one writer per key, the updates to that key are **totally ordered by that writer** (its HLC is
monotonic). There are no concurrent writes to reconcile, so LWW is not a lossy heuristic — it is the
*correct* and only outcome. This collapses the hard CRDT case (multi-value registers, concurrency
detection) into a trivial one (a per-source register merged by `max`).

---

## 5. Why a late / duplicate / reordered update can't clobber a newer one

This is the property that makes AP gossip safe, and it is one line of code:

- **Reordered:** delta `v2` arrives, then the older `v1` arrives. `apply(v1)` hits
  `v1.version <= stored(v2)` → `Optional.empty()`, rejected. The newer value stands.
- **Duplicated:** the same `v2` arrives twice. The second hits `<= 0` (equal) → rejected. Idempotent.
- **Late after restart:** an owner that restarts resumes above its durable high-water, so its first
  post-restart write outranks every pre-crash version still floating in the gossip layer.

Because rejection depends only on the stored version, never on arrival order, an observer **never sees
a key's version go backward** — monotonic-per-key reads ([`guarantees.md`](guarantees.md)). This is
also why anti-entropy is safe to run repeatedly: re-exchanging old entries (`ownedDeltas`,
`contentDigest` feeding a `MerkleTree`) can only ever be a no-op or a forward step.

---

## 6. What is explicitly *out* of scope

| Not provided | Why / what to use instead |
|---|---|
| **Linearizable reads** | the registry is **AP**: a lookup is a *hint*, with stale-positive and stale-negative windows. Consumers must retry / fail over ([`guarantees.md`](guarantees.md)). For a linearizable answer, use the `CONSENSUS` tier / the elector. |
| **Read-after-write across nodes** | another node sees your write only after gossip + anti-entropy converge — a bounded window, not instant. Below the `CONSENSUS` dial there is no cross-node RYW (session-guarantee table, [`guarantees.md`](guarantees.md)). |
| **A general multi-value register** | LWW keeps exactly one value per key. Concurrent multi-writer keys would need MV-registers / ITCs; prism forbids them by the single-writer invariant (ADR-0003). |
| **Cross-key / cross-node atomicity or transactions** | each key converges independently; there is no multi-key snapshot. |
| **Total order across keys** | the HLC totally orders *writes to one key*; it is **not** a global serialization of all registry events. |

If you need a globally agreed, linearizable value, that is the elector's job (consensus), not the
registry's — see [`paxos.md`](paxos.md).

---

## 7. Proof / evidence

The SEC proposition (`max` over a totally-ordered version domain is commutative, associative,
idempotent ⇒ order- and duplicate-independent ⇒ SEC) is stated and proved in
[`decisions/0003-single-writer-lww-versioning.md`](decisions/0003-single-writer-lww-versioning.md).
It is then checked against the **real** code: `RegistryConvergenceTest` drives the same delta set
through `RegistryStore` in shuffled, duplicated orders across 200 seeds and asserts identical live
state; `RegistryStoreTest` covers the no-resurrection tombstone rule; and `FileClockJournalTest`
verifies HLC versions never regress across a restart even with the wall clock stepped backward. The
evidence map in [`guarantees.md`](guarantees.md) links each registry guarantee to its test. The pure,
side-effect-free design of `RegistryStore` is what makes that determinism — and therefore the proof's
applicability to the running system — possible.
