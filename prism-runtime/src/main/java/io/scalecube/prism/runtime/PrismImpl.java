package io.scalecube.prism.runtime;

import io.scalecube.cluster.Cluster;
import io.scalecube.cluster.ClusterImpl;
import io.scalecube.cluster.Member;
import io.scalecube.cluster.transport.api.Transport;
import io.scalecube.cluster.transport.api.TransportConfig;
import io.scalecube.prism.Prism;
import io.scalecube.prism.consensus.ConfigJournal;
import io.scalecube.prism.consensus.LeaseJournal;
import io.scalecube.prism.consensus.LeaseRequest;
import io.scalecube.prism.consensus.PeerCaller;
import io.scalecube.prism.consensus.QuorumConfig;
import io.scalecube.prism.consensus.QuorumNode;
import io.scalecube.prism.consensus.ReconfigurationManager;
import io.scalecube.prism.consensus.TransportConfigReplicator;
import io.scalecube.prism.consensus.TransportLeaseTransfer;
import io.scalecube.prism.consensus.TransportPeerCaller;
import io.scalecube.prism.elector.SingletonElector;
import io.scalecube.prism.elector.impl.LeaseElector;
import io.scalecube.prism.metrics.Metrics;
import io.scalecube.prism.persistence.FileClockJournal;
import io.scalecube.prism.persistence.FileConfigJournal;
import io.scalecube.prism.persistence.FileLeaseJournal;
import io.scalecube.prism.registry.ServiceRegistry;
import io.scalecube.prism.registry.impl.GossipServiceRegistry;
import io.scalecube.prism.versioning.HybridLogicalClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Default {@link Prism}: decorates a scalecube cluster. Takes an <em>unstarted</em> {@link
 * ClusterImpl}, installs prism's message handler, and starts it.
 *
 * <p>If a {@link PrismConfig} is supplied, {@link #elector()} is enabled: prism binds a dedicated
 * consensus transport, joins the configured static quorum, and runs a partition-safe lease elector
 * (ADR-0012). Without it, {@code elector()} is unavailable.
 *
 * <pre>{@code
 * Prism prism =
 *     new PrismImpl(
 *             new ClusterImpl().transportFactory(TcpTransportFactory::new),
 *             new PrismConfig("host:7001", quorum, TcpTransportFactory::new))
 *         .startAwait();
 * }</pre>
 *
 * <p>prism owns the lifecycle of what it starts: {@link #shutdown()} stops the elector, the
 * consensus transport, and the cluster.
 */
public final class PrismImpl implements Prism {

  private final ClusterImpl clusterBuilder;
  private final PrismConfig prismConfig; // nullable — elector disabled when absent
  private final Metrics metrics;
  private final GossipServiceRegistry registry;

  /** Internal control group whose leader drives self-electing-quorum reconfiguration (ADR-0015). */
  private static final String QUORUM_CONTROL_GROUP = "sc/prism/quorum/control";

  private Cluster cluster;
  private Transport consensusTransport;
  private QuorumNode quorumNode;
  private LeaseElector elector;
  private ReconfigurationManager reconfigManager; // present only with dynamicQuorum
  private ScheduledExecutorService reconfigTicker;

  /**
   * Creates a prism without the elector (registry only).
   *
   * @param clusterBuilder a configured but not-yet-started {@link ClusterImpl}
   */
  public PrismImpl(ClusterImpl clusterBuilder) {
    this(clusterBuilder, null, Metrics.NOOP);
  }

  /**
   * Creates a prism with the elector enabled.
   *
   * @param clusterBuilder a configured but not-yet-started {@link ClusterImpl}
   * @param prismConfig consensus/quorum configuration enabling {@link #elector()}
   */
  public PrismImpl(ClusterImpl clusterBuilder, PrismConfig prismConfig) {
    this(clusterBuilder, prismConfig, Metrics.NOOP);
  }

  /**
   * Creates a prism with the elector enabled and a metrics sink.
   *
   * @param clusterBuilder a configured but not-yet-started {@link ClusterImpl}
   * @param prismConfig consensus/quorum configuration enabling {@link #elector()} (nullable)
   * @param metrics metrics sink for registry and elector
   */
  public PrismImpl(ClusterImpl clusterBuilder, PrismConfig prismConfig, Metrics metrics) {
    this.clusterBuilder = Objects.requireNonNull(clusterBuilder, "clusterBuilder");
    this.prismConfig = prismConfig;
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    final HybridLogicalClock clock =
        (prismConfig != null && prismConfig.persistenceDir() != null)
            ? new HybridLogicalClock(
                System::currentTimeMillis,
                new FileClockJournal(prismConfig.persistenceDir().resolve("clock.journal")))
            : new HybridLogicalClock();
    this.registry = new GossipServiceRegistry(clock, metrics);
  }

  @Override
  public Mono<Prism> start() {
    return Mono.fromCallable(this::startAwait);
  }

  /** Anti-entropy beacon interval for the registry. */
  private static final Duration ANTI_ENTROPY_INTERVAL = Duration.ofSeconds(5);

  @Override
  public Prism startAwait() {
    this.cluster = clusterBuilder.handler(registry::bind).startAwait();
    registry.start(ANTI_ENTROPY_INTERVAL); // live anti-entropy
    // QUORUM read-repair (lookupQuorum) rides the cluster's own transport — no extra port to bind.
    if (prismConfig != null) {
      startElector();
    }
    return this;
  }

  private void startElector() {
    final int port = Transport.parsePort(prismConfig.consensusAddress());
    final TransportConfig config =
        TransportConfig.defaultConfig()
            .port(port)
            .transportFactory(prismConfig.transportFactory().get());

    final LeaseJournal leaseJournal =
        prismConfig.persistenceDir() != null
            ? new FileLeaseJournal(prismConfig.persistenceDir().resolve("lease.journal"))
            : LeaseJournal.noop();

    this.consensusTransport = Transport.bindAwait(config);
    final LongSupplier clock = System::currentTimeMillis;

    if (prismConfig.dynamicQuorum()) {
      // C0 = the seed; the quorum sizes/heals itself from here (ADR-0015). The committed config
      // chain is journaled (when durable) so a restart resumes it, rather than resetting to C0.
      final ConfigJournal configJournal =
          prismConfig.persistenceDir() != null
              ? new FileConfigJournal(prismConfig.persistenceDir().resolve("config.journal"))
              : ConfigJournal.noop();
      final QuorumConfig quorumConfig =
          new QuorumConfig(prismConfig.quorumMembers(), configJournal);
      // Pass the CONFIGURED consensus address as self (same namespace as members), not
      // transport.address() — so the store recognizes this node as a member and counts its local
      // acceptor (review F2 follow-up: members.contains(self) must be reliable).
      this.quorumNode =
          QuorumNode.attachDynamic(
              consensusTransport,
              prismConfig.consensusAddress(),
              quorumConfig,
              clock,
              prismConfig.callTimeout(),
              leaseJournal);
    } else {
      this.quorumNode =
          QuorumNode.attach(
              consensusTransport,
              prismConfig.consensusAddress(),
              prismConfig.quorumMembers(),
              clock,
              prismConfig.callTimeout(),
              leaseJournal);
    }

    // Randomized acquire backoff (Raft-style [T, 2T]) so dueling Paxos proposers desynchronize;
    // in-process tests/simulation use the no-backoff default (deterministic).
    final long ttlMillis = Math.max(1L, prismConfig.leaseTtl().toMillis());
    this.elector =
        new LeaseElector(
            cluster.member(),
            quorumNode.store(),
            cluster::memberById,
            prismConfig.leaseTtl(),
            clock,
            metrics,
            () -> ttlMillis + ThreadLocalRandom.current().nextLong(ttlMillis));
    this.elector.start(prismConfig.tickInterval());

    if (prismConfig.dynamicQuorum()) {
      startReconfiguration(clock);
    }
  }

  /**
   * Wires the self-electing-quorum reconfiguration loop: a control group whose leader runs the
   * {@link ReconfigurationManager}. The candidate roster and live set are derived from the
   * <b>cluster gossip pool</b> — each node advertises its consensus address as member metadata
   * (ADR-0015) — so the quorum forms and heals from the live cluster instead of a hand-listed set.
   * configured {@code quorumMembers} stays the seed C0 and a fallback until a node has advertised
   * (e.g. while metadata is still propagating).
   */
  private void startReconfiguration(LongSupplier clock) {
    final String self = prismConfig.consensusAddress();
    final QuorumConfig quorumConfig = quorumNode.config();
    final PeerCaller probeCaller = new TransportPeerCaller(consensusTransport);

    advertiseConsensusAddress(); // publish our consensus address to the gossip pool

    final Supplier<List<String>> roster = this::clusterRoster;
    final Supplier<Set<String>> liveMembers = () -> clusterLive(probeCaller, self);

    this.reconfigManager =
        new ReconfigurationManager(
            self,
            QUORUM_CONTROL_GROUP,
            quorumConfig,
            quorumNode.store(),
            new TransportConfigReplicator(
                self, consensusTransport, quorumConfig, roster,
                QUORUM_CONTROL_GROUP, prismConfig.callTimeout()),
            new TransportLeaseTransfer(probeCaller, prismConfig.callTimeout()),
            liveMembers,
            roster,
            prismConfig.targetQuorumSize(),
            clock);

    // Contend for control leadership so exactly one node drives reconfiguration.
    this.elector.campaign(QUORUM_CONTROL_GROUP).block();

    final long millis = prismConfig.tickInterval().toMillis();
    this.reconfigTicker =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "prism-reconfig-" + self);
              t.setDaemon(true);
              return t;
            });
    this.reconfigTicker.scheduleAtFixedRate(
        () -> {
          try {
            reconfigManager.tick();
          } catch (RuntimeException ignored) {
            // a transient transport/quorum hiccup; retried next tick
          }
        },
        millis,
        millis,
        TimeUnit.MILLISECONDS);
  }

  /** Live consensus members = self plus any roster member whose acceptor answers a lease GET. */
  private static Set<String> probeLive(
      PeerCaller caller, List<String> roster, String self, Duration timeout) {
    final Set<String> live = new HashSet<>();
    live.add(self);
    final List<String> peers =
        roster.stream().filter(m -> !m.equals(self)).collect(java.util.stream.Collectors.toList());
    final List<String> responders =
        Flux.fromIterable(peers)
            .flatMap(
                peer ->
                    caller
                        .call(peer, LeaseRequest.get(QUORUM_CONTROL_GROUP))
                        .timeout(timeout)
                        .map(resp -> peer)
                        .onErrorResume(e -> Mono.empty()))
            .collectList()
            .block(timeout.multipliedBy(2));
    if (responders != null) {
      live.addAll(responders);
    }
    return live;
  }

  /**
   * Publishes this node's consensus address to the gossip pool as member metadata, so peers can
   * discover it for the dynamic roster. Non-destructive: merges into existing string-map metadata,
   * and skips if the application uses a non-map metadata object (so it is never clobbered) — the
   * roster then falls back to the configured {@code quorumMembers}. Best-effort.
   */
  private void advertiseConsensusAddress() {
    final Optional<Object> existing = cluster.metadata();
    if (existing.isPresent() && !(existing.get() instanceof Map)) {
      return; // application uses non-map metadata; do not clobber it
    }
    final Map<String, String> metadata =
        new HashMap<>(metadataMap(cluster.member()).orElseGet(HashMap::new));
    metadata.put(MetadataRoster.CONSENSUS_ADDRESS_KEY, prismConfig.consensusAddress());
    try {
      cluster.updateMetadata(metadata).block(prismConfig.callTimeout());
    } catch (RuntimeException ignored) {
      // best-effort; roster falls back to the configured members until the next advertise
    }
  }

  /** Candidate roster: configured seed + self + every consensus address advertised in gossip. */
  private List<String> clusterRoster() {
    final Set<String> roster = new TreeSet<>(prismConfig.quorumMembers());
    roster.add(prismConfig.consensusAddress());
    roster.addAll(MetadataRoster.consensusAddresses(cluster.members(), this::metadataMap));
    return new ArrayList<>(roster);
  }

  /**
   * The live consensus members: those advertised by currently-alive cluster members (the SWIM
   * failure detector), plus self. Falls back to probing the configured roster while no node has
   * advertised yet, so a node never sees an empty live set during metadata propagation.
   */
  private Set<String> clusterLive(PeerCaller probeCaller, String self) {
    final List<String> discovered =
        MetadataRoster.consensusAddresses(cluster.members(), this::metadataMap);
    if (discovered.isEmpty()) {
      return probeLive(probeCaller, prismConfig.quorumMembers(), self, prismConfig.callTimeout());
    }
    final Set<String> live = new HashSet<>(discovered);
    live.add(self);
    return live;
  }

  /** Reads a member's metadata as a string map, or empty if absent / not a string map. */
  private Optional<Map<String, String>> metadataMap(Member member) {
    final Optional<Object> md = cluster.metadata(member);
    if (md.isEmpty() || !(md.get() instanceof Map<?, ?> map)) {
      return Optional.empty();
    }
    final Map<String, String> out = new HashMap<>();
    map.forEach(
        (k, v) -> {
          if (k instanceof String ks && v instanceof String vs) {
            out.put(ks, vs);
          }
        });
    return Optional.of(out);
  }

  @Override
  public ServiceRegistry registry() {
    requireStarted();
    return registry;
  }

  @Override
  public SingletonElector elector() {
    requireStarted();
    if (elector == null) {
      throw new UnsupportedOperationException(
          "elector not configured — construct PrismImpl(cluster, PrismConfig)");
    }
    return elector;
  }

  @Override
  public Cluster cluster() {
    requireStarted();
    return cluster;
  }

  @Override
  public Member member() {
    requireStarted();
    return cluster.member();
  }

  @Override
  public Mono<Void> shutdown() {
    registry.stop();
    if (reconfigTicker != null) {
      reconfigTicker.shutdownNow();
    }
    if (elector != null) {
      elector.stop();
    }
    if (quorumNode != null) {
      quorumNode.stop();
    }
    if (cluster == null) {
      return Mono.empty();
    }
    cluster.shutdown();
    return cluster.onShutdown();
  }

  private void requireStarted() {
    if (cluster == null) {
      throw new IllegalStateException("Prism is not started; call start()/startAwait() first");
    }
  }
}
