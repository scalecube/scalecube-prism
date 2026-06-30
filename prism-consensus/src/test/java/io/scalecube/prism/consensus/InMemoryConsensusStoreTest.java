package io.scalecube.prism.consensus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("In-memory consensus store: compare-and-set semantics")
class InMemoryConsensusStoreTest {

  private final InMemoryConsensusStore store = new InMemoryConsensusStore();

  /**
   * Given an absent key,
   * When two proposers both compare-and-set from absent,
   * Then only the first succeeds.
   */
  @Test
  void setsFromAbsentOnlyOnce() {
    LeaseRecord a = new LeaseRecord("g", "A", 1, 1000);
    LeaseRecord b = new LeaseRecord("g", "B", 1, 1000);

    assertTrue(store.compareAndSet("g", null, a));
    assertFalse(store.compareAndSet("g", null, b));
    assertEquals("A", store.get("g").orElseThrow().owner());
  }

  /**
   * Given a stored value,
   * When compare-and-set is called with a non-matching expected,
   * Then it fails; with the matching expected it succeeds.
   */
  @Test
  void failsOnExpectedMismatch() {
    LeaseRecord a = new LeaseRecord("g", "A", 1, 1000);
    LeaseRecord a2 = new LeaseRecord("g", "A", 1, 2000);
    store.compareAndSet("g", null, a);

    LeaseRecord stale = new LeaseRecord("g", "A", 1, 999);
    assertFalse(store.compareAndSet("g", stale, a2));
    assertTrue(store.compareAndSet("g", a, a2));
  }

  /**
   * Given a stored value,
   * When compare-and-set is called with a null next,
   * Then the entry is cleared.
   */
  @Test
  void clearsWithNullNext() {
    LeaseRecord a = new LeaseRecord("g", "A", 1, 1000);
    store.compareAndSet("g", null, a);

    assertTrue(store.compareAndSet("g", a, null));
    assertEquals(Optional.empty(), store.get("g"));
  }
}
