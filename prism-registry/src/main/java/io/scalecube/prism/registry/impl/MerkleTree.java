package io.scalecube.prism.registry.impl;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A range-based Merkle tree over a key→content-hash map, for efficient anti-entropy reconciliation
 * (the technique Dynamo/Cassandra use for read-repair).
 *
 * <p>Keys are bucketed into {@code 2^depth} fixed ranges by a hash of the key, so two trees over
 * <em>different</em> key sets still share the same shape and can be compared positionally. Each
 * leaf is the order-independent XOR of its entries' hashes; internal nodes hash their children.
 * Comparing two roots is O(1); when they differ, {@link #diff} descends only the differing
 * branches, so finding the changed buckets costs O(d · k) for k differing buckets rather than
 * scanning the whole catalog.
 *
 * <p>The caller resends only the entries in the differing buckets — turning full-state sync into a
 * delta exchange. Immutable once built.
 */
public final class MerkleTree { // public: a reusable anti-entropy algorithm

  private final int depth;
  private final int buckets;
  private final long[] node; // heap layout: node[1]=root, leaves at [buckets, 2*buckets)

  /**
   * Builds a tree of {@code 2^depth} buckets over the given key→content-hash entries.
   *
   * @param depth tree depth (bucket count is {@code 2^depth})
   * @param entries key → content hash (e.g. a hash of version + tombstone flag)
   */
  public MerkleTree(int depth, Map<String, Long> entries) {
    this.depth = depth;
    this.buckets = 1 << depth;
    this.node = new long[2 * buckets];
    for (Map.Entry<String, Long> e : entries.entrySet()) {
      int b = bucketOf(e.getKey(), depth);
      node[buckets + b] ^= mix(((long) e.getKey().hashCode() << 32) ^ e.getValue());
    }
    linkInternalNodes();
  }

  private MerkleTree(int depth, long[] leaves) {
    this.depth = depth;
    this.buckets = 1 << depth;
    this.node = new long[2 * buckets];
    System.arraycopy(leaves, 0, node, buckets, buckets);
    linkInternalNodes();
  }

  /** Hashes each internal node from its two children, bottom-up (root ends at {@code node[1]}). */
  private void linkInternalNodes() {
    for (int i = buckets - 1; i >= 1; i--) {
      node[i] = mix(node[2 * i] * 0x9E3779B97F4A7C15L + node[2 * i + 1]);
    }
  }

  /**
   * Reconstructs a tree from the per-bucket leaf hashes carried in an anti-entropy beacon (see
   * {@link #bucketHashes()}), recomputing the internal nodes so {@link #diff} can run against a
   * locally-built tree.
   *
   * @param depth tree depth (bucket count is {@code 2^depth})
   * @param leaves the {@code 2^depth} per-bucket leaf hashes
   * @return a tree with exactly those leaves
   */
  public static MerkleTree fromBucketHashes(int depth, long[] leaves) {
    if (leaves.length != (1 << depth)) {
      throw new IllegalArgumentException(
          "expected " + (1 << depth) + " leaves, got " + leaves.length);
    }
    return new MerkleTree(depth, leaves);
  }

  /**
   * The root hash; equal roots mean equal state (up to hash collisions).
   *
   * @return the root hash
   */
  public long rootHash() {
    return node[1];
  }

  /**
   * The per-bucket leaf hashes (an empty bucket is {@code 0}). Carried in an anti-entropy beacon so
   * a peer can {@link #fromBucketHashes reconstruct} the tree and compute the differing buckets.
   *
   * @return a copy of the {@code 2^depth} leaf hashes, indexed by bucket
   */
  public long[] bucketHashes() {
    final long[] leaves = new long[buckets];
    System.arraycopy(node, buckets, leaves, 0, buckets);
    return leaves;
  }

  /**
   * Tree depth (bucket count is {@code 2^depth}).
   *
   * @return the depth
   */
  public int depth() {
    return depth;
  }

  /**
   * Returns the bucket indices whose contents differ from {@code other} — the buckets whose entries
   * must be exchanged to reconcile.
   *
   * @param other a tree of the same depth
   * @return differing bucket indices
   */
  public Set<Integer> diff(MerkleTree other) {
    if (other.depth != depth) {
      throw new IllegalArgumentException("depth mismatch");
    }
    Set<Integer> out = new TreeSet<>();
    descend(1, other, out);
    return out;
  }

  private void descend(int i, MerkleTree other, Set<Integer> out) {
    if (node[i] == other.node[i]) {
      return; // subtree identical — prune
    }
    if (i >= buckets) {
      out.add(i - buckets); // differing leaf
      return;
    }
    descend(2 * i, other, out);
    descend(2 * i + 1, other, out);
  }

  /**
   * The bucket a key maps to.
   *
   * @param key the key
   * @param depth tree depth
   * @return bucket index in {@code [0, 2^depth)}
   */
  public static int bucketOf(String key, int depth) {
    return (int) (mix(key.hashCode()) & ((1L << depth) - 1));
  }

  // SplitMix64 finalizer — strong avalanche so small input changes flip the hash.
  private static long mix(long z) {
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }
}
