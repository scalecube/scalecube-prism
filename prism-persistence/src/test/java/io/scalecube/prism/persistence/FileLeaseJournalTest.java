package io.scalecube.prism.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.scalecube.prism.consensus.Acceptor;
import io.scalecube.prism.consensus.LeaseRecord;
import io.scalecube.prism.consensus.LeaseRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Durable lease journal: crash-safe acceptor recovery")
class FileLeaseJournalTest {

  /**
   * Given several leases appended for a group,
   * When the journal is reloaded,
   * Then it recovers the highest-epoch lease (write-ahead state survives).
   */
  @Test
  void appendThenLoadRecoversHighestLease(@TempDir Path dir) {
    Path file = dir.resolve("lease.journal");
    FileLeaseJournal journal = new FileLeaseJournal(file);

    journal.append(new LeaseRecord("gw", "A", 1, 1000));
    journal.append(new LeaseRecord("gw", "A", 2, 2000));

    LeaseRecord recovered = new FileLeaseJournal(file).load().get("gw");
    assertEquals("A", recovered.owner());
    assertEquals(2, recovered.epoch());
    assertEquals(2000, recovered.expiresAt());
  }

  /**
   * Given an acceptor that accepted a lease at epoch 5 and then "crashed",
   * When a new acceptor recovers from the same journal,
   * Then it remembers the promise — it rejects a lower-epoch takeover and only accepts a strictly
   * higher epoch once the lease has expired (safety preserved across the crash).
   */
  @Test
  void recoveredAcceptorPreservesItsPromise(@TempDir Path dir) {
    Path file = dir.resolve("lease.journal");

    // Acceptor accepts (A, epoch 5) valid until t=10_000, then we drop it (crash).
    Acceptor before = new Acceptor(new FileLeaseJournal(file));
    assertTrue(before.handle(LeaseRequest.accept(new LeaseRecord("gw", "A", 5, 10_000)), 1_000).ok());

    // Recover into a fresh acceptor (process restart).
    Acceptor recovered = new Acceptor(new FileLeaseJournal(file));

    // A different owner with a lower epoch must be rejected (promise survived the crash).
    assertFalse(
        recovered.handle(LeaseRequest.accept(new LeaseRecord("gw", "B", 3, 20_000)), 2_000).ok());

    // Even a higher epoch is rejected while the recovered lease is still valid (no preemption).
    assertFalse(
        recovered.handle(LeaseRequest.accept(new LeaseRecord("gw", "B", 6, 20_000)), 2_000).ok());

    // After expiry, a strictly higher epoch may take over.
    assertTrue(
        recovered.handle(LeaseRequest.accept(new LeaseRecord("gw", "B", 6, 30_000)), 11_000).ok());
  }

  /**
   * Given a journal whose process crashed mid-append, leaving a torn (partial) final record,
   * When a new journal recovers from the file,
   * Then it drops the torn tail and recovers every fully-written record — a badly-timed crash never
   * prevents restart (review F4). The torn record was never acknowledged, so losing it is safe.
   */
  @Test
  void tornFinalRecordIsToleratedOnRecovery(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("lease.journal");
    FileLeaseJournal journal = new FileLeaseJournal(file);
    journal.append(new LeaseRecord("gw", "A", 1, 1000));
    journal.append(new LeaseRecord("gw", "A", 2, 2000));

    // Simulate a crash mid-append: a partial line with missing fields and no trailing newline.
    Files.write(
        file, "gw\tA\t".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);

    LeaseRecord recovered = new FileLeaseJournal(file).load().get("gw");
    assertEquals(2, recovered.epoch(), "fully-written records survive; the torn tail is dropped");
  }

  /**
   * Given a journal that compacts every few appends and a long run of lease renewals,
   * When many renewals accumulate,
   * Then the on-disk file stays bounded (it does not grow without limit) and both the live journal
   * and a fresh recovery still report the highest epoch — compaction keeps disk + restart cost flat
   * (review F3).
   */
  @Test
  void compactionBoundsFileSizeAndKeepsHighestEpoch(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("lease.journal");
    FileLeaseJournal journal = new FileLeaseJournal(file, 4); // compact every 4 appends

    for (int epoch = 1; epoch <= 50; epoch++) {
      journal.append(new LeaseRecord("gw", "A", epoch, 1000L + epoch));
    }

    long lines =
        Files.readAllLines(file, StandardCharsets.UTF_8).stream().filter(l -> !l.isEmpty()).count();
    assertTrue(lines <= 4, "compaction bounds the file (got " + lines + " lines for one group)");
    assertEquals(50, journal.load().get("gw").epoch(), "live journal keeps the highest epoch");
    assertEquals(50, new FileLeaseJournal(file).load().get("gw").epoch(), "recovery keeps it too");
  }
}
