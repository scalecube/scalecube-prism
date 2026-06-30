package io.scalecube.prism.sim;

import io.scalecube.cluster.Member;
import io.scalecube.prism.consensus.Acceptor;
import io.scalecube.prism.consensus.LeaseRequest;
import io.scalecube.prism.consensus.PeerCaller;
import io.scalecube.prism.consensus.QuorumConsensusStore;
import io.scalecube.prism.elector.impl.LeaseElector;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Mono;

/**
 * Deterministic, seeded simulator for the <b>self-electing (dynamic) quorum</b> of ADR-0015 — the
 * companion to {@code SelfElectingQuorum.tla}. Where {@link SimCluster} fixes the membership,
 * this harness evolves a committed configuration over a fixed pool of acceptors and drives the
 * <b>real</b> safety kernel ({@link Acceptor} + {@link QuorumConsensusStore} +
 * {@link LeaseElector}) through it. The reconfiguration policy (single-member changes) is enforced
 * by the harness; the property under test is that it preserves "never two leaders".
 *
 * <p>Acceptors are created once and <b>persist across reconfiguration</b>: a node dropped from the
 * config keeps any lease it accepted, which is exactly the {@code prevConfig} overlap mechanism the
 * model relies on during a configuration change. Leases age out via the virtual clock and the TTL.
 *
 * <p>The {@link #trueLeaders(String)} oracle mirrors the spec's {@code Leader(o)}: an owner is a
 * leader if a majority of the <em>current</em> config OR a majority of the <em>previous</em> config
 * currently holds its unexpired lease. With single-member reconfiguration the two majorities always
 * overlap, so the count can never exceed 1 — that is what the fuzz asserts.
 *
 * <p>Limitation (shared with {@link SimCluster}): isolation models a pause/partition, not a
 * state-losing crash; durable acceptors are needed before modelling hard crash-recovery here.
 */
public final class ReconfigSimCluster {

  private final int poolSize;
  private final int minQuorum;
  private final Duration ttl;
  private final Random random;
  private final AtomicLong clock = new AtomicLong(1000);

  private final List<String> pool = new ArrayList<>();
  private final Map<String, Member> memberObjs = new HashMap<>();
  private final Map<String, Acceptor> acceptors = new HashMap<>(); // persistent, one per pool node
  private final Map<String, QuorumConsensusStore> stores = new HashMap<>(); // current config only
  private final Map<String, LeaseElector> electors = new HashMap<>(); // current config only
  private final Set<String> campaigning = new HashSet<>(); // leadership intent, survives rebuilds

  private List<String> config = new ArrayList<>();
  private List<String> prevConfig = new ArrayList<>();

  private final Set<String> isolated = new HashSet<>();
  private final Set<String> dropped = new HashSet<>();
  private int lossPercent;
  private String group = "g";

  /**
   * Builds a pool of {@code poolSize} acceptors and an initial committed configuration of the first
   * {@code initialConfigSize} of them.
   *
   * @param poolSize total potential acceptors (the reconfiguration universe)
   * @param initialConfigSize size of the bootstrap configuration C0 (e.g. seed roster)
   * @param minQuorum smallest configuration the harness will shrink to (≥ 1)
   * @param seed RNG seed (the whole run reproduces from it)
   * @param ttl lease validity
   */
  public ReconfigSimCluster(
      int poolSize, int initialConfigSize, int minQuorum, long seed, Duration ttl) {
    this.poolSize = poolSize;
    this.minQuorum = Math.max(1, minQuorum);
    this.ttl = ttl;
    this.random = new Random(seed);
    for (int i = 0; i < poolSize; i++) {
      String id = "n" + i;
      pool.add(id);
      acceptors.put(id, new Acceptor());
      memberObjs.put(id, new Member(id, null, id + "@sim", "prism"));
    }
    for (int i = 0; i < initialConfigSize; i++) {
      config.add(pool.get(i));
    }
    prevConfig = new ArrayList<>(config);
    rebuild();
  }

  /** Rebuilds stores/electors for the current config; re-issues campaign for intent-holders. */
  private void rebuild() {
    stores.clear();
    electors.clear();
    for (String self : config) {
      PeerCaller caller =
          (peer, req) ->
              cut(self, peer)
                  ? Mono.error(new RuntimeException("partitioned"))
                  : Mono.just(acceptors.get(peer).handle(req, clock.get()));
      QuorumConsensusStore store =
          new QuorumConsensusStore(
              self, config, acceptors.get(self), caller, clock::get, Duration.ofMillis(10));
      stores.put(self, store);
      electors.put(
          self,
          new LeaseElector(
              memberObjs.get(self),
              store,
              id -> Optional.ofNullable(memberObjs.get(id)),
              ttl,
              clock::get));
    }
    // Preserve leadership intent across the rebuild so the incumbent keeps renewing (stickiness).
    for (String self : config) {
      if (campaigning.contains(self)) {
        electors.get(self).campaign(group).block();
      }
    }
  }

  private boolean cut(String a, String b) {
    return isolated.contains(a) != isolated.contains(b) || dropped.contains(linkKey(a, b));
  }

  private static String linkKey(String a, String b) {
    return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
  }

  /**
   * Sets the per-message loss rate injected each chaos step.
   *
   * @param percent loss probability per link per step, 0..100
   */
  public void setMessageLoss(int percent) {
    this.lossPercent = percent;
  }

  /**
   * Every node in the current configuration campaigns for the group.
   *
   * @param group the election group
   */
  public void campaignAll(String group) {
    this.group = group;
    for (String self : config) {
      campaigning.add(self);
      electors.get(self).campaign(group).block();
    }
  }

  /**
   * Advances one chaotic step: a random partition/heal or a <b>single-member reconfiguration</b>
   * (grow or shrink, respecting the pool and {@code minQuorum} bounds), then transient link loss, a
   * virtual-time jump that may cross the TTL, an occasional re-campaign, and a tick of every node.
   *
   * @param group the election group
   */
  public void chaosStep(String group) {
    this.group = group;
    int dice = random.nextInt(100);
    if (dice < 20) {
      repartition();
    } else if (dice < 30) {
      isolated.clear();
    } else if (dice < 50) {
      reconfigureSingleMember();
    }
    injectLoss();
    clock.addAndGet(random.nextInt((int) (ttl.toMillis() * 3 / 2)));
    if (random.nextInt(100) < 15 && !config.isEmpty()) {
      String n = config.get(random.nextInt(config.size()));
      campaigning.add(n);
      electors.get(n).campaign(group).block();
    }
    tickAll();
  }

  /**
   * Commits a single-member configuration change (the safe rule): add one pool node or remove one
   * current member, keeping the size within {@code [minQuorum, poolSize]}. The pre-change config is
   * retained as {@code prevConfig}, modelling in-flight leases that span the change.
   */
  public void reconfigureSingleMember() {
    List<String> candidatesToAdd = new ArrayList<>();
    for (String n : pool) {
      if (!config.contains(n)) {
        candidatesToAdd.add(n);
      }
    }
    boolean canGrow = !candidatesToAdd.isEmpty();
    boolean canShrink = config.size() > minQuorum;
    if (!canGrow && !canShrink) {
      return;
    }
    boolean grow = canGrow && (!canShrink || random.nextBoolean());
    List<String> next = new ArrayList<>(config);
    if (grow) {
      next.add(candidatesToAdd.get(random.nextInt(candidatesToAdd.size())));
    } else {
      next.remove(random.nextInt(next.size()));
    }
    prevConfig = new ArrayList<>(config);
    config = next;
    stateTransfer(); // a joining member must learn the fencing high-water (see method javadoc)
    rebuild();
  }

  /**
   * Models the mandatory <b>fencing high-water state transfer</b> step of the reconfiguration
   * protocol (ADR-0015 §7). When the configuration changes, every new-config member adopts the
   * highest-epoch lease record visible across the previous-and-current configs (their majorities
   * overlap under single-member reconfiguration, so the previous committed high-water is always
   * visible). Without this step, removing the nodes that remember a high epoch would let a new
   * leader win on fresh members at a <em>lower</em> epoch — preserving mutual exclusion but
   * regressing the fencing token, which DST surfaced (ADR-0015 §13.2 / §14). The transferred record
   * is written as-is (expiry unchanged), so it only raises the epoch floor; never a live backer.
   */
  private void stateTransfer() {
    var highWater = highestKnownLease(group);
    if (highWater == null) {
      return;
    }
    final long now = clock.get();
    for (String n : config) {
      var stored = acceptors.get(n).handle(LeaseRequest.get(group), now).currentLease();
      if (stored.isEmpty() || stored.get().epoch() < highWater.epoch()) {
        acceptors.get(n).handle(LeaseRequest.accept(highWater), now);
      }
    }
  }

  /** Highest-epoch lease record (any expiry) across the previous-and-current config, or null. */
  private io.scalecube.prism.consensus.LeaseRecord highestKnownLease(String group) {
    final long now = clock.get();
    final Set<String> scope = new HashSet<>(prevConfig);
    scope.addAll(config);
    io.scalecube.prism.consensus.LeaseRecord best = null;
    for (String n : scope) {
      var lease = acceptors.get(n).handle(LeaseRequest.get(group), now).currentLease();
      if (lease.isPresent() && (best == null || lease.get().epoch() > best.epoch())) {
        best = lease.get();
      }
    }
    return best;
  }

  /**
   * <b>Negative control</b> — commits a multi-member jump (the unsafe rule) <b>without</b> the
   * state-transfer step. Used only to prove the {@link #trueLeaders(String)} oracle has teeth: with
   * disjoint majorities it can observe two leaders, exactly as TLC's
   * {@code SelfElectingQuorum_unsafe.cfg} counterexample predicts.
   *
   * @param newConfig the configuration to jump to (may differ by more than one member)
   */
  public void forceConfig(List<String> newConfig) {
    prevConfig = new ArrayList<>(config);
    config = new ArrayList<>(newConfig);
    rebuild();
  }

  private void injectLoss() {
    dropped.clear();
    if (lossPercent <= 0) {
      return;
    }
    for (int i = 0; i < poolSize; i++) {
      for (int j = i + 1; j < poolSize; j++) {
        if (random.nextInt(100) < lossPercent) {
          dropped.add(linkKey(pool.get(i), pool.get(j)));
        }
      }
    }
  }

  private void repartition() {
    isolated.clear();
    for (String m : config) {
      if (random.nextBoolean()) {
        isolated.add(m);
      }
    }
  }

  /** Ticks every node currently in the configuration. */
  public void tickAll() {
    for (LeaseElector e : electors.values()) {
      e.tick();
    }
  }

  /**
   * The god-view safety oracle, mirroring the spec's {@code Leader(o)}: the number of distinct
   * owners that hold a majority of the <em>current</em> config OR a majority of the
   * <em>previous</em> config (unexpired leases only, over the whole pool). Must never exceed 1.
   *
   * @param group the election group
   * @return the number of true (majority-backed, current-or-previous config) leaders
   */
  public int trueLeaders(String group) {
    final long now = clock.get();
    final Map<String, Set<String>> backers = backersByOwner(group, now);
    final Set<String> leaders = new HashSet<>();
    for (Map.Entry<String, Set<String>> e : backers.entrySet()) {
      if (certifiedUnder(e.getValue(), config) || certifiedUnder(e.getValue(), prevConfig)) {
        leaders.add(e.getKey());
      }
    }
    return leaders.size();
  }

  /**
   * The <b>committed</b> fencing epoch of the current majority-backed leader (under the current or
   * previous config), or {@code -1} if there is none. "Committed" means the highest epoch a
   * <em>majority</em> of the config actually holds — a half-written higher epoch that never reached
   * a quorum was never a valid fencing token, so it does not count. This is the honest measure for
   * asserting fencing-epoch monotonicity across reconfiguration.
   *
   * @param group the election group
   * @return the leader's committed epoch, or -1
   */
  public long currentLeaderEpoch(String group) {
    final long now = clock.get();
    final Map<String, Map<String, Long>> backers = backerEpochsByOwner(group, now);
    long best = -1;
    for (Map.Entry<String, Map<String, Long>> e : backers.entrySet()) {
      best = Math.max(best, committedEpochUnder(e.getValue(), config));
      best = Math.max(best, committedEpochUnder(e.getValue(), prevConfig));
    }
    return best;
  }

  /** Highest epoch a majority of {@code cfg} holds for this owner, or -1 if not certified there. */
  private long committedEpochUnder(Map<String, Long> ownerEpochs, List<String> cfg) {
    if (cfg.isEmpty()) {
      return -1;
    }
    final List<Long> epochsInCfg = new ArrayList<>();
    for (String n : cfg) {
      Long e = ownerEpochs.get(n);
      if (e != null) {
        epochsInCfg.add(e);
      }
    }
    final int majority = cfg.size() / 2 + 1;
    if (epochsInCfg.size() < majority) {
      return -1;
    }
    epochsInCfg.sort((a, b) -> Long.compare(b, a)); // descending
    return epochsInCfg.get(majority - 1); // highest e with >= majority holders at epoch >= e
  }

  private boolean certifiedUnder(Set<String> ownerBackers, List<String> cfg) {
    if (cfg.isEmpty()) {
      return false;
    }
    int inCfg = 0;
    for (String n : ownerBackers) {
      if (cfg.contains(n)) {
        inCfg++;
      }
    }
    return inCfg >= cfg.size() / 2 + 1;
  }

  /** owner → set of pool nodes currently holding that owner's unexpired lease. */
  private Map<String, Set<String>> backersByOwner(String group, long now) {
    final Map<String, Set<String>> perOwner = new HashMap<>();
    backerEpochsByOwner(group, now)
        .forEach((owner, epochs) -> perOwner.put(owner, new HashSet<>(epochs.keySet())));
    return perOwner;
  }

  /** owner → (node → epoch) for every pool node currently holding that owner's unexpired lease. */
  private Map<String, Map<String, Long>> backerEpochsByOwner(String group, long now) {
    final Map<String, Map<String, Long>> perOwner = new HashMap<>();
    for (String n : pool) {
      acceptors
          .get(n)
          .handle(LeaseRequest.get(group), now)
          .currentLease()
          .filter(lease -> !lease.isExpired(now))
          .ifPresent(
              lease ->
                  perOwner
                      .computeIfAbsent(lease.owner(), k -> new HashMap<>())
                      .put(n, lease.epoch()));
    }
    return perOwner;
  }

  /**
   * Test hook: grants a lease directly on one node's acceptor, bypassing the (deliberately
   * conservative) elector. This drives the raw {@code Accept} action of the model — the only way to
   * <em>construct</em> the split-brain that an unsafe multi-member jump permits, so that the
   * {@link #trueLeaders(String)} oracle can be shown to detect it.
   *
   * @param group the election group
   * @param node the acceptor to write
   * @param owner the lease owner to grant
   * @param epoch the fencing epoch
   * @param ttlFromNow lease validity from the current virtual time
   */
  public void grantRaw(String group, String node, String owner, long epoch, long ttlFromNow) {
    final long now = clock.get();
    acceptors
        .get(node)
        .handle(
            io.scalecube.prism.consensus.LeaseRequest.accept(
                new io.scalecube.prism.consensus.LeaseRecord(
                    group, owner, epoch, now + ttlFromNow)),
            now);
  }

  /**
   * The current committed configuration (the members that count toward a quorum right now).
   *
   * @return an immutable view of the current config
   */
  public List<String> currentConfig() {
    return List.copyOf(config);
  }

  /** Clears all partitions and lost links (full connectivity). */
  public void heal() {
    isolated.clear();
    dropped.clear();
    lossPercent = 0;
  }
}
