package io.scalecube.prism.persistence;

import io.scalecube.prism.consensus.ConfigJournal;
import io.scalecube.prism.consensus.ConfigRecord;
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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * A write-ahead, file-backed {@link ConfigJournal}. Each committed configuration is appended as one
 * line and {@code fsync}'d before {@link #append} returns, so an acknowledged change survives a
 * crash.
 *
 * <p>Only the highest-epoch configuration is ever needed for recovery, so the log is
 * <b>compacted</b> in place once it has grown by {@link #compactEveryAppends} appends: the current
 * highest-epoch record (held in memory) is written to a temp file, {@code fsync}'d, and atomically
 * renamed over the live file — bounding on-disk size and restart cost. Config commits are rare, so
 * this rarely fires, but it keeps the file from growing without bound over a long-lived process.
 *
 * <p>Append-only between compactions: a tab-separated {@code epoch\tmembers} record (members
 * comma-separated); ids must not contain tabs, commas, or newlines (host:port ids never do). A torn
 * final record (crash mid-append) is dropped on recovery; an interior malformed record fails loud
 * (acknowledged committed state must not be silently lost).
 */
public final class FileConfigJournal implements ConfigJournal {

  /** Appends between in-place compactions. */
  private static final int DEFAULT_COMPACT_EVERY_APPENDS = 256;

  private final Path path;
  private final int compactEveryAppends;
  private ConfigRecord best; // highest-epoch committed config seen
  private int appendsSinceCompaction;

  /**
   * Opens (creating if needed) a config journal at the given path, recovering its current state.
   *
   * @param path the journal file
   */
  public FileConfigJournal(Path path) {
    this(path, DEFAULT_COMPACT_EVERY_APPENDS);
  }

  /**
   * As {@link #FileConfigJournal(Path)} but with a custom compaction interval (for tests).
   *
   * @param path the journal file
   * @param compactEveryAppends appends between in-place compactions (must be ≥ 1)
   */
  public FileConfigJournal(Path path, int compactEveryAppends) {
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
      throw new UncheckedIOException("cannot open config journal: " + path, e);
    }
    this.best = replay().orElse(null);
  }

  @Override
  public synchronized void append(ConfigRecord config) {
    try (FileChannel channel =
        FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
      channel.write(ByteBuffer.wrap(format(config).getBytes(StandardCharsets.UTF_8)));
      channel.force(true); // fsync data + metadata: durable before we return
    } catch (IOException e) {
      throw new UncheckedIOException("cannot append to config journal: " + path, e);
    }
    if (best == null || config.epoch() > best.epoch()) {
      best = config;
    }
    if (++appendsSinceCompaction >= compactEveryAppends) {
      compact();
    }
  }

  @Override
  public synchronized Optional<ConfigRecord> load() {
    return Optional.ofNullable(best); // already recovered (and kept current) in memory
  }

  /** Rewrites the log to just the highest-epoch config, atomically. */
  private void compact() {
    if (best == null) {
      return;
    }
    final Path tmp = path.resolveSibling(path.getFileName() + ".compact");
    try (FileChannel channel =
        FileChannel.open(
            tmp,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING)) {
      channel.write(ByteBuffer.wrap(format(best).getBytes(StandardCharsets.UTF_8)));
      channel.force(true);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write compacted config journal: " + tmp, e);
    }
    try {
      Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException atomicUnsupported) {
      try {
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException e) {
        throw new UncheckedIOException("cannot replace config journal: " + path, e);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot replace config journal: " + path, e);
    }
    appendsSinceCompaction = 0;
  }

  private static String format(ConfigRecord config) {
    return config.epoch() + "\t" + String.join(",", config.members()) + "\n";
  }

  /** Reads the file and reduces it to the highest-epoch config, tolerating a torn tail. */
  private Optional<ConfigRecord> replay() {
    final List<String> lines;
    try {
      lines = Files.readAllLines(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read config journal: " + path, e);
    }
    ConfigRecord highest = null;
    for (int i = 0; i < lines.size(); i++) {
      final String line = lines.get(i);
      if (line.isEmpty()) {
        continue;
      }
      try {
        final String[] f = line.split("\t", -1);
        if (f.length < 2) {
          throw new IllegalArgumentException("short record");
        }
        final long epoch = Long.parseLong(f[0]);
        final List<String> members = Arrays.asList(f[1].split(",", -1));
        final ConfigRecord record = new ConfigRecord(epoch, members);
        if (highest == null || epoch > highest.epoch()) {
          highest = record;
        }
      } catch (RuntimeException e) {
        // Tolerate a torn FINAL record (crash mid-append, never acknowledged); fail loud on an
        // interior one (acknowledged committed state must not be silently dropped). See review F4.
        if (i == lines.size() - 1) {
          break;
        }
        throw new IllegalStateException(
            "corrupt config journal record at line " + (i + 1) + ": " + path, e);
      }
    }
    return Optional.ofNullable(highest);
  }
}
