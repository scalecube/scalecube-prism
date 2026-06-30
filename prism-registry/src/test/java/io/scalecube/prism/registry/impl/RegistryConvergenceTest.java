package io.scalecube.prism.registry.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.scalecube.prism.registry.ConsistencyTier;
import io.scalecube.prism.registry.ServiceEntry;
import io.scalecube.prism.versioning.HybridTimestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Registry CRDT: strong eventual consistency (order/duplication independence)")
class RegistryConvergenceTest {

  /** One delta: a versioned entry, possibly a tombstone. */
  private static final class Op {
    final ServiceEntryImpl entry;
    final boolean tombstone;

    Op(ServiceEntryImpl entry, boolean tombstone) {
      this.entry = entry;
      this.tombstone = tombstone;
    }
  }

  /**
   * Given the same set of versioned deltas (each version unique),
   * When one replica applies them in generation order and another applies a reordered stream that
   * also contains duplicates,
   * Then both replicas converge to identical live state — across many seeds (strong eventual
   * consistency: order-independent and idempotent).
   */
  @Test
  void convergesRegardlessOfOrderAndDuplicates() {
    for (long seed = 0; seed < 200; seed++) {
      Random rng = new Random(seed);
      List<Op> ops = generate(rng, 300);

      RegistryStore a = new RegistryStore();
      ops.forEach(o -> a.apply(o.entry, o.tombstone));

      List<Op> reordered = new ArrayList<>(ops);
      for (int i = 0; i < 60; i++) {
        reordered.add(ops.get(rng.nextInt(ops.size()))); // duplicate deliveries
      }
      Collections.shuffle(reordered, rng);

      RegistryStore b = new RegistryStore();
      reordered.forEach(o -> b.apply(o.entry, o.tombstone));

      assertEquals(liveState(a), liveState(b), "replicas diverged at seed=" + seed);
    }
  }

  private static List<Op> generate(Random rng, int count) {
    List<Op> ops = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      String service = "svc-" + rng.nextInt(15);
      String owner = "own-" + (Math.floorMod(service.hashCode(), 4)); // one owner per service
      boolean tombstone = rng.nextInt(5) == 0;
      ServiceEntryImpl entry =
          new ServiceEntryImpl(
              service, owner, owner + "@addr", Map.of("v", Integer.toString(i)),
              new HybridTimestamp(i, 0), // unique, strictly increasing version → deterministic LWW
              ConsistencyTier.CAUSAL, true);
      ops.add(new Op(entry, tombstone));
    }
    return ops;
  }

  private static Map<String, String> liveState(RegistryStore store) {
    Map<String, String> state = new TreeMap<>();
    for (ServiceEntry e : store.list()) {
      state.put(
          e.owner() + "/" + e.service(),
          e.version().physical() + ":" + e.version().logical() + ":" + e.properties());
    }
    return state;
  }
}
