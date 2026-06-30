# 0013 — Formal verification + deterministic simulation testing

Status: **Accepted** (rationale expanded to research grade; decision unchanged)

## Context
The elector is safety-critical. We commit to two **complementary** forms of evidence, kept in
lock-step with the code: a model-checked formal specification, and deterministic simulation testing of
the real implementation.

## Theory: complementary coverage of the execution space
No single method is sufficient:

- **Model checking** (TLA+/TLC; Lamport) is **exhaustive but bounded** — it explores *every*
  interleaving up to a finite configuration (small N, bounded epochs), catching subtle ordering bugs
  that escape tests. Its blind spot is the *model–code gap*: it verifies the spec, not the bytes.
- **Deterministic Simulation Testing** (ADR-0010) runs the **real code** over deep, adversarial,
  fault-injecting schedules, **reproducibly** from a seed — **unbounded depth but sampled**. Its blind
  spot is non-exhaustiveness.

Together they cover each other's blind spots: TLC proves the *protocol* correct; DST checks the
*implementation* realizes it under faults. This is precisely the methodology Amazon documented for its
core services ("How Amazon Web Services Uses Formal Methods", Newcombe et al., CACM 2015), and that
MongoDB, Azure (Cosmos), and TiDB apply to their replication protocols.

## Decision
1. **TLA+ specifications**, model-checked by TLC:
   - `LeaseElection.tla` — static quorum lease: `AtMostOneLeader`, `AgreementPerEpoch`.
   - `SelfElectingQuorum.tla` — dynamic membership (ADR-0015); a **safe** config proves the invariant
     and an **unsafe** config returns a counterexample, demonstrating the single-member rule is
     necessary.
   The `Acceptor.handle` rule is the spec's `Accept` action verbatim (model↔code correspondence is
   documented in `prism-docs/spec/README.md`).
2. **Deterministic seeded fuzzer** (`prism-sim`): virtual clock + seeded RNG drive partitions, clock
   skew, message loss, re-campaigns; a **god-view oracle** checks `AtMostOneLeader` and fencing-epoch
   monotonicity every step across hundreds of seeds.

## Evidence that this pays off
Formalising `Accept` **revealed a real defect**: the original rule allowed a higher epoch to **preempt
a still-valid lease** (a stickiness hazard). The spec forced the precise rule (a different owner may
take over only when the lease is expired *and* the epoch is strictly higher), and the code was
tightened to match — a bug class removed before it shipped. This is the concrete return on formal
methods, not a hypothetical.

## Consequences
- Spec↔code correspondence must be maintained: changing the acceptor rule requires updating the spec
  and re-checking (CI runs the fuzzer; a `spec` job runs TLC).
- DST is scoped to the safety kernel today; extending to the registry (convergence/monotonicity,
  already a property test) and to state-losing crashes (needs durable acceptors — done, ADR/persistence)
  broadens coverage.
- Bounds honesty: TLC results hold within the configured constants; DST is sampled. Neither is a proof
  for unbounded N — but the combination is far stronger than tests alone, and matches industry best
  practice for this class of system.

## References
1. Newcombe, Rath, Zhang, Munteanu, Brooker, Deardeuff. *How Amazon Web Services Uses Formal Methods.*
   CACM, 2015.
2. Lamport. *Specifying Systems: The TLA+ Language and Tools.* 2002.
3. Konnov, Kukovec, et al. *Apalache* (symbolic model checking for TLA+) — a path beyond TLC's
   explicit-state bounds.
4. FoundationDB / TigerBeetle DST; Kingsbury, *Jepsen*.
