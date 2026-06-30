package io.scalecube.prism.registry.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.scalecube.prism.registry.ConsistencyTier;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RegistryReadCodec: quorum read request/response wire round-trips")
class RegistryReadCodecTest {

  @Test
  void requestRoundTrips() {
    final byte[] bytes = RegistryReadCodec.encodeRequest("orders");
    assertEquals("orders", RegistryReadCodec.decodeRequest(bytes));
  }

  @Test
  void responseRoundTripsLiveAndTombstoneRecords() {
    final RegistryGossip live =
        new RegistryGossip(
            "orders",
            "n1",
            "host:4801",
            ConsistencyTier.QUORUM.name(),
            42L,
            7L,
            false,
            Map.of("weight", "100", "zone", "eu"));
    final RegistryGossip tomb =
        new RegistryGossip(
            "orders", "n2", "host:4802", ConsistencyTier.CAUSAL.name(), 99L, 0L, true, Map.of());

    final byte[] bytes = RegistryReadCodec.encodeResponse(List.of(live, tomb));
    final List<RegistryGossip> back = RegistryReadCodec.decodeResponse(bytes);

    assertEquals(2, back.size());

    final RegistryGossip g0 = back.get(0);
    assertEquals("orders", g0.service());
    assertEquals("n1", g0.owner());
    assertEquals("host:4801", g0.address());
    assertEquals(ConsistencyTier.QUORUM.name(), g0.tier());
    assertEquals(42L, g0.physical());
    assertEquals(7L, g0.logical());
    assertTrue(!g0.tombstone());
    assertEquals("100", g0.properties().get("weight"));
    assertEquals("eu", g0.properties().get("zone"));

    final RegistryGossip g1 = back.get(1);
    assertEquals("n2", g1.owner());
    assertTrue(g1.tombstone());
    assertTrue(g1.properties().isEmpty());
  }

  @Test
  void emptyResponseRoundTrips() {
    final byte[] bytes = RegistryReadCodec.encodeResponse(List.of());
    assertTrue(RegistryReadCodec.decodeResponse(bytes).isEmpty());
  }

  @Test
  void rejectsUnknownWireVersion() {
    try {
      RegistryReadCodec.decodeRequest(new byte[] {(byte) 99, 0, 0, 0, 0});
      throw new AssertionError("expected an exception for an unknown wire version");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("version"));
    }
  }
}
