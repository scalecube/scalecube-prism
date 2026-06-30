package io.scalecube.prism.sim;

import io.scalecube.cluster.Member;
import io.scalecube.prism.consensus.Acceptor;
import io.scalecube.prism.consensus.ConfigRecord;
import io.scalecube.prism.consensus.ConfigReplicator;
import io.scalecube.prism.consensus.LeaseRequest;
import io.scalecube.prism.consensus.LeaseTransfer;
import io.scalecube.prism.consensus.PeerCaller;
import io.scalecube.prism.consensus.QuorumConfig;
import io.scalecube.prism.consensus.QuorumConsensusStore;
import io.scalecube.prism.consensus.ReconfigurationManager;
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
 * Deterministic, seeded simulator that drives the <b>real</b> self-electing-quorum reconfiguration
 * code — {@link ReconfigurationManager}, a real {@link ConfigReplicator} and {@link LeaseTransfer},
 * over real {@link Acceptor}/{@link QuorumConsensusStore}/{@link LeaseElector} — through staggered,
 * lossy, partitionable in-memory delivery, with concurrent election (ADR-0015).
 *
 * <p>This closes the gap that {@link ReconfigSimCluster} leaves open: that harness reimplements
 * reconfiguration (it mutates the config and transfers the high-water itself), so it validates the
 * rule, not the shipped multi-step rollout. Here every node runs its own real
 * {@code ReconfigurationManager.tick()}: catch-up, leader-only single-member planning, the §7.1
 * high-water carry to a majority of the new config, dissemination via the replicator, and local
 * commit — exactly the concurrent, multi-message path that runs in production.
 *
 * <p>Acceptors and per-node {@link QuorumConfig} persist across the run; each node reads its own
 * (possibly diverging) committed config. The {@link #trueLeaders}/{@link #committedEpoch} god-view
 * oracle evaluates safety against the single authoritative committed chain (the head config and its
 * immediate predecessor), and {@link #configForkDetected} flags two distinct configs ever committed
 * at one epoch — so a second majority-backed leader, a regressed fencing epoch, or a forked config
 * chain is caught.
 */
public final class RealReconfigSimCluster {

  private static final String GROUP = "g";

  private final List<String> pool = new ArrayList<>();
  private final int target;
  private final Duration ttl;
  private final Random random;
  private final AtomicLong clock = new AtomicLong(1000);

  private final Map<String, Member> members = new HashMap<>();
  private final Map<String, Acceptor> acceptors = new HashMap<>();
  private final Map<String, QuorumConfig> configs = new HashMap<>();
  private final Map<String, LeaseElector> electors = new HashMap<>();
  private final Map<String, ReconfigurationManager> managers = new HashMap<>();

  private final Set<String> dead = new HashSet<>();
  private final Set<String> isolated = new HashSet<>();
  private final Set<String> droppedLinks = new HashSet<>();
  private int lossPercent;
  private boolean partitionsEnabled = true;

  // The single authoritative committed config chain (epoch -> members), recorded as commits land
  // — the sound basis for the god-view oracle (per-node configs diverge under catch-up). One config
  // per epoch is an integrity invariant; a second, different config at the same epoch is a fork.
  private final java.util.TreeMap<Long, List<String>> committedChain = new java.util.TreeMap<>();
  private boolean configFork;

  /**
   * Builds a pool of {@code poolSize} nodes seeded with an initial config of the first
   * {@code initialConfigSize}, each running the real reconfiguration manager toward {@code target}.
   *
   * @param poolSize total nodes (the reconfiguration universe / roster)
   * @param initialConfigSize size of the bootstrap config C0
   * @param target target (odd) quorum size
   * @param seed RNG seed (the whole run reproduces from it)
   * @param ttl lease validity
   */
  public RealReconfigSimCluster(
      int poolSize, int initialConfigSize, int target, long seed, Duration ttl) {
    this.target = target;
    this.ttl = ttl;
    this.random = new Random(seed);
    final List<String> seedConfig = new ArrayList<>();
    for (int i = 0; i < poolSize; i++) {
      final String id = "n" + i;
      pool.add(id);
      members.put(id, new Member(id, null, id + "@sim", "prism"));
      acceptors.put(id, new Acceptor());
      if (i < initialConfigSize) {
        seedConfig.add(id);
      }
    }
    for (String self : pool) {
      wire(self, seedConfig);
    }
    committedChain.put(0L, new ArrayList<>(new java.util.TreeSet<>(seedConfig))); // C0 (normalized)
  }

  private void wire(String self, List<String> seedConfig) {
    final QuorumConfig config = new QuorumConfig(seedConfig);
    configs.put(self, config);
    final PeerCaller caller =
        (peer, req) ->
            reachable(self, peer)
                ? Mono.just(acceptors.get(peer).handle(req, clock.get()))
                : Mono.error(new RuntimeException("unreachable"));
    final QuorumConsensusStore store =
        new QuorumConsensusStore(
            self, config::members, acceptors.get(self), caller, clock::get, Duration.ofMillis(10));
    electors.put(
        self,
        new LeaseElector(
            members.get(self), store, id -> Optional.ofNullable(members.get(id)), ttl, clock::get));
    managers.put(
        self,
        new ReconfigurationManager(
            self,
            GROUP,
            config,
            store,
            replicatorFor(self),
            transferFor(self),
            () -> new HashSet<>(alive()),
            () -> new ArrayList<>(pool),
            target,
            clock::get));
  }

  /** Every currently-alive node campaigns for leadership of the (control) group. */
  public void campaignAll() {
    for (String self : alive()) {
      electors.get(self).campaign(GROUP).block();
    }
  }

  /**
   * Advances one chaotic step: clock jump (may cross the TTL), random partition/heal, link loss, an
   * occasional kill/revive, then a tick of every alive elector and reconfiguration manager.
   */
  public void chaosStep() {
    final int dice = random.nextInt(100);
    if (partitionsEnabled && dice < 18) {
      repartition();
    } else if (partitionsEnabled && dice < 28) {
      isolated.clear();
    } else if (dice < 38) {
      killOne();
    } else if (dice < 48) {
      reviveOne();
    }
    injectLoss();
    clock.addAndGet(1 + random.nextInt((int) (ttl.toMillis() * 3 / 2)));
    // Tick electors and managers in a randomized order each step (staggered interleaving).
    final List<String> order = new ArrayList<>(alive());
    java.util.Collections.shuffle(order, random);
    for (String self : order) {
      try {
        electors.get(self).tick();
      } catch (RuntimeException ignored) {
        // a transient unreachable peer; retried next tick
      }
    }
    java.util.Collections.shuffle(order, random);
    for (String self : order) {
      try {
        managers.get(self).tick();
      } catch (RuntimeException ignored) {
        // a transient hiccup mid-rollout; retried next tick
      }
    }
  }

  // ---- in-memory real SPIs ----

  private ConfigReplicator replicatorFor(String self) {
    return new ConfigReplicator() {
      @Override
      public boolean commit(ConfigRecord record, List<String> currentConfig) {
        int acks = currentConfig.contains(self) ? 1 : 0; // self counts only if it is a member
        for (String peer : currentConfig) {
          if (peer.equals(self) || !reachable(self, peer)) {
            continue;
          }
          configs.get(peer).adopt(record.epoch(), record.members());
          if (configs.get(peer).epoch() >= record.epoch()) {
            acks++;
          }
        }
        final boolean committed = acks >= currentConfig.size() / 2 + 1;
        if (committed) {
          final List<String> existing = committedChain.get(record.epoch());
          if (existing != null && !existing.equals(record.members())) {
            configFork = true; // two distinct configs at the same epoch — a fork (real violation)
          }
          committedChain.put(record.epoch(), record.members());
        }
        return committed;
      }

      @Override
      public Optional<ConfigRecord> latestKnown() {
        ConfigRecord best = null;
        for (String peer : pool) {
          if (!peer.equals(self) && !reachable(self, peer)) {
            continue;
          }
          final QuorumConfig.Snapshot snap = configs.get(peer).snapshot();
          if (best == null || snap.epoch() > best.epoch()) {
            best = new ConfigRecord(Math.max(snap.epoch(), 1), snap.members());
          }
        }
        return Optional.ofNullable(best);
      }
    };
  }

  private LeaseTransfer transferFor(String self) {
    return (member, highWater) ->
        reachable(self, member)
            && acceptors.get(member).handle(LeaseRequest.accept(highWater), clock.get()).ok();
  }

  // ---- chaos primitives ----

  private void injectLoss() {
    droppedLinks.clear();
    if (lossPercent <= 0) {
      return;
    }
    for (int i = 0; i < pool.size(); i++) {
      for (int j = i + 1; j < pool.size(); j++) {
        if (random.nextInt(100) < lossPercent) {
          droppedLinks.add(linkKey(pool.get(i), pool.get(j)));
        }
      }
    }
  }

  /** Sets the per-link message loss rate injected each step. */
  public void setMessageLoss(int percent) {
    this.lossPercent = percent;
  }

  /** Enables/disables clean network partitions (loss/kill/churn still apply when disabled). */
  public void setPartitionsEnabled(boolean enabled) {
    this.partitionsEnabled = enabled;
  }

  private void repartition() {
    isolated.clear();
    for (String n : pool) {
      if (random.nextBoolean()) {
        isolated.add(n);
      }
    }
  }

  private void killOne() {
    final List<String> a = alive();
    // Never kill the last node; otherwise kills are unconstrained — the quorum may shrink toward 1
    // under partition and loss. Exercises the full single-member churn (drop-dead + add-live, §7.1
    // high-water carry) and the majority-loss boundary.
    if (a.size() > 1) {
      dead.add(a.get(random.nextInt(a.size())));
    }
  }

  private void reviveOne() {
    if (!dead.isEmpty()) {
      final List<String> d = new ArrayList<>(dead);
      dead.remove(d.get(random.nextInt(d.size())));
    }
  }

  private boolean reachable(String a, String b) {
    if (dead.contains(a) || dead.contains(b)) {
      return false;
    }
    return isolated.contains(a) == isolated.contains(b) && !droppedLinks.contains(linkKey(a, b));
  }

  private List<String> alive() {
    final List<String> out = new ArrayList<>();
    for (String n : pool) {
      if (!dead.contains(n)) {
        out.add(n);
      }
    }
    return out;
  }

  private static String linkKey(String a, String b) {
    return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
  }

  // ---- god-view oracle ----

  /** owner → (node → epoch) for every pool acceptor holding that owner's unexpired lease. */
  private Map<String, Map<String, Long>> backerEpochs() {
    final long now = clock.get();
    final Map<String, Map<String, Long>> perOwner = new HashMap<>();
    for (String n : pool) {
      acceptors
          .get(n)
          .handle(LeaseRequest.get(GROUP), now)
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
   * The configs that can certify a leader right now: the HEAD of the authoritative committed chain
   * and its immediate predecessor ({@code E} and {@code E-1}). During a staggered rollout the
   * leader sits at {@code E} while in-flight leases may rely on {@code E-1} — both are "live".
   * Superseded configs are excluded: a lingering lease under one is at a lower fencing epoch, so
   * it is fenced out, not a second leader. The chain is the single global record of what actually
   * committed, so this is sound even though per-node {@link QuorumConfig}s diverge under catch-up.
   */
  private List<List<String>> activeConfigs() {
    final List<List<String>> out = new ArrayList<>();
    final Long e = committedChain.lastKey();
    out.add(committedChain.get(e));
    final Long prev = committedChain.lowerKey(e);
    if (prev != null) {
      out.add(committedChain.get(prev));
    }
    return out;
  }

  /** True if two distinct configs were ever committed at the same epoch (a config-chain fork). */
  public boolean configForkDetected() {
    return configFork;
  }

  /** Compact per-node state line (config epoch+members and held lease) for trace analysis. */
  public String state() {
    final long now = clock.get();
    final StringBuilder sb = new StringBuilder("chain=" + committedChain + " dead=" + dead + "\n");
    for (String n : pool) {
      final QuorumConfig.Snapshot s = configs.get(n).snapshot();
      sb.append(n).append(" e=").append(s.epoch()).append(s.members());
      configs.get(n); // keep members snapshot
      acceptors.get(n).handle(LeaseRequest.get(GROUP), now).currentLease()
          .ifPresent(l -> sb.append(" L=").append(l.owner()).append('@').append(l.epoch())
              .append(l.isExpired(now) ? "x" : "v"));
      sb.append(reachableNote(n)).append('\n');
    }
    return sb.toString();
  }

  private String reachableNote(String n) {
    return isolated.contains(n) ? " [iso]" : "";
  }

  /**
   * The god-view safety oracle: the number of distinct owners that hold a majority of <em>some</em>
   * active config (current or previous, on any node). Must never exceed 1 — that is "never two
   * leaders" across reconfiguration.
   *
   * @return the number of true (majority-backed) leaders
   */
  public int trueLeaders() {
    final Map<String, Map<String, Long>> backers = backerEpochs();
    final List<List<String>> active = activeConfigs();
    final Set<String> leaders = new HashSet<>();
    for (Map.Entry<String, Map<String, Long>> e : backers.entrySet()) {
      if (certifiedUnderAny(e.getValue().keySet(), active)) {
        leaders.add(e.getKey());
      }
    }
    return leaders.size();
  }

  /**
   * The committed fencing epoch of the current majority-backed leader (highest epoch a majority of
   * some active config holds), or -1 if there is none. Used to assert fencing monotonicity.
   *
   * @return the committed leader epoch, or -1
   */
  public long committedEpoch() {
    final Map<String, Map<String, Long>> backers = backerEpochs();
    final List<List<String>> active = activeConfigs();
    long best = -1;
    for (Map<String, Long> ownerEpochs : backers.values()) {
      for (List<String> cfg : active) {
        best = Math.max(best, committedEpochUnder(ownerEpochs, cfg));
      }
    }
    return best;
  }

  private static boolean certifiedUnderAny(Set<String> backers, List<List<String>> configs) {
    for (List<String> cfg : configs) {
      if (!cfg.isEmpty()) {
        int inCfg = 0;
        for (String n : backers) {
          if (cfg.contains(n)) {
            inCfg++;
          }
        }
        if (inCfg >= cfg.size() / 2 + 1) {
          return true;
        }
      }
    }
    return false;
  }

  private static long committedEpochUnder(Map<String, Long> ownerEpochs, List<String> cfg) {
    if (cfg.isEmpty()) {
      return -1;
    }
    final List<Long> epochs = new ArrayList<>();
    for (String n : cfg) {
      final Long e = ownerEpochs.get(n);
      if (e != null) {
        epochs.add(e);
      }
    }
    final int majority = cfg.size() / 2 + 1;
    if (epochs.size() < majority) {
      return -1;
    }
    epochs.sort((a, b) -> Long.compare(b, a));
    return epochs.get(majority - 1);
  }
}
