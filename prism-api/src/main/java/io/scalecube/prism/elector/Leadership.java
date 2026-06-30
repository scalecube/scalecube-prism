package io.scalecube.prism.elector;

import io.scalecube.cluster.Member;

/**
 * A leadership state change for an election group. Emitted whenever the local member is granted or
 * loses the Active role for a group.
 *
 * <p>The {@link #epoch()} is a monotonically increasing fencing token: every action taken while
 * Active must be tagged with it, and downstream resources must reject actions bearing a lower
 * epoch.
 * This is what makes an early or mistaken failover harmless.
 */
public interface Leadership {

  /** The election group (e.g. {@code "A"}). */
  String group();

  /** The member this state refers to (the local member). */
  Member member();

  /** Monotonic fencing token for this leadership term. */
  long epoch();

  /** {@code true} when the role was granted (Active), {@code false} when revoked. */
  boolean active();
}
