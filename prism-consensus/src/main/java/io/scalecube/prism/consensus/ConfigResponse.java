package io.scalecube.prism.consensus;

import java.util.Optional;

/**
 * Reply to a {@link ConfigRequest}: whether a PROPOSE was adopted, and the responder's latest known
 * {@link ConfigRecord} (so a proposer or a catching-up node learns the freshest committed config).
 */
public final class ConfigResponse {

  private final boolean accepted;
  private final ConfigRecord latest; // nullable

  private ConfigResponse(boolean accepted, ConfigRecord latest) {
    this.accepted = accepted;
    this.latest = latest;
  }

  /**
   * Builds a response.
   *
   * @param accepted whether a PROPOSE was adopted (false for GET or a stale PROPOSE)
   * @param latest the responder's latest known config, or null
   * @return the response
   */
  public static ConfigResponse of(boolean accepted, ConfigRecord latest) {
    return new ConfigResponse(accepted, latest);
  }

  /** A failed/unreachable response. */
  public static ConfigResponse fail() {
    return new ConfigResponse(false, null);
  }

  public boolean accepted() {
    return accepted;
  }

  /** The responder's latest known config, if any. */
  public Optional<ConfigRecord> latest() {
    return Optional.ofNullable(latest);
  }
}
