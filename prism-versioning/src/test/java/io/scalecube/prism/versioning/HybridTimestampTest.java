package io.scalecube.prism.versioning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Hybrid timestamp: ordering & value equality")
class HybridTimestampTest {

  /**
   * Given timestamps differing in physical and logical parts,
   * When compared,
   * Then ordering is by physical first, then logical.
   */
  @Test
  void ordersByPhysicalThenLogical() {
    HybridTimestamp a = new HybridTimestamp(100, 5);
    HybridTimestamp b = new HybridTimestamp(100, 6);
    HybridTimestamp c = new HybridTimestamp(101, 0);

    assertTrue(a.compareTo(b) < 0);
    assertTrue(b.compareTo(c) < 0);
    assertTrue(c.compareTo(a) > 0);
    assertEquals(0, a.compareTo(new HybridTimestamp(100, 5)));
  }

  /**
   * Given two timestamps with the same components,
   * When compared for equality,
   * Then they are equal with equal hash codes; differing ones are not equal.
   */
  @Test
  void equalsAndHashCodeByValue() {
    HybridTimestamp a = new HybridTimestamp(100, 5);
    HybridTimestamp b = new HybridTimestamp(100, 5);

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertNotEquals(a, new HybridTimestamp(100, 6));
    assertNotEquals(a, new HybridTimestamp(101, 5));
  }
}
