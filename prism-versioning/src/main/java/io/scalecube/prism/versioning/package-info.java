/**
 * Versioning primitives: Hybrid Logical Clock and Interval Tree Clock implementations of {@link
 * io.scalecube.prism.version.Version}, plus freshness-token construction. Restart-safe, causality-
 * respecting, no synchronized clocks required.
 *
 * <p>Governed by ADR-0003 (single-writer + HLC + LWW).
 * Guarantees: {@code prism-docs/guarantees.md}.
 */
package io.scalecube.prism.versioning;
