# Debugging prism — a runbook

A practical guide to debugging prism (a service registry + leader elector). If you are new here and
worried about "debugging a foreign distributed system," read section 1 first: prism is **deliberately
easier to debug than most**, because its safety-critical core is a **deterministic simulation** that
**replays any failure exactly from a seed**.

This complements [`troubleshooting.md`](troubleshooting.md) (symptom → fix for a *running* cluster)
and [`ops/runbook.md`](ops/runbook.md) (alerts/thresholds). Start there for "production is on fire";
start here for "I need to find and fix the bug." The science behind the sim is in
[`formal-verification-dst.md`](formal-verification-dst.md) — this page is the *how-to*.

---

## 1. Why debugging prism is different (better)

Distributed bugs are normally horrible because they are **non-reproducible**: a partition + a clock
skew + a reorder line up once, the test goes red, and you can never make it happen again. prism
removes that pain for its consensus/election kernel:

- **Determinism.** The simulator drives *both* sources of nondeterminism — **time** (a virtual
  `AtomicLong clock`) and **the network** (a `new Random(seed)`) — from a single seed. The whole
  long, adversarial run is reproducible bit-for-bit. A flaky-looking timing bug becomes a permanent
  regression case: **you save the seed, not a screenshot.**
- **A god-view oracle.** After every step the sim reads *every acceptor's* stored state directly —
  independent of what any elector *believes* — and checks the invariants. So you don't infer a
  violation from symptoms; the oracle tells you the **exact seed and step** an invariant broke.
- **Two-sided evidence.** The same invariants are model-checked in TLA+ (`spec/LeaseElection.tla`).
  If you suspect a *design* gap rather than a code gap, TLC explores every interleaving in the bound.

Net: for the elector core, "reproduce the bug" is one command with a number, not an afternoon.

---

## 2. Reproduce from a seed

A failing assertion in the sim prints the offending seed and step, e.g.:

```
two leaders at seed=137 step=88
leader epoch regressed at seed=42 step=12
```

The harnesses are `SimCluster` (fixed membership) and `ReconfigSimCluster` (dynamic membership),
both under [`../prism-sim/src/main/java/io/scalecube/prism/sim/`](../prism-sim/src/main/java/io/scalecube/prism/sim/).
Seeds are passed straight into the constructor: `new SimCluster(nodeCount, seed, ttl)`.

**Run the whole sim suite** (this is what CI runs — see section 7):

```
mvn -q -pl prism-sim -am test
```

**Re-run one failing seed locally.** The fuzz tests loop `for (long seed = 0; seed < N; seed++)`.
To replay seed 137 in isolation, point the loop at just that seed. The smallest change is a scratch
test next to [`ElectorSafetyFuzzTest`](../prism-sim/src/test/java/io/scalecube/prism/sim/ElectorSafetyFuzzTest.java)
that reuses the exact body:

```java
@Test
void repro() {
  long seed = 137;                                   // the seed CI printed
  SimCluster sim = new SimCluster(5, seed, Duration.ofMillis(1000));
  sim.campaignAll("gw");
  for (int step = 0; step < 200; step++) {
    sim.chaosStep("gw");
    assertTrue(sim.trueLeaders("gw") <= 1, "two leaders at step=" + step);
  }
}
```

Run just it: `mvn -q -pl prism-sim -am test -Dtest=ElectorSafetyFuzzTest#repro`.

**Shrink.** Because the run is deterministic, bisect the **step**: the assertion already prints it, so
re-run to just before that step and dump state (`sim.trueLeaders`, `sim.currentLeaderEpoch`,
`sim.aliveMembers`, and for reconfig `sim.currentConfig`). To narrow the *fault* that triggered it,
temporarily disable arms of `chaosStep` (partition vs. loss vs. clock jump) and see which one is
load-bearing for the failure — the seed keeps every *other* choice identical.

**Loop / widen.** To hunt for a rare violation, raise the seed ceiling in the `for` loop (the fuzzers
ship at 300/200/60 seeds) or the step count; every new seed is still a permanent repro if it fails.

For real-transport (non-sim) reproduction of the elector over an actual network, use
`mvn -q -pl prism-elector -am test` (e.g.
[`QuorumElectionIntegrationTest`](../prism-elector/src/test/java/io/scalecube/prism/elector/impl/QuorumElectionIntegrationTest.java)).

---

## 3. The invariant oracle — what the sim checks every step

The oracle lives on the harness, not in the system under test, so it is an honest external witness.
Two safety invariants are asserted after **every** `chaosStep`:

- **Never two leaders** — `SimCluster.trueLeaders(group) <= 1`. `trueLeaders` counts the distinct
  owners that hold a majority of acceptors with an *unexpired* lease. More than one is split-brain.
- **Fencing epoch never regresses** — `SimCluster.currentLeaderEpoch(group)` is monotone. It returns
  the epoch of the current majority-backed leader (or `-1`); a successive leader at a *lower* epoch is
  a fencing-token regression (stale writers could be un-fenced).

A violation is reported as a JUnit `assertTrue` failure naming the **seed and step** (see the
messages in section 2). The dynamic-membership oracle in
[`ReconfigSimCluster`](../prism-sim/src/main/java/io/scalecube/prism/sim/ReconfigSimCluster.java)
is config-aware: `trueLeaders` counts an owner as leader if it is certified under the **current OR
previous** config, and `currentLeaderEpoch` returns the highest **committed** (majority-held) epoch —
the analogue of `SelfElectingQuorum.tla`'s `Leader(o)`. These are the *same* properties TLC checks
(`AtMostOneLeader`, `AgreementPerEpoch`); see [`formal-verification-dst.md`](formal-verification-dst.md)
§4 and [`spec/README.md`](spec/README.md).

The fault knobs the oracle runs against: `repartition` (2-way split), `injectLoss` /
`setMessageLoss(percent)` (per-link drop), a virtual-time jump up to 1.5× TTL inside `chaosStep`
(clock skew → lease expiry), occasional re-campaign, and `kill` / `revive` churn (driven by
[`MultiGroupChurnStressTest`](../prism-sim/src/test/java/io/scalecube/prism/sim/MultiGroupChurnStressTest.java)).

---

## 4. Symptom → where to look

Each row points to the real module/class to inspect and the explainer/ADR for the *intended*
behavior. For runtime symptoms not covered here, see [`troubleshooting.md`](troubleshooting.md).

| Symptom | Where to look (code) | Reproduce / explainer |
|---|---|---|
| **Two leaders observed** | `Acceptor.handle` Accept rule — [`Acceptor.java`](../prism-consensus/src/main/java/io/scalecube/prism/consensus/Acceptor.java); oracle `SimCluster.trueLeaders` | [`ElectorSafetyFuzzTest`](../prism-sim/src/test/java/io/scalecube/prism/sim/ElectorSafetyFuzzTest.java); [`formal-verification-dst.md`](formal-verification-dst.md); [`paxos.md`](paxos.md); ADR [`0012`](decisions/0012-distributed-quorum-lease-elector.md) |
| **No leader / livelock** | `LeaseElector.campaign`/`tick` — [`LeaseElector.java`](../prism-elector/src/main/java/io/scalecube/prism/elector/impl/LeaseElector.java); quorum reachability in `QuorumConsensusStore` | `convergesToOneLeaderWhenHealthy` in [`ElectorSafetyFuzzTest`](../prism-sim/src/test/java/io/scalecube/prism/sim/ElectorSafetyFuzzTest.java); [`troubleshooting.md`](troubleshooting.md) "majority loss"; ADR [`0006`](decisions/0006-consensus-not-gossip-for-election.md) |
| **Epoch regressed** | epoch bump in `Acceptor`/`QuorumConsensusStore`; reconfig high-water `ReconfigSimCluster.stateTransfer` | `currentLeaderEpoch` assert in [`FaultInjectionTest`](../prism-sim/src/test/java/io/scalecube/prism/sim/FaultInjectionTest.java); ADR [`0015`](decisions/0015-self-electing-quorum.md) §13.2 |
| **Lease not released on resign** | `LeaseElector.resign`/`releaseIfHeld` (releases as an *already-expired* lease — the quorum store has no delete) — [`LeaseElector.java`](../prism-elector/src/main/java/io/scalecube/prism/elector/impl/LeaseElector.java) | [`LeaseElectorQuorumReleaseTest`](../prism-elector/src/test/java/io/scalecube/prism/elector/impl/LeaseElectorQuorumReleaseTest.java) |
| **Registry entry stale / not converging** | `RegistryStore` + Merkle anti-entropy — [`RegistryStore.java`](../prism-registry/src/main/java/io/scalecube/prism/registry/impl/RegistryStore.java), [`MerkleTree.java`](../prism-registry/src/main/java/io/scalecube/prism/registry/impl/MerkleTree.java) | [`anti-entropy-merkle.md`](anti-entropy-merkle.md), [`crdt-hlc.md`](crdt-hlc.md); ADR [`0003`](decisions/0003-single-writer-lww-versioning.md); watch `prism.registry.ae.*` |
| **Config change didn't propagate** | single-member reconfig — `ReconfigSimCluster.reconfigureSingleMember` + `stateTransfer` | [`self-electing-quorum.md`](self-electing-quorum.md); ADR [`0015`](decisions/0015-self-electing-quorum.md); [`ReconfigurationSafetyFuzzTest`](../prism-sim/src/test/java/io/scalecube/prism/sim/ReconfigurationSafetyFuzzTest.java) |
| **Versions regress after restart** | durability / stable id | [`troubleshooting.md`](troubleshooting.md); ADR [`0003`](decisions/0003-single-writer-lww-versioning.md) |

---

## 5. Reading the system live

In a real cluster you cannot read the god-view, so watch the emitted surface. The metric SPI is
[`Metrics`](../prism-api/src/main/java/io/scalecube/prism/metrics/Metrics.java) (NOOP by default;
[`InMemoryMetrics`](../prism-observability/src/main/java/io/scalecube/prism/observability/InMemoryMetrics.java)
for tests/introspection; Micrometer/OTel via an adapter — ADR
[`0014`](decisions/0014-observability-metrics-spi.md)). The names emitted today
([`config-reference.md`](config-reference.md) §10):

| Signal | Maps to protocol step | Watch for |
|---|---|---|
| `prism.elector.granted` | this node became active for a group (`LeaseElector` recorded leadership) | a leadership change; pairs with a peer's `revoked` |
| `prism.elector.revoked` | this node lost/released leadership | rapid `granted`/`revoked` churn = **flapping** (tighten `leaseTtl`/`tickInterval`) |
| `prism.registry.ae.beacon` | a Merkle-root anti-entropy beacon was sent | should be steady; silence = AE not running |
| `prism.registry.ae.readvertise` | this node re-advertised its slice to heal a divergence | a spike = nodes were diverging and self-healing |
| `prism.registry.event.{registered,updated,deregistered,expired}` | a registry `RegistryEvent` was emitted | `expired` spikes = TTLs lapsing (providers not renewing) |

The grant/revoke counter is emitted from `LeaseElector` (`metrics.increment(active ?
"prism.elector.granted" : "prism.elector.revoked")`). Two nodes reporting `granted` for the same group
with no intervening `revoked` is the live smell of the split-brain the sim forbids — confirm with the
oracle by reproducing the schedule in the sim. Alert thresholds: [`ops/runbook.md`](ops/runbook.md).

---

## 6. Adding a regression test

prism has no `.feature` files — tests are JUnit 5 with **Given/When/Then in the Javadoc** (BDD-style);
follow that style. Two ways to capture an incident:

1. **Seeded sim test (preferred for consensus/election bugs).** Add a `@Test` to the matching fuzz
   class in [`../prism-sim/src/test/java/io/scalecube/prism/sim/`](../prism-sim/src/test/java/io/scalecube/prism/sim/)
   that hard-codes the captured seed and asserts the oracle (`trueLeaders`/`currentLeaderEpoch`) at
   every step — copy the body of [`ElectorSafetyFuzzTest`](../prism-sim/src/test/java/io/scalecube/prism/sim/ElectorSafetyFuzzTest.java)
   or [`FaultInjectionTest`](../prism-sim/src/test/java/io/scalecube/prism/sim/FaultInjectionTest.java).
   For dynamic membership or a constructed split-brain, use `ReconfigSimCluster`'s `forceConfig` +
   `grantRaw` hooks the way `multiMemberJumpCanSplitBrain_oracleDetectsIt` does in
   [`ReconfigurationSafetyFuzzTest`](../prism-sim/src/test/java/io/scalecube/prism/sim/ReconfigurationSafetyFuzzTest.java).
2. **Real-transport / behavior test.** For elector-over-network or registry/runtime behavior, mirror
   [`LeaseElectorQuorumReleaseTest`](../prism-elector/src/test/java/io/scalecube/prism/elector/impl/LeaseElectorQuorumReleaseTest.java)
   or the e2e tests under `prism-runtime` (e.g.
   [`UseCaseE2eTest`](../prism-runtime/src/test/java/io/scalecube/prism/runtime/e2e/UseCaseE2eTest.java),
   which asserts `prism.elector.granted` via `InMemoryMetrics`).

Keep the seed in the test name/comment so the next person can replay it instantly.

---

## 7. Escalation / first principles

When the symptom doesn't match any row and you need to recover the *intended* invariant, climb the
ladder of evidence:

1. **Explainer** — what the protocol is supposed to do: [`paxos.md`](paxos.md) (lease/Accept rule),
   [`self-electing-quorum.md`](self-electing-quorum.md) (dynamic membership),
   [`anti-entropy-merkle.md`](anti-entropy-merkle.md) + [`crdt-hlc.md`](crdt-hlc.md) (registry
   convergence), and [`guarantees.md`](guarantees.md) (what is and isn't promised).
2. **ADR** — *why* it is that way: [`decisions/README.md`](decisions/README.md), then the specific one
   (election: [`0006`](decisions/0006-consensus-not-gossip-for-election.md),
   [`0012`](decisions/0012-distributed-quorum-lease-elector.md); reconfig:
   [`0015`](decisions/0015-self-electing-quorum.md); verification:
   [`0013`](decisions/0013-formal-verification-and-dst.md)).
3. **Spec** — the machine-checked source of truth: [`spec/LeaseElection.tla`](spec/LeaseElection.tla)
   and [`spec/SelfElectingQuorum.tla`](spec/SelfElectingQuorum.tla), with recorded TLC results and the
   line-for-line model↔code map in [`spec/README.md`](spec/README.md). If TLC and the code disagree,
   one of them has the bug — that is the whole point of keeping both.

**CI runs all of this on every push and PR** ([`../.github/workflows/ci.yml`](../.github/workflows/ci.yml)):
the `build` job runs `mvn -B -ntp verify` (the full DST suite is the hard gate), and the `spec` job
runs TLC over the safe configs plus the inverted unsafe negative control. So a regression you add as a
seeded test stays caught.
