package io.scalecube.prism.version;

/**
 * A monotonic, restart-safe version stamp for a single key, produced by its sole writer (the owning
 * member). Backed by a Hybrid Logical Clock: a logical counter correlated with physical time so
 * that
 * last-writer-wins is causally sound without synchronized clocks.
 *
 * <p>Comparison is total: greater means newer. Readers apply an update iff its version is strictly
 * greater than what they hold — which makes dissemination idempotent and reorder-safe.
 */
public interface Version extends Comparable<Version> {

  /** Logical component (counter). */
  long logical();

  /** Physical component (wall-clock millis at creation), for HLC ordering and observability. */
  long physical();
}
