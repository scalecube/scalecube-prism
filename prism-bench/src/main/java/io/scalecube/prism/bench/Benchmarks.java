package io.scalecube.prism.bench;

import io.scalecube.prism.consensus.Acceptor;
import io.scalecube.prism.consensus.LeaseRecord;
import io.scalecube.prism.consensus.LeaseRequest;
import io.scalecube.prism.registry.ConsistencyTier;
import io.scalecube.prism.registry.impl.MerkleTree;
import io.scalecube.prism.registry.impl.RegistryStore;
import io.scalecube.prism.registry.impl.ServiceEntryImpl;
import io.scalecube.prism.versioning.HybridLogicalClock;
import io.scalecube.prism.versioning.HybridTimestamp;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A lightweight throughput/latency harness for prism's core algorithms. Indicative numbers (warmed
 * {@code System.nanoTime} loops), not JMH-grade — run its {@code main} manually via the exec plugin
 * with {@code -Dexec.mainClass=io.scalecube.prism.bench.Benchmarks}.
 */
public final class Benchmarks {

  private Benchmarks() {}

  /**
   * Runs the benchmarks.
   *
   * @param args ignored
   */
  public static void main(String[] args) {
    hlc();
    registryApply();
    acceptor();
    merkle();
  }

  private static void hlc() {
    HybridLogicalClock clock = new HybridLogicalClock();
    int n = 5_000_000;
    for (int i = 0; i < n / 10; i++) {
      clock.now(); // warm-up
    }
    long start = System.nanoTime();
    for (int i = 0; i < n; i++) {
      clock.now();
    }
    report("HLC now()", n, System.nanoTime() - start);
  }

  private static void registryApply() {
    int n = 1_000_000;
    RegistryStore store = new RegistryStore();
    long start = System.nanoTime();
    for (int i = 0; i < n; i++) {
      store.apply(entry("svc-" + (i % 10_000), "owner", i), false);
    }
    report("RegistryStore apply()", n, System.nanoTime() - start);
  }

  private static void acceptor() {
    int n = 2_000_000;
    Acceptor acceptor = new Acceptor();
    long start = System.nanoTime();
    for (int i = 0; i < n; i++) {
      acceptor.handle(LeaseRequest.accept(new LeaseRecord("gw", "A", i, Long.MAX_VALUE)), 0);
    }
    report("Acceptor handle(ACCEPT)", n, System.nanoTime() - start);
  }

  private static void merkle() {
    int size = 100_000;
    Map<String, Long> base = new LinkedHashMap<>();
    for (int i = 0; i < size; i++) {
      base.put("svc-" + i, (long) i);
    }
    long buildStart = System.nanoTime();
    MerkleTree a = new MerkleTree(12, base);
    long buildNanos = System.nanoTime() - buildStart;

    Map<String, Long> changed = new LinkedHashMap<>(base);
    changed.put("svc-" + (size / 2), -1L);
    MerkleTree b = new MerkleTree(12, changed);

    long diffStart = System.nanoTime();
    int rounds = 100_000;
    for (int i = 0; i < rounds; i++) {
      a.diff(b);
    }
    long diffNanos = System.nanoTime() - diffStart;

    System.out.printf(
        "%-28s build(%,d)=%.2f ms   diff(1 change)=%.3f us%n",
        "MerkleTree", size, buildNanos / 1e6, (diffNanos / (double) rounds) / 1e3);
  }

  private static ServiceEntryImpl entry(String service, String owner, long version) {
    return new ServiceEntryImpl(
        service, owner, owner + "@addr", Map.of(), new HybridTimestamp(version, 0),
        ConsistencyTier.CAUSAL, true);
  }

  private static void report(String label, long ops, long nanos) {
    double opsPerSec = ops / (nanos / 1e9);
    double nsPerOp = nanos / (double) ops;
    System.out.printf("%-28s %,.0f ops/s   %.1f ns/op%n", label, opsPerSec, nsPerOp);
  }
}
