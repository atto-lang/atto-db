package org.oreslang.db.primitive;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/** Independent golden bytes + real read-only FileChannel, no mock I/O. */
public final class ReadOnlyWalReplayTest {
  private static int assertions;
  private ReadOnlyWalReplayTest() { }

  private static void check(boolean condition, String message) {
    ++assertions;
    if (!condition) throw new AssertionError(message);
  }

  private static byte[] frame(int tenant, int gen, int seq, int op,
                              int kind, byte[] key, byte[] value) {
    ByteBuffer b = ByteBuffer.allocate(30 + key.length + value.length)
        .order(ByteOrder.LITTLE_ENDIAN);
    b.put(new byte[] {79,68,66,49,1,(byte)kind});
    b.putInt(tenant).putInt(gen).putInt(seq).putInt(op);
    b.putShort((short) key.length).putShort((short) value.length);
    b.put(key).put(value);
    int a = 1, c = 0;
    for (int i = 0; i < b.position(); i++) {
      a = (a + (b.get(i) & 255)) % 65521;
      c = (c + a) % 65521;
    }
    b.putShort((short)a).putShort((short)c);
    return b.array();
  }

  private static byte[] put(int gen, int seq, int op) {
    return frame(7, gen, seq, op, 1, new byte[] {107}, new byte[] {118});
  }
  private static byte[] del(int gen, int seq, int op) {
    return frame(7, gen, seq, op, 2, new byte[] {107}, new byte[0]);
  }
  private static byte[] concat(byte[]... items) {
    int size = 0;
    for (byte[] bytes : items) size += bytes.length;
    byte[] all = new byte[size];
    int offset = 0;
    for (byte[] bytes : items) {
      System.arraycopy(bytes, 0, all, offset, bytes.length);
      offset += bytes.length;
    }
    return all;
  }

  private static ReadOnlyWalReplay.Inspection inspect(Path wal, byte[] bytes) throws IOException {
    Files.write(wal, bytes);
    try (FileChannel channel = FileChannel.open(wal, StandardOpenOption.READ)) {
      return ReadOnlyWalReplay.inspectPreopened(channel, 7);
    }
  }

  private static void rejected(Path wal, byte[] bytes, String reason, long offset) throws Exception {
    try {
      inspect(wal, bytes);
      throw new AssertionError("accepted corrupt log: " + reason);
    } catch (ReadOnlyWalReplay.InvalidWal failure) {
      check(reason.equals(failure.reason()), "reason: " + reason + " got " + failure.reason());
      check(offset == failure.offset(), "failure position: " + reason);
    }
    check(Arrays.equals(Files.readAllBytes(wal), bytes), "rejected log was never repaired");
  }

  private static void basics(Path wal) throws Exception {
    var empty = inspect(wal, new byte[0]);
    check(empty.records().isEmpty() && empty.lastSequence() == 0 && empty.bytesChecked() == 0,
          "new empty log");
    byte[] first = put(3, 1, 42);
    byte[] golden = new byte[] {79,68,66,49,1,1,7,0,0,0,3,0,0,0,1,0,0,0,42,0,0,0,
        1,0,1,0,107,118,33,2,112,31};
    check(Arrays.equals(first, golden), "shared ODB1 golden vector");
    byte[] both = concat(first, del(4, 2, 43));
    var scan = inspect(wal, both);
    check(scan.bytesChecked() == both.length && scan.records().size() == 2,
          "whole-file snapshot and both records");
    check(scan.lastSequence() == 2 && scan.lastGeneration() == 4,
          "ordered replay across activation generation");
    check(scan.records().get(0).kind() == 1 && scan.records().get(1).kind() == 2,
          "put/delete meaning preserved");
    byte[] borrowedKey = scan.records().get(0).key();
    borrowedKey[0] = 0;
    check(scan.records().get(0).key()[0] == 107, "key defensive copy");
    byte[] borrowedVal = scan.records().get(0).value();
    borrowedVal[0] = 0;
    check(scan.records().get(0).value()[0] == 118, "value defensive copy");
    boolean immutable = false;
    try { scan.records().clear(); } catch (UnsupportedOperationException expected) {
      immutable = true;
    }
    check(immutable, "unmodifiable result list");
    check(Arrays.equals(Files.readAllBytes(wal), both), "inspection never writes");
    try (FileChannel ch = FileChannel.open(wal, StandardOpenOption.READ)) {
      boolean denied = false;
      try { ReadOnlyWalReplay.inspectPreopened(ch, 0); }
      catch (IllegalArgumentException expected) { denied = true; }
      check(denied, "invalid trusted tenant refused");
    }
  }

  private static void corruptions(Path wal) throws Exception {
    byte[] first = put(3, 1, 42), second = del(3, 2, 43);
    byte[] badChecksum = concat(first, second);
    badChecksum[first.length + 26] ^= 1;
    rejected(wal, badChecksum, "checksum_mismatch", first.length);
    rejected(wal, concat(first, put(3, 3, 44)), "sequence_gap", first.length);
    rejected(wal, concat(first, put(3, 2, 42)), "duplicate_operation", first.length);
    rejected(wal, concat(first, put(2, 2, 44)), "generation_regression", first.length);
    rejected(wal, concat(first, frame(8, 3, 2, 44, 1,
        new byte[] {107}, new byte[] {118})), "invalid_identity_or_tenant", first.length);
    byte[] highId = first.clone(); highId[9] = (byte) 0x80;
    rejected(wal, highId, "high_bit_identity", 0);
    byte[] badMagic = first.clone(); badMagic[0] = 0;
    rejected(wal, badMagic, "invalid_magic_or_version", 0);
    byte[] badKind = first.clone(); badKind[5] = 3;
    rejected(wal, badKind, "invalid_kind", 0);
    rejected(wal, Arrays.copyOf(first, 25), "unverified_short_tail", 0);
    rejected(wal, Arrays.copyOf(first, 29),
        "possible_partial_tail_no_repair_authority", 0);
    rejected(wal, concat(first, new byte[] {1, 2, 3}),
        "unverified_short_tail", first.length);
    rejected(wal, concat(first, Arrays.copyOf(second, 30)),
        "possible_partial_tail_no_repair_authority", first.length);
    byte[] badLen = first.clone(); badLen[22] = 65;
    rejected(wal, badLen, "invalid_payload_length", 0);
    byte[] invalidDelete = frame(7, 3, 1, 1, 2, new byte[] {107}, new byte[] {1});
    rejected(wal, invalidDelete, "invalid_payload_length", 0);
  }

  private static void bounds(Path wal) throws Exception {
    byte[] records = new byte[4096 * 31];
    for (int i = 1; i <= 4096; i++) {
      byte[] rec = del(3, i, i);
      System.arraycopy(rec, 0, records, (i-1)*31, 31);
    }
    var full = inspect(wal, records);
    check(full.records().size() == 4096 && full.lastSequence() == 4096,
          "full 4096 record history verified");
    rejected(wal, concat(records, del(3, 4097, 4097)),
        "too_many_records", records.length);
    rejected(wal, new byte[ReadOnlyWalReplay.MAX_BYTES + 1],
        "wal_size_out_of_bounds", 0);
    // Valid maximum 1,118-byte ODB1 PUT frame.
    var max = frame(7, 3, 1, 42, 1, new byte[64], new byte[1024]);
    check(max.length == 1118 && inspect(wal, max).records().size() == 1,
          "largest valid frame is accepted");
  }

  public static void main(String[] args) throws Exception {
    Path dir = Files.createTempDirectory("ores-db-readonly-replay-");
    Path wal = dir.resolve("case.wal");
    try {
      basics(wal);
      corruptions(wal);
      bounds(wal);
      System.out.println("HOST_WAL_READONLY_REPLAY_PASS checks=" + assertions
          + " (read-only diagnostic; not guest, lease, truncation, commit or crash proof)");
    } finally {
      Files.deleteIfExists(wal);
      Files.deleteIfExists(dir);
    }
  }
}
