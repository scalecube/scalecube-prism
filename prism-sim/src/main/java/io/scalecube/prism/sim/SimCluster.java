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
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Mono;

/**
 * A deterministic, seeded in-process simulator for the quorum elector. Every source of
 * nondeterminism — time and the network — is driven by a virtual clock and a seeded {@link Random},
 * so a scenario is fully reproducible from its seed (the FoundationDB / TigerBeetle approach,
 * scoped to the safety-critical consensus layer).
 *
 * <p>It models a 2-way partition ({@code isolated} | {@code rest}), virtual-time jumps that cross
 * the lease TTL, and (via {@link #setClockSkew(long)}) a fixed per-acceptor clock skew so nodes
 * disagree on "now" — directly exercising the fencing-covered zombie window. The
 * {@link #trueLeaders(String)} check is a <b>god-view</b> over every acceptor's stored state, each
 * judged by that acceptor's own local clock — independent of what any elector believes — so it is
 * an honest safety oracle.
 *
 * <p>Limitation: acceptors keep state across isolation (a pause/partition, not a state-losing
 * crash). Durable acceptors (persistence) are required before modelling hard crash-recovery — a
 * real production gap tracked for {@code prism-persistence}.
 */
public final class SimCluster {

  private final int nodeCount;
  private final Duration ttl;
  private final Random random;
  private final AtomicLong clock = new AtomicLong(1000);

  private final List<String> members = new ArrayList<>();
  private final Map<String, Acceptor> acceptors = new HashMap<>();
  private final Map<String, QuorumConsensusStore> stores = new HashMap<>();
  private final Map<String, LeaseElector> electors = new HashMap<>();
  private final Set<String> isolated = new HashSet<>();
  private final Set<String> dropped = new HashSet<>(); // transient per-step lost links
  private final Set<String> down = new HashSet<>(); // killed nodes (unreachable, not ticking)
  private final Map<String, Long> skew = new HashMap<>(); // per-node clock offset (ms), default 0
  private int lossPercent;

  /**
   * Builds a cluster of {@code nodeCount} acceptors + electors wired over a seeded simulated net.
   *
   * @param nodeCount number of quorum members
   * @param seed RNG seed (the whole run is reproducible from it)
   * @param ttl lease validity
   */
  public SimCluster(int nodeCount, long seed, Duration ttl) {
    this.nodeCount = nodeCount;
    this.ttl = ttl;
    this.random = new Random(seed);

    final Map<String, Member> memberObjs = new HashMap<>();
    for (int i = 0; i < nodeCount; i++) {
      String id = "n" + i;
      members.add(id);
      acceptors.put(id, new Acceptor());
      memberObjs.put(id, new Member(id, null, id + "@sim", "prism"));
    }
    for (String self : members) {
      PeerCaller caller =
          (peer, req) ->
              cut(self, peer)
                  ? Mono.error(new RuntimeException("partitioned"))
                  // the peer's acceptor evaluates expiry against ITS OWN (skewed) clock
                  : Mono.just(acceptors.get(peer).handle(req, localTime(peer)));
      stores.put(
          self,
          new QuorumConsensusStore(
              self,
              members,
              acceptors.get(self),
              caller,
              () -> localTime(self),
              Duration.ofMillis(10)));
      electors.put(
          self,
          new LeaseElector(
              memberObjs.get(self),
              stores.get(self),
              id -> java.util.Optional.ofNullable(memberObjs.get(id)),
              ttl,
              () -> localTime(self)));
    }
  }

  private boolean cut(String a, String b) {
    return down.contains(a)
        || down.contains(b)
        || isolated.contains(a) != isolated.contains(b)
        || dropped.contains(linkKey(a, b));
  }

  private static String linkKey(String a, String b) {
    return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
  }

  /**
   * Sets the per-message loss rate injected each chaos step (deterministically from the seed).
   *
   * @param percent loss probability per link per step, 0..100
   */
  public void setMessageLoss(int percent) {
    this.lossPercent = percent;
  }

  /** Every node campaigns for the group. */
  public void campaignAll(String group) {
    for (LeaseElector e : electors.values()) {
      e.campaign(group).block();
    }
  }

  /** All member ids in the cluster (alive or killed). */
  public List<String> members() {
    return List.copyOf(members);
  }

  /** Currently-alive member ids (not killed). */
  public List<String> aliveMembers() {
    final List<String> alive = new ArrayList<>();
    for (String m : members) {
      if (!down.contains(m)) {
        alive.add(m);
      }
    }
    return alive;
  }

  /** Currently-killed member ids. */
  public List<String> downMembers() {
    return new ArrayList<>(down);
  }

  /**
   * One member campaigns for one group (lets a test build a multi-group, random-order workload).
   *
   * @param member the node id
   * @param group the election group
   */
  public void campaign(String member, String group) {
    electors.get(member).campaign(group).block();
  }

  /**
   * Kills a node: it stops ticking and becomes unreachable (its acceptor state is frozen — a
   * crash/pause, not state loss). A killed node holds no live influence; its leases age out.
   *
   * @param member the node id to kill
   */
  public void kill(String member) {
    down.add(member);
  }

  /**
   * Revives a previously-killed node with its (durable) acceptor state intact; its elector resumes
   * ticking and renews/recampaigns the groups it had joined.
   *
   * @param member the node id to revive
   */
  public void revive(String member) {
    down.remove(member);
  }

  /**
   * One group-agnostic chaotic step shared by all groups: a random partition/heal, transient link
   * loss, and a virtual-time jump that may cross the lease TTL — then every live node ticks (renews
   * or acquires for <em>all</em> its groups). Node kill/revive is driven by the caller.
   */
  public void chaosStep() {
    int dice = random.nextInt(100);
    if (dice < 25) {
      repartition();
    } else if (dice < 40) {
      isolated.clear();
    }
    injectLoss();
    clock.addAndGet(random.nextInt((int) (ttl.toMillis() * 3 / 2)));
    tickAll();
  }

  /**
   * Advances one chaotic step: a random partition/heal, a virtual-time jump (which may cross the
   * lease TTL and trigger failover), an occasional re-campaign — then every reachable node ticks.
   *
   * @param group the election group
   */
  public void chaosStep(String group) {
    int dice = random.nextInt(100);
    if (dice < 25) {
      repartition();
    } else if (dice < 40) {
      isolated.clear();
    }
    injectLoss(); // transient lost links for this step (deterministic from the seed)
    clock.addAndGet(random.nextInt((int) (ttl.toMillis() * 3 / 2))); // up to 1.5x TTL → can expire
    if (random.nextInt(100) < 15) {
      electors.get(members.get(random.nextInt(nodeCount))).campaign(group).block();
    }
    tickAll();
  }

  private void injectLoss() {
    dropped.clear();
    if (lossPercent <= 0) {
      return;
    }
    for (int i = 0; i < nodeCount; i++) {
      for (int j = i + 1; j < nodeCount; j++) {
        if (random.nextInt(100) < lossPercent) {
          dropped.add(linkKey(members.get(i), members.get(j)));
        }
      }
    }
  }

  private void repartition() {
    isolated.clear();
    for (String m : members) {
      if (random.nextBoolean()) {
        isolated.add(m);
      }
    }
  }

  /** Ticks every live node (an isolated node fails to renew; a killed node does not tick). */
  public void tickAll() {
    for (String m : members) {
      if (!down.contains(m)) {
        electors.get(m).tick();
      }
    }
  }

  /**
   * The god-view safety oracle: how many distinct owners currently hold a majority of acceptors
   * with an unexpired lease. Must never exceed 1.
   *
   * @param group the election group
   * @return the number of true (majority-backed) leaders
   */
  public int trueLeaders(String group) {
    final int majority = nodeCount / 2 + 1;
    return (int) backersByOwner(group).values().stream().filter(c -> c >= majority).count();
  }

  /**
   * The epoch of the current (unique) majority-backed leader, or {@code -1} if there is none. Used
   * to assert fencing-epoch monotonicity: successive leaders never regress in epoch.
   *
   * @param group the election group
   * @return the leader's epoch, or -1
   */
  public long currentLeaderEpoch(String group) {
    final int majority = nodeCount / 2 + 1;
    final Map<String, Integer> byOwner = backersByOwner(group);
    long epoch = -1;
    for (String m : members) {
      final long now = localTime(m); // acceptor m judges validity by its own clock
      var lease = acceptors.get(m).handle(LeaseRequest.get(group), now).currentLease();
      if (lease.isPresent()
          && !lease.get().isExpired(now)
          && byOwner.getOrDefault(lease.get().owner(), 0) >= majority) {
        epoch = Math.max(epoch, lease.get().epoch());
      }
    }
    return epoch;
  }

  private Map<String, Integer> backersByOwner(String group) {
    final Map<String, Integer> perOwner = new HashMap<>();
    for (String m : members) {
      final long now = localTime(m); // each acceptor counts as a backer only by its OWN clock
      acceptors
          .get(m)
          .handle(LeaseRequest.get(group), now)
          .currentLease()
          .filter(lease -> !lease.isExpired(now))
          .ifPresent(lease -> perOwner.merge(lease.owner(), 1, Integer::sum));
    }
    return perOwner;
  }

  /**
   * The local clock of one node: the global virtual clock plus that node's fixed skew offset (0 by
   * default). Each acceptor judges lease expiry against its own local clock, so a bounded
   * per-acceptor skew directly exercises the fencing-covered "zombie" window — yet safety, being
   * clock-independent (quorum intersection + monotone fencing), must still hold.
   *
   * @param node the node id
   * @return that node's local virtual time in millis
   */
  public long localTime(String node) {
    return clock.get() + skew.getOrDefault(node, 0L);
  }

  /**
   * Assigns each node a fixed, deterministic clock offset in
   * {@code [-maxSkewMillis, +maxSkewMillis]} (drawn from the seeded RNG), so acceptors disagree on
   * "now". Safety is clock-independent and must survive any skew; only liveness (how fast a stale
   * lease is reclaimed) depends on time. Call once before campaigning.
   *
   * @param maxSkewMillis the bound on the absolute per-node offset
   */
  public void setClockSkew(long maxSkewMillis) {
    skew.clear();
    if (maxSkewMillis <= 0) {
      return;
    }
    for (String m : members) {
      skew.put(m, (long) random.nextInt((int) (2 * maxSkewMillis + 1)) - maxSkewMillis);
    }
  }

  /** Clears all partitions and lost links (full connectivity). */
  public void heal() {
    isolated.clear();
    dropped.clear();
    lossPercent = 0;
  }
}
