# Onboarding — learning scalecube-prism from zero

> **Read this if:** you've inherited this codebase, you didn't write it, and you want to get to the
> point where you can change it *safely* without an AI holding your hand. That is an honest, reachable
> goal, and this page is the route.

## 0. What this guide is (and the promise)

This is a **sequenced learning path**, not a reference. The reference material already exists and is
excellent — [`prism-docs/`](prism-docs/README.md) has an explainer for every building block, an ADR
for every binding decision, and a TLA+ spec for the safety-critical core. The problem that page
*doesn't* solve is **order**: where do you start, what do you read next, and how do you know you've
actually understood a thing rather than skimmed it. That is what this guide is for.

**The promise: a path, not a cliff.** prism looks intimidating because it names a lot of PhD-flavoured
algorithms in one breath — Paxos, CRDTs, hybrid logical clocks, Merkle anti-entropy, TLA+. The
reassuring truth is in the README's own words: *"prism isn't a new algorithm; it's a careful assembly
of well-understood ones."* Every piece here is **textbook, transferable computer science**. None of it
is proprietary cleverness you can only learn by reading our code. Learn these building blocks and you
are a better distributed-systems engineer *anywhere* — at the next job, on the next system, for the
rest of your career. The code is just where you'll see them wired together for real.

**The principle: humans own comprehension; AI accelerates.** Use an AI to summarize a paper, draft a
test, or explain a stack trace — that's leverage, use it. But the bar for merging a change is that **a
human on this team understands it**. AI is a power tool, not a substitute for the understanding this
guide exists to build. By the end you should be able to read any file here, predict what it does, and
defend a change to it in review.

How long? Section 6 lays out a concrete **2-week plan**. You can be productive on the AP registry in a
few days; the CP elector and the formal-verification track take the full two weeks to internalize.
That's normal — consensus is genuinely hard, and we'd rather you respect it than rush it.

---

## 1. First 60 minutes — clone, build, run one example

Goal: prove to yourself the thing works on your machine before you read a single algorithm.

```bash
git clone <this-repo> scalecube-prism
cd scalecube-prism
mvn clean install            # Java 17+. Pulls scalecube-cluster from Maven Central / GH Packages.
```

The build runs the **whole** test suite, including the deterministic safety fuzzer and the codec /
registry / elector BDD tests. If `mvn clean install` is green, the safety properties this project
claims actually held across hundreds of seeded fault scenarios on *your* checkout. That green is
meaningful — sit with that for a second.

Now run the smallest possible program,
[`HelloPrismExample`](prism-examples/src/main/java/io/scalecube/prism/examples/HelloPrismExample.java).
It wraps a single-node cluster, registers a service, and looks it up locally:

```bash
mvn -pl prism-examples dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "prism-examples/target/classes;$(cat prism-examples/cp.txt)" \
  io.scalecube.prism.examples.HelloPrismExample
```

(On Git Bash use `;` as the classpath separator on Windows, `:` on Linux/macOS.)

**What to watch for in the output:**
- `prism is up at <address>` — prism *decorated* an ordinary scalecube `Cluster`; it did not replace
  it. Hold onto this: prism is a **layer**, never a fork.
- `registered: hello -> {greeting=world}` — the AP registry was usable *immediately*, with no quorum,
  no coordinator, no waiting. That's the whole point of AP.
- `prism is down` — `shutdown()` stopped prism but left the underlying cluster's lifecycle to you.

When you want a multi-node feel, run
[`ServiceRegistryExample`](prism-examples/src/main/java/io/scalecube/prism/examples/ServiceRegistryExample.java)
(advertise on one node, discover on another) and then
[`PrismGatewayExample`](prism-examples/src/main/java/io/scalecube/prism/examples/PrismGatewayExample.java)
(a partition-safe 3-node leader election). The full catalog of 12 runnable programs, simple → advanced,
is in [`prism-examples/README.md`](prism-examples/README.md).

For a guided version of this section with more detail, read
[`prism-docs/getting-started.md`](prism-docs/getting-started.md).

---

## 2. The mental model in one page

prism is **two products on one cluster, behind one wrapper**:

1. **The service registry** — *"who offers service X, where, right now?"* This is **AP** (Available
   under partition): every node answers from its local replica, instantly, possibly slightly stale.
   Built from **gossip + CRDTs**.
2. **The singleton elector** — *"exactly one node owns this job — never two, even in a split-brain."*
   This is **CP** (Consistent under partition): the minority side stops answering rather than risk a
   second leader. Built from **Paxos + a fenced lease**.

These are two halves of one need (shared cluster state) at two different consistency points. Most
stacks pay for them twice (Eureka/gossip *and* ZooKeeper/etcd — two clusters). prism runs **one**
gossip fabric and puts a **per-key dial** on top, so each key picks the weakest tier that's still
correct: `EVENTUAL → CAUSAL → QUORUM → CONSENSUS`.

```
   one application                         prism.registry()        prism.elector()
        |                                   (AP, per-key dial)      (CP, never two)
        v                                         |                      |
  +-----------------------------------------------+----------------------+--------+
  | L4  Singleton elector     lease - epoch - fencing - affinity   prism-elector  |
  | L3  Consensus engine      quorum lease - self-electing quorum   prism-consensus|
  |  --- boundary: reactive above - deterministic below ---                        |
  | L2  Consistency router    per-key tier dispatch                 prism-registry |
  | L1  Service registry      versioned per-key CRDT map (reactive) prism-registry |
  | L0  scalecube-cluster     SWIM + gossip + membership (AP)       (dependency)   |
  +-------------------------------------------------------------------------------+

   the consistency dial:
   EVENTUAL ----- CAUSAL ----- QUORUM ----- CONSENSUS
   available, fast, stale-ok  ........  linearizable, safe, coordinated
```

Dependencies flow **downward** only — nothing points back up at `scalecube-cluster`. The single most
important rule in the whole system lives in this picture: **gossip (L0) informs failover; consensus
(L3/L4) decides it.** Gossip can *never* be trusted to elect a leader (that's split-brain); see
track 4. Full treatment: [`prism-docs/architecture.md`](prism-docs/architecture.md).

---

## 3. The learning path — one track per building block

Eight tracks, each in the same shape:

> **ADR** (the binding decision + *why*) → **explainer** (how it maps to our code) → **theory** (the
> canonical external source — learn the idea cold) → **source** (1–3 real files to read) →
> **exercise** (one small hands-on task to prove you understood).

Do them roughly in this order — each leans on the ones before. **Tracks A–C are the AP registry**
(start here, they're the gentler ramp). **Tracks D–F are the CP elector** (the hard, beautiful core).
**Tracks G–H are cross-cutting** (security, and how we *prove* any of it).

### Track A — Membership & failure detection (SWIM + gossip)

The substrate. Everything rides on it; prism *consumes* it and never reimplements it.

1. **ADR:** [`prism-docs/decisions/0001-keep-cluster-as-is-layer-dont-fork.md`](prism-docs/decisions/0001-keep-cluster-as-is-layer-dont-fork.md)
   (layer, don't fork) and
   [`prism-docs/decisions/0005-membership-as-tombstone.md`](prism-docs/decisions/0005-membership-as-tombstone.md)
   (membership death *is* the tombstone).
2. **Explainer:** [`prism-docs/gossip-swim.md`](prism-docs/gossip-swim.md).
3. **Theory:** *"SWIM: Scalable Weakly-consistent Infection-style Process Group Membership Protocol"* —
   Das, Gupta & Motwani, DSN 2002. For the gossip/epidemic foundation: Demers et al., *"Epidemic
   Algorithms for Replicated Database Maintenance"*, PODC 1987. (Bonus: HashiCorp's *Lifeguard* paper,
   Dadgar et al. 2018, on cutting SWIM false positives.)
4. **Source:** how prism binds onto the cluster's public APIs and reacts to a member dying —
   `handleMembership` / `bind` in
   [`prism-registry/.../GossipServiceRegistry.java`](prism-registry/src/main/java/io/scalecube/prism/registry/impl/GossipServiceRegistry.java).
5. **Exercise:** find `handleMembership`; confirm prism acts on the **removal** edge only and emits
   `EXPIRED` for the dead owner's keys. Then answer in one sentence why a SWIM *false-positive* `DEAD`
   is self-healing here (hint: anti-entropy, track C).

### Track B — The registry CRDT + Hybrid Logical Clock

How uncoordinated writes still converge to one catalog.

1. **ADR:** [`prism-docs/decisions/0003-single-writer-lww-versioning.md`](prism-docs/decisions/0003-single-writer-lww-versioning.md)
   (single-writer-per-key + monotonic HLC + last-writer-wins).
2. **Explainer:** [`prism-docs/crdt-hlc.md`](prism-docs/crdt-hlc.md).
3. **Theory:** CRDTs — Shapiro, Preguiça, Baquero & Zawirski, *"Conflict-free Replicated Data Types"*,
   SSS 2011 (and their tech report *"A comprehensive study of CRDTs"*). Clocks — Kulkarni, Demirbas,
   Madappa, Avva & Leone, *"Logical Physical Clocks and Consistent Snapshotting in Globally Distributed
   Databases"* (HLC), 2014; for the foundation, Lamport, *"Time, Clocks, and the Ordering of Events in
   a Distributed System"*, CACM 1978.
4. **Source:** the pure, deterministic semilattice —
   [`prism-registry/.../RegistryStore.java`](prism-registry/src/main/java/io/scalecube/prism/registry/impl/RegistryStore.java)
   (read `apply` — the whole LWW merge is one `compareTo` guard); the clock —
   [`prism-versioning/.../HybridLogicalClock.java`](prism-versioning/src/main/java/io/scalecube/prism/versioning/HybridLogicalClock.java)
   and the totally-ordered stamp
   [`prism-versioning/.../HybridTimestamp.java`](prism-versioning/src/main/java/io/scalecube/prism/versioning/HybridTimestamp.java).
5. **Exercise:** in `RegistryStore.apply`, explain why the `<= 0` (not `< 0`) version guard is what
   makes a **duplicate** delivery a safe no-op. Then trace why **single-writer-per-key** makes LWW
   *trivially* correct here rather than lossy.

### Track C — Merkle anti-entropy

Gossip is best-effort, so it can drop a message. This is the backstop that guarantees convergence.

1. **ADR:** (no dedicated ADR; it serves ADR-0003's convergence goal — the explainer is the spec.)
2. **Explainer:** [`prism-docs/anti-entropy-merkle.md`](prism-docs/anti-entropy-merkle.md).
3. **Theory:** Merkle, *"A Digital Signature Based on a Conventional Encryption Function"*, CRYPTO
   1987 (the tree). For the anti-entropy *pattern* in a real registry, read the Amazon **Dynamo**
   paper — DeCandia et al., *"Dynamo: Amazon's Highly Available Key-value Store"*, SOSP 2007, §4.7
   (Merkle-tree replica synchronization).
4. **Source:** the tree —
   [`prism-registry/.../MerkleTree.java`](prism-registry/src/main/java/io/scalecube/prism/registry/impl/MerkleTree.java);
   the beacon + reaction (`broadcastBeacon`, `handleAntiEntropy`, `reAdvertiseBuckets`) and the
   sparse per-bucket digest (`MerkleDigestCodec`) in
   [`prism-registry/.../GossipServiceRegistry.java`](prism-registry/src/main/java/io/scalecube/prism/registry/impl/GossipServiceRegistry.java).
5. **Exercise:** run `MerkleTreeTest` and `AntiEntropyTest` in `prism-registry`. Read
   `singleChangedValueIsLocalizedToItsBucket` and `diffReadvertisesOnlyTheChangedBucketNotTheWholeSlice`,
   and explain why the beacon's per-bucket digest makes repair proportional to the *actual*
   difference, not the catalog size.

### Track D — Leader election (single-decree Paxos + a fenced lease)

The heart of the CP side. This is the hardest track; budget real time for it.

1. **ADR:** [`prism-docs/decisions/0006-consensus-not-gossip-for-election.md`](prism-docs/decisions/0006-consensus-not-gossip-for-election.md)
   (*why gossip cannot elect*), then
   [`prism-docs/decisions/0012-distributed-quorum-lease-elector.md`](prism-docs/decisions/0012-distributed-quorum-lease-elector.md)
   (the actual elector design), with
   [`prism-docs/decisions/0007-raft-first-epaxos-shaped.md`](prism-docs/decisions/0007-raft-first-epaxos-shaped.md)
   for the path that got us here.
2. **Explainer:** [`prism-docs/paxos.md`](prism-docs/paxos.md).
3. **Theory:** Lamport, *"Paxos Made Simple"*, 2001 (start here — read it twice). Then the FLP
   impossibility result — Fischer, Lynch & Paterson, *"Impossibility of Distributed Consensus with One
   Faulty Process"*, JACM 1985 (why *liveness* can't be guaranteed). For fencing tokens specifically,
   Martin Kleppmann, *Designing Data-Intensive Applications*, Ch. 8 ("The leader and the lock"), and
   his blog post *"How to do distributed locking"*.
4. **Source:** the proposer —
   [`prism-elector/.../LeaseElector.java`](prism-elector/src/main/java/io/scalecube/prism/elector/impl/LeaseElector.java)
   (`tryAcquire`); the acceptor —
   [`prism-consensus/.../Acceptor.java`](prism-consensus/src/main/java/io/scalecube/prism/consensus/Acceptor.java)
   (`handle` — this is the safety kernel, and it is the TLA+ `Accept` action verbatim); the public
   surface — [`prism-api/.../SingletonElector.java`](prism-api/src/main/java/io/scalecube/prism/elector/SingletonElector.java).
5. **Exercise:** in `Acceptor.handle`, find the rule that lets a *different* owner take over only when
   the lease is **expired AND the epoch is strictly higher**. Explain why dropping the "expired" clause
   would reintroduce the bug that formal verification caught (track H, §7). Then run
   [`GatewayElectionExample`](prism-examples/src/main/java/io/scalecube/prism/examples/GatewayElectionExample.java)
   and watch a failover with the fencing epoch printed.

### Track E — Tunable consistency (the per-key dial)

The framing that lets one cluster serve both AP and CP.

1. **ADR:** [`prism-docs/decisions/0002-per-key-tunable-consistency.md`](prism-docs/decisions/0002-per-key-tunable-consistency.md).
2. **Explainer:** [`prism-docs/tunable-consistency.md`](prism-docs/tunable-consistency.md).
3. **Theory:** Gilbert & Lynch, *"Brewer's Conjecture and the Feasibility of ... CAP"*, 2002; Abadi,
   *"Consistency Tradeoffs in Modern Distributed Database System Design"* (PACELC), IEEE Computer 2012.
   For the licence to mix tiers per key, Li et al., *"Making Geo-Replicated Systems Fast as Possible,
   Consistent when Necessary"* (RedBlue consistency), OSDI 2012. Session guarantees: Terry et al.,
   *"Session Guarantees for Weakly Consistent Replicated Data"* (Bayou), 1994.
4. **Source:** the dial —
   [`prism-api/.../ConsistencyTier.java`](prism-api/src/main/java/io/scalecube/prism/registry/ConsistencyTier.java);
   how the tier travels with each entry in
   [`prism-registry/.../GossipServiceRegistry.java`](prism-registry/src/main/java/io/scalecube/prism/registry/impl/GossipServiceRegistry.java).
5. **Exercise:** run
   [`ConsistencyTiersExample`](prism-examples/src/main/java/io/scalecube/prism/examples/ConsistencyTiersExample.java).
   From the explainer's "honest caveats" table, state which tier is **designed but not yet implemented**
   and what you'd actually get today if you tagged a key with it.

### Track F — Self-electing (dynamic) quorum

The most intricate, safety-critical subsystem — and it ships *last and opt-in*. Do it after D.

1. **ADR:** [`prism-docs/decisions/0015-self-electing-quorum.md`](prism-docs/decisions/0015-self-electing-quorum.md).
2. **Explainer:** [`prism-docs/self-electing-quorum.md`](prism-docs/self-electing-quorum.md).
3. **Theory:** Ongaro & Ousterhout, *"In Search of an Understandable Consensus Algorithm"* (Raft),
   USENIX ATC 2014 — read the **membership-change** sections (single-server changes and joint
   consensus) closely; that is exactly the hazard this track manages. Skim a Jepsen report on a
   membership-change split-brain (jepsen.io) to see it happen in production systems.
4. **Source:** the config + its evolution policy —
   [`prism-consensus/.../QuorumConfig.java`](prism-consensus/src/main/java/io/scalecube/prism/consensus/QuorumConfig.java)
   (read `planNextStep` and the single-member commit rule); the leader-driven loop —
   [`prism-consensus/.../ReconfigurationManager.java`](prism-consensus/src/main/java/io/scalecube/prism/consensus/ReconfigurationManager.java).
5. **Exercise:** explain, using *quorum intersection*, why changing the quorum **one member at a time**
   keeps adjacent configurations' majorities overlapping — and therefore preserves "never two leaders"
   across a reconfiguration. Then run
   [`SelfElectingQuorumExample`](prism-examples/src/main/java/io/scalecube/prism/examples/SelfElectingQuorumExample.java)
   and watch the quorum self-form 1 → 3.

### Track G — Codec & security (no Java serialization)

Small, sharp, and easy to fully understand in an afternoon — a good morale win between hard tracks.

1. **ADR:** [`prism-docs/decisions/0009-schema-codec-no-jdk-serialization.md`](prism-docs/decisions/0009-schema-codec-no-jdk-serialization.md).
2. **Explainer:** [`prism-docs/codec-security.md`](prism-docs/codec-security.md).
3. **Theory:** Frohoff & Lawrence, *"Marshalling Pickles: how deserialization for fun and profit"*,
   AppSecCali 2015 (the gadget-chain RCE class). The CERT secure-coding rule **SER12-J**, *"Prevent
   deserialization of untrusted data."*
4. **Source:** the two-class wire substrate —
   [`prism-codec/.../WireReader.java`](prism-codec/src/main/java/io/scalecube/prism/codec/WireReader.java)
   (note: it *never* calls `readObject`); a real message codec —
   [`prism-consensus/.../LeaseCodec.java`](prism-consensus/src/main/java/io/scalecube/prism/consensus/LeaseCodec.java).
5. **Exercise:** run a repo-wide search for `Serializable`, `ObjectInputStream`, `readObject` and
   confirm the only hit is the words *"never calls readObject"* in `WireReader`'s Javadoc. Then read
   `LeaseCodecTest` and explain why a corrupted **version byte** is rejected rather than mis-parsed.

### Track H — Formal verification + deterministic simulation (DST)

How "never two leaders" is *checked*, not just claimed. This is what makes the CP tracks trustworthy.

1. **ADR:** [`prism-docs/decisions/0010-sim-before-consensus.md`](prism-docs/decisions/0010-sim-before-consensus.md)
   (build the simulator *first*) and
   [`prism-docs/decisions/0013-formal-verification-and-dst.md`](prism-docs/decisions/0013-formal-verification-and-dst.md).
2. **Explainer:** [`prism-docs/formal-verification-dst.md`](prism-docs/formal-verification-dst.md).
3. **Theory:** Leslie Lamport's TLA+ resources — *Specifying Systems* (the book, free PDF) and the TLA+
   Video Course; Newcombe et al., *"How Amazon Web Services Uses Formal Methods"*, CACM 2015. For DST,
   the FoundationDB simulation talk (Will Wilson, *"Testing Distributed Systems w/ Deterministic
   Simulation"*, Strange Loop 2014) and TigerBeetle's writing/talks on deterministic simulation testing.
4. **Source:** the spec —
   [`prism-docs/spec/LeaseElection.tla`](prism-docs/spec/LeaseElection.tla)
   (and [`prism-docs/spec/README.md`](prism-docs/spec/README.md) for the model-to-code map and recorded
   TLC numbers); the DST harness —
   [`prism-sim/.../SimCluster.java`](prism-sim/src/main/java/io/scalecube/prism/sim/SimCluster.java)
   (virtual clock + seeded RNG + god-view oracle); a fuzz test —
   [`prism-sim/.../ElectorSafetyFuzzTest.java`](prism-sim/src/test/java/io/scalecube/prism/sim/ElectorSafetyFuzzTest.java).
5. **Exercise:** read the `AtMostOneLeader` invariant in `LeaseElection.tla` and find its twin — the
   `trueLeaders(group) <= 1` oracle check — in `SimCluster`. Explain in your own words *why we need
   both*: what does exhaustive-but-bounded TLC catch that sampled-but-real DST can't, and vice versa?

---

## 4. How to make a change safely

The workflow that keeps this codebase trustworthy. Follow it every time.

1. **Read the *why* first.** Find the ADR and explainer that govern the area you're touching (use the
   tracks above). If your change *contradicts* an ADR, you don't just edit code — you write a **new
   ADR** that supersedes the old one (ADRs are append-only and immutable once Accepted; see
   [`prism-docs/decisions/README.md`](prism-docs/decisions/README.md)). The decision record is the
   contract; the code is the implementation of it.
2. **Make the change** in the smallest module that owns the behaviour. Respect the layering: nothing
   below points up. Respect the **reactive-above / deterministic-below** boundary
   ([`prism-docs/decisions/0004-reactive-vs-deterministic-boundary.md`](prism-docs/decisions/0004-reactive-vs-deterministic-boundary.md))
   — the consensus kernel stays pure and deterministic so the simulator can drive it.
3. **Run the module tests:** `mvn -pl <module> test` (e.g. `-pl prism-registry`). Add a test for your
   change. The pure, side-effect-free design of stores like `RegistryStore` and `Acceptor` exists
   precisely so you *can* unit-test the logic deterministically.
4. **If you touched the safety kernel** (anything under `prism-consensus`, `prism-elector`, or the
   acceptor rule): run the deterministic simulator. `mvn -pl prism-sim test` re-runs the fuzzers across
   hundreds of seeds with partitions, message loss, and clock skew, and the god-view oracle checks
   "never two leaders" and fencing-epoch monotonicity directly. **If you changed the acceptor rule, you
   must also update the TLA+ spec and re-check it** — `Acceptor.handle` is the spec's `Accept` action
   verbatim, and CI's `spec` job will surface the drift.
5. **The comprehension bar — the one rule that matters most:** *nothing merges that no human on this
   team understands.* If you can't explain in review why your change is safe — referencing the relevant
   ADR, invariant, or proof — it is not ready, no matter how green the build is. An AI may help you get
   to understanding faster; it cannot stand in for it. This is the whole reason this onboarding guide
   exists.

---

## 5. A 2-week onboarding plan

A suggested schedule. Adjust to your pace — depth beats speed here, especially in week 2.

### Week 1 — the AP registry and the substrate (gentler ramp)

- **Day 1 — Orientation & quickstart.** Section 1 (build green, run `HelloPrismExample`). Read this
  whole guide, the [`README.md`](README.md), and [`prism-docs/architecture.md`](prism-docs/architecture.md)
  (skim). Read [`prism-docs/context.md`](prism-docs/context.md) for lineage. Goal: hold the
  Section-2 mental model in your head.
- **Day 2 — Track A (SWIM + gossip).** Read the SWIM paper, the explainer, the ADRs, do the exercise.
- **Day 3 — Track B (CRDT + HLC).** The Shapiro CRDT paper + the Kulkarni HLC paper, the explainer,
  read `RegistryStore.apply` and the clock, do the exercise. This is the conceptual core of the AP side.
- **Day 4 — Track C (Merkle anti-entropy) + Track E (the dial).** Both are short given B. Run
  `MerkleTreeTest`, `AntiEntropyTest`, and `ConsistencyTiersExample`. Do both exercises.
- **Day 5 — Consolidate + Track G (codec/security).** Do the codec track (a satisfying, fully-graspable
  one). Then write a *throwaway* small change to the registry (e.g. add a log line or a metric on the
  membership-removal path) and run `mvn -pl prism-registry test`. You've now practised the Section-4
  workflow on the easy side.

### Week 2 — the CP elector and the proof (the hard, beautiful core)

- **Day 6 — Paxos theory.** Read *Paxos Made Simple* (twice) and skim FLP. Don't open our code yet;
  get the protocol cold first.
- **Day 7 — Track D part 1.** ADR-0006 (why gossip can't elect — this is *the* load-bearing idea) and
  ADR-0012, the [`paxos.md`](prism-docs/paxos.md) explainer. Read `Acceptor.handle` line by line.
- **Day 8 — Track D part 2.** Read `LeaseElector.tryAcquire`. Run `GatewayElectionExample` and
  `PrismGatewayExample`. Do the Track D exercise (the expired-AND-higher-epoch rule). This is the day
  it should "click."
- **Day 9 — Track H (formal verification + DST).** Read the explainer, then `LeaseElection.tla` + its
  README, then `SimCluster` and `ElectorSafetyFuzzTest`. Do the exercise (match the TLC invariant to
  the DST oracle). You now understand *why you can trust* Day 7–8.
- **Day 10 — Track F (self-electing quorum) + a real first change.** Read the Raft membership-change
  sections, ADR-0015, the explainer; run `SelfElectingQuorumExample`. Then pick a genuinely small,
  real change (a metric, a doc fix, a tightened test, a clearer comment in the acceptor), run the
  module tests **and** `prism-sim` if it's near the kernel, and walk a teammate through *why it's safe*.
  That conversation is graduation.

By the end you should be able to open any file in the eight tracks, predict its behaviour, and defend a
change to it — without an AI in the loop. That's the goal stated at the top, reached.

---

## 6. Keep it honest — onboarding as a tested artifact

Two commitments keep this guide from rotting into the kind of stale doc nobody trusts.

**The links are CI-enforced.** Every relative link on this page — into `prism-docs/`, into the ADRs,
and into actual source files — is checked by
[`scripts/check-doc-links.sh`](scripts/check-doc-links.sh), the repo's doc-honesty guard: it fails the
build on any broken intra-repo link, including links into source code that point at a moved or renamed
file. So if you follow a link here and it 404s, that's a *bug* — the build should have caught it; fix
it. (The companion guard is CI's `spec` job in
[`.github/workflows/ci.yml`](.github/workflows/ci.yml), which keeps the TLA+ specs honest by failing if
the safe config breaks *or* if the deliberately-unsafe config stops producing its split-brain
counterexample.)

**Log every confusion; fix the doc.** You are the best reviewer this onboarding will ever have, because
you're the only one reading it without already knowing the answers. When something here is unclear,
wrong, or out of order — when an exercise doesn't land, a link is stale, or an explainer assumes
knowledge you didn't have — **open an issue or a PR and fix it.** Treat onboarding as a tested artifact,
not a one-time read: the next hire inherits exactly the path you leave behind. Improving it is itself a
perfect first contribution, and a low-risk way to practise the Section-4 workflow.

Welcome aboard. The algorithms here are famous for a reason — they're worth knowing. Take the time, and
this codebase stops being someone else's and starts being yours.
