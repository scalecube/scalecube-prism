package io.scalecube.prism.registry.impl;

import io.scalecube.prism.registry.ConsistencyTier;
import io.scalecube.prism.registry.ServiceEntry;
import io.scalecube.prism.version.Version;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable {@link ServiceEntry} value. Properties are defensively copied and unmodifiable. */
public final class ServiceEntryImpl implements ServiceEntry {

  private final String service;
  private final String owner;
  private final String address;
  private final Map<String, String> properties;
  private final Version version;
  private final ConsistencyTier tier;
  private final boolean alive;

  /**
   * Creates a service entry.
   *
   * @param service logical service name
   * @param owner id of the owning (sole-writer) member
   * @param address network address of the owner
   * @param properties key/value properties (copied)
   * @param version monotonic version of this property set
   * @param tier consistency tier
   * @param alive whether the owner is currently alive in the membership view
   */
  public ServiceEntryImpl(
      String service,
      String owner,
      String address,
      Map<String, String> properties,
      Version version,
      ConsistencyTier tier,
      boolean alive) {
    this.service = Objects.requireNonNull(service, "service");
    this.owner = Objects.requireNonNull(owner, "owner");
    this.address = Objects.requireNonNull(address, "address");
    this.properties =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(Objects.requireNonNull(properties, "properties")));
    this.version = Objects.requireNonNull(version, "version");
    this.tier = Objects.requireNonNull(tier, "tier");
    this.alive = alive;
  }

  @Override
  public String service() {
    return service;
  }

  @Override
  public String owner() {
    return owner;
  }

  @Override
  public String address() {
    return address;
  }

  @Override
  public Map<String, String> properties() {
    return properties;
  }

  @Override
  public Version version() {
    return version;
  }

  @Override
  public ConsistencyTier tier() {
    return tier;
  }

  @Override
  public boolean alive() {
    return alive;
  }

  /**
   * Returns a copy of this entry with a different liveness flag.
   *
   * @param newAlive the liveness value for the copy
   * @return a copy with {@code alive == newAlive}
   */
  public ServiceEntryImpl withAlive(boolean newAlive) {
    return new ServiceEntryImpl(service, owner, address, properties, version, tier, newAlive);
  }

  @Override
  public String toString() {
    return "ServiceEntry{"
        + service
        + "@"
        + owner
        + ", v="
        + version
        + ", tier="
        + tier
        + ", alive="
        + alive
        + ", props="
        + properties
        + '}';
  }
}
