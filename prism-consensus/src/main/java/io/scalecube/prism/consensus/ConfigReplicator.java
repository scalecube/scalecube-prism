package io.scalecube.prism.consensus;

import java.util.List;
import java.util.Optional;

/**
 * Disseminates committed {@link ConfigRecord}s across the quorum and surfaces the highest one a
 * node has learned (ADR-0015). The single proposer is always the current leader (unique,
 * lease-backed), so config proposals are serialized by leadership itself — this SPI only needs to
 * replicate, not resolve concurrent proposals.
 *
 * <p>Implementations: an in-memory one for tests/simulation; a transport-backed one that sends to
 * peers and counts acks (analogous to {@link PeerCaller} for leases).
 */
public interface ConfigReplicator {

  /**
   * Disseminates {@code record} and reports whether a <b>majority of {@code currentConfig}</b> has
   * durably adopted it (including this node). Only then is the change considered committed.
   *
   * @param record the new configuration to commit
   * @param currentConfig the configuration whose majority must adopt the change
   * @return true if a majority adopted it
   */
  boolean commit(ConfigRecord record, List<String> currentConfig);

  /**
   * The highest-epoch configuration this node currently knows of (from prior commits or peer sync),
   * used so a freshly elected leader — or a lagging node — can adopt the latest committed config.
   *
   * @return the latest known config, or empty if none beyond the seed
   */
  Optional<ConfigRecord> latestKnown();
}
