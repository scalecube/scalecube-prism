package io.scalecube.prism.elector;

import io.scalecube.cluster.Member;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Safe singleton election over the cluster. For each group, at most one member is {@code Active} at
 * any time — guaranteed, never two — backed by a consensus-granted lease with fencing tokens.
 *
 * <p><b>Why consensus, not gossip.</b> SWIM is an unreliable failure detector; under a partition or
 * a false-positive suspicion, gossip-only election would let two members both go Active. Safety
 * here comes from the {@code CONSENSUS} tier; SWIM/Lifeguard only makes detection fast and stable
 * so the
 * Active role is sticky and switches only on real loss of the lease.
 */
public interface SingletonElector {

  /**
   * Campaign to become the Active member for {@code group}. Wins only if no valid lease is held.
   * Completes when the campaign has been submitted; leadership changes arrive on
   * {@link #leadership(String)}.
   */
  Mono<Void> campaign(String group);

  /** Voluntarily release leadership of {@code group}, enabling instant safe handoff. */
  Mono<Void> resign(String group);

  /**
   * Stream of leadership changes for {@code group} as seen by the local member: an {@code active}
   * grant carries the fencing epoch; a revocation signals the local member must stop acting.
   */
  Flux<Leadership> leadership(String group);

  /** Best-known current leader of {@code group}, if any. May be momentarily stale. */
  Optional<Member> currentLeader(String group);

  // ============================================================================
  // Leader affinity (ADR-0016) — opt-in; unsupported impls fall back to plain election.
  // ============================================================================

  /**
   * <b>Mode A (autonomous).</b> Bias which candidate wins for {@code group} without ever preempting
   * a healthy leader. {@code PREFERRED} candidates acquire a free/expired lease immediately;
   * {@code STANDBY} candidates wait {@code yieldWindow} (so a preferred candidate can win first);
   * {@code INELIGIBLE} never campaigns. With {@code autoMove}, a leader that becomes non-preferred
   * (the anchor's locality changed) steps down once so the now-preferred candidate can take over —
   * a single controlled handoff, never automatic failback.
   *
   * @param group the election group
   * @param preference the (continuously re-evaluated) preference signal for this node
   * @param yieldWindow how long a {@code STANDBY} waits before contending (≥ anchor failover time)
   * @param autoMove whether to hand off once when this leader becomes non-preferred
   */
  default void affinity(
      String group, Supplier<Preference> preference, Duration yieldWindow, boolean autoMove) {
    // default: preference ignored (plain election) — affinity-aware impls override this
  }

  /**
   * <b>Mode B (controller-driven).</b> Cooperatively make this node Active for {@code group}: it
   * acquires only if the lease is free or expired and then holds it (renewing); if another node
   * holds a valid lease it returns {@code false} without preempting. To move leadership, a
   * controller issues {@link #demote(String)} on the old leader, then {@code promote} on the new.
   *
   * @param group the election group
   * @return whether this node won leadership
   */
  default Mono<Boolean> promote(String group) {
    throw new UnsupportedOperationException("promote() not supported by this elector");
  }

  /**
   * <b>Mode B (controller-driven).</b> Retire this node as Active for {@code group}: release the
   * lease and stay passive (it does not re-campaign). Equivalent to {@link #resign(String)}.
   *
   * @param group the election group
   * @return completion
   */
  default Mono<Void> demote(String group) {
    return resign(group);
  }
}
