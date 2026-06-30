package io.scalecube.prism.consensus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Reconfiguration wire codec: schema'd round-trips")
class ConfigCodecTest {

  /**
   * Given a PROPOSE request carrying a config record,
   * When it is encoded and decoded,
   * Then the group and the full member set survive the round-trip.
   */
  @Test
  void proposeRequestRoundTrips() {
    ConfigRequest in = ConfigRequest.propose("gw", new ConfigRecord(7, List.of("n3", "n1", "n2")));
    ConfigRequest out = ConfigCodec.decodeRequest(ConfigCodec.encode(in));
    assertFalse(out.isGet());
    assertEquals("gw", out.group());
    assertEquals(7, out.record().epoch());
    assertEquals(List.of("n1", "n2", "n3"), out.record().members());
  }

  /**
   * Given a GET request,
   * When it is encoded and decoded,
   * Then it round-trips as a GET with the same group.
   */
  @Test
  void getRequestRoundTrips() {
    ConfigRequest out = ConfigCodec.decodeRequest(ConfigCodec.encode(ConfigRequest.get("gw")));
    assertTrue(out.isGet());
    assertEquals("gw", out.group());
  }

  /**
   * Given a response carrying acceptance and a latest config,
   * When it is encoded and decoded,
   * Then the flag and the record survive; an empty-latest response also round-trips.
   */
  @Test
  void responseRoundTrips() {
    ConfigResponse withLatest = ConfigResponse.of(true, new ConfigRecord(4, List.of("a", "b")));
    ConfigResponse out = ConfigCodec.decodeResponse(ConfigCodec.encode(withLatest));
    assertTrue(out.accepted());
    assertEquals(4, out.latest().orElseThrow().epoch());
    assertEquals(List.of("a", "b"), out.latest().orElseThrow().members());

    ConfigResponse empty = ConfigCodec.decodeResponse(ConfigCodec.encode(ConfigResponse.fail()));
    assertFalse(empty.accepted());
    assertTrue(empty.latest().isEmpty());
  }
}
