package io.scalecube.prism.persistence;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.scalecube.prism.versioning.HybridLogicalClock;
import io.scalecube.prism.versioning.HybridTimestamp;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Durable clock: versions never regress across restart")
class FileClockJournalTest {

  /**
   * Given a clock that issued timestamps and then "crashed",
   * When a new clock recovers from the same journal — even with the wall clock turned backward —
   * Then the first timestamp it issues is strictly greater than any issued before the crash.
   */
  @Test
  void recoveredClockNeverRegresses(@TempDir Path dir) {
    Path file = dir.resolve("clock.journal");
    AtomicLong wall = new AtomicLong(10_000);

    HybridLogicalClock before = new HybridLogicalClock(wall::get, new FileClockJournal(file));
    HybridTimestamp last = before.now();
    for (int i = 0; i < 5; i++) {
      last = before.now();
    }

    // Restart with the wall clock moved BACKWARD (e.g. NTP step) — the worst case.
    wall.set(1_000);
    HybridLogicalClock after = new HybridLogicalClock(wall::get, new FileClockJournal(file));
    HybridTimestamp resumed = after.now();

    assertTrue(
        resumed.compareTo(last) > 0,
        "recovered clock must issue a strictly greater timestamp than before the crash");
  }

  /**
   * Given a non-durable clock (no journal),
   * When timestamps are taken,
   * Then behaviour is unchanged (first timestamp tracks the wall clock at logical 0).
   */
  @Test
  void nonDurableClockKeepsOriginalBehaviour() {
    HybridLogicalClock clock = new HybridLogicalClock(() -> 1000);
    HybridTimestamp t = clock.now();
    assertTrue(t.physical() == 1000 && t.logical() == 0);
  }
}
