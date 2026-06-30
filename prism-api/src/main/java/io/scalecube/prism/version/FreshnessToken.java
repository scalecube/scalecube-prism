package io.scalecube.prism.version;

/**
 * A quantified staleness handle returned alongside reads. It states: "this view reflects all
 * updates
 * up to {@link #upTo()} from owner {@link #ownerId()}." Lets a consumer reason about staleness
 * numerically instead of trusting "eventually consistent".
 */
public interface FreshnessToken {

  /** The owning member whose updates this token bounds. */
  String ownerId();

  /** The highest version from that owner reflected in the read. */
  Version upTo();
}
