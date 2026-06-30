package io.scalecube.prism.registry.impl;

import io.scalecube.prism.codec.WireReader;
import io.scalecube.prism.codec.WireWriter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Schema'd binary codec for {@link RegistryGossip} deltas (ADR-0009). Encodes field-by-field to
 * {@code byte[]} — no Java serialization, so no deserialization-gadget surface. A leading version
 * byte allows wire evolution.
 */
final class RegistryGossipCodec {

  private static final byte VERSION = 1;

  private RegistryGossipCodec() {}

  static byte[] encode(RegistryGossip g) {
    WireWriter w =
        new WireWriter()
            .writeByte(VERSION)
            .writeString(g.service())
            .writeString(g.owner())
            .writeString(g.address())
            .writeString(g.tier())
            .writeLong(g.physical())
            .writeLong(g.logical())
            .writeBoolean(g.tombstone());
    Map<String, String> props = g.properties();
    w.writeInt(props.size());
    for (Map.Entry<String, String> e : props.entrySet()) {
      w.writeString(e.getKey()).writeString(e.getValue());
    }
    return w.toBytes();
  }

  static RegistryGossip decode(byte[] bytes) {
    WireReader r = new WireReader(bytes);
    if (r.readByte() != VERSION) {
      throw new IllegalArgumentException("unsupported registry wire version");
    }
    String service = r.readString();
    String owner = r.readString();
    String address = r.readString();
    String tier = r.readString();
    long physical = r.readLong();
    long logical = r.readLong();
    boolean tombstone = r.readBoolean();
    int size = r.readInt();
    Map<String, String> props = new LinkedHashMap<>();
    for (int i = 0; i < size; i++) {
      props.put(r.readString(), r.readString());
    }
    return new RegistryGossip(service, owner, address, tier, physical, logical, tombstone, props);
  }
}
