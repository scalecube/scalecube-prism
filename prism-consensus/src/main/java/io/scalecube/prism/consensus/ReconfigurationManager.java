package io.scalecube.prism.consensus;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Drives <b>leader-driven, single-member</b> reconfiguration of a self-electing quorum (ADR-0015).
 * Only the current leader (the unique lease holder) originates a change, so config proposals are
 * serialized by leadership itself — there are never two competing proposers. Each {@link #tick()}:
 *
 * <ol>
 *   <li><b>Catches up</b> — adopts the highest committed config a peer has disseminated (so a
 *       freshly elected or lagging node converges to the latest config).
 *   <li><b>If leader</b> — plans at most one single-member step toward a healthy, correctly-sized
 *       config ({@link QuorumConfig#planNextStep}); performs the §7.1 fencing high-water
 *       {@link LeaseTransfer state transfer} onto a <em>majority of the new config</em> (not just
 *       joiners — see {@code step}), then disseminates and commits the new config to a majority of
 *       the <em>current</em> config before adopting it locally.
 * </ol>
 *
 * <p>Deterministic and free of reactive operators (ADR-0004): {@link #tick()} runs the whole
 * decision inline and can be driven manually (simulator) or periodically. At most one reconfig is
 * in flight, because a step is only committed once a majority has adopted it.
 */
public final class ReconfigurationManager {

  private final String self;
  private final String group;
  private final QuorumConfig config;
  private final ConsensusStore store;
  private final ConfigReplicator replicator;
  private final LeaseTransfer transfer;
  private final Supplier<Set<String>> liveMembers;
  private final Supplier<List<String>> roster;
  private final int targetSize;
  private final LongSupplier clock;

  /**
   * Creates a reconfiguration manager for one election group.
   *
   * @param self this node's consensus address
   * @param group the election/consensus group this quorum serves
   * @param config the local committed configuration (mutated as changes are adopted)
   * @param store the consensus store, used to read the current lease (leadership + high-water)
   * @param replicator disseminates and confirms config commits across a majority
   * @param transfer pushes the fencing high-water to a joining member (§7.1)
   * @param liveMembers supplier of currently-alive members (from the failure detector)
   * @param roster supplier of candidate members that may join (gossip pool / seed roster)
   * @param targetSize configured target quorum size (odd; capped by what is live)
   * @param clock physical time source in millis
   */
  public ReconfigurationManager(
      String self,
      String group,
      QuorumConfig config,
      ConsensusStore store,
      ConfigReplicator replicator,
      LeaseTransfer transfer,
      Supplier<Set<String>> liveMembers,
      Supplier<List<String>> roster,
      int targetSize,
      LongSupplier clock) {
    this.self = Objects.requireNonNull(self, "self");
    this.group = Objects.requireNonNull(group, "group");
    this.config = Objects.requireNonNull(config, "config");
    this.store = Objects.requireNonNull(store, "store");
    this.replicator = Objects.requireNonNull(replicator, "replicator");
    this.transfer = Objects.requireNonNull(transfer, "transfer");
    this.liveMembers = Objects.requireNonNull(liveMembers, "liveMembers");
    this.roster = Objects.requireNonNull(roster, "roster");
    this.targetSize = targetSize;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Runs one reconfiguration round: catch-up, then (only if leader) one single-member step. */
  public void tick() {
    catchUp();
    if (!isLeader()) {
      return;
    }
    step();
  }

  /** Adopts the highest committed config a peer knows of, so a new/lagging leader converges. */
  private void catchUp() {
    final Optional<ConfigRecord> latest = replicator.latestKnown();
    if (latest.isPresent() && latest.get().epoch() > config.epoch()) {
      config.adopt(latest.get().epoch(), latest.get().members());
    }
  }

  /** True iff this node currently holds a valid lease for the group (i.e. it is the leader). */
  private boolean isLeader() {
    final long now = clock.getAsLong();
    return store
        .get(group)
        .filter(lease -> !lease.isExpired(now))
        .map(lease -> lease.owner().equals(self))
        .orElse(false);
  }

  private void step() {
    final LeaseRecord highWater = store.get(group).orElse(null);
    if (highWater == null) {
      return; // no lease to anchor the fencing high-water; nothing to transfer safely
    }
    final List<String> current = config.members();
    final Optional<List<String>> planned =
        QuorumConfig.planNextStep(current, liveMembers.get(), roster.get(), targetSize, self);
    if (planned.isEmpty()) {
      return; // already healthy and correctly sized
    }
    final List<String> next = planned.get();
    final long newEpoch = config.epoch() + 1;

    // Defense in depth (review F11): validate the planned step is single-member BEFORE
    // disseminating it. planNextStep only ever produces single-member changes, but a future bug
    // there must not push a multi-member config to peers (who adopt permissively) and only fail
    // locally afterward — leaving peers ahead of a leader that rejected its own change.
    if (!QuorumConfig.isSingleMemberChange(current, next)) {
      return;
    }

    // §7.1 (generalized) — re-establish the fencing high-water on a MAJORITY of the NEW config
    // BEFORE retiring the old one. Transferring only to joining members is unsafe: a shrink that
    // drops a high-water holder can let a stale, lower-epoch lease regain a majority of the smaller
    // config and resurrect it — a fencing-token regression that "never two leaders" does not catch
    // (proven by SelfElectingQuorum.tla, NoTokenRegression). If a majority of the new config cannot
    // be made to hold the high-water (e.g. a stale valid lease still blocks it, or members are
    // unreachable), we do not commit the change this tick and retry — safe-unavailable, not unsafe.
    int holdingHighWater = 0;
    final int majorityOfNext = next.size() / 2 + 1;
    for (String member : next) {
      if (member.equals(self) || transfer.transfer(member, highWater)) {
        holdingHighWater++;
      }
    }
    if (holdingHighWater < majorityOfNext) {
      return; // could not carry the high-water to a majority of the new config; retry next tick
    }

    // A majority of the new config now holds the high-water; commit to a majority of the CURRENT
    // config, then adopt locally.
    final ConfigRecord record = new ConfigRecord(newEpoch, next);
    if (replicator.commit(record, current)) {
      config.commit(newEpoch, next);
    }
  }
}
