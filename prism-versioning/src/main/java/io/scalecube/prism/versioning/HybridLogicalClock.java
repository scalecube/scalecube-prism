package io.scalecube.prism.versioning;

import io.scalecube.prism.version.Version;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * A Hybrid Logical Clock (Kulkarni et al., 2014).
 *
 * <p>Produces {@link HybridTimestamp}s that are strictly monotonic and respect causality without
 * requiring synchronized physical clocks. The physical component tracks wall-clock time but never
 * goes backward; the logical component disambiguates events that share a physical instant and
 * absorbs remote clocks that are ahead.
 *
 * <p>Two operations mutate the clock:
 *
 * <ul>
 *   <li>{@link #now()} — stamp a local event (or an outbound message);
 *   <li>{@link #update(Version)} — merge a timestamp observed on an inbound message.
 * </ul>
 *
 * <p>Both always advance the clock, so successive returned timestamps are strictly increasing. The
 * physical time source is injectable for testing and for the deterministic simulator.
 *
 * <p>Instances are thread-safe (the mutating methods are synchronized).
 */
public final class HybridLogicalClock {

  private final LongSupplier physicalTime;

  /** Persist-ahead window (millis): bounds fsync frequency to ~once per window. */
  private static final long PERSIST_WINDOW_MILLIS = 1000L;

  private final ClockJournal journal;

  private long physical; // l — max physical time observed
  private long logical; // c — logical counter
  private long highWater; // persisted-ahead bound; issued physical is always < highWater

  /** Creates a clock backed by {@link System#currentTimeMillis()} with no durability. */
  public HybridLogicalClock() {
    this(System::currentTimeMillis);
  }

  /**
   * Creates a clock with an explicit physical time source (millis) and no durability.
   *
   * @param physicalTime supplier of the current physical time in milliseconds
   */
  public HybridLogicalClock(LongSupplier physicalTime) {
    this(physicalTime, ClockJournal.noop());
  }

  /**
   * Creates a durable clock: it resumes above the persisted high-water on restart, so versions
   * never regress across a crash.
   *
   * @param physicalTime supplier of the current physical time in milliseconds
   * @param journal durable high-water storage
   */
  public HybridLogicalClock(LongSupplier physicalTime, ClockJournal journal) {
    this.physicalTime = Objects.requireNonNull(physicalTime, "physicalTime");
    this.journal = Objects.requireNonNull(journal, "journal");
    final long persisted = journal.load();
    this.physical = persisted; // 0 when non-durable → original behaviour
    this.logical = 0L;
    this.highWater = persisted;
  }

  private void persistAhead() {
    if (physical >= highWater) {
      highWater = physical + PERSIST_WINDOW_MILLIS;
      journal.store(highWater);
    }
  }

  /**
   * Advances the clock for a local event and returns the new timestamp.
   *
   * @return a timestamp strictly greater than any previously returned by this clock
   */
  public synchronized HybridTimestamp now() {
    final long pt = physicalTime.getAsLong();
    final long lPrev = physical;

    physical = Math.max(lPrev, pt);
    logical = (physical == lPrev) ? logical + 1 : 0L;

    persistAhead();
    return new HybridTimestamp(physical, logical);
  }

  /**
   * Merges a timestamp received from another node, advances the clock past it, and returns the new
   * timestamp.
   *
   * @param remote a timestamp observed on an inbound message
   * @return a timestamp strictly greater than both the previous local state and {@code remote}
   */
  public synchronized HybridTimestamp update(Version remote) {
    Objects.requireNonNull(remote, "remote");

    final long pt = physicalTime.getAsLong();
    final long lPrev = physical;
    final long cPrev = logical;
    final long rl = remote.physical();
    final long rc = remote.logical();

    physical = Math.max(Math.max(lPrev, rl), pt);
    if (physical == lPrev && physical == rl) {
      logical = Math.max(cPrev, rc) + 1;
    } else if (physical == lPrev) {
      logical = cPrev + 1;
    } else if (physical == rl) {
      logical = rc + 1;
    } else {
      logical = 0L;
    }

    persistAhead();
    return new HybridTimestamp(physical, logical);
  }

  /**
   * Returns the current clock value without advancing it.
   *
   * @return the current timestamp
   */
  public synchronized HybridTimestamp current() {
    return new HybridTimestamp(physical, logical);
  }
}
