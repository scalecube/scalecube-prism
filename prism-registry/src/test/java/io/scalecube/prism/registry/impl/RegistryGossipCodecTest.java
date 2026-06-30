package io.scalecube.prism.registry.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.scalecube.prism.registry.ConsistencyTier;
import io.scalecube.prism.versioning.HybridTimestamp;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Registry gossip codec: schema'd binary round-trip (no Java serialization)")
class RegistryGossipCodecTest {

  /**
   * Given a registry delta with properties and a version,
   * When encoded and decoded,
   * Then every field (owner, address, tier, version, properties, tombstone) round-trips exactly.
   */
  @Test
  void deltaRoundTrips() {
    ServiceEntryImpl entry =
        new ServiceEntryImpl(
            "orders", "A", "A@addr", Map.of("weight", "100", "zone", "eu"),
            new HybridTimestamp(7, 3), ConsistencyTier.CONSENSUS, true);

    RegistryGossip out =
        RegistryGossipCodec.decode(RegistryGossipCodec.encode(RegistryGossip.of(entry, false)));
    ServiceEntryImpl e = out.toEntry();

    assertEquals("orders", e.service());
    assertEquals("A", e.owner());
    assertEquals("A@addr", e.address());
    assertEquals(ConsistencyTier.CONSENSUS, e.tier());
    assertEquals(7, e.version().physical());
    assertEquals(3, e.version().logical());
    assertEquals("100", e.properties().get("weight"));
    assertEquals("eu", e.properties().get("zone"));
    assertFalse(out.tombstone());
  }
}
