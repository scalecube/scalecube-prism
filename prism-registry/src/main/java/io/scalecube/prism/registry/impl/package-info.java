/**
 * Implementation of {@link io.scalecube.prism.registry.ServiceRegistry} (Phase 2, layers L1+L2).
 *
 * <p>Per-owner, single-writer, per-key versioned CRDT property map disseminated over the
 * scalecube-cluster gossip protocol with delta + Merkle anti-entropy. Entry lifecycle is bound to
 * membership: auto-purge on {@code DEAD}, versioned tombstone + GC for live deregistration. Houses
 * the consistency router that dispatches reads/writes per {@link
 * io.scalecube.prism.registry.ConsistencyTier}.
 *
 * <p>Governed by ADR-0002 (per-key tunable consistency), ADR-0003 (single-writer + HLC + LWW =
 * strong eventual consistency), ADR-0005 (membership-as-tombstone). Guarantees:
 * {@code prism-docs/guarantees.md}.
 */
package io.scalecube.prism.registry.impl;
