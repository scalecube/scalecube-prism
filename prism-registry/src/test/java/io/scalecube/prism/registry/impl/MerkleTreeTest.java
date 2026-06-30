package io.scalecube.prism.registry.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Merkle anti-entropy: efficient replica reconciliation")
class MerkleTreeTest {

  private static final int DEPTH = 8; // 256 buckets

  private static Map<String, Long> catalog(int n) {
    Map<String, Long> m = new LinkedHashMap<>();
    for (int i = 0; i < n; i++) {
      m.put("svc-" + i, (long) i);
    }
    return m;
  }

  /**
   * Given two replicas with identical content,
   * When their Merkle trees are compared,
   * Then the roots are equal and there are no differing buckets.
   */
  @Test
  void identicalReplicasHaveNoDiff() {
    MerkleTree a = new MerkleTree(DEPTH, catalog(1000));
    MerkleTree b = new MerkleTree(DEPTH, catalog(1000));

    assertEquals(a.rootHash(), b.rootHash());
    assertTrue(a.diff(b).isEmpty());
  }

  /**
   * Given the same content inserted in different orders,
   * When trees are built,
   * Then the root hash is identical (order-independent, set semantics).
   */
  @Test
  void rootHashIsOrderIndependent() {
    Map<String, Long> forward = new LinkedHashMap<>();
    Map<String, Long> reverse = new LinkedHashMap<>();
    for (int i = 0; i < 500; i++) {
      forward.put("svc-" + i, (long) i);
    }
    for (int i = 499; i >= 0; i--) {
      reverse.put("svc-" + i, (long) i);
    }

    assertEquals(
        new MerkleTree(DEPTH, forward).rootHash(), new MerkleTree(DEPTH, reverse).rootHash());
  }

  /**
   * Given two replicas differing in exactly one entry's value (a version bump),
   * When compared,
   * Then the diff localizes the change to the single bucket containing that key (sub-linear).
   */
  @Test
  void singleChangedValueIsLocalizedToItsBucket() {
    Map<String, Long> base = catalog(1000);
    Map<String, Long> changed = new LinkedHashMap<>(base);
    changed.put("svc-417", 999_999L); // version bump for one service

    Set<Integer> diff = new MerkleTree(DEPTH, base).diff(new MerkleTree(DEPTH, changed));

    assertFalse(diff.isEmpty());
    assertTrue(diff.contains(MerkleTree.bucketOf("svc-417", DEPTH)));
    assertTrue(diff.size() <= 1, "a single change must localize to one bucket");
  }

  /**
   * Given a replica missing one entry the other has,
   * When compared,
   * Then the diff includes the bucket of the missing key.
   */
  @Test
  void missingEntryIsDetected() {
    Map<String, Long> full = catalog(1000);
    Map<String, Long> missing = new LinkedHashMap<>(full);
    missing.remove("svc-123");

    Set<Integer> diff = new MerkleTree(DEPTH, full).diff(new MerkleTree(DEPTH, missing));

    assertTrue(diff.contains(MerkleTree.bucketOf("svc-123", DEPTH)));
  }

  /**
   * Given an empty replica and a populated one,
   * When compared,
   * Then many buckets differ (a full reconciliation is required).
   */
  @Test
  void emptyVersusPopulatedDiffersBroadly() {
    Set<Integer> diff =
        new MerkleTree(DEPTH, Map.of()).diff(new MerkleTree(DEPTH, catalog(1000)));
    assertTrue(diff.size() > 100, "empty vs full should differ in many buckets");
  }
}
