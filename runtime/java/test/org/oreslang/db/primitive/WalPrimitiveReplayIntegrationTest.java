package org.oreslang.db.primitive;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** Host producer->replay compatibility ONLY; no actor, guest, ACK or power-loss proof. */
public final class WalPrimitiveReplayIntegrationTest {
  private static int checks;
  private static final WalFilePrimitive.Owner OWNER =
      new WalFilePrimitive.Owner(7, 11, 5, 3);
  private WalPrimitiveReplayIntegrationTest() { }

  private static void check(boolean condition, String why) {
    ++checks;
    if (!condition) throw new AssertionError(why);
  }

  // Independent v1 wire builder. No calls into the production frame codec.
  private static byte[] frame(int sequence, int operation, int kind,
                              byte[] key, byte[] value) {
    ByteBuffer b = ByteBuffer.allocate(30 + key.length + value.length)
        .order(ByteOrder.LITTLE_ENDIAN);
    b.put(new byte[] {79,68,66,49,1,(byte)kind});
    b.putInt(7).putInt(3).putInt(sequence).putInt(operation);
    b.putShort((short)key.length).putShort((short)value.length);
    b.put(key).put(value);
    int a = 1, c = 0;
    for (int i = 0; i < b.position(); i++) {
      a = (a + (b.get(i) & 255)) % 65521;
      c = (c + a) % 65521;
    }
    b.putShort((short)a).putShort((short)c);
    return b.array();
  }

  private static void run(Path dir) throws Exception {
    Path wal = dir.resolve("writer-replay.wal");
    byte[] put = frame(1, 101, 1, new byte[] {107}, new byte[] {118});
    byte[] del = frame(2, 102, 2, new byte[] {107}, new byte[0]);
    check(put.length == 32 && del.length == 31, "v1 record bounds");
    byte[] expectedBytes = new byte[put.length + del.length];
    System.arraycopy(put, 0, expectedBytes, 0, put.length);
    System.arraycopy(del, 0, expectedBytes, put.length, del.length);

    FileChannel output = FileChannel.open(wal, StandardOpenOption.CREATE_NEW,
        StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    WalFilePrimitive producer = WalFilePrimitive.fromRuntimeFile(output, OWNER);
    try {
      var first = producer.appendAndSync(OWNER, put).toCompletableFuture()
          .get(12, TimeUnit.SECONDS);
      check(first.operationId() == 101 && first.sequence() == 1,
          "first real force receipt identity");
      check(first.endOffset() == put.length &&
          Arrays.equals(first.frameSha256(), MessageDigest.getInstance("SHA-256").digest(put)),
          "first source digest/offset are authentic host results");

      var second = producer.appendAndSync(OWNER, del).toCompletableFuture()
          .get(12, TimeUnit.SECONDS);
      check(second.operationId() == 102 && second.sequence() == 2,
          "second real force receipt identity");
      check(second.endOffset() == expectedBytes.length &&
          Arrays.equals(second.frameSha256(), MessageDigest.getInstance("SHA-256").digest(del)),
          "second host receipt digest/offset");

      boolean foreignDenied = false;
      try {
        producer.appendAndSync(new WalFilePrimitive.Owner(8, 11, 5, 3),
            frame(3, 103, 2, new byte[] {107}, new byte[0]));
      } catch (SecurityException expected) { foreignDenied = true; }
      check(foreignDenied, "foreign tenant cannot submit to host owner");

      boolean duplicateDenied = false;
      try {
        producer.appendAndSync(OWNER,
            frame(3, 101, 2, new byte[] {107}, new byte[0]));
      } catch (RejectedExecutionException expected) { duplicateDenied = true; }
      check(duplicateDenied, "same operation cannot be admitted twice");

      producer.revokeAndCloseAsync().toCompletableFuture().get(12, TimeUnit.SECONDS);
    } finally {
      // Do not forcibly close the descriptor when owner retirement is uncertain.
      // The production owner handles its own quiescent channel close.
    }

    check(Arrays.equals(Files.readAllBytes(wal), expectedBytes),
        "host writer produced exact ordered byte stream");
    try (FileChannel input = FileChannel.open(wal, StandardOpenOption.READ)) {
      var inspection = ReadOnlyWalReplay.inspectPreopened(input, 7);
      check(inspection.records().size() == 2 &&
          inspection.lastSequence() == 2 && inspection.lastGeneration() == 3,
          "read-only inspector validates producer's entire stream");
      check(inspection.bytesChecked() == expectedBytes.length,
          "inspector checks exact total byte length");
      check(Arrays.equals(inspection.snapshotSha256(),
          MessageDigest.getInstance("SHA-256").digest(expectedBytes)),
          "read-only snapshot fingerprint bound to actual WAL bytes");
      check(inspection.records().get(0).operationId() == 101 &&
          inspection.records().get(1).operationId() == 102,
          "replay retained both durable-operation candidates");
      check(inspection.records().get(0).kind() == 1 &&
          inspection.records().get(1).kind() == 2 &&
          Arrays.equals(inspection.records().get(0).key(), new byte[] {107}) &&
          Arrays.equals(inspection.records().get(0).value(), new byte[] {118}),
          "replayed PUT and DELETE have exact contents");
    }

    boolean reopenedDenied = false;
    try (FileChannel again = FileChannel.open(wal,
        StandardOpenOption.READ, StandardOpenOption.WRITE,
        LinkOption.NOFOLLOW_LINKS)) {
      try { WalFilePrimitive.fromRuntimeFile(again, OWNER); }
      catch (IllegalStateException expected) { reopenedDenied = true; }
    }
    check(reopenedDenied, "nonempty WAL refuses append without trusted replay admission");

    byte[] corrupt = expectedBytes.clone();
    corrupt[26] ^= 1;
    Files.write(wal, corrupt);
    try (FileChannel input = FileChannel.open(wal, StandardOpenOption.READ)) {
      try {
        ReadOnlyWalReplay.inspectPreopened(input, 7);
        throw new AssertionError("corrupt WAL accepted");
      } catch (ReadOnlyWalReplay.InvalidWal expected) {
        check("checksum_mismatch".equals(expected.reason()) &&
            expected.offset() == 0, "complete corrupted frame rejected");
      }
    }
    check(Arrays.equals(Files.readAllBytes(wal), corrupt),
        "read-only rejection preserves original corrupt bytes");
    Files.delete(wal);
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 0) throw new IllegalArgumentException("unexpected args");
    Path dir = Files.createTempDirectory("ores-db-writer-replay-");
    try {
      run(dir);
      System.out.println("HOST_WAL_PRIMITIVE_REPLAY_PASS checks=" + checks
          + " (actual host WAL writer and read-only inspector; NOT Oreslang guest or DB ACK)");
    } finally {
      try (var entries = Files.list(dir)) {
        for (Path entry : entries.toList()) Files.deleteIfExists(entry);
      }
      Files.deleteIfExists(dir);
    }
  }
}
