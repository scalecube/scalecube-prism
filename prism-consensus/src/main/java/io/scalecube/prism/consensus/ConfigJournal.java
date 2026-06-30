package io.scalecube.prism.consensus;

import java.util.Optional;

/**
 * Durable storage for the committed configuration chain of a self-electing quorum (ADR-0015). A
 * dynamic-quorum node that restarts must recover the configuration it had committed — otherwise it
 * resets to the bootstrap seed C0 at epoch 0, and a whole-cluster restart would lose the committed
 * member set entirely. The journal records each committed {@link ConfigRecord} (epoch + members)
 * write-ahead and replays the highest-epoch one on restart.
 *
 * <p>Only the committed config needs to be durable; the {@code previous} config matters only during
 * an in-flight change, of which there is none immediately after a restart.
 */
public interface ConfigJournal {

  /**
   * Durably records a committed configuration before it is acknowledged.
   *
   * @param config the committed configuration (epoch + members)
   */
  void append(ConfigRecord config);

  /**
   * Recovers the highest-epoch committed configuration from durable storage.
   *
   * @return the recovered configuration, or empty if none was ever committed
   */
  Optional<ConfigRecord> load();

  /**
   * A non-durable journal (in-memory / tests).
   *
   * @return a no-op journal
   */
  static ConfigJournal noop() {
    return new ConfigJournal() {
      @Override
      public void append(ConfigRecord config) {
        // no-op
      }

      @Override
      public Optional<ConfigRecord> load() {
        return Optional.empty();
      }
    };
  }
}
