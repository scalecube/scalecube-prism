package io.scalecube.prism.persistence;

import io.scalecube.prism.consensus.LeaseJournal;
import io.scalecube.prism.consensus.LeaseRecord;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A write-ahead, file-backed {@link LeaseJournal}. Each accepted lease is appended as one line and
 * {@code fsync}'d before {@link #append} returns, so an acknowledged acceptance survives a crash.
 *
 * <p>Only the highest-epoch lease per group is ever needed for recovery, so the log is
 * <b>compacted</b> in place once it has grown by {@link #compactEveryAppends} appends: the
 * highest-epoch record per group (held in memory) is written to a temp file, {@code fsync}'d, and
 * atomically renamed over the live file. This bounds both on-disk size (≈ one line per group after
 * each compaction) and restart cost (recovery replays a small, recently-compacted file) — fixing
 * the unbounded-growth/slow-restart hazard of a plain append log.
 *
 * <p>Append-only between compactions, with a tab-separated record
 * {@code group\towner\tepoch\texpiresAt}; group/owner ids must not contain tabs or newlines (they
 * never do). A torn final record (crash mid-append) is dropped on recovery; an interior
 * malformed record fails loud (acknowledged state must not be silently lost).
 */
public final class FileLeaseJournal implements LeaseJournal {

  /** Appends between in-place compactions — bounds the log to roughly this many lines + #groups. */
  private static final int DEFAULT_COMPACT_EVERY_APPENDS = 1024;

  private final Path path;
  private final int compactEveryAppends;
  private final Map<String, LeaseRecord> highest = new HashMap<>(); // highest-epoch lease per group
  private int appendsSinceCompaction;

  /**
   * Opens (creating if needed) a journal at the given path, recovering its current state.
   *
   * @param path the journal file
   */
  public FileLeaseJournal(Path path) {
    this(path, DEFAULT_COMPACT_EVERY_APPENDS);
  }

  /**
   * As {@link #FileLeaseJournal(Path)} but with a custom compaction interval (for tests).
   *
   * @param path the journal file
   * @param compactEveryAppends appends between in-place compactions (must be ≥ 1)
   */
  public FileLeaseJournal(Path path, int compactEveryAppends) {
    this.path = path;
    this.compactEveryAppends = Math.max(1, compactEveryAppends);
    try {
      if (path.getParent() != null) {
        Files.createDirectories(path.getParent());
      }
      if (!Files.exists(path)) {
        Files.createFile(path);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot open lease journal: " + path, e);
    }
    highest.putAll(replay());
  }

  @Override
  public synchronized void append(LeaseRecord lease) {
    try (FileChannel channel =
        FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
      channel.write(ByteBuffer.wrap(format(lease).getBytes(StandardCharsets.UTF_8)));
      channel.force(true); // fsync data + metadata: durable before we return
    } catch (IOException e) {
      throw new UncheckedIOException("cannot append to lease journal: " + path, e);
    }
    highest.merge(lease.group(), lease, (a, b) -> b.epoch() >= a.epoch() ? b : a);
    if (++appendsSinceCompaction >= compactEveryAppends) {
      compact();
    }
  }

  @Override
  public synchronized Map<String, LeaseRecord> load() {
    return new HashMap<>(highest); // already recovered (and kept current) in memory
  }

  /** Rewrites the log to just the highest-epoch record per group, atomically. */
  private void compact() {
    final Path tmp = path.resolveSibling(path.getFileName() + ".compact");
    final StringBuilder sb = new StringBuilder();
    for (LeaseRecord r : highest.values()) {
      sb.append(format(r));
    }
    try (FileChannel channel =
        FileChannel.open(
            tmp,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING)) {
      channel.write(ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8)));
      channel.force(true);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write compacted lease journal: " + tmp, e);
    }
    try {
      Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException atomicUnsupported) {
      try {
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException e) {
        throw new UncheckedIOException("cannot replace lease journal: " + path, e);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot replace lease journal: " + path, e);
    }
    appendsSinceCompaction = 0;
  }

  private static String format(LeaseRecord r) {
    return r.group() + '\t' + r.owner() + '\t' + r.epoch() + '\t' + r.expiresAt() + '\n';
  }

  /** Reads the file and reduces it to the highest-epoch lease per group, tolerating a torn tail. */
  private Map<String, LeaseRecord> replay() {
    final Map<String, LeaseRecord> recovered = new HashMap<>();
    final List<String> lines;
    try {
      lines = Files.readAllLines(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read lease journal: " + path, e);
    }
    for (int i = 0; i < lines.size(); i++) {
      final String line = lines.get(i);
      if (line.isEmpty()) {
        continue;
      }
      try {
        final String[] f = line.split("\t", -1);
        if (f.length < 4) {
          throw new IllegalArgumentException("short record");
        }
        final LeaseRecord r =
            new LeaseRecord(f[0], f[1], Long.parseLong(f[2]), Long.parseLong(f[3]));
        recovered.merge(r.group(), r, (a, b) -> b.epoch() >= a.epoch() ? b : a);
      } catch (RuntimeException e) {
        // A WAL's only legitimately-torn record is the LAST one: a crash mid-append, before fsync
        // completed, so it was never acknowledged and can be dropped. A malformed INTERIOR record
        // is real corruption of acknowledged state — fail loud rather than silently lose a lease.
        if (i == lines.size() - 1) {
          break;
        }
        throw new IllegalStateException(
            "corrupt lease journal record at line " + (i + 1) + ": " + path, e);
      }
    }
    return recovered;
  }
}
