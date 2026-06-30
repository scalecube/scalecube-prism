# Merkle anti-entropy in prism — what's implemented, and what isn't

prism heals registry drift with a **range-based Merkle tree** over the service catalog — the
Dynamo/Cassandra technique for cheaply answering "are two replicas in sync?" and, when they aren't,
repairing **only the delta**. This page explains exactly what the implementation does, how each part
maps to the code, and where it deliberately stays simple.

> **One-line answer:** a fixed-shape, 256-bucket Merkle tree turns "compare two whole catalogs" into
> a per-bucket digest comparison; on divergence the tree descends only the differing branches, and
> the authoritative owner re-advertises just its records in those buckets. It is the **convergence
> backstop** behind delta gossip — a healing mechanism for the AP registry, **not** a consistency
> upgrade. (The beacon carries the sparse per-bucket digest and the receiver exchanges only the
> differing buckets — `MerkleTree.diff` is wired into the protocol; see §5–§6.)

---

## 1. Why anti-entropy at all?

The registry disseminates each change as a **delta gossip** message (`spread` in
[`GossipServiceRegistry.java`](../prism-registry/src/main/java/io/scalecube/prism/registry/impl/GossipServiceRegistry.java)).
Gossip is fast and best-effort, and "best-effort" is the whole problem: a message can be dropped, a
node can be briefly partitioned, a peer can join *after* a write fanned out. Each such miss leaves two
replicas holding **different** catalogs — silent, indefinite drift. The CRDT merge rule guarantees
that replicas which have *seen the same updates* converge (see [`guarantees.md`](guarantees.md),
"Registry — Strong Eventual Consistency"); it says nothing about updates a replica simply **never
received**. Something has to notice the gap and close it. That something is anti-entropy.

The naive fix — periodically ship your entire catalog to a peer and let CRDT merge sort it out —
converges, but it costs O(n) bandwidth on every round even when the replicas are already identical
(the common case). What you want is a way to detect "we agree" in O(1), and pay the repair cost only
proportional to the **actual** disagreement. That is precisely what a Merkle tree buys.

---

## 2. Merkle anti-entropy in 60 seconds

A Merkle (hash) tree is a binary tree whose leaves hash data and whose internal nodes hash their
children, all the way up to a single **root hash**. Two facts make it the canonical anti-entropy
structure:

1. **Equal roots ⇒ equal state** (up to hash collisions). One integer comparison settles "are we in
   sync?" for the entire dataset — no matter how large.
2. **A difference is *localized*.** If the roots differ, exactly one child of the root differs (or
   both). Descend into the differing child(ren) and recurse; identical subtrees are pruned instantly.
   Finding *k* changed leaves costs O(d · k) where d is the depth — you never touch the agreeing
   majority of the tree.

The Dynamo/Cassandra read-repair pattern bucketizes the keyspace into fixed ranges so two trees over
**different** key sets still share the same shape and compare positionally. Leaves accumulate their
entries order-independently (so insertion order can't change the hash), and reconciliation ships only
the entries in the differing buckets — full-state sync collapses into a delta exchange.

---

## 3. How it maps to prism's code

The tree itself is
[`MerkleTree.java`](../prism-registry/src/main/java/io/scalecube/prism/registry/impl/MerkleTree.java);
the catalog it indexes comes from `RegistryStore.contentDigest()`
([`RegistryStore.java`](../prism-registry/src/main/java/io/scalecube/prism/registry/impl/RegistryStore.java));
the periodic beacon and the divergence reaction live in
[`GossipServiceRegistry.java`](../prism-registry/src/main/java/io/scalecube/prism/registry/impl/GossipServiceRegistry.java).

| Merkle concept | prism code |
|---|---|
| **Fixed-shape tree over the keyspace** | `new MerkleTree(MERKLE_DEPTH, …)` with `MERKLE_DEPTH = 8` → **256 buckets**; keys are placed by `bucketOf(key, depth)` so different key sets still align positionally. |
| **Leaf hash (order-independent)** | each leaf is the **XOR** of its entries' mixed hashes — XOR is commutative, so insertion order is irrelevant (`MerkleTree` constructor). |
| **Internal-node hash** | `node[i] = mix(node[2i] * 0x9E37… + node[2i+1])` — children combined and run through a SplitMix64 finalizer (`mix`) for strong avalanche. |
| **The per-entry content** | `RegistryStore.contentDigest()` maps every `owner/service` to a hash of its **version (HLC physical+logical) and tombstone flag** — so a version bump or a delete changes the leaf. |
| **Cached local tree** | `GossipServiceRegistry.localTree()` builds the tree from `contentDigest()` and caches it, rebuilding lazily only after the catalog changes (`rootDirty`, review F7) — so a steady catalog beacons for free. |
| **Descend only divergent branches** | `MerkleTree.diff(other)` → `descend()` prunes any subtree where `node[i] == other.node[i]`, returning just the differing **bucket indices**. |
| **The beacon** | `broadcastBeacon()` gossips a `sc/prism/registry/ae` message carrying the tree's **per-bucket leaf hashes**, sparse-encoded (only non-empty buckets) by `MerkleDigestCodec`, on a fixed schedule (`start(Duration)`, a daemon `scheduleAtFixedRate`). |
| **The reaction** | `handleAntiEntropy()`: decode the peer's digest, rebuild its tree via `MerkleTree.fromBucketHashes`, `diff` against ours, and `reAdvertiseBuckets(differing)` → re-`spread` only this node's **owned** records whose key falls in a differing bucket. A beacon it can't parse falls back to `reAdvertiseOwned()` (full slice). |
| **Convergence via merge** | the re-advertised deltas flow back through normal `handleGossip` → `store.apply`, where the CRDT LWW guard keeps the newer version — so re-advertisement can never clobber fresher state. |

The neat detail: because every node is the **single writer** of its own keys, "repair" never needs a
two-way reconciliation handshake. A diverging node only re-pushes its own records in the buckets the
diff flagged — proportional to the drift, not its whole slice; the receiver's CRDT merge does the rest.

---

## 4. The efficiency argument

The point of the tree is to make the *steady state* — replicas already in sync — nearly free, and to
make repair proportional to the drift, not the dataset.

- **Detection is cheap, repair is proportional.** A beacon carries the sparse per-bucket digest
  (bytes proportional to the *non-empty* buckets, not the catalog). When two replicas are converged,
  `diff` prunes at the root and `handleAntiEntropy` re-advertises **nothing** — the
  `convergedReplicasDoNotChangeOnBeacon` test; when they differ by one entry, only that entry is
  re-advertised — the `diffReadvertisesOnlyTheChangedBucketNotTheWholeSlice` test (it asserts far
  fewer entries cross the wire than the owned slice), both in
  [`AntiEntropyTest.java`](../prism-registry/src/test/java/io/scalecube/prism/registry/impl/AntiEntropyTest.java).
- **Localization is sub-linear.** `MerkleTreeTest.singleChangedValueIsLocalizedToItsBucket`
  ([`MerkleTreeTest.java`](../prism-registry/src/test/java/io/scalecube/prism/registry/impl/MerkleTreeTest.java))
  bumps one service's version in a 1000-entry catalog and asserts `diff` returns **exactly one**
  bucket — O(d) descent, not an O(n) scan. `missingEntryIsDetected` and
  `emptyVersusPopulatedDiffersBroadly` confirm the two ends of the spectrum (a single missing key vs.
  a cold replica that legitimately needs broad repair).
- **It's measured, not just asserted.** [`prism-bench`](../prism-bench/src/main/java/io/scalecube/prism/bench/Benchmarks.java)
  (`merkle()`) builds a tree and times `diff` for a single change at depth 12, reporting build-vs-diff
  cost; this is the "sub-linear anti-entropy" evidence row in [`guarantees.md`](guarantees.md).

---

## 5. How it complements gossip

These two mechanisms are deliberately different tools for the same convergence goal:

- **Delta gossip** (`spread` / `handleGossip`) is the **fast path**: every write is pushed
  immediately, best-effort, and reaches most of the cluster in a few rounds. It is cheap and timely
  but offers no delivery guarantee.
- **Merkle anti-entropy** (`broadcastBeacon` / `handleAntiEntropy`) is the **backstop**: a slow,
  periodic sweep that detects any update gossip dropped and heals it. It offers no timeliness but
  *does* close the gap — `rootBeaconHealsAMissedUpdate` in `AntiEntropyTest` registers a service
  while a peer is disconnected, reconnects, beacons, and watches the peer converge from the
  bucket diff alone.

Together they give the registry its Strong-Eventual-Consistency property in practice: gossip makes
convergence **fast**, anti-entropy makes it **certain**. Neither alone is enough — gossip can miss,
and a pure anti-entropy sweep would be far too slow as the only channel.

---

## 6. What is *not* wired up, honestly

The beacon now carries the per-bucket digest and `handleAntiEntropy` exchanges only the differing
buckets (the previous "compare roots, re-advertise the whole slice" path is gone). Two precise seams
remain:

- **No multi-round tree walk.** The beacon ships the full leaf vector in one message (sparse, so it
  is proportional to the *non-empty* buckets), rather than a request/response that descends a remote
  subtree level by level. For a 256-bucket tree this is the simpler and cheaper choice; a deeper tree
  would favour an interactive walk. The receiver's `diff` still narrows repair to the differing
  buckets, so only drifted entries are re-sent.
- **Granularity is bounded by depth.** At `MERKLE_DEPTH = 8` (256 buckets) the tree localizes drift
  to a bucket; with far more than 256 keys per node, a bucket holds several services and the unit of
  "difference" is coarser than a single key. This is the classic Merkle-granularity trade-off, not a
  bug.

Neither weakens correctness — re-advertisement is idempotent under LWW merge — they only bound how
*tightly* repair is scoped. A mixed-version peer whose beacon can't be parsed simply triggers the
full-slice `reAdvertiseOwned()` fallback, so convergence still holds.

---

## 7. What is explicitly *out* of scope

| Not implemented | Why it's not needed here |
|---|---|
| **A consistency upgrade.** Anti-entropy does not make the registry linearizable or even quorum-fresh. | The registry is AP by design; this heals drift in the eventual/causal tiers (see the dial in [`guarantees.md`](guarantees.md)). Freshness lives in the `QUORUM`/`CONSENSUS` tiers, not here. |
| **Cryptographic integrity.** The hashes are SplitMix64 mixes, not SHA-2; this is a *sync* tree, not a tamper-evidence tree. | The threat model is dropped messages, not a Byzantine peer forging entries (non-Byzantine assumption, `guarantees.md`). |
| **Tombstone resurrection.** Re-advertising never revives a deleted key. | Tombstones carry a version and ride the same LWW merge (ADR-0005); `contentDigest` hashes the tombstone flag, so a delete *is* a state difference. |
| **Cross-node atomicity / transactions.** | Out of scope for the whole registry, not just anti-entropy. |

If you need *fresh* reads rather than *converged* ones, reach for the consistency dial — anti-entropy
guarantees you'll eventually agree, not that you agree *right now*.

---

## 8. Evidence

The structure is unit-tested in
[`MerkleTreeTest.java`](../prism-registry/src/test/java/io/scalecube/prism/registry/impl/MerkleTreeTest.java)
(order-independence of the root, single-change localization to one bucket, missing-entry detection,
empty-vs-full broad diff). The end-to-end healing behavior is in
[`AntiEntropyTest.java`](../prism-registry/src/test/java/io/scalecube/prism/registry/impl/AntiEntropyTest.java)
(a beacon heals an update missed during a disconnect; converged replicas don't churn on a beacon).
The sub-linear cost is measured by `merkle()` in
[`prism-bench`](../prism-bench/src/main/java/io/scalecube/prism/bench/Benchmarks.java) and recorded as
the "sub-linear anti-entropy" row of the evidence map in [`guarantees.md`](guarantees.md). Tests as
the contract, the benchmark as the cost proof — complementary evidence.
