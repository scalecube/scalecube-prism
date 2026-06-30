package io.scalecube.prism.elector;

/**
 * A candidate's election-time preference for a group (ADR-0016). Consulted only when a lease is
 * acquirable — it biases <i>who wins an election</i>, never preempting a healthy leader (so
 * leadership stays sticky and there is no automatic failback).
 */
public enum Preference {

  /** Co-located with the active anchor: campaigns immediately for a free/expired lease. */
  PREFERRED,

  /** Eligible but not preferred: campaigns only after the yield window with no preferred winner. */
  STANDBY,

  /** Must never lead (e.g. drained): never campaigns (the {@code nofailover} analog). */
  INELIGIBLE
}
