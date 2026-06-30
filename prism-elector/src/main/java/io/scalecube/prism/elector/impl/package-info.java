/**
 * Implementation of {@link io.scalecube.prism.elector.SingletonElector} (Phase 4, layer L4).
 *
 * <p>Consensus-granted lease + monotonic epoch + fencing tokens: at most one Active per group,
 * ever. SWIM {@code LEAVING}/{@code DEAD} events drive fast safe handoff; lease expiry is the
 * fallback for
 * ungraceful death. Fencing makes an early or mistaken failover harmless — a partitioned zombie
 * leader's stale-epoch actions are rejected downstream.
 *
 * <p>Governed by ADR-0006 (consensus, not gossip, for election) and ADR-0012 (quorum lease +
 * fencing theorems). Formal model: {@code prism-docs/spec/LeaseElection.tla}; guarantees:
 * {@code prism-docs/guarantees.md}.
 */
package io.scalecube.prism.elector.impl;
