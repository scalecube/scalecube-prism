package io.scalecube.prism.consensus;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * In-process, linearizable {@link ConsensusStore} backed by a synchronized map. Models the
 * consensus guarantee within a single JVM — suitable for unit tests and single-node use.
 *
 * <p><b>Not distributed.</b> Using one instance per node would give every node its own store and
 * thus its own leader — two actives. A real cross-node elector requires
 * {@link QuorumConsensusStore} (single-decree Paxos over a majority quorum, ADR-0012).
 */
public final class InMemoryConsensusStore implements ConsensusStore {

  private final Map<String, LeaseRecord> values = new HashMap<>();

  @Override
  public synchronized Optional<LeaseRecord> get(String group) {
    return Optional.ofNullable(values.get(group));
  }

  @Override
  public synchronized boolean compareAndSet(String group, LeaseRecord expected, LeaseRecord next) {
    if (!Objects.equals(values.get(group), expected)) {
      return false;
    }
    if (next == null) {
      values.remove(group);
    } else {
      values.put(group, next);
    }
    return true;
  }
}
