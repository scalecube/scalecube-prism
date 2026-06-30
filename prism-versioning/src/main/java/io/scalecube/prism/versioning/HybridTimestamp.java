package io.scalecube.prism.versioning;

import io.scalecube.prism.version.Version;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Immutable Hybrid Logical Clock timestamp: a physical component {@code l} (millis) fused with a
 * logical counter {@code c}. Ordered lexicographically by {@code (physical, logical)} — greater
 * means newer — which gives a total order suitable for last-writer-wins.
 *
 * @see HybridLogicalClock
 */
public final class HybridTimestamp implements Version {

  private final long physical;
  private final long logical;

  /**
   * Creates a timestamp.
   *
   * @param physical physical component (millis)
   * @param logical logical counter
   */
  public HybridTimestamp(long physical, long logical) {
    this.physical = physical;
    this.logical = logical;
  }

  @Override
  public long logical() {
    return logical;
  }

  @Override
  public long physical() {
    return physical;
  }

  @Override
  public int compareTo(Version other) {
    int byPhysical = Long.compare(physical, other.physical());
    return byPhysical != 0 ? byPhysical : Long.compare(logical, other.logical());
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    HybridTimestamp that = (HybridTimestamp) o;
    return physical == that.physical && logical == that.logical;
  }

  @Override
  public int hashCode() {
    return Objects.hash(physical, logical);
  }

  @Override
  public String toString() {
    return new StringJoiner(",", "HLC(", ")")
        .add(Long.toString(physical))
        .add(Long.toString(logical))
        .toString();
  }
}
