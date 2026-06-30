package io.scalecube.prism.codec;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The reader counterpart of {@link WireWriter}. Reads only primitives and length-prefixed UTF
 * strings via {@link DataInputStream} — it never calls {@code readObject}, so a malicious payload
 * cannot trigger class instantiation / deserialization gadgets (ADR-0009).
 */
public final class WireReader {

  private final DataInputStream in;

  public WireReader(byte[] bytes) {
    this.in = new DataInputStream(new ByteArrayInputStream(bytes));
  }

  public byte readByte() {
    return run(in::readByte);
  }

  public boolean readBoolean() {
    return run(in::readBoolean);
  }

  public int readInt() {
    return run(in::readInt);
  }

  public long readLong() {
    return run(in::readLong);
  }

  /** Reads a nullable string written by {@link WireWriter#writeString}. */
  public String readString() {
    return run(() -> in.readBoolean() ? in.readUTF() : null);
  }

  private interface IoSupplier<T> {
    T get() throws IOException;
  }

  private <T> T run(IoSupplier<T> supplier) {
    try {
      return supplier.get();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
