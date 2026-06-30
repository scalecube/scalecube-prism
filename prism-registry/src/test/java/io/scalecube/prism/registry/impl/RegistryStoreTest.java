package io.scalecube.prism.registry.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.scalecube.prism.registry.ConsistencyTier;
import io.scalecube.prism.registry.RegistryEvent;
import io.scalecube.prism.registry.ServiceEntry;
import io.scalecube.prism.versioning.HybridTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Registry store: last-writer-wins merge core")
class RegistryStoreTest {

  private RegistryStore store;

  @BeforeEach
  void setUp() {
    store = new RegistryStore();
  }

  private static ServiceEntryImpl entry(String service, String owner, long version, String... kv) {
    Map<String, String> props = new LinkedHashMap<>();
    for (int i = 0; i + 1 < kv.length; i += 2) {
      props.put(kv[i], kv[i + 1]);
    }
    return new ServiceEntryImpl(
        service, owner, owner + "@addr", props, new HybridTimestamp(version, 0),
        ConsistencyTier.CAUSAL, true);
  }

  /**
   * Given an empty store,
   * When a new entry is applied,
   * Then it is REGISTERED and visible to lookup.
   */
  @Test
  void registersNewEntry() {
    Optional<RegistryEvent> ev = store.apply(entry("orders", "A", 1, "weight", "100"), false);

    assertTrue(ev.isPresent());
    assertEquals(RegistryEvent.Type.REGISTERED, ev.get().type());
    assertEquals(1, store.lookup("orders").size());
  }

  /**
   * Given an existing entry,
   * When a higher-version entry for the same key is applied,
   * Then it is UPDATED to the newer value.
   */
  @Test
  void newerVersionUpdates() {
    store.apply(entry("orders", "A", 1, "weight", "100"), false);

    Optional<RegistryEvent> ev = store.apply(entry("orders", "A", 2, "weight", "0"), false);

    assertTrue(ev.isPresent());
    assertEquals(RegistryEvent.Type.UPDATED, ev.get().type());
    assertEquals("0", store.lookup("orders").get(0).properties().get("weight"));
  }

  /**
   * Given an entry at version 2,
   * When an older version-1 entry arrives,
   * Then it is rejected and the newer value retained.
   */
  @Test
  void olderVersionRejected() {
    store.apply(entry("orders", "A", 2, "weight", "0"), false);

    Optional<RegistryEvent> ev = store.apply(entry("orders", "A", 1, "weight", "100"), false);

    assertFalse(ev.isPresent());
    assertEquals("0", store.lookup("orders").get(0).properties().get("weight"));
  }

  /**
   * Given an entry at a version,
   * When the same version is applied again,
   * Then it is rejected (idempotent).
   */
  @Test
  void equalVersionRejected() {
    store.apply(entry("orders", "A", 1), false);

    assertFalse(store.apply(entry("orders", "A", 1), false).isPresent());
  }

  /**
   * Given a live entry,
   * When a higher-version tombstone is applied,
   * Then the entry is DEREGISTERED and no longer visible.
   */
  @Test
  void tombstoneDeregistersLiveEntry() {
    store.apply(entry("orders", "A", 1), false);

    Optional<RegistryEvent> ev = store.apply(entry("orders", "A", 2), true);

    assertTrue(ev.isPresent());
    assertEquals(RegistryEvent.Type.DEREGISTERED, ev.get().type());
    assertTrue(store.lookup("orders").isEmpty());
  }

  /**
   * Given a tombstone at version 2,
   * When an older live entry arrives it stays removed, but a newer live entry re-registers,
   * Then anti-entropy cannot resurrect a delete, yet legitimate re-registration works.
   */
  @Test
  void tombstoneIsNotResurrectedByOlderEntryButIsByNewer() {
    store.apply(entry("orders", "A", 1), false);
    store.apply(entry("orders", "A", 2), true); // tombstone at v2

    assertFalse(store.apply(entry("orders", "A", 1), false).isPresent());
    assertTrue(store.lookup("orders").isEmpty());

    Optional<RegistryEvent> ev = store.apply(entry("orders", "A", 3), false);
    assertTrue(ev.isPresent());
    assertEquals(RegistryEvent.Type.REGISTERED, ev.get().type());
    assertEquals(1, store.lookup("orders").size());
  }

  /**
   * Given several live entries owned by a member,
   * When that owner is purged (it left the membership),
   * Then each previously-live entry is reported EXPIRED and removed.
   */
  @Test
  void purgeOwnerExpiresLiveEntries() {
    store.apply(entry("orders", "A", 1), false);
    store.apply(entry("billing", "A", 1), false);

    List<RegistryEvent> events = store.purgeOwner("A");

    assertEquals(2, events.size());
    assertTrue(events.stream().allMatch(e -> e.type() == RegistryEvent.Type.EXPIRED));
    assertTrue(store.list().isEmpty());
  }

  /**
   * Given two owners advertising the same service,
   * When one owner is purged,
   * Then only that owner's instance is removed; the other remains.
   */
  @Test
  void ownersAreIsolated() {
    store.apply(entry("orders", "A", 1), false);
    store.apply(entry("orders", "B", 1), false);

    assertEquals(2, store.lookup("orders").size());

    store.purgeOwner("A");
    List<ServiceEntry> remaining = store.lookup("orders");
    assertEquals(1, remaining.size());
    assertEquals("B", remaining.get(0).owner());
  }

  /**
   * Given multiple entries for an owner,
   * When the highest version is queried,
   * Then it returns the maximum (and empty for an unknown owner).
   */
  @Test
  void highestVersionTracksMax() {
    store.apply(entry("orders", "A", 1), false);
    store.apply(entry("billing", "A", 5), false);

    assertEquals(5, store.highestVersion("A").orElseThrow().physical());
    assertFalse(store.highestVersion("unknown").isPresent());
  }

  private static ServiceEntryImpl entryAt(String service, String owner, long phys, long logical) {
    return new ServiceEntryImpl(
        service, owner, owner + "@addr", Map.of(), new HybridTimestamp(phys, logical),
        ConsistencyTier.CAUSAL, true);
  }

  /**
   * Given one node that holds a LIVE entry at version (p, even L) and another that holds a same-key
   * TOMBSTONE at (p, L+1) — the exact state after a same-millisecond register+deregister where one
   * node dropped the delete gossip,
   * When their anti-entropy content digests are compared,
   * Then the digests DIFFER, so the mismatch is detected and the dead entry can heal. (Regression for
   * code-review finding 1: the old `physical*K ^ logical ^ (tombstone?1:0)` cancelled for even L, so
   * a live entry and a one-version-later tombstone produced an identical digest and never healed.)
   */
  @Test
  void liveAndOneVersionLaterTombstoneHaveDistinctDigests() {
    RegistryStore live = new RegistryStore();
    live.apply(entryAt("orders", "A", 5, 0), false); // live at (5, 0) — even logical

    RegistryStore deleted = new RegistryStore();
    deleted.apply(entryAt("orders", "A", 5, 0), false);
    deleted.apply(entryAt("orders", "A", 5, 1), true); // tombstone at (5, 1)

    long liveDigest = live.contentDigest().get("A/orders");
    long deletedDigest = deleted.contentDigest().get("A/orders");
    assertTrue(
        liveDigest != deletedDigest,
        "live (5,0) and tombstone (5,1) digests must differ, else the delete never heals");
  }
}
