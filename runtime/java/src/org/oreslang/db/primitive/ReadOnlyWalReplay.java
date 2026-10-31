package org.oreslang.db.primitive;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only ODB1 inspection on a trusted, preopened, EXCLUSIVELY LEASED descriptor.
 * This is a HOST diagnostic, not an Oreslang guest capability, commit, or
 * authorization to truncate, reopen for appends, publish a shard index, or ACK.
 * The host must independently authenticate tenant/isolate/actor generation and
 * exclusive descriptor custody. No caller-supplied path is accepted.
 */
final class ReadOnlyWalReplay {
  static final int MAX_BYTES = 1024 * 1024;
  static final int MAX_RECORDS = 4096;
  private static final int HEADER_BYTES = 26;
  private static final int TRAILER_BYTES = 4;
  private static final int MAX_FRAME_BYTES = 1118;

  static final class InvalidWal extends IOException {
    private static final long serialVersionUID = 1L;
    private final String reason;
    private final long offset;
    InvalidWal(String reason, long offset) {
      super(reason + " at byte " + offset);
      this.reason = reason;
      this.offset = offset;
    }
    String reason() { return reason; }
    long offset() { return offset; }
  }

  /** Immutable-by-contract, with byte arrays defensively copied on both sides. */
  static final class Record {
    private final int generation;
    private final int sequence;
    private final int operationId;
    private final int kind;
    private final byte[] key;
    private final byte[] value;
    Record(int generation, int sequence, int operationId, int kind,
           byte[] key, byte[] value) {
      this.generation = generation;
      this.sequence = sequence;
      this.operationId = operationId;
      this.kind = kind;
      this.key = key.clone();
      this.value = value.clone();
    }
    int generation() { return generation; }
    int sequence() { return sequence; }
    int operationId() { return operationId; }
    int kind() { return kind; }
    byte[] key() { return key.clone(); }
    byte[] value() { return value.clone(); }
  }

  /** A verified *candidate record stream*, NOT a durable/recovered index. */
  record Inspection(List<Record> records, long bytesChecked,
                    int lastSequence, int lastGeneration) {
    Inspection {
      records = List.copyOf(records);
    }
  }

  private ReadOnlyWalReplay() { }

  /**
   * Verify the ENTIRE ODB1 file before returning any records; fail closed on
   * ambiguous tail, damaged middle, gaps, duplicates, and foreign tenants.
   * Full-log-only v1: sequence begins at 1; no checkpoint or resume cursor.
   * This routine never mutates or closes the caller's FileChannel.
   */
  static Inspection inspectPreopened(FileChannel channel, int trustedTenant) throws IOException {
    Objects.requireNonNull(channel);
    if (trustedTenant <= 0) throw new IllegalArgumentException("invalid trusted tenant");
    long size = channel.size();
    if (size < 0 || size > MAX_BYTES) throw new InvalidWal("wal_size_out_of_bounds", 0);
    byte[] bytes = new byte[(int) size];
    ByteBuffer destination = ByteBuffer.wrap(bytes);
    long position = 0;
    while (destination.hasRemaining()) {
      int n = channel.read(destination, position);
      if (n <= 0) throw new InvalidWal("wal_short_or_stalled_read", position);
      position += n;
    }
    // Still requires an external exclusive lease: size checks alone do not
    // protect against a concurrent in-place overwrite or ABA replacement.
    if (channel.size() != size) throw new InvalidWal("wal_changed_during_scan", position);

    List<Record> candidate = new ArrayList<>();
    Set<Integer> operations = new HashSet<>();
    int at = 0;
    int lastSequence = 0;
    int lastGeneration = 0;
    while (at < bytes.length) {
      if (candidate.size() == MAX_RECORDS)
        throw new InvalidWal("too_many_records", at);
      int left = bytes.length - at;
      if (left < HEADER_BYTES) throw new InvalidWal("unverified_short_tail", at);
      if (u8(bytes, at) != 79 || u8(bytes, at + 1) != 68 ||
          u8(bytes, at + 2) != 66 || u8(bytes, at + 3) != 49 ||
          u8(bytes, at + 4) != 1)
        throw new InvalidWal("invalid_magic_or_version", at);
      int kind = u8(bytes, at + 5);
      if (kind != 1 && kind != 2) throw new InvalidWal("invalid_kind", at);
      int tenant = i31(bytes, at + 6, at);
      int generation = i31(bytes, at + 10, at);
      int seq = i31(bytes, at + 14, at);
      int op = i31(bytes, at + 18, at);
      if (tenant != trustedTenant || tenant == 0 || generation == 0 ||
          seq == 0 || op == 0)
        throw new InvalidWal("invalid_identity_or_tenant", at);
      if (generation < lastGeneration)
        throw new InvalidWal("generation_regression", at);
      if (lastSequence == Integer.MAX_VALUE || seq != lastSequence + 1)
        throw new InvalidWal("sequence_gap", at);
      if (operations.contains(op)) throw new InvalidWal("duplicate_operation", at);
      int keyLength = u16(bytes, at + 22);
      int valueLength = u16(bytes, at + 24);
      if (keyLength < 1 || keyLength > 64 || valueLength > 1024 ||
          (kind == 2 && valueLength != 0))
        throw new InvalidWal("invalid_payload_length", at);
      int frameLength = HEADER_BYTES + keyLength + valueLength + TRAILER_BYTES;
      if (frameLength > MAX_FRAME_BYTES) throw new InvalidWal("invalid_frame_length", at);
      if (left < frameLength)
        throw new InvalidWal("possible_partial_tail_no_repair_authority", at);
      int a = 1, b = 0;
      for (int i = at; i < at + frameLength - TRAILER_BYTES; ++i) {
        a = (a + u8(bytes, i)) % 65521;
        b = (b + a) % 65521;
      }
      int trailer = at + frameLength - TRAILER_BYTES;
      if (u16(bytes, trailer) != a || u16(bytes, trailer + 2) != b)
        throw new InvalidWal("checksum_mismatch", at);
      byte[] key = new byte[keyLength], value = new byte[valueLength];
      System.arraycopy(bytes, at + HEADER_BYTES, key, 0, keyLength);
      System.arraycopy(bytes, at + HEADER_BYTES + keyLength, value, 0, valueLength);
      candidate.add(new Record(generation, seq, op, kind, key, value));
      operations.add(op);
      lastSequence = seq;
      lastGeneration = generation;
      at += frameLength;
    }
    if (channel.size() != size) throw new InvalidWal("wal_changed_during_scan", at);
    return new Inspection(candidate, size, lastSequence, lastGeneration);
  }

  private static int u8(byte[] bytes, int offset) { return bytes[offset] & 255; }
  private static int u16(byte[] bytes, int offset) {
    return u8(bytes, offset) | u8(bytes, offset + 1) << 8;
  }
  private static int i31(byte[] bytes, int offset, int frameOffset) throws InvalidWal {
    if (u8(bytes, offset + 3) > 127)
      throw new InvalidWal("high_bit_identity", frameOffset);
    return u8(bytes, offset) | u8(bytes, offset + 1) << 8 |
        u8(bytes, offset + 2) << 16 | u8(bytes, offset + 3) << 24;
  }
}
