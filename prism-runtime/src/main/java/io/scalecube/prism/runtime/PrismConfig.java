package io.scalecube.prism.runtime;

import io.scalecube.cluster.transport.api.TransportFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Configuration enabling the singleton elector on a {@link PrismImpl}. Declares this node's
 * dedicated consensus-transport address and the configured static quorum it belongs to (ADR-0007,
 * ADR-0012).
 *
 * <p>Immutable; tunables are set with {@code withXxx} copies.
 */
public final class PrismConfig {

  /** Default lease validity before it must be renewed. */
  public static final Duration DEFAULT_LEASE_TTL = Duration.ofSeconds(5);
  /** Default renewal/acquisition tick interval. */
  public static final Duration DEFAULT_TICK_INTERVAL = Duration.ofSeconds(1);
  /** Default per-peer consensus call timeout. */
  public static final Duration DEFAULT_CALL_TIMEOUT = Duration.ofSeconds(1);
  /** Default target quorum size when the self-electing quorum is enabled (odd; ADR-0015). */
  public static final int DEFAULT_TARGET_QUORUM_SIZE = 3;

  private final String consensusAddress;
  private final List<String> quorumMembers;
  private final Supplier<TransportFactory> transportFactory;
  private final Duration leaseTtl;
  private final Duration tickInterval;
  private final Duration callTimeout;
  private final Path persistenceDir; // nullable — durability off when absent
  private final boolean dynamicQuorum; // ADR-0015: self-electing/self-healing quorum (opt-in)
  private final int targetQuorumSize;

  /**
   * Creates a config with default timings.
   *
   * @param consensusAddress this node's consensus-transport address (e.g. {@code host:7001}); must
   *     be one of {@code quorumMembers}
   * @param quorumMembers all quorum members' consensus addresses (including this node)
   * @param transportFactory factory for the dedicated consensus transport
   */
  public PrismConfig(
      String consensusAddress,
      List<String> quorumMembers,
      Supplier<TransportFactory> transportFactory) {
    this(
        consensusAddress,
        quorumMembers,
        transportFactory,
        DEFAULT_LEASE_TTL,
        DEFAULT_TICK_INTERVAL,
        DEFAULT_CALL_TIMEOUT,
        null,
        false,
        DEFAULT_TARGET_QUORUM_SIZE);
  }

  private PrismConfig(
      String consensusAddress,
      List<String> quorumMembers,
      Supplier<TransportFactory> transportFactory,
      Duration leaseTtl,
      Duration tickInterval,
      Duration callTimeout,
      Path persistenceDir,
      boolean dynamicQuorum,
      int targetQuorumSize) {
    this.consensusAddress = Objects.requireNonNull(consensusAddress, "consensusAddress");
    this.quorumMembers = List.copyOf(Objects.requireNonNull(quorumMembers, "quorumMembers"));
    this.transportFactory = Objects.requireNonNull(transportFactory, "transportFactory");
    this.leaseTtl = leaseTtl;
    this.tickInterval = tickInterval;
    this.callTimeout = callTimeout;
    this.persistenceDir = persistenceDir;
    this.dynamicQuorum = dynamicQuorum;
    this.targetQuorumSize = targetQuorumSize;
  }

  public String consensusAddress() {
    return consensusAddress;
  }

  public List<String> quorumMembers() {
    return quorumMembers;
  }

  public Supplier<TransportFactory> transportFactory() {
    return transportFactory;
  }

  public Duration leaseTtl() {
    return leaseTtl;
  }

  public Duration tickInterval() {
    return tickInterval;
  }

  public Duration callTimeout() {
    return callTimeout;
  }

  /**
   * Directory for durable state (lease journal + clock high-water), or {@code null} for in-memory.
   * Enable with a stable {@code memberId} so a restart resumes safely.
   *
   * @return the persistence directory, or null
   */
  public Path persistenceDir() {
    return persistenceDir;
  }

  /**
   * Whether the self-electing / self-healing quorum is enabled (ADR-0015). When true,
   * {@link #quorumMembers()} is the <b>candidate roster</b> and the quorum sizes itself to
   * {@link #targetQuorumSize()} and self-heals by single-member reconfiguration. Off by default;
   * the static quorum is the safe default.
   *
   * @return true if the dynamic quorum is enabled
   */
  public boolean dynamicQuorum() {
    return dynamicQuorum;
  }

  /**
   * The target (odd) quorum size when {@link #dynamicQuorum()} is enabled.
   *
   * @return the target quorum size
   */
  public int targetQuorumSize() {
    return targetQuorumSize;
  }

  /**
   * Copy with a different lease TTL.
   *
   * @param value the new lease TTL
   * @return a copy
   */
  public PrismConfig withLeaseTtl(Duration value) {
    return new PrismConfig(
        consensusAddress, quorumMembers, transportFactory, value, tickInterval, callTimeout,
        persistenceDir, dynamicQuorum, targetQuorumSize);
  }

  /**
   * Copy with a different tick interval.
   *
   * @param value the new tick interval
   * @return a copy
   */
  public PrismConfig withTickInterval(Duration value) {
    return new PrismConfig(
        consensusAddress, quorumMembers, transportFactory, leaseTtl, value, callTimeout,
        persistenceDir, dynamicQuorum, targetQuorumSize);
  }

  /**
   * Copy with a different call timeout.
   *
   * @param value the new per-call timeout
   * @return a copy
   */
  public PrismConfig withCallTimeout(Duration value) {
    return new PrismConfig(
        consensusAddress, quorumMembers, transportFactory, leaseTtl, tickInterval, value,
        persistenceDir, dynamicQuorum, targetQuorumSize);
  }

  /**
   * Copy that enables durability under the given directory.
   *
   * @param dir the persistence directory
   * @return a copy
   */
  public PrismConfig withPersistenceDir(Path dir) {
    return new PrismConfig(
        consensusAddress, quorumMembers, transportFactory, leaseTtl, tickInterval, callTimeout,
        dir, dynamicQuorum, targetQuorumSize);
  }

  /**
   * Copy that enables the self-electing / self-healing quorum (ADR-0015) with the given target.
   * {@link #quorumMembers()} becomes the candidate roster; the quorum sizes itself to the target
   * (rounded to odd) and self-heals by single-member reconfiguration. The static quorum remains the
   * default; this is an explicit opt-in.
   *
   * @param target the target quorum size (odd; e.g. 3)
   * @return a copy with the dynamic quorum enabled
   */
  public PrismConfig withDynamicQuorum(int target) {
    return new PrismConfig(
        consensusAddress, quorumMembers, transportFactory, leaseTtl, tickInterval, callTimeout,
        persistenceDir, true, target);
  }
}
