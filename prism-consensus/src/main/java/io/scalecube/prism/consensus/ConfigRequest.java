package io.scalecube.prism.consensus;

/**
 * A reconfiguration-protocol request: either a {@code GET} (return the recipient's latest known
 * configuration) or a {@code PROPOSE} (adopt the carried {@link ConfigRecord} if its epoch is
 * higher). The single proposer is always the current leader, so a PROPOSE never races (ADR-0015).
 */
public final class ConfigRequest {

  private final boolean get;
  private final String group;
  private final ConfigRecord record; // null for GET

  private ConfigRequest(boolean get, String group, ConfigRecord record) {
    this.get = get;
    this.group = group;
    this.record = record;
  }

  /**
   * A request to read the recipient's latest known config for a group.
   *
   * @param group the election/consensus group
   * @return the request
   */
  public static ConfigRequest get(String group) {
    return new ConfigRequest(true, group, null);
  }

  /**
   * A request to adopt a committed config.
   *
   * @param group the election/consensus group
   * @param record the configuration to adopt
   * @return the request
   */
  public static ConfigRequest propose(String group, ConfigRecord record) {
    return new ConfigRequest(false, group, record);
  }

  public boolean isGet() {
    return get;
  }

  public String group() {
    return group;
  }

  /** The proposed config (only present for PROPOSE). */
  public ConfigRecord record() {
    return record;
  }
}
