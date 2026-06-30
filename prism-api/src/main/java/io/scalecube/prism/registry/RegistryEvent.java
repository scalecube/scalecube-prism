package io.scalecube.prism.registry;

import java.util.Objects;
import java.util.StringJoiner;

/** A change in the registry, emitted on the {@link ServiceRegistry#watch()} stream. */
public final class RegistryEvent {

  /** Kind of change. */
  public enum Type {
    /** First time this service entry is seen. */
    REGISTERED,
    /** An existing entry's properties changed (higher version). */
    UPDATED,
    /** Owner explicitly deregistered the service while alive. */
    DEREGISTERED,
    /** Entry removed because its owning member died (membership-driven tombstone). */
    EXPIRED
  }

  private final Type type;
  private final ServiceEntry entry;

  public RegistryEvent(Type type, ServiceEntry entry) {
    this.type = Objects.requireNonNull(type, "type");
    this.entry = Objects.requireNonNull(entry, "entry");
  }

  public Type type() {
    return type;
  }

  public ServiceEntry entry() {
    return entry;
  }

  @Override
  public String toString() {
    return new StringJoiner(", ", RegistryEvent.class.getSimpleName() + "[", "]")
        .add("type=" + type)
        .add("entry=" + entry)
        .toString();
  }
}
