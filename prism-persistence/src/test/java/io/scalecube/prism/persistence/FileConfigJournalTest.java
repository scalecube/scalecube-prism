package io.scalecube.prism.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.scalecube.prism.consensus.ConfigRecord;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Durable config journal: crash-safe self-electing-quorum config recovery")
class FileConfigJournalTest {

  /**
   * Given several committed configs appended for the quorum,
   * When the journal is reloaded,
   * Then it recovers the highest-epoch configuration (the committed chain survives a restart).
   */
  @Test
  void appendThenLoadRecoversHighestEpochConfig(@TempDir Path dir) {
    Path file = dir.resolve("config.journal");
    FileConfigJournal journal = new FileConfigJournal(file);

    journal.append(new ConfigRecord(1, List.of("n0", "n1", "n2")));
    journal.append(new ConfigRecord(2, List.of("n0", "n1", "n2", "n3")));
    journal.append(new ConfigRecord(3, List.of("n1", "n2", "n3")));

    ConfigRecord recovered = new FileConfigJournal(file).load().orElseThrow();
    assertEquals(3, recovered.epoch());
    assertEquals(List.of("n1", "n2", "n3"), recovered.members());
  }

  /**
   * Given an empty (never-committed) journal,
   * When it is loaded,
   * Then nothing is recovered — the caller falls back to the bootstrap seed C0.
   */
  @Test
  void emptyJournalRecoversNothing(@TempDir Path dir) {
    Path file = dir.resolve("config.journal");
    assertTrue(new FileConfigJournal(file).load().isEmpty());
  }

  /**
   * Given a config journal whose process crashed mid-append (a torn final record),
   * When it is reloaded,
   * Then the torn tail is dropped and the last fully-committed config is recovered — a badly-timed
   * crash never prevents restart (review F4).
   */
  @Test
  void tornFinalRecordIsToleratedOnRecovery(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("config.journal");
    FileConfigJournal journal = new FileConfigJournal(file);
    journal.append(new ConfigRecord(1, List.of("n0", "n1", "n2")));
    journal.append(new ConfigRecord(2, List.of("n0", "n1", "n2", "n3")));

    // Crash mid-append: a partial line (only the epoch field, no members, no newline).
    Files.write(file, "3".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);

    ConfigRecord recovered = new FileConfigJournal(file).load().orElseThrow();
    assertEquals(2, recovered.epoch(), "last committed config survives; the torn tail is dropped");
    assertEquals(List.of("n0", "n1", "n2", "n3"), recovered.members());
  }

  /**
   * Given a config journal that compacts every few appends and a long run of config commits,
   * When many commits accumulate,
   * Then the file stays bounded and both the live journal and a fresh recovery report the highest
   * epoch config — compaction keeps disk + restart cost flat (review F3).
   */
  @Test
  void compactionBoundsFileSizeAndKeepsHighestEpoch(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("config.journal");
    FileConfigJournal journal = new FileConfigJournal(file, 4); // compact every 4 appends

    for (int epoch = 1; epoch <= 30; epoch++) {
      journal.append(new ConfigRecord(epoch, List.of("n0", "n1", "n2")));
    }

    long lines =
        Files.readAllLines(file, StandardCharsets.UTF_8).stream().filter(l -> !l.isEmpty()).count();
    assertTrue(lines <= 4, "compaction bounds the file (got " + lines + " lines)");
    assertEquals(30, journal.load().orElseThrow().epoch(), "live journal keeps the highest epoch");
    assertEquals(
        30, new FileConfigJournal(file).load().orElseThrow().epoch(), "recovery keeps it");
  }
}
