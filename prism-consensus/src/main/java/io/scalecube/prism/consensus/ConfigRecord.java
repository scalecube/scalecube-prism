package io.scalecube.prism.consensus;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * An immutable committed configuration of the self-electing quorum: a monotonic {@code epoch} and
 * the member set agreed at that epoch (ADR-0015). Configurations form a totally ordered single-step
 * chain; a higher epoch supersedes a lower one. Disseminated by the leader; adopted by peers.
 */
public final class ConfigRecord {

  private final long epoch;
  private final List<String> members;

  /**
   * Creates a config record (members are normalized to a sorted, de-duplicated list).
   *
   * @param epoch the monotonic config epoch
   * @param members the member set at this epoch (non-empty)
   */
  public ConfigRecord(long epoch, List<String> members) {
    this.epoch = epoch;
    this.members = List.copyOf(new TreeSet<>(Objects.requireNonNull(members, "members")));
    if (this.members.isEmpty()) {
      throw new IllegalArgumentException("config members must be non-empty");
    }
  }

  public long epoch() {
    return epoch;
  }

  public List<String> members() {
    return members;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ConfigRecord)) {
      return false;
    }
    ConfigRecord that = (ConfigRecord) o;
    return epoch == that.epoch && members.equals(that.members);
  }

  @Override
  public int hashCode() {
    return Objects.hash(epoch, members);
  }

  @Override
  public String toString() {
    return "Config{epoch=" + epoch + ", members=" + members + '}';
  }
}
