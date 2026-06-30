/**
 * Deterministic consensus engine (layer L3).
 *
 * <p><b>Single-decree Paxos</b> — a PREPARE/promise round then ACCEPT, over a majority quorum of
 * {@link io.scalecube.prism.consensus.Acceptor}s ({@link
 * io.scalecube.prism.consensus.QuorumConsensusStore}) — on a single-threaded, allocation-tight
 * path, <b>not</b> reactive, behind an interface a leaderless engine (EPaxos) could slot in later.
 * scalecube SWIM/Lifeguard feeds failure detection; reactive cluster events are adapted to the
 * deterministic core only at the module boundary. The quorum can self-elect/heal its own membership
 * by single-member reconfiguration ({@link io.scalecube.prism.consensus.ReconfigurationManager},
 * ADR-0015), shipped opt-in.
 *
 * <p>Governed by ADR-0004 (deterministic boundary), ADR-0007 (engine), ADR-0012 (quorum lease
 * + safety theorems), ADR-0015 (self-electing quorum). Formal models:
 * {@code prism-docs/spec/LeaseElection.tla} and {@code SelfElectingQuorum.tla}; guarantees:
 * {@code prism-docs/guarantees.md}.
 */
package io.scalecube.prism.consensus;
