package io.scalecube.prism.versioning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Hybrid logical clock: monotonicity & causality")
class HybridLogicalClockTest {

  private final AtomicLong physical = new AtomicLong();
  private HybridLogicalClock hlc;

  @BeforeEach
  void setUp() {
    physical.set(1000);
    hlc = new HybridLogicalClock(physical::get);
  }

  /**
   * Given a clock whose physical time does not advance,
   * When timestamps are taken repeatedly,
   * Then the logical counter increments and each timestamp is strictly greater.
   */
  @Test
  void logicalIncrementsWhilePhysicalStable() {
    HybridTimestamp t1 = hlc.now();
    HybridTimestamp t2 = hlc.now();
    HybridTimestamp t3 = hlc.now();

    assertEquals(1000, t1.physical());
    assertEquals(0, t1.logical());
    assertEquals(1000, t2.physical());
    assertEquals(1, t2.logical());
    assertEquals(2, t3.logical());
    assertTrue(t2.compareTo(t1) > 0);
    assertTrue(t3.compareTo(t2) > 0);
  }

  /**
   * Given a clock with some logical progress,
   * When physical time advances,
   * Then the physical part jumps and the logical counter resets to zero.
   */
  @Test
  void logicalResetsWhenPhysicalAdvances() {
    hlc.now(); // (1000,0)
    physical.set(2000);

    HybridTimestamp t = hlc.now();

    assertEquals(2000, t.physical());
    assertEquals(0, t.logical());
  }

  /**
   * Given the wall clock jumps backward,
   * When a timestamp is taken,
   * Then the clock never regresses (physical held, logical incremented).
   */
  @Test
  void neverRegressesWhenPhysicalGoesBackward() {
    physical.set(5000);
    HybridTimestamp high = hlc.now(); // (5000,0)

    physical.set(1000); // wall clock jumps backward
    HybridTimestamp after = hlc.now();

    assertEquals(5000, after.physical());
    assertEquals(1, after.logical());
    assertTrue(after.compareTo(high) > 0);
  }

  /**
   * Given a remote timestamp ahead in physical time,
   * When merged via update,
   * Then the clock adopts the remote physical and a greater logical (happens-after the remote).
   */
  @Test
  void updateAdoptsRemotePhysicalWhenAhead() {
    HybridTimestamp remote = new HybridTimestamp(2000, 7);

    HybridTimestamp t = hlc.update(remote); // physicalTime is 1000

    assertEquals(2000, t.physical());
    assertEquals(8, t.logical());
    assertTrue(t.compareTo(remote) > 0);
  }

  /**
   * Given local and remote share the same physical instant,
   * When merged,
   * Then the logical counter is max(local, remote) + 1.
   */
  @Test
  void updateMergesLogicalWhenPhysicalEqual() {
    hlc.now(); // local -> (1000,0)
    HybridTimestamp remote = new HybridTimestamp(1000, 5);

    HybridTimestamp t = hlc.update(remote);

    assertEquals(1000, t.physical());
    assertEquals(6, t.logical());
  }

  /**
   * Given physical time dominates both local and remote,
   * When merged,
   * Then the physical part wins and logical resets to zero.
   */
  @Test
  void updateUsesPhysicalTimeWhenItDominates() {
    physical.set(3000);
    HybridTimestamp remote = new HybridTimestamp(2000, 9);

    HybridTimestamp t = hlc.update(remote);

    assertEquals(3000, t.physical());
    assertEquals(0, t.logical());
    assertTrue(t.compareTo(remote) > 0);
  }

  /**
   * Given a message passed from node A to node B,
   * When B receives it and then does local work,
   * Then causality is preserved (receive happens-after send; later events happen-after receive).
   */
  @Test
  void causalityIsPreservedAcrossNodes() {
    AtomicLong pa = new AtomicLong(1000);
    AtomicLong pb = new AtomicLong(900); // B's clock lags A's
    HybridLogicalClock a = new HybridLogicalClock(pa::get);
    HybridLogicalClock b = new HybridLogicalClock(pb::get);

    HybridTimestamp sent = a.now();
    HybridTimestamp received = b.update(sent);
    HybridTimestamp afterReceive = b.now();

    assertTrue(received.compareTo(sent) > 0, "receive must happen-after send");
    assertTrue(afterReceive.compareTo(received) > 0, "local progress after receive");
  }

  /**
   * Given a long mixed sequence of local events and merges with occasional clock advances,
   * When timestamps are produced,
   * Then the sequence is strictly monotonic throughout.
   */
  @Test
  void sequenceIsStrictlyMonotonic() {
    HybridTimestamp prev = hlc.now();
    for (int i = 0; i < 2000; i++) {
      if (i % 3 == 0) {
        physical.incrementAndGet();
      }
      HybridTimestamp next =
          (i % 5 == 0) ? hlc.update(new HybridTimestamp(physical.get() + 1, 0)) : hlc.now();
      assertTrue(next.compareTo(prev) > 0, "regression at step " + i);
      prev = next;
    }
  }

  /**
   * Given a current timestamp,
   * When current() is read,
   * Then it returns the same value without advancing the clock.
   */
  @Test
  void currentDoesNotAdvanceTheClock() {
    HybridTimestamp t = hlc.now();
    assertEquals(0, t.compareTo(hlc.current()));
    assertEquals(0, hlc.current().compareTo(hlc.current()));
  }
}
