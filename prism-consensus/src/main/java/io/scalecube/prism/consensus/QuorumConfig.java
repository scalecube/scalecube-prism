package io.scalecube.prism.consensus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The committed configuration of a self-electing quorum and the deterministic policy that evolves
 * it (ADR-0015). It holds the <b>current</b> member set, the <b>immediately previous</b> one
 * (in-flight leases may still rely on it during a change), and a monotonic <b>config epoch</b> that
 * totally orders the single-step chain {@code C0 → C1 → C2 → …}.
 *
 * <p>All evolution goes through {@link #commit}, which accepts only a strictly higher config epoch
 * and a <b>single-member</b> change — the load-bearing rule TLA+ proves keeps adjacent configs'
 * majorities overlapping, so two leaders can never be certified across a reconfiguration
 * ({@code SelfElectingQuorum.tla}). Multi-member jumps are rejected here, not merely discouraged.
 *
 * <p>The reconfiguration <em>policy</em> ({@link #planNextStep}) is a pure function of the current
 * config, the live membership, the candidate roster and the target size; the leader applies it one
 * step at a time. Thread-safe.
 */
public final class QuorumConfig {

  private long epoch;
  private List<String> members;
  private List<String> previous;
  private final ConfigJournal journal;

  /**
   * Seeds the configuration at epoch 0 with {@code seed} as both current and previous config
   * (non-durable; the committed config is not recovered across a restart).
   *
   * @param seed the bootstrap roster C0 (e.g. the seed members), non-empty
   */
  public QuorumConfig(Collection<String> seed) {
    this(seed, ConfigJournal.noop());
  }

  /**
   * Seeds the configuration at epoch 0 with {@code seed}, then recovers any higher-epoch committed
   * configuration from {@code journal} (so a restarted node resumes its committed config chain
   * instead of resetting to C0). Subsequent commits/adoptions are appended to the journal.
   *
   * @param seed the bootstrap roster C0, non-empty
   * @param journal durable store for the committed chain (or {@link ConfigJournal#noop()})
   */
  public QuorumConfig(Collection<String> seed, ConfigJournal journal) {
    final List<String> copy = normalize(seed);
    if (copy.isEmpty()) {
      throw new IllegalArgumentException("seed config must be non-empty");
    }
    this.journal = Objects.requireNonNull(journal, "journal");
    this.epoch = 0;
    this.members = copy;
    this.previous = copy;
    journal
        .load()
        .filter(recovered -> recovered.epoch() > this.epoch)
        .ifPresent(
            recovered -> {
              this.epoch = recovered.epoch();
              this.members = normalize(recovered.members());
              this.previous = this.members; // no in-flight change immediately after recovery
            });
  }

  /** The current committed member set. */
  public synchronized List<String> members() {
    return List.copyOf(members);
  }

  /**
   * The immediately previous member set (overlaps the current one by all-but-one member under the
   * single-member rule).
   *
   * <p>Not consulted on the live path: quorum reads and writes use only {@link #members()}.
   * Safety across a reconfiguration needs no joint read: adjacent single-member configs
   * have overlapping majorities — any new-config majority intersects any old-config majority,
   * so a lease committed under the old config is always visible to the new one. {@code previous} is
   * retained for observability/snapshots and as the basis of that overlap argument.
   */
  public synchronized List<String> previous() {
    return List.copyOf(previous);
  }

  /** The current config epoch (monotonic; totally orders the single-step config chain). */
  public synchronized long epoch() {
    return epoch;
  }

  /** Majority of the current config: {@code |C|/2 + 1}. */
  public synchronized int majority() {
    return members.size() / 2 + 1;
  }

  /** An immutable point-in-time snapshot of (epoch, current, previous). */
  public synchronized Snapshot snapshot() {
    return new Snapshot(epoch, List.copyOf(members), List.copyOf(previous));
  }

  /**
   * Commits a new configuration, remembering the current one as previous. Accepts only a strictly
   * higher epoch and a single-member change from the current config (a re-commit at the same or a
   * lower epoch is a no-op).
   *
   * @param newEpoch the proposed config epoch (must be {@code > } current to take effect)
   * @param newMembers the proposed member set (must differ from current by exactly one member)
   * @return true if this call advanced the configuration
   * @throws IllegalArgumentException if the change is not single-member
   */
  public synchronized boolean commit(long newEpoch, Collection<String> newMembers) {
    if (newEpoch <= epoch) {
      return false; // stale or duplicate — already at or beyond this config
    }
    final List<String> next = normalize(newMembers);
    if (next.isEmpty()) {
      throw new IllegalArgumentException("config must be non-empty");
    }
    if (!isSingleMemberChange(members, next)) {
      throw new IllegalArgumentException(
          "reconfiguration must be single-member: " + members + " -> " + next);
    }
    this.previous = this.members;
    this.members = next;
    this.epoch = newEpoch;
    journal.append(new ConfigRecord(newEpoch, next)); // durable before the change is relied upon
    return true;
  }

  /**
   * Adopts an already-committed configuration learned from a peer (follower catch-up). Unlike
   * {@link #commit}, this does <b>not</b> enforce a single-member delta, because a lagging node may
   * have missed several individually-single-member steps; it only requires a strictly higher epoch.
   * Used for convergence, never to originate a change (only the leader originates, via {@link
   * #commit}).
   *
   * @param newEpoch the committed config epoch (must be {@code > } current)
   * @param newMembers the committed member set
   * @return true if this call advanced the configuration
   */
  public synchronized boolean adopt(long newEpoch, Collection<String> newMembers) {
    if (newEpoch <= epoch) {
      return false;
    }
    final List<String> next = normalize(newMembers);
    if (next.isEmpty()) {
      throw new IllegalArgumentException("config must be non-empty");
    }
    this.previous = this.members;
    this.members = next;
    this.epoch = newEpoch;
    journal.append(new ConfigRecord(newEpoch, next)); // durable before the change is relied upon
    return true;
  }

  // ================================================
  // ============== Policy (pure) ===================
  // ================================================

  /**
   * Plans the next single-member step toward a healthy, correctly-sized configuration, or empty if
   * the current config already matches the live, desired set. Applied by the leader one step per
   * reconfiguration (ADR-0015 §6/§7). Deterministic: candidate selection is by sorted id, so all
   * observers compute the same step.
   *
   * <p>Priority: (1) drop a dead current member; (2) grow toward the desired size with a live
   * candidate; (3) shrink toward the desired size. At most one member changes.
   *
   * @param current the current committed config
   * @param live the currently-alive members (from the failure detector)
   * @param roster candidate members that may join (e.g. the gossip pool / seed roster)
   * @param target the configured target quorum size (odd; capped by what is live)
   * @return the proposed next config, or empty if no change is warranted
   */
  public static Optional<List<String>> planNextStep(
      Collection<String> current, Set<String> live, Collection<String> roster, int target) {
    return planNextStep(current, live, roster, target, null);
  }

  /**
   * As {@link #planNextStep(Collection, Set, Collection, int)}, but never removes {@code keep} when
   * shrinking — the leader passes itself so a sizing-shrink can never evict the current leader (and
   * force an unnecessary failover). A dead member is still removed regardless (the leader is
   * alive, so it is never the one removed for being dead).
   *
   * @param current the current committed config
   * @param live the currently-alive members
   * @param roster candidate members that may join
   * @param target the configured target quorum size
   * @param keep a member never removed on a sizing-shrink (e.g. the leader), or null
   * @return the proposed next config, or empty if no change is warranted
   */
  public static Optional<List<String>> planNextStep(
      Collection<String> current,
      Set<String> live,
      Collection<String> roster,
      int target,
      String keep) {
    final List<String> cur = normalize(current);
    final int desired = desiredSize(live.size(), target);

    // (1) Remove a dead member — but never below a single live survivor.
    final List<String> deadInConfig = new ArrayList<>();
    for (String m : cur) {
      if (!live.contains(m)) {
        deadInConfig.add(m);
      }
    }
    final int liveInConfig = cur.size() - deadInConfig.size();
    if (!deadInConfig.isEmpty() && cur.size() > 1 && liveInConfig >= 1) {
      final List<String> next = new ArrayList<>(cur);
      next.remove(deadInConfig.get(0)); // lowest dead id (cur is sorted)
      return Optional.of(next);
    }

    // (2) Grow toward desired with a live candidate not already in the config.
    if (cur.size() < desired) {
      final Set<String> candidates = new TreeSet<>(roster);
      candidates.addAll(live);
      candidates.removeAll(cur);
      candidates.retainAll(live); // only add members we can actually reach
      if (!candidates.isEmpty()) {
        final List<String> next = new ArrayList<>(cur);
        next.add(candidates.iterator().next()); // lowest candidate id
        return Optional.of(normalize(next));
      }
    }

    // (3) Shrink toward desired (config larger than warranted): drop the highest id that is not
    // protected (the leader). Removing the leader would force a needless failover.
    if (cur.size() > desired && cur.size() > 1) {
      for (int i = cur.size() - 1; i >= 0; i--) {
        if (!cur.get(i).equals(keep)) {
          final List<String> next = new ArrayList<>(cur);
          next.remove(i);
          return Optional.of(next);
        }
      }
    }

    return Optional.empty();
  }

  /**
   * The desired (odd) quorum size for a given live count and configured target: the largest odd
   * number that is {@code <= min(target, liveCount)} and {@code >= 1}. So with target 3:
   * 1 live → 1, 2 → 1, 3 → 3, 10 → 3; with target 5: 4 live → 3, 5 → 5.
   *
   * @param liveCount number of currently-alive members
   * @param target the configured target size
   * @return the desired odd quorum size (≥ 1)
   */
  public static int desiredSize(int liveCount, int target) {
    int m = Math.min(Math.max(target, 1), Math.max(liveCount, 1));
    if (m % 2 == 0) {
      m -= 1; // round down to odd
    }
    return Math.max(1, m);
  }

  /** True iff {@code b} differs from {@code a} by exactly one member added or removed. */
  public static boolean isSingleMemberChange(Collection<String> a, Collection<String> b) {
    final Set<String> sa = new HashSet<>(a);
    final Set<String> sb = new HashSet<>(b);
    final Set<String> union = new HashSet<>(sa);
    union.addAll(sb);
    final Set<String> intersection = new HashSet<>(sa);
    intersection.retainAll(sb);
    return union.size() - intersection.size() == 1;
  }

  private static List<String> normalize(Collection<String> in) {
    return new ArrayList<>(new TreeSet<>(Objects.requireNonNull(in, "members")));
  }

  /** Immutable snapshot of a configuration at one point in the chain. */
  public static final class Snapshot {
    private final long epoch;
    private final List<String> members;
    private final List<String> previous;

    Snapshot(long epoch, List<String> members, List<String> previous) {
      this.epoch = epoch;
      this.members = members;
      this.previous = previous;
    }

    public long epoch() {
      return epoch;
    }

    public List<String> members() {
      return members;
    }

    public List<String> previous() {
      return previous;
    }
  }
}
