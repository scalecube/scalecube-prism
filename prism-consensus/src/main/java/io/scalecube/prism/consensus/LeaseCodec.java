package io.scalecube.prism.consensus;

import io.scalecube.prism.codec.WireReader;
import io.scalecube.prism.codec.WireWriter;

/**
 * Schema'd binary codec for the lease protocol messages (ADR-0009). Encodes/decodes
 * {@link LeaseRequest} and {@link LeaseResponse} to {@code byte[]} field-by-field — no Java
 * serialization, so no deserialization-gadget surface. A leading version byte allows evolution.
 */
public final class LeaseCodec {

  private static final byte VERSION = 1;

  private LeaseCodec() {}

  /**
   * Encodes a request.
   *
   * @param request the request
   * @return the encoded bytes
   */
  public static byte[] encode(LeaseRequest request) {
    WireWriter w = new WireWriter().writeByte(VERSION).writeBoolean(request.isGet());
    w.writeString(request.group());
    if (request.isGet()) {
      w.writeLong(request.prepareBallot()); // 0 = plain GET; > 0 = PREPARE
    } else {
      LeaseRecord lease = request.toLease();
      w.writeString(lease.owner()).writeLong(lease.epoch()).writeLong(lease.expiresAt());
    }
    return w.toBytes();
  }

  /**
   * Encodes a response.
   *
   * @param response the response
   * @return the encoded bytes
   */
  public static byte[] encode(LeaseResponse response) {
    WireWriter w = new WireWriter().writeByte(VERSION).writeBoolean(response.ok());
    w.writeLong(response.promised());
    LeaseRecord lease = response.currentLease().orElse(null);
    w.writeBoolean(lease != null);
    if (lease != null) {
      w.writeString(lease.group())
          .writeString(lease.owner())
          .writeLong(lease.epoch())
          .writeLong(lease.expiresAt());
    }
    return w.toBytes();
  }

  /**
   * Decodes a request.
   *
   * @param bytes the encoded bytes
   * @return the request
   */
  public static LeaseRequest decodeRequest(byte[] bytes) {
    WireReader r = new WireReader(bytes);
    checkVersion(r.readByte());
    boolean get = r.readBoolean();
    String group = r.readString();
    if (get) {
      long prepareBallot = r.readLong();
      return prepareBallot > 0
          ? LeaseRequest.prepare(group, prepareBallot)
          : LeaseRequest.get(group);
    }
    String owner = r.readString();
    long epoch = r.readLong();
    long expiresAt = r.readLong();
    return LeaseRequest.accept(new LeaseRecord(group, owner, epoch, expiresAt));
  }

  /**
   * Decodes a response.
   *
   * @param bytes the encoded bytes
   * @return the response
   */
  public static LeaseResponse decodeResponse(byte[] bytes) {
    WireReader r = new WireReader(bytes);
    checkVersion(r.readByte());
    boolean ok = r.readBoolean();
    long promised = r.readLong();
    boolean hasLease = r.readBoolean();
    final LeaseRecord lease;
    if (hasLease) {
      String group = r.readString();
      String owner = r.readString();
      long epoch = r.readLong();
      long expiresAt = r.readLong();
      lease = new LeaseRecord(group, owner, epoch, expiresAt);
    } else {
      lease = null;
    }
    return LeaseResponse.promise(ok, lease, promised);
  }

  private static void checkVersion(byte version) {
    if (version != VERSION) {
      throw new IllegalArgumentException("unsupported lease wire version: " + version);
    }
  }
}
