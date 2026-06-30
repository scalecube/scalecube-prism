package io.scalecube.prism.consensus;

import java.util.Objects;
import java.util.StringJoiner;

/**
 * An immutable leadership lease for one group: who holds it, the monotonic fencing {@code epoch},
 * and when it expires. Stored in a {@link ConsensusStore} and replaced via compare-and-set.
 */
public final class LeaseRecord {

  private final String group;
  private final String owner;
  private final long epoch;
  private final long expiresAt;

  /**
   * Creates a lease record.
   *
   * @param group election group
   * @param owner id of the holding member
   * @param epoch monotonic fencing token for this leadership term
   * @param expiresAt absolute expiry time in millis
   */
  public LeaseRecord(String group, String owner, long epoch, long expiresAt) {
    this.group = Objects.requireNonNull(group, "group");
    this.owner = Objects.requireNonNull(owner, "owner");
    this.epoch = epoch;
    this.expiresAt = expiresAt;
  }

  public String group() {
    return group;
  }

  public String owner() {
    return owner;
  }

  public long epoch() {
    return epoch;
  }

  public long expiresAt() {
    return expiresAt;
  }

  /**
   * Whether the lease has expired at the given time.
   *
   * @param nowMillis current time in millis
   * @return true if expired
   */
  public boolean isExpired(long nowMillis) {
    return nowMillis >= expiresAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    LeaseRecord that = (LeaseRecord) o;
    return epoch == that.epoch
        && expiresAt == that.expiresAt
        && group.equals(that.group)
        && owner.equals(that.owner);
  }

  @Override
  public int hashCode() {
    return Objects.hash(group, owner, epoch, expiresAt);
  }

  @Override
  public String toString() {
    return new StringJoiner(", ", "Lease{", "}")
        .add("group=" + group)
        .add("owner=" + owner)
        .add("epoch=" + epoch)
        .add("expiresAt=" + expiresAt)
        .toString();
  }
}
