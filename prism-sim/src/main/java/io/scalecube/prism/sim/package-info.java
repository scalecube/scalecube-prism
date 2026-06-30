/**
 * Deterministic discrete-event simulator (Phase 1) built on scalecube's {@code NetworkEmulator}:
 * virtual clock + seeded RNG so large multi-node scenarios run fast and reproduce bit-for-bit.
 *
 * <p>Home of the safety/liveness property tests: membership convergence, no false-positive deaths,
 * metadata monotonicity, partition heal, and — critically — <em>never two Actives</em> for the
 * elector under partition and chaos.
 */
package io.scalecube.prism.sim;
