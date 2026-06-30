package io.scalecube.prism.registry;

import io.scalecube.prism.version.Version;
import java.util.Map;

/**
 * An immutable snapshot of one advertised service, owned by exactly one member (single-writer).
 * Properties are mutable over the service's lifetime; each change carries a higher {@link
 * #version()}.
 */
public interface ServiceEntry {

  /** Logical service name (e.g. {@code "orders"}). */
  String service();

  /** Id of the member that owns/advertises this entry (the sole writer). */
  String owner();

  /** Network address of the owning member. */
  String address();

  /** Arbitrary key/value properties (e.g. {@code weight}, {@code status}, {@code version}). */
  Map<String, String> properties();

  /** Monotonic version of this entry's property set. */
  Version version();

  /** Consistency tier this entry was registered under. */
  ConsistencyTier tier();

  /**
   * Whether the owning member is currently {@code ALIVE} in the membership view. A {@code false}
   * value means the entry is stale-positive — present but its host may be gone.
   */
  boolean alive();
}
