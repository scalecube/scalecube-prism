# prism-docs

The home for scalecube-prism's design knowledge. **Documentation only** — this module is
`pom`-packaged and produces no jar; it exists so the project's reasoning lives *with* the code and
versions alongside it.

## Contents

| Doc | What it answers |
|-----|-----------------|
| [`getting-started.md`](getting-started.md) | Zero to a working registry + elector in minutes |
| [`use-cases.md`](use-cases.md) | Business use cases as user stories → feature → validating test (full coverage) |
| [`user-guide.md`](user-guide.md) | Feature-by-feature tour, each backed by a runnable example |
| [`architecture.md`](architecture.md) | What the system is: layers, modules, data flow, formal foundations |
| **Building-block explainers** | One page per approach prism uses — see the grouped list below |
| [`guarantees.md`](guarantees.md) | The authoritative contract: safety/liveness/consistency claims + evidence |
| [`plan.md`](plan.md) | What we're building and in what order: the phased roadmap (overview) |
| [`phases/`](phases/README.md) | The **detailed** per-phase plan: work items, tests, definition-of-done |
| [`context.md`](context.md) | Why it looks like this: SWIM/gossip background, the 90s P2P lineage, how it compares to Consul / Eureka / Aeron |
| [`decisions/`](decisions/) | The binding choices, as Architecture Decision Records (ADRs) |
| [`spec/`](spec/) | The TLA+ formal specifications (lease elector + self-electing quorum) |
| [`ops/runbook.md`](ops/runbook.md) | Production operations: sizing, config, monitoring, failure modes |
| [`config-reference.md`](config-reference.md) | **Complete** reference: every config option, API method, tier, tunable, metric, on-disk file |
| [persistence.md](persistence.md) | Durability & operations: the journals, what's stored, growth, compaction risk |
| [`troubleshooting.md`](troubleshooting.md) | Symptom → diagnosis → fix |
| [`debugging.md`](debugging.md) | How to debug prism — reproduce any failure deterministically from a seed |
| [`concurrency.md`](concurrency.md) | Thread map, shared-state discipline, invariants (concurrency audit) |
| [`ownership.md`](ownership.md) | Module ownership / bus-factor map — who understands what, and what to study |
| [`../ONBOARDING.md`](../ONBOARDING.md) | The sequenced learning path: zero → can-change-it-safely, theory + code |

## Building-block explainers
prism isn't a new algorithm; it's a careful assembly of well-understood ones. Each page below
explains one approach the way [`paxos.md`](paxos.md) does — what it is, how it maps to the actual
code (with file links), and what's deliberately out of scope.

| Approach | Explainer |
|----------|-----------|
| Membership — SWIM + gossip (via scalecube-cluster) | [`gossip-swim.md`](gossip-swim.md) |
| Discovery — per-key LWW-map CRDT versioned by a Hybrid Logical Clock | [`crdt-hlc.md`](crdt-hlc.md) |
| Anti-entropy — Merkle-tree reconciliation | [`anti-entropy-merkle.md`](anti-entropy-merkle.md) |
| Leader election — single-decree Paxos + fenced lease | [`paxos.md`](paxos.md) |
| Tunable consistency — the per-key dial (eventual → causal → quorum → consensus) | [`tunable-consistency.md`](tunable-consistency.md) |
| Self-healing quorum — single-member reconfiguration through consensus | [`self-electing-quorum.md`](self-electing-quorum.md) |
| Security — the schema'd binary codec (no Java serialization) | [`codec-security.md`](codec-security.md) |
| Proof — TLA+ model checking + deterministic simulation testing | [`formal-verification-dst.md`](formal-verification-dst.md) |

## How to use these
- **New to the project?** Read `context.md` → `architecture.md` → `plan.md`, then skim the ADRs.
- **Proposing a change?** If it contradicts an ADR, write a new ADR that supersedes it — don't just
  change code. The ADRs are the contract for *why*.
- **Implementing a phase?** Each module's own `README.md` has the local detail; this folder has the
  cross-cutting picture.

## Conventions
- ADRs are numbered, append-only, and immutable once `Accepted`. To reverse one, add a new ADR with
  status `Supersedes NNNN`.
- Keep docs in Markdown, 100-column-friendly, no external assets.
