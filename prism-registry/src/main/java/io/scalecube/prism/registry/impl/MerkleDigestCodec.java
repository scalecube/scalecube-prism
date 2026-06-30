package io.scalecube.prism.registry.impl;

import io.scalecube.prism.codec.WireReader;
import io.scalecube.prism.codec.WireWriter;

/**
 * Schema'd binary codec for the anti-entropy beacon: a {@link MerkleTree}'s per-bucket leaf hashes,
 * encoded <b>sparsely</b> (only the non-empty buckets), so a small catalog beacons a few bytes
 * while a large one stays bounded by {@code 2^depth} buckets. A peer decodes it back to the full
 * leaf
 * vector, rebuilds the tree via {@link MerkleTree#fromBucketHashes}, and exchanges only the
 * differing buckets. Like the rest of the wire (ADR-0009), never Java serialization.
 */
final class MerkleDigestCodec {

  private static final int VERSION = 1;
  private static final int MAX_DEPTH = 24; // refuse to allocate an implausibly large leaf vector

  private MerkleDigestCodec() {}

  /**
   * Encodes a tree's non-empty buckets: {@code version, depth, count, count×(index, hash)}.
   *
   * @param tree the local catalog tree
   * @return the beacon payload
   */
  static byte[] encode(MerkleTree tree) {
    final long[] leaves = tree.bucketHashes();
    int count = 0;
    for (long h : leaves) {
      if (h != 0L) {
        count++;
      }
    }
    final WireWriter w =
        new WireWriter().writeByte(VERSION).writeInt(tree.depth()).writeInt(count);
    for (int b = 0; b < leaves.length; b++) {
      if (leaves[b] != 0L) {
        w.writeInt(b).writeLong(leaves[b]);
      }
    }
    return w.toBytes();
  }

  /**
   * Decodes a beacon back to the full per-bucket leaf vector (empty buckets are {@code 0}).
   *
   * @param bytes the beacon payload
   * @return the {@code 2^depth} leaf hashes, or {@code null} if the version/format is unrecognised
   *     (e.g. an older root-only beacon) — the caller then falls back to a full re-advertise
   */
  static long[] decode(byte[] bytes) {
    try {
      final WireReader r = new WireReader(bytes);
      if (r.readByte() != VERSION) {
        return null;
      }
      final int depth = r.readInt();
      if (depth < 0 || depth > MAX_DEPTH) {
        return null;
      }
      final long[] leaves = new long[1 << depth];
      final int count = r.readInt();
      for (int i = 0; i < count; i++) {
        final int b = r.readInt();
        final long h = r.readLong();
        if (b >= 0 && b < leaves.length) {
          leaves[b] = h;
        }
      }
      return leaves;
    } catch (RuntimeException e) {
      return null; // malformed / truncated / foreign payload — treat as unrecognised
    }
  }
}
