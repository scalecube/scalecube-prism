package io.scalecube.prism.consensus;

import io.scalecube.prism.codec.WireReader;
import io.scalecube.prism.codec.WireWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Schema'd binary codec for the reconfiguration-protocol messages (ADR-0009). Encodes/decodes
 * {@link ConfigRequest} and {@link ConfigResponse} field-by-field — no Java serialization. A
 * leading version byte allows evolution.
 */
public final class ConfigCodec {

  private static final byte VERSION = 1;

  private ConfigCodec() {}

  /**
   * Encodes a request.
   *
   * @param request the request
   * @return the encoded bytes
   */
  public static byte[] encode(ConfigRequest request) {
    WireWriter w = new WireWriter().writeByte(VERSION).writeBoolean(request.isGet());
    w.writeString(request.group());
    if (!request.isGet()) {
      writeRecord(w, request.record());
    }
    return w.toBytes();
  }

  /**
   * Encodes a response.
   *
   * @param response the response
   * @return the encoded bytes
   */
  public static byte[] encode(ConfigResponse response) {
    WireWriter w = new WireWriter().writeByte(VERSION).writeBoolean(response.accepted());
    ConfigRecord latest = response.latest().orElse(null);
    w.writeBoolean(latest != null);
    if (latest != null) {
      writeRecord(w, latest);
    }
    return w.toBytes();
  }

  /**
   * Decodes a request.
   *
   * @param bytes the encoded bytes
   * @return the request
   */
  public static ConfigRequest decodeRequest(byte[] bytes) {
    WireReader r = new WireReader(bytes);
    checkVersion(r.readByte());
    boolean get = r.readBoolean();
    String group = r.readString();
    if (get) {
      return ConfigRequest.get(group);
    }
    return ConfigRequest.propose(group, readRecord(r));
  }

  /**
   * Decodes a response.
   *
   * @param bytes the encoded bytes
   * @return the response
   */
  public static ConfigResponse decodeResponse(byte[] bytes) {
    WireReader r = new WireReader(bytes);
    checkVersion(r.readByte());
    boolean accepted = r.readBoolean();
    boolean hasLatest = r.readBoolean();
    return ConfigResponse.of(accepted, hasLatest ? readRecord(r) : null);
  }

  private static void writeRecord(WireWriter w, ConfigRecord record) {
    w.writeLong(record.epoch()).writeInt(record.members().size());
    for (String m : record.members()) {
      w.writeString(m);
    }
  }

  private static ConfigRecord readRecord(WireReader r) {
    long epoch = r.readLong();
    int size = r.readInt();
    List<String> members = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      members.add(r.readString());
    }
    return new ConfigRecord(epoch, members);
  }

  private static void checkVersion(byte version) {
    if (version != VERSION) {
      throw new IllegalArgumentException("unsupported config wire version: " + version);
    }
  }
}
