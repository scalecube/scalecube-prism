package io.scalecube.prism.codec;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * A tiny, safe binary writer for prism's wire messages. Backed by {@link DataOutputStream} — it
 * writes primitives and length-prefixed UTF strings only, never serialized objects, so the
 * corresponding reader can never be coerced into instantiating arbitrary classes (no Java
 * deserialization gadget surface — ADR-0009).
 */
public final class WireWriter {

  private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
  private final DataOutputStream out = new DataOutputStream(buffer);

  public WireWriter writeByte(int value) {
    return run(() -> out.writeByte(value));
  }

  public WireWriter writeBoolean(boolean value) {
    return run(() -> out.writeBoolean(value));
  }

  public WireWriter writeInt(int value) {
    return run(() -> out.writeInt(value));
  }

  public WireWriter writeLong(long value) {
    return run(() -> out.writeLong(value));
  }

  /** Writes a nullable string (a presence flag then the UTF bytes). */
  public WireWriter writeString(String value) {
    return run(
        () -> {
          out.writeBoolean(value != null);
          if (value != null) {
            out.writeUTF(value);
          }
        });
  }

  /** Returns the encoded bytes. */
  public byte[] toBytes() {
    return buffer.toByteArray();
  }

  private interface IoAction {
    void run() throws IOException;
  }

  private WireWriter run(IoAction action) {
    try {
      action.run();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return this;
  }
}
