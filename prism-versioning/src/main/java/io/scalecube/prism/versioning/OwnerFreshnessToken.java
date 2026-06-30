package io.scalecube.prism.versioning;

import io.scalecube.prism.version.FreshnessToken;
import io.scalecube.prism.version.Version;
import java.util.Objects;

/**
 * Default {@link FreshnessToken}: states that a read reflects all updates up to {@code upTo} from
 * {@code ownerId}.
 */
public final class OwnerFreshnessToken implements FreshnessToken {

  private final String ownerId;
  private final Version upTo;

  /**
   * Creates a freshness token.
   *
   * @param ownerId the owning member whose updates this token bounds
   * @param upTo the highest version from that owner reflected in the read
   */
  public OwnerFreshnessToken(String ownerId, Version upTo) {
    this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
    this.upTo = Objects.requireNonNull(upTo, "upTo");
  }

  @Override
  public String ownerId() {
    return ownerId;
  }

  @Override
  public Version upTo() {
    return upTo;
  }

  @Override
  public String toString() {
    return "Freshness(" + ownerId + "@" + upTo + ")";
  }
}
