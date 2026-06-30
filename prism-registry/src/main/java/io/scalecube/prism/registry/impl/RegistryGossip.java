package io.scalecube.prism.registry.impl;

import io.scalecube.prism.registry.ConsistencyTier;
import io.scalecube.prism.versioning.HybridTimestamp;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One registry delta (a versioned entry, possibly a tombstone) disseminated over gossip. A plain
 * DTO; it crosses the wire via {@link RegistryGossipCodec} (schema'd binary), never Java
 * serialization (ADR-0009).
 */
final class RegistryGossip {

  private final String service;
  private final String owner;
  private final String address;
  private final String tier;
  private final long physical;
  private final long logical;
  private final boolean tombstone;
  private final Map<String, String> properties;

  RegistryGossip(
      String service,
      String owner,
      String address,
      String tier,
      long physical,
      long logical,
      boolean tombstone,
      Map<String, String> properties) {
    this.service = service;
    this.owner = owner;
    this.address = address;
    this.tier = tier;
    this.physical = physical;
    this.logical = logical;
    this.tombstone = tombstone;
    this.properties = properties;
  }

  static RegistryGossip of(ServiceEntryImpl entry, boolean tombstone) {
    return new RegistryGossip(
        entry.service(),
        entry.owner(),
        entry.address(),
        entry.tier().name(),
        entry.version().physical(),
        entry.version().logical(),
        tombstone,
        new LinkedHashMap<>(entry.properties()));
  }

  ServiceEntryImpl toEntry() {
    return new ServiceEntryImpl(
        service,
        owner,
        address,
        properties,
        new HybridTimestamp(physical, logical),
        ConsistencyTier.valueOf(tier),
        true);
  }

  String service() {
    return service;
  }

  String owner() {
    return owner;
  }

  String address() {
    return address;
  }

  String tier() {
    return tier;
  }

  long physical() {
    return physical;
  }

  long logical() {
    return logical;
  }

  boolean tombstone() {
    return tombstone;
  }

  Map<String, String> properties() {
    return properties;
  }
}
