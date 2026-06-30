package io.scalecube.prism.persistence;

import io.scalecube.prism.versioning.ClockJournal;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A durable, single-value {@link ClockJournal}: stores the Hybrid Logical Clock's high-water
 * physical value and {@code fsync}s it. On restart the clock loads it and resumes above it, so
 * versions never regress. Writes are infrequent (once per persist-ahead window), so the fsync cost
 * is negligible.
 */
public final class FileClockJournal implements ClockJournal {

  private final Path path;

  /**
   * Opens (creating if needed) a clock journal at the given path.
   *
   * @param path the journal file
   */
  public FileClockJournal(Path path) {
    this.path = path;
    try {
      if (path.getParent() != null) {
        Files.createDirectories(path.getParent());
      }
      if (!Files.exists(path)) {
        Files.createFile(path);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot open clock journal: " + path, e);
    }
  }

  @Override
  public synchronized long load() {
    try {
      String content = Files.readString(path, StandardCharsets.UTF_8).trim();
      return content.isEmpty() ? 0L : Long.parseLong(content);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read clock journal: " + path, e);
    }
  }

  @Override
  public synchronized void store(long physical) {
    try (FileChannel channel =
        FileChannel.open(
            path,
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING)) {
      channel.write(ByteBuffer.wrap(Long.toString(physical).getBytes(StandardCharsets.UTF_8)));
      channel.force(true);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write clock journal: " + path, e);
    }
  }
}
