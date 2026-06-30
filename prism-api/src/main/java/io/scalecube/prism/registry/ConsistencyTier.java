package io.scalecube.prism.registry;

/**
 * Per-key consistency level. The owner of a key declares the weakest tier that is still correct for
 * that data; the registry routes reads/writes accordingly over a single membership substrate.
 *
 * <p>Ordered from most available to most strongly consistent.
 */
public enum ConsistencyTier {

  /** Pure gossip last-writer-wins. Cheapest, local read, stale-tolerant. */
  EVENTUAL,

  /** Gossip + causal context → Bayou session guarantees (read-your-writes, monotonic reads). */
  CAUSAL,

  /** On-demand read-repair across {@code k} replicas; tunable freshness at read time. */
  QUORUM,

  /** Linearizable via a small elected consensus group. For singleton ownership, locks. */
  CONSENSUS
}
