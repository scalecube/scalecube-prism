package io.scalecube.prism.registry.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Anti-entropy digest codec: sparse round-trip + diff reconstruction")
class MerkleDigestCodecTest {

  private static final int DEPTH = 8;

  @Test
  void roundTripReconstructsTheSameTree() {
    Map<String, Long> catalog = new HashMap<>();
    for (int i = 0; i < 50; i++) {
      catalog.put("svc-" + i, (long) (i * 1_000_003L + 7));
    }
    MerkleTree tree = new MerkleTree(DEPTH, catalog);

    long[] decoded = MerkleDigestCodec.decode(MerkleDigestCodec.encode(tree));

    assertArrayEquals(tree.bucketHashes(), decoded, "leaf vector did not survive the round-trip");
    MerkleTree rebuilt = MerkleTree.fromBucketHashes(DEPTH, decoded);
    assertEquals(tree.rootHash(), rebuilt.rootHash());
    assertTrue(tree.diff(rebuilt).isEmpty(), "reconstructed tree must diff-equal the original");
  }

  @Test
  void sparseEncodingIsProportionalToNonEmptyBuckets() {
    MerkleTree few = new MerkleTree(DEPTH, Map.of("a", 1L, "b", 2L, "c", 3L));
    MerkleTree empty = new MerkleTree(DEPTH, Map.of());

    // A 3-entry catalog must encode far smaller than the full 256-bucket × 8-byte vector (2 KiB).
    assertTrue(MerkleDigestCodec.encode(few).length < 256, "sparse digest should be compact");
    assertTrue(MerkleDigestCodec.encode(empty).length < 32, "empty digest should be tiny");
  }

  @Test
  void unrecognisedPayloadDecodesToNull() {
    assertNull(MerkleDigestCodec.decode(new byte[] {9, 9, 9, 9}), "foreign payload must be null");
    assertNull(MerkleDigestCodec.decode(new byte[0]), "empty payload must be null");
  }
}
