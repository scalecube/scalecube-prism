package io.scalecube.prism.consensus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Lease codec: schema'd binary round-trip (no Java serialization)")
class LeaseCodecTest {

  /**
   * Given an ACCEPT request,
   * When encoded and decoded,
   * Then all lease fields round-trip exactly.
   */
  @Test
  void acceptRequestRoundTrips() {
    LeaseRequest in = LeaseRequest.accept(new LeaseRecord("gw", "A", 5, 12345));
    LeaseRequest out = LeaseCodec.decodeRequest(LeaseCodec.encode(in));

    assertFalse(out.isGet());
    assertEquals("gw", out.group());
    assertEquals("A", out.toLease().owner());
    assertEquals(5, out.toLease().epoch());
    assertEquals(12345, out.toLease().expiresAt());
  }

  /**
   * Given a GET request,
   * When encoded and decoded,
   * Then it round-trips as a GET for the same group.
   */
  @Test
  void getRequestRoundTrips() {
    LeaseRequest out = LeaseCodec.decodeRequest(LeaseCodec.encode(LeaseRequest.get("gw")));
    assertTrue(out.isGet());
    assertEquals("gw", out.group());
  }

  /**
   * Given responses with and without a current lease,
   * When encoded and decoded,
   * Then acceptance flag and lease presence/values round-trip.
   */
  @Test
  void responseRoundTrips() {
    LeaseResponse withLease =
        LeaseCodec.decodeResponse(
            LeaseCodec.encode(LeaseResponse.of(true, new LeaseRecord("gw", "B", 2, 99))));
    assertTrue(withLease.ok());
    assertEquals("B", withLease.currentLease().orElseThrow().owner());
    assertEquals(2, withLease.currentLease().orElseThrow().epoch());

    LeaseResponse empty = LeaseCodec.decodeResponse(LeaseCodec.encode(LeaseResponse.fail()));
    assertFalse(empty.ok());
    assertTrue(empty.currentLease().isEmpty());
  }

  /**
   * Given bytes with an unsupported wire version,
   * When decoded,
   * Then it is rejected (no silent misparse of an evolved/forged frame).
   */
  @Test
  void rejectsUnsupportedWireVersion() {
    byte[] bytes = LeaseCodec.encode(LeaseRequest.get("gw"));
    bytes[0] = (byte) 99; // corrupt the leading version byte
    assertThrows(IllegalArgumentException.class, () -> LeaseCodec.decodeRequest(bytes));
  }
}
