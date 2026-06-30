package io.scalecube.prism.registry.impl;

import io.scalecube.prism.registry.RegistryEvent;
import io.scalecube.prism.registry.ServiceEntry;
import io.scalecube.prism.version.Version;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The conflict-free core of the registry: a per-owner, per-service map of versioned entries with
 * last-writer-wins merge. Pure and side-effect-free (no I/O, no gossip) so it is fully
 * deterministic and unit-testable; the gossip-wired façade drives it from inbound deltas and
 * membership events.
 *
 * <p>Convergence rests on two facts: each key {@code (owner, service)} has a single writer (its
 * owner), and every write carries a strictly greater {@link Version}. {@link #apply} therefore
 * accepts an update iff it is newer than what is held — making merge idempotent and reorder-safe.
 *
 * <p>Deletes are tombstones (a versioned "removed" marker), never bare removals, so anti-entropy
 * cannot resurrect a deleted key. Tombstone sweeping/GC is the façade's concern; this store retains
 * a tombstone until a newer entry supersedes it or its owner is purged.
 *
 * <p>Not thread-safe; callers confine access to a single thread (e.g. the registry's scheduler).
 */
public final class RegistryStore {

  /** Internal cell: an entry plus whether it is a tombstone. */
  private static final class Record {
    final ServiceEntryImpl entry;
    final boolean tombstone;

    Record(ServiceEntryImpl entry, boolean tombstone) {
      this.entry = entry;
      this.tombstone = tombstone;
    }

    Version version() {
      return entry.version();
    }
  }

  private final Map<String, Map<String, Record>> byOwner = new HashMap<>();

  /**
   * Merges an incoming entry with last-writer-wins semantics.
   *
   * @param entry the entry to merge (its {@link ServiceEntry#version()} decides the winner)
   * @param tombstone whether this update marks the key as removed
   * @return the resulting change to publish, or empty if the update was not newer (rejected) or had
   *     no externally visible effect
   */
  public Optional<RegistryEvent> apply(ServiceEntryImpl entry, boolean tombstone) {
    final String owner = entry.owner();
    final String service = entry.service();
    final Map<String, Record> services = byOwner.computeIfAbsent(owner, k -> new HashMap<>());
    final Record existing = services.get(service);

    if (existing != null && entry.version().compareTo(existing.version()) <= 0) {
      return Optional.empty(); // not newer — last-writer-wins rejects it
    }

    services.put(service, new Record(entry, tombstone));

    if (tombstone) {
      if (existing != null && !existing.tombstone) {
        return Optional.of(new RegistryEvent(RegistryEvent.Type.DEREGISTERED, existing.entry));
      }
      return Optional.empty(); // tombstone over nothing visible
    }

    if (existing == null || existing.tombstone) {
      return Optional.of(new RegistryEvent(RegistryEvent.Type.REGISTERED, entry));
    }
    return Optional.of(new RegistryEvent(RegistryEvent.Type.UPDATED, entry));
  }

  /**
   * Removes everything owned by a member because it left the membership (its death is the
   * tombstone).
   *
   * @param ownerId the member whose entries to purge
   * @return one {@code EXPIRED} event per previously-live entry
   */
  public List<RegistryEvent> purgeOwner(String ownerId) {
    final Map<String, Record> services = byOwner.remove(ownerId);
    if (services == null) {
      return new ArrayList<>();
    }
    final List<RegistryEvent> events = new ArrayList<>();
    for (Record r : services.values()) {
      if (!r.tombstone) {
        events.add(new RegistryEvent(RegistryEvent.Type.EXPIRED, r.entry));
      }
    }
    return events;
  }

  /**
   * Returns all live instances of a service across owners.
   *
   * @param service the service name
   * @return matching live entries (never tombstones)
   */
  public List<ServiceEntry> lookup(String service) {
    final List<ServiceEntry> out = new ArrayList<>();
    for (Map<String, Record> services : byOwner.values()) {
      final Record r = services.get(service);
      if (r != null && !r.tombstone) {
        out.add(r.entry);
      }
    }
    return out;
  }

  /**
   * Returns a snapshot of all live entries.
   *
   * @return every live entry currently held
   */
  public List<ServiceEntry> list() {
    final List<ServiceEntry> out = new ArrayList<>();
    for (Map<String, Record> services : byOwner.values()) {
      for (Record r : services.values()) {
        if (!r.tombstone) {
          out.add(r.entry);
        }
      }
    }
    return out;
  }

  /** A locally-owned entry plus whether it is a tombstone — for anti-entropy re-advertisement. */
  public static final class OwnedDelta {
    private final ServiceEntryImpl entry;
    private final boolean tombstone;

    OwnedDelta(ServiceEntryImpl entry, boolean tombstone) {
      this.entry = entry;
      this.tombstone = tombstone;
    }

    public ServiceEntryImpl entry() {
      return entry;
    }

    public boolean tombstone() {
      return tombstone;
    }
  }

  /**
   * Returns all records (live and tombstone) owned by a member, so it can re-advertise its slice
   * during anti-entropy.
   *
   * @param ownerId the owning member
   * @return that owner's deltas
   */
  public List<OwnedDelta> ownedDeltas(String ownerId) {
    final List<OwnedDelta> out = new ArrayList<>();
    final Map<String, Record> services = byOwner.get(ownerId);
    if (services != null) {
      for (Record r : services.values()) {
        out.add(new OwnedDelta(r.entry, r.tombstone));
      }
    }
    return out;
  }

  /**
   * Returns a content digest of the whole store: each key {@code owner/service} mapped to a hash of
   * its version and tombstone flag. Feed this to a {@link MerkleTree} for anti-entropy
   * reconciliation — two nodes compare roots and exchange only the differing buckets.
   *
   * @return key → content hash for every entry (live or tombstone)
   */
  public Map<String, Long> contentDigest() {
    final Map<String, Long> out = new HashMap<>();
    for (Map.Entry<String, Map<String, Record>> ownerEntry : byOwner.entrySet()) {
      for (Map.Entry<String, Record> svc : ownerEntry.getValue().entrySet()) {
        final Record r = svc.getValue();
        // Encode (physical, logical, tombstone) INJECTIVELY, then avalanche. The tombstone flag is
        // the low bit of an injective encoding (combined<<1 | flag) — never XOR'd next to the
        // logical counter — so a live entry can no longer share a digest with a same-key tombstone
        // one version later. (The earlier `... ^ logical ^ (tombstone?1:0)` cancelled for even
        // logical: live (p,L) == tombstone (p,L+1). See code-review finding 1.)
        final long combined = r.version().physical() * 1_000_003L + r.version().logical();
        final long hash = mix((combined << 1) | (r.tombstone ? 1L : 0L));
        out.put(ownerEntry.getKey() + "/" + svc.getKey(), hash);
      }
    }
    return out;
  }

  /** SplitMix64 finalizer — a bijection, so the injective encoding above stays collision-free. */
  private static long mix(long z) {
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }

  /**
   * Returns every record for a service across all owners, <b>including tombstones</b> — the slice a
   * peer ships in response to a quorum read-repair request. Tombstones must be included so a peer's
   * known deregistration can repair a requester that still holds the entry as live.
   *
   * @param service the service name
   * @return that service's records (live and tombstone), one per owner that holds it
   */
  public List<OwnedDelta> recordsForService(String service) {
    final List<OwnedDelta> out = new ArrayList<>();
    for (Map<String, Record> services : byOwner.values()) {
      final Record r = services.get(service);
      if (r != null) {
        out.add(new OwnedDelta(r.entry, r.tombstone));
      }
    }
    return out;
  }

  /**
   * Returns the highest version held for an owner (across live entries and tombstones), for
   * building a freshness token or an anti-entropy digest.
   *
   * @param ownerId the owner
   * @return the highest version, or empty if nothing is held for the owner
   */
  public Optional<Version> highestVersion(String ownerId) {
    final Map<String, Record> services = byOwner.get(ownerId);
    if (services == null || services.isEmpty()) {
      return Optional.empty();
    }
    Version max = null;
    for (Record r : services.values()) {
      if (max == null || r.version().compareTo(max) > 0) {
        max = r.version();
      }
    }
    return Optional.ofNullable(max);
  }
}
