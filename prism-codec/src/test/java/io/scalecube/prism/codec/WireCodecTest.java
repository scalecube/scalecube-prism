package io.scalecube.prism.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Wire codec: safe primitive round-trip")
class WireCodecTest {

  /**
   * Given primitives and strings (including a null) written to the wire,
   * When read back in the same order,
   * Then every value round-trips exactly.
   */
  @Test
  void roundTripsAllPrimitives() {
    byte[] bytes =
        new WireWriter()
            .writeByte(7)
            .writeBoolean(true)
            .writeInt(-42)
            .writeLong(9_000_000_000L)
            .writeString("hello")
            .writeString(null)
            .toBytes();

    WireReader r = new WireReader(bytes);
    assertEquals(7, r.readByte());
    assertTrue(r.readBoolean());
    assertEquals(-42, r.readInt());
    assertEquals(9_000_000_000L, r.readLong());
    assertEquals("hello", r.readString());
    assertNull(r.readString());
  }
}
