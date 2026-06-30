# 0010 — Build the deterministic simulator before consensus/elector

Status: **Accepted** (rationale expanded to research grade; decision unchanged)

## Context
The consensus/elector layer is safety-critical ("never two leaders"). The question is *when* to build
the test infrastructure relative to the code it validates.

## Theory: you cannot sample your way to safety, post hoc
Safety properties are **universally quantified over executions** ("for all interleavings of crashes,
partitions, reordering, clock skew, no two leaders"). Ordinary unit/integration tests **sample** a
vanishingly small subset of that space and are non-reproducible when concurrency is involved, so they
cannot establish a safety property — they can only refute it occasionally and flakily. The accepted
techniques are:

- **Deterministic Simulation Testing (DST):** run the whole system on a **virtual clock + seeded RNG**
  controlling time and the network, so a long, fault-injecting execution is **reproducible bit-for-bit
  from a seed** (FoundationDB; TigerBeetle). This explores deep, adversarial schedules and *shrinks*
  to a minimal failing seed.
- **Property-based testing** (Claessen & Hughes, QuickCheck): assert invariants over generated inputs
  rather than fixed cases.
- **Fault injection** (Jepsen; Lineage-Driven Fault Injection, Alvaro et al.): partitions, clock skew,
  message loss as first-class test operators.
- **Model checking** (ADR-0013): exhaustive but bounded; *complementary* to DST's unbounded-depth
  sampling on the real code.

The decisive observation: **all of these are infrastructure that must exist before the safety-critical
code can be trusted.** Writing Raft/elector first and "adding tests later" means writing the most
dangerous code in the project with no way to reproduce the adversarial schedules that break it. The
dependency is therefore: harness first.

## Decision
Build `prism-sim` (Phase 1) — a deterministic, seeded, fault-injecting discrete-event simulator over
`NetworkEmulator` — **before** `prism-consensus`/`prism-elector` (Phases 3–4). Every safety-critical
phase ships with property tests that run inside it (god-view oracle: never-two-leaders, fencing
monotonicity; across hundreds of seeds; with message loss + partitions + clock skew).

## Consequences
- Phase 1 is a hard gate on all safety-critical work.
- Failing seeds become permanent regression cases (save the seed, not a flaky timing).
- The simulator is itself reusable (a deterministic SWIM/consensus sim) and may graduate to its own
  module/repo.
- Scope honesty: DST samples deep but is not exhaustive; it pairs with model checking (ADR-0013),
  which is exhaustive but bounded. Neither alone suffices; together they are strong evidence.

## References
1. Claessen & Hughes. *QuickCheck: A Lightweight Tool for Random Testing of Haskell Programs.* ICFP,
   2000.
2. FoundationDB — deterministic simulation testing (talks/engineering reports); TigerBeetle VOPR.
3. Kingsbury. *Jepsen* — partition/fault-injection testing of distributed systems.
4. Alvaro, Rosen, Hellerstein. *Lineage-Driven Fault Injection.* SIGMOD, 2015.
