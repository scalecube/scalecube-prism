# prism-bench

A lightweight throughput/latency harness for prism's core algorithms. Indicative numbers (warmed
`System.nanoTime` loops) — not JMH-grade, but enough to show the hot paths are not a bottleneck and
that the Merkle diff is sub-linear.

## Run
```
mvn -q -pl prism-bench -am compile \
  exec:java -Dexec.mainClass=io.scalecube.prism.bench.Benchmarks
```

## Sample results
Single run on a developer laptop (JDK 17). Your numbers will vary; the point is the order of
magnitude and the Merkle scaling.

| Algorithm | Result |
|-----------|--------|
| `HybridLogicalClock.now()` | ~44.5M ops/s (~22 ns/op) |
| `RegistryStore.apply()` | ~5.4M ops/s (~184 ns/op) |
| `Acceptor.handle(ACCEPT)` | ~27.8M ops/s (~36 ns/op) |
| `MerkleTree` build (100k entries) | ~14 ms |
| `MerkleTree.diff()` (1 changed key in 100k) | **~0.16 µs** |

The headline is the last row: localizing a single change among 100k entries in well under a
microsecond confirms the range-based Merkle tree's O(depth) diff — anti-entropy reconciles by
comparing roots and descending only differing branches, never scanning the catalog.

> These hot paths run far faster than the protocol cadence (gossip ~200 ms, lease TTL ~seconds), so
> versioning/merge/consensus-accept are never the limiting factor; network round-trips dominate.
