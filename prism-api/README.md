# prism-api

**Layer:** L1–L4 (public surface) · **Status:** interfaces defined, no implementation

## What it does
Defines the entire public contract of scalecube-prism — the interfaces and DTOs that every other
module implements or consumes. No logic lives here, only types. This is the analogue of
`scalecube-cluster-api`.

## Goal
Give consumers (and the impl modules) a single, stable dependency that describes *what* prism offers
without coupling them to *how* it works. Implementations can change freely behind these types.

## What's inside
| Type | Purpose |
|------|---------|
| `registry.ServiceRegistry` | Register / update / deregister / lookup / watch services |
| `registry.ServiceEntry` | Immutable snapshot of one advertised service (owner, properties, version, liveness) |
| `registry.RegistryEvent` | Change notification: `REGISTERED` / `UPDATED` / `DEREGISTERED` / `EXPIRED` |
| `registry.ConsistencyTier` | Per-key consistency level: `EVENTUAL` → `CAUSAL` → `QUORUM` → `CONSENSUS` |
| `elector.SingletonElector` | Safe singleton election: campaign / resign / leadership stream |
| `elector.Leadership` | A grant/revocation carrying the fencing `epoch` |
| `version.Version` | Monotonic, restart-safe HLC version stamp |
| `version.FreshnessToken` | Quantified staleness handle for reads |

## How it's meant to be used
- Application code depends on `prism-api` + a runtime module (`prism-registry`, `prism-elector`).
- The contracts encode the **consistency promises** in their javadoc — read them. In particular:
  - the registry is **complete, convergent, monotonic-per-key — not linearizable** (the "registry is a hint" contract), and
  - the elector guarantees **at most one Active per group, ever**, via consensus + fencing.

## Important
- Reactor types (`Mono`/`Flux`) and `io.scalecube.cluster.Member` are used in signatures; they arrive
  transitively via `scalecube-cluster-api`.
- Keep this module **pure interfaces/DTOs** — if you find yourself adding behavior, it belongs in an
  impl module.
- API packages (`io.scalecube.prism.registry`, `.elector`, `.version`) are deliberately separate from
  the impl packages (`.registry.impl`, `.elector.impl`) to avoid split packages.

## Depends on
`scalecube-cluster-api`
