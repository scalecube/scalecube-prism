package io.scalecube.prism.registry.impl;

import io.scalecube.prism.codec.WireReader;
import io.scalecube.prism.codec.WireWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Schema'd binary codec for the quorum read-repair protocol (ADR-0009, ADR-0002 {@code QUORUM}
 * tier). A request carries the service name; a response carries that service's records across
 * owners (live and tombstone). Field-by-field encoding — no Java serialization — with a leading
 * version byte for evolution. Each record is encoded exactly like a {@link RegistryGossip} delta so
 * the two paths stay wire-compatible.
 */
final class RegistryReadCodec {

  private static final byte VERSION = 1;

  private RegistryReadCodec() {}

  /**
   * Encodes a read request.
   *
   * @param service the service being read
   * @return the encoded bytes
   */
  static byte[] encodeRequest(String service) {
    return new WireWriter().writeByte(VERSION).writeString(service).toBytes();
  }

  /**
   * Decodes a read request.
   *
   * @param bytes the encoded bytes
   * @return the requested service name
   */
  static String decodeRequest(byte[] bytes) {
    final WireReader r = new WireReader(bytes);
    checkVersion(r.readByte());
    return r.readString();
  }

  /**
   * Encodes a read response: the responder's records for the service (live and tombstone).
   *
   * @param records the records to ship back
   * @return the encoded bytes
   */
  static byte[] encodeResponse(List<RegistryGossip> records) {
    final WireWriter w = new WireWriter().writeByte(VERSION).writeInt(records.size());
    for (RegistryGossip g : records) {
      writeRecord(w, g);
    }
    return w.toBytes();
  }

  /**
   * Decodes a read response into its records.
   *
   * @param bytes the encoded bytes
   * @return the responder's records for the service
   */
  static List<RegistryGossip> decodeResponse(byte[] bytes) {
    final WireReader r = new WireReader(bytes);
    checkVersion(r.readByte());
    final int count = r.readInt();
    final List<RegistryGossip> out = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      out.add(readRecord(r));
    }
    return out;
  }

  private static void writeRecord(WireWriter w, RegistryGossip g) {
    w.writeString(g.service())
        .writeString(g.owner())
        .writeString(g.address())
        .writeString(g.tier())
        .writeLong(g.physical())
        .writeLong(g.logical())
        .writeBoolean(g.tombstone());
    final Map<String, String> props = g.properties();
    w.writeInt(props.size());
    for (Map.Entry<String, String> e : props.entrySet()) {
      w.writeString(e.getKey()).writeString(e.getValue());
    }
  }

  private static RegistryGossip readRecord(WireReader r) {
    final String service = r.readString();
    final String owner = r.readString();
    final String address = r.readString();
    final String tier = r.readString();
    final long physical = r.readLong();
    final long logical = r.readLong();
    final boolean tombstone = r.readBoolean();
    final int size = r.readInt();
    final Map<String, String> props = new LinkedHashMap<>();
    for (int i = 0; i < size; i++) {
      props.put(r.readString(), r.readString());
    }
    return new RegistryGossip(service, owner, address, tier, physical, logical, tombstone, props);
  }

  private static void checkVersion(byte version) {
    if (version != VERSION) {
      throw new IllegalArgumentException("unsupported registry read wire version: " + version);
    }
  }
}
