package io.scalecube.prism.versioning;

/**
 * Durable high-water storage for a {@link HybridLogicalClock}. To keep versions monotonic across a
 * restart (so a recovered node never re-issues a version it already used — which last-writer-wins
 * would then reject), the clock persists a value <em>ahead</em> of what it has issued and resumes
 * from it. Persisting ahead by a window bounds fsync frequency to roughly once per window rather
 * than once per timestamp.
 */
public interface ClockJournal {

  /**
   * Loads the persisted high-water physical value.
   *
   * @return the stored value, or 0 if none
   */
  long load();

  /**
   * Durably stores a new high-water physical value.
   *
   * @param physical the value to persist (already includes the look-ahead window)
   */
  void store(long physical);

  /** A non-durable journal (default). */
  static ClockJournal noop() {
    return new ClockJournal() {
      @Override
      public long load() {
        return 0L;
      }

      @Override
      public void store(long physical) {
        // no-op
      }
    };
  }
}
