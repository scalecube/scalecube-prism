# 0011 — Public API shape (entry point, watch, default tier)

Status: **Accepted** (rationale expanded to research grade; decision unchanged)

## Context
We designed the public API **example-first** (`prism-examples`): write the end-user usage we want,
then shape the API to it. Three choices needed locking: the entry point, `watch()` semantics, and the
default consistency tier.

## Principles applied
- **Bloch's API design maxims:** an API should be easy to use correctly and hard to use incorrectly;
  when in doubt, leave it out; minimize conceptual surface. We expose **one** new top-level type
  (`Prism`); everything else is `ServiceRegistry`/`SingletonElector`.
- **Decorator pattern** (GoF): `Prism` decorates a scalecube `Cluster`, adding capability without
  subclassing or owning the cluster's identity — congruent with "layer, don't fork" (ADR-0001).
- **Principle of least astonishment** and **Hyrum's Law:** observable behavior becomes a contract, so
  the consumer-facing semantics (especially consistency) must be *stated*, not incidental.
- **Session guarantees** (Terry et al., 1994) give the precise vocabulary for the consumer contract.

## Decision
1. **Entry point — decorator over `Cluster`.** `new PrismImpl(cluster).startAwait()` (a build-time
   handler is required because scalecube delivers gossip/membership through a handler installed before
   start). prism does not own the cluster's lifecycle unless it started it.
2. **`watch()` — snapshot-then-stream.** A new subscriber first receives the current catalog as
   `REGISTERED` events, then live changes — so a late subscriber builds a complete view from a single
   subscription (a *monotonic-reads* session guarantee on the local replica).
3. **Registration tier — default `CAUSAL`, override allowed.** Safe property-versioning by default;
   strong consistency is explicit per key (ADR-0002).
4. **Reactive, with blocking convenience.** Mutating ops return `Mono<Void>`; `*Await()` variants
   block (matching `Cluster#startAwait`).

## The consistency contract, stated in session-guarantee terms
For `EVENTUAL`/`CAUSAL` keys the registry provides, on a single client's local replica:
**Read-Your-Writes** (for keys it owns), **Monotonic Reads**, **Monotonic Writes**, and
**Writes-Follow-Reads** (via HLC causality) — i.e. *causal+* — but **not** linearizability. The
documented operational rule "a lookup is a hint; retry/fall back" is the honest consequence of an AP
read.

## Consequences
- `Prism` is the only new concept users learn.
- `PrismImpl` needs an aggregator module (`prism-runtime`) depending on registry + elector impls.
- `watch()` implementations must replay current state atomically before switching to live events.
- The default-tier overload is a `default` method on `ServiceRegistry` (no impl burden).

## References
1. Bloch. *How to Design a Good API and Why It Matters.* OOPSLA, 2006.
2. Gamma, Helm, Johnson, Vlissides. *Design Patterns* (Decorator). 1994.
3. Terry, Demers, Petersen, Spreitzer, Theimer, Welch. *Session Guarantees for Weakly Consistent
   Replicated Data.* PDIS, 1994.
4. Wright (Hyrum). *Hyrum's Law* — implicit dependence on observable behavior.
