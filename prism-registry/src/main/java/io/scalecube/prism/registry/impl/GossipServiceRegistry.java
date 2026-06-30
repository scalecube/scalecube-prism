package io.scalecube.prism.registry.impl;

import io.scalecube.cluster.Cluster;
import io.scalecube.cluster.ClusterMessageHandler;
import io.scalecube.cluster.Member;
import io.scalecube.cluster.membership.MembershipEvent;
import io.scalecube.cluster.transport.api.Message;
import io.scalecube.cluster.transport.api.Transport;
import io.scalecube.prism.metrics.Metrics;
import io.scalecube.prism.registry.ConsistencyTier;
import io.scalecube.prism.registry.QuorumUnavailableException;
import io.scalecube.prism.registry.RegistryEvent;
import io.scalecube.prism.registry.ServiceEntry;
import io.scalecube.prism.registry.ServiceRegistry;
import io.scalecube.prism.version.FreshnessToken;
import io.scalecube.prism.version.Version;
import io.scalecube.prism.versioning.HybridLogicalClock;
import io.scalecube.prism.versioning.HybridTimestamp;
import io.scalecube.prism.versioning.OwnerFreshnessToken;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Gossip-backed {@link ServiceRegistry}: a per-owner, single-writer, versioned catalog disseminated
 * over a scalecube {@link Cluster}. Every node holds the complete catalog locally; a node writes
 * only its own services.
 *
 * <p>Wire it as the cluster's message handler at build time:
 *
 * <pre>{@code
 * GossipServiceRegistry registry = new GossipServiceRegistry();
 * Cluster cluster =
 *     new ClusterImpl()
 *         .transportFactory(TcpTransportFactory::new)
 *         .handler(registry::bind)
 *         .startAwait();
 * }</pre>
 *
 * <p>Convergence is last-writer-wins by {@link Version} (see {@link RegistryStore}); membership
 * death purges the dead owner's entries. State access is confined by a single lock since handler
 * callbacks and user calls run on different threads.
 */
public final class GossipServiceRegistry implements ServiceRegistry {

  private static final Logger LOGGER = LoggerFactory.getLogger(GossipServiceRegistry.class);

  /** Gossip qualifier identifying registry deltas. */
  public static final String QUALIFIER = "sc/prism/registry";

  /** Gossip qualifier for anti-entropy Merkle-root beacons. */
  public static final String AE_QUALIFIER = "sc/prism/registry/ae";

  /** Point-to-point qualifier for a quorum read request (reader → peer). */
  public static final String READ_REQUEST_QUALIFIER = "sc/prism/registry/read/req";

  /** Point-to-point qualifier for a quorum read reply (peer → reader). */
  public static final String READ_RESPONSE_QUALIFIER = "sc/prism/registry/read/resp";

  /** Per-peer budget for a quorum read; a peer slower than this is treated as unreachable. */
  private static final Duration QUORUM_CALL_TIMEOUT = Duration.ofSeconds(3);

  private static final int MERKLE_DEPTH = 8; // 256 buckets

  private static final Sinks.EmitFailureHandler EMIT =
      Sinks.EmitFailureHandler.busyLooping(Duration.ofSeconds(1));

  private final Object lock = new Object();
  private final RegistryStore store = new RegistryStore();
  private final HybridLogicalClock clock;
  private final Metrics metrics;
  private final Sinks.Many<RegistryEvent> sink = Sinks.many().multicast().directBestEffort();

  // Cached anti-entropy Merkle tree (review F7): rebuilding it on every beacon — sent and received
  // — was O(catalog) under the global lock, contending with reads. It is rebuilt lazily only after
  // the catalog changes (rootDirty), so a steady catalog beacons for free; the cached tree also
  // serves the beacon's per-bucket digest and the diff against a peer's digest (review F6 / O2).
  private MerkleTree cachedTree;
  private boolean rootDirty = true;

  private Cluster cluster;
  private ScheduledExecutorService antiEntropy;

  // Point-to-point RPC for QUORUM read-repair (ADR-0002) rides the cluster's OWN transport — the
  // same one SWIM/gossip/metadata use — so prism opens no extra port. The 2.7.8 Cluster facade
  // doesn't expose it (it was moved to the Transport layer and not re-surfaced), so we borrow it
  // reflectively; replace with cluster.transport() once scalecube exposes an accessor. Resolved
  // once, lazily; null (→ local-only lookupQuorum) if it can't be reached.
  private Transport clusterTransport;
  private boolean clusterTransportResolved;

  /** Creates a registry with a wall-clock-backed Hybrid Logical Clock and no metrics. */
  public GossipServiceRegistry() {
    this(new HybridLogicalClock(), Metrics.NOOP);
  }

  /**
   * Creates a registry with a wall-clock HLC and a metrics sink.
   *
   * @param metrics metrics sink
   */
  public GossipServiceRegistry(Metrics metrics) {
    this(new HybridLogicalClock(), metrics);
  }

  /**
   * Creates a registry with an explicit clock (for tests / the simulator).
   *
   * @param clock the version clock to stamp local writes with
   */
  public GossipServiceRegistry(HybridLogicalClock clock) {
    this(clock, Metrics.NOOP);
  }

  /**
   * Creates a registry with an explicit clock and metrics sink.
   *
   * @param clock the version clock to stamp local writes with
   * @param metrics metrics sink
   */
  public GossipServiceRegistry(HybridLogicalClock clock, Metrics metrics) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  /**
   * Binds to a cluster and returns the handler to install via {@code ClusterImpl.handler(...)}.
   * Must be called during cluster construction so inbound gossip and membership events are
   * delivered.
   *
   * @param cluster the cluster this registry rides on
   * @return the message handler to register with the cluster
   */
  public ClusterMessageHandler bind(Cluster cluster) {
    this.cluster = Objects.requireNonNull(cluster, "cluster");
    return new ClusterMessageHandler() {
      @Override
      public void onGossip(Message gossip) {
        if (AE_QUALIFIER.equals(gossip.qualifier())) {
          handleAntiEntropy(gossip);
        } else {
          handleGossip(gossip);
        }
      }

      @Override
      public void onMessage(Message message) {
        if (READ_REQUEST_QUALIFIER.equals(message.qualifier())) {
          answerRead(message);
        }
      }

      @Override
      public void onMembershipEvent(MembershipEvent event) {
        handleMembership(event);
      }
    };
  }

  /**
   * Starts periodic anti-entropy: the node broadcasts its catalog's per-bucket Merkle digest; a
   * peer whose tree differs re-advertises only its <b>owned entries in the differing buckets</b>,
   * so any missed update heals while traffic stays proportional to the delta (and stops once
   * the trees match). Safe to call once after the cluster has started.
   *
   * @param interval beacon interval
   */
  public void start(Duration interval) {
    requireBound();
    synchronized (lock) {
      if (antiEntropy != null) {
        return;
      }
      antiEntropy =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread t = new Thread(r, "prism-registry-ae-" + localId());
                t.setDaemon(true);
                return t;
              });
      long millis = interval.toMillis();
      antiEntropy.scheduleAtFixedRate(
          this::broadcastBeacon, millis, millis, TimeUnit.MILLISECONDS);
    }
  }

  /** Stops periodic anti-entropy. (Quorum-read RPC rides the cluster transport.) */
  public void stop() {
    synchronized (lock) {
      if (antiEntropy != null) {
        antiEntropy.shutdownNow();
        antiEntropy = null;
      }
    }
  }

  @Override
  public Mono<Void> register(String service, Map<String, String> properties, ConsistencyTier tier) {
    return Mono.fromRunnable(
        () -> {
          requireBound();
          final ServiceEntryImpl entry;
          // Stamp version + apply under one lock (review F13): else two concurrent writes
          // to the same key from this node could be stamped in one order and applied in the other,
          // and last-writer-wins would silently drop the newer one. (The owner is still expected to
          // be the single writer of its keys — ADR-0003 — this just makes the local path safe.)
          synchronized (lock) {
            HybridTimestamp version = clock.now();
            entry =
                new ServiceEntryImpl(
                    service, localId(), localAddress(), properties, version, tier, true);
            applyLocal(entry, false);
          }
          spread(entry, false);
        });
  }

  @Override
  public Mono<Void> update(String service, String key, String value) {
    return Mono.fromRunnable(
        () -> {
          requireBound();
          final ServiceEntryImpl entry;
          synchronized (lock) { // stamp + read-modify-write + apply atomically (review F13)
            ServiceEntry current = ownEntry(service);
            Map<String, String> props =
                new LinkedHashMap<>(current != null ? current.properties() : Map.of());
            props.put(key, value);
            ConsistencyTier tier = current != null ? current.tier() : ConsistencyTier.CAUSAL;
            HybridTimestamp version = clock.now();
            entry =
                new ServiceEntryImpl(
                    service, localId(), localAddress(), props, version, tier, true);
            applyLocal(entry, false);
          }
          spread(entry, false);
        });
  }

  @Override
  public Mono<Void> deregister(String service) {
    return Mono.fromRunnable(
        () -> {
          requireBound();
          final ServiceEntryImpl tombstone;
          synchronized (lock) { // stamp + apply atomically (review F13)
            HybridTimestamp version = clock.now();
            tombstone =
                new ServiceEntryImpl(
                    service, localId(), localAddress(), Map.of(), version, ConsistencyTier.CAUSAL,
                    true);
            applyLocal(tombstone, true);
          }
          spread(tombstone, true);
        });
  }

  @Override
  public Collection<ServiceEntry> lookup(String service) {
    synchronized (lock) {
      return store.lookup(service);
    }
  }

  @Override
  public Mono<Collection<ServiceEntry>> lookupQuorum(String service) {
    return Mono.defer(
        () -> {
          requireBound();
          if (clusterTransport() == null) {
            // Cluster transport not reachable (e.g. unknown scalecube layout): degrade to local.
            return Mono.just(localLookup(service));
          }
          metrics.increment("prism.registry.quorum.read");

          // The registry is fully replicated, so a "replica" is just another member. A majority of
          // the live membership is the read quorum; the local copy always counts as one voice, so
          // we only need (majority - 1) peers to reply. Reading a majority and taking the highest
          // version per key guarantees we observe any value a majority already holds (quorum
          // intersection) — that is the freshness QUORUM promises.
          final List<Member> others = new ArrayList<>(cluster.otherMembers());
          final int clusterSize = others.size() + 1; // include self
          final int majority = clusterSize / 2 + 1;
          final int neededPeers = majority - 1;

          if (neededPeers <= 0) {
            // Single-node (or self-is-already-a-majority): the local view IS a quorum.
            return Mono.just(localLookup(service));
          }

          return Flux.fromIterable(others)
              .flatMap(member -> requestFromPeer(member, service))
              .take(neededPeers) // stop as soon as a quorum of peers has answered
              .collectList()
              .map(
                  peerResponses -> {
                    if (peerResponses.size() < neededPeers) {
                      metrics.increment("prism.registry.quorum.unavailable");
                      throw new QuorumUnavailableException(
                          "quorum read of '"
                              + service
                              + "' reached "
                              + (peerResponses.size() + 1)
                              + " of "
                              + clusterSize
                              + " members (majority "
                              + majority
                              + " required)");
                    }
                    return repairAndCollect(service, peerResponses);
                  });
        });
  }

  /**
   * Asks one peer for its records of {@code service} over the cluster's own transport, addressed
   * by the peer's membership address. The reply arrives by correlation id; a failed peer abstains.
   */
  private Mono<List<RegistryGossip>> requestFromPeer(Member member, String service) {
    final Message request =
        Message.withData(RegistryReadCodec.encodeRequest(service))
            .qualifier(READ_REQUEST_QUALIFIER)
            .correlationId(UUID.randomUUID().toString())
            .build(); // the cluster transport stamps the sender (SenderAwareTransport)
    return clusterTransport()
        .requestResponse(member.address(), request)
        .timeout(QUORUM_CALL_TIMEOUT)
        .map(reply -> RegistryReadCodec.decodeResponse(reply.data()))
        .onErrorResume(ex -> Mono.empty()); // a slow/failed peer simply abstains
  }

  @Override
  public Collection<ServiceEntry> list() {
    synchronized (lock) {
      return store.list();
    }
  }

  @Override
  public Flux<RegistryEvent> watch() {
    // Best-effort, not gap-free (review F12): a snapshot, then the live event sink.
    // An event applied AFTER the snapshot is taken but BEFORE the sink subscription becomes active
    // (the sink is a best-effort multicast with no replay) can fall in the gap. The convergent
    // source of truth is the store: a consumer that needs certainty should reconcile via
    // lookup()/list() (and freshness() for read-your-writes) rather than treat watch() as a durable
    // log. The catalog still converges by CRDT merge + Merkle anti-entropy regardless.
    return Flux.defer(
            () -> {
              final List<RegistryEvent> snapshot;
              synchronized (lock) {
                snapshot =
                    store.list().stream()
                        .map(e -> new RegistryEvent(RegistryEvent.Type.REGISTERED, e))
                        .collect(Collectors.toList());
              }
              return Flux.fromIterable(snapshot).concatWith(sink.asFlux());
            })
        .onBackpressureBuffer();
  }

  @Override
  public FreshnessToken freshness(String ownerId) {
    final Version version;
    synchronized (lock) {
      version = store.highestVersion(ownerId).orElse(new HybridTimestamp(0, 0));
    }
    return new OwnerFreshnessToken(ownerId, version);
  }

  // ================================================
  // ============== Internal ========================
  // ================================================

  void broadcastBeacon() {
    metrics.increment("prism.registry.ae.beacon");
    final Message beacon =
        Message.withData(MerkleDigestCodec.encode(localTree()))
            .qualifier(AE_QUALIFIER)
            .build();
    cluster.spreadGossip(beacon).subscribe(null, ex -> { });
  }

  private void handleAntiEntropy(Message beacon) {
    final long[] remoteLeaves = MerkleDigestCodec.decode(beacon.data());
    final MerkleTree local = localTree();
    if (remoteLeaves == null || remoteLeaves.length != (1 << MERKLE_DEPTH)) {
      // Unrecognised beacon (e.g. an older root-only format) — fall back to a safe full sync.
      reAdvertiseOwned();
      return;
    }
    // Exchange only the differing buckets (review F6 / O2): compare our tree to the peer's digest
    // and re-advertise just our owned entries in the buckets that differ. diff() prunes at the root
    // when the trees match, so a converged pair does no work.
    final Set<Integer> differing =
        local.diff(MerkleTree.fromBucketHashes(MERKLE_DEPTH, remoteLeaves));
    if (!differing.isEmpty()) {
      reAdvertiseBuckets(differing);
    }
  }

  private MerkleTree localTree() {
    synchronized (lock) {
      if (rootDirty || cachedTree == null) {
        cachedTree = new MerkleTree(MERKLE_DEPTH, store.contentDigest());
        rootDirty = false;
      }
      return cachedTree;
    }
  }

  /** Re-advertises only this node's owned entries whose key falls in a differing bucket. */
  private void reAdvertiseBuckets(Set<Integer> differing) {
    metrics.increment("prism.registry.ae.readvertise");
    final List<RegistryStore.OwnedDelta> deltas;
    synchronized (lock) {
      deltas = store.ownedDeltas(localId());
    }
    int sent = 0;
    for (RegistryStore.OwnedDelta d : deltas) {
      final String key = d.entry().owner() + "/" + d.entry().service();
      if (differing.contains(MerkleTree.bucketOf(key, MERKLE_DEPTH))) {
        spread(d.entry(), d.tombstone());
        sent++;
      }
    }
    metrics.gauge("prism.registry.ae.readvertise.entries", sent);
  }

  /** Full-slice re-advertise: the fallback when a peer's beacon can't be diffed. */
  private void reAdvertiseOwned() {
    metrics.increment("prism.registry.ae.readvertise");
    final List<RegistryStore.OwnedDelta> deltas;
    synchronized (lock) {
      deltas = store.ownedDeltas(localId());
    }
    for (RegistryStore.OwnedDelta d : deltas) {
      spread(d.entry(), d.tombstone());
    }
  }

  private void handleGossip(Message gossip) {
    if (!QUALIFIER.equals(gossip.qualifier())) {
      return;
    }
    final RegistryGossip delta = RegistryGossipCodec.decode(gossip.data());
    if (delta.owner().equals(localId())) {
      return; // our own data — we are the sole writer, ignore echoes
    }
    final HybridTimestamp remote = new HybridTimestamp(delta.physical(), delta.logical());
    synchronized (lock) {
      clock.update(remote);
      store.apply(delta.toEntry(), delta.tombstone()).ifPresent(this::onChanged);
    }
  }

  private void handleMembership(MembershipEvent event) {
    if (!event.isRemoved()) {
      return;
    }
    final String ownerId = event.member().id();
    synchronized (lock) {
      for (RegistryEvent e : store.purgeOwner(ownerId)) {
        onChanged(e);
      }
    }
  }

  private void applyLocal(ServiceEntryImpl entry, boolean tombstone) {
    synchronized (lock) {
      store.apply(entry, tombstone).ifPresent(this::onChanged);
    }
  }

  /** A change landed: invalidate the cached anti-entropy root, then publish the event. */
  private void onChanged(RegistryEvent event) {
    rootDirty = true; // always under `lock` (all callers hold it)
    emit(event);
  }

  private void spread(ServiceEntryImpl entry, boolean tombstone) {
    Message message =
        Message.withData(RegistryGossipCodec.encode(RegistryGossip.of(entry, tombstone)))
            .qualifier(QUALIFIER)
            .build();
    cluster
        .spreadGossip(message)
        .subscribe(
            null,
            ex ->
                LOGGER.debug(
                    "[{}] failed to spread registry delta: {}", localId(), ex.toString()));
  }

  /**
   * Answers a peer's quorum read (delivered on the cluster transport via {@code onMessage}): ship
   * our records for the service (live AND tombstones), replying on the same transport by corr id.
   */
  private void answerRead(Message request) {
    final Transport transport = clusterTransport();
    if (transport == null) {
      return; // can't reply without the cluster transport; requester will time the peer out
    }
    final String service = RegistryReadCodec.decodeRequest(request.data());
    final List<RegistryGossip> records = new ArrayList<>();
    synchronized (lock) {
      for (RegistryStore.OwnedDelta d : store.recordsForService(service)) {
        records.add(RegistryGossip.of(d.entry(), d.tombstone()));
      }
    }
    final Message reply =
        Message.withData(RegistryReadCodec.encodeResponse(records))
            .qualifier(READ_RESPONSE_QUALIFIER)
            .correlationId(request.correlationId())
            .build();
    transport.send(request.sender(), reply).subscribe(null, ex -> { });
  }

  /**
   * Returns the cluster's own transport — the one SWIM/gossip/metadata already use — so quorum-read
   * RPC opens no extra port. scalecube 2.7.8 hides it from the {@code Cluster} facade (it lives on
   * the internal {@code Transport}), so we read the private field reflectively, once. Returns null
   * (→ {@code lookupQuorum} degrades to a local read) if the layout is unknown. TODO: switch to a
   * {@code cluster.transport()} accessor once scalecube provides one.
   */
  private Transport clusterTransport() {
    synchronized (lock) {
      if (!clusterTransportResolved) {
        clusterTransportResolved = true;
        clusterTransport = resolveClusterTransport(cluster);
        if (clusterTransport == null) {
          LOGGER.warn(
              "[{}] could not access the cluster transport; lookupQuorum() falls back to a local"
                  + " read. Expose cluster.transport() upstream to enable quorum reads.",
              localId());
        }
      }
      return clusterTransport;
    }
  }

  private static Transport resolveClusterTransport(Cluster cluster) {
    for (Class<?> c = cluster.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
      try {
        final java.lang.reflect.Field field = c.getDeclaredField("transport");
        if (Transport.class.isAssignableFrom(field.getType())) {
          field.setAccessible(true);
          return (Transport) field.get(cluster);
        }
      } catch (NoSuchFieldException ignored) {
        // try the superclass
      } catch (ReflectiveOperationException | RuntimeException e) {
        return null; // field exists but is inaccessible (e.g. JPMS) — degrade gracefully
      }
    }
    return null;
  }

  /**
   * Merges the quorum's replies into the local store (last-writer-wins repair) and returns the
   * freshest live view of the service. Anything newer than what we held is applied through the same
   * path as inbound gossip, so a repaired entry also advances the clock and emits a watch event.
   */
  private Collection<ServiceEntry> repairAndCollect(
      String service, List<List<RegistryGossip>> peerResponses) {
    int repaired = 0;
    synchronized (lock) {
      for (List<RegistryGossip> response : peerResponses) {
        for (RegistryGossip g : response) {
          final HybridTimestamp remote = new HybridTimestamp(g.physical(), g.logical());
          clock.update(remote);
          final Optional<RegistryEvent> change = store.apply(g.toEntry(), g.tombstone());
          if (change.isPresent()) {
            repaired++;
            onChanged(change.get());
          }
        }
      }
      metrics.gauge("prism.registry.quorum.repaired", repaired);
      return store.lookup(service);
    }
  }

  private Collection<ServiceEntry> localLookup(String service) {
    synchronized (lock) {
      return store.lookup(service);
    }
  }

  private ServiceEntry ownEntry(String service) {
    synchronized (lock) {
      return store.lookup(service).stream()
          .filter(e -> e.owner().equals(localId()))
          .findFirst()
          .orElse(null);
    }
  }

  private void emit(RegistryEvent event) {
    metrics.increment("prism.registry.event." + event.type().name().toLowerCase(Locale.ROOT));
    sink.emitNext(event, EMIT);
  }

  private String localId() {
    return cluster.member().id();
  }

  private String localAddress() {
    return cluster.member().address();
  }

  private void requireBound() {
    if (cluster == null) {
      throw new IllegalStateException("GossipServiceRegistry is not bound to a cluster");
    }
  }
}
