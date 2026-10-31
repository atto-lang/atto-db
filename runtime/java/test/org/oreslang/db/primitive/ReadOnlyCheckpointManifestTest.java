package org.oreslang.db.primitive;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.CRC32C;

/** Independent ODC1 golden vectors with real read-only FileChannel. */
public final class ReadOnlyCheckpointManifestTest {
  private static int checks;
  private ReadOnlyCheckpointManifestTest() { }

  private static void check(boolean ok, String why) {
    ++checks;
    if (!ok) throw new AssertionError(why);
  }
  private static byte[] digest(byte[] bytes) throws Exception {
    return MessageDigest.getInstance("SHA-256").digest(bytes);
  }
  private static byte[] walFrame(int sequence, int op, int generation) {
    ByteBuffer b = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
    b.put(new byte[] {79,68,66,49,1,1}).putInt(7).putInt(generation)
        .putInt(sequence).putInt(op).putShort((short)1).putShort((short)1);
    b.put((byte)107).put((byte)118);
    int a = 1, c = 0;
    for (int i = 0; i < 28; i++) {
      a = (a + (b.get(i) & 255)) % 65521;
      c = (c + a) % 65521;
    }
    b.putShort((short)a).putShort((short)c);
    return b.array();
  }
  private static byte[] concat(byte[] a, byte[] b) {
    byte[] c = Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, c, a.length, b.length);
    return c;
  }
  private static byte[] manifest(int tenant, int shard, int generation,
                                 int sequence, int ops, byte[] walBytes,
                                 byte[] indexDigest) throws Exception {
    ByteBuffer b = ByteBuffer.allocate(100).order(ByteOrder.LITTLE_ENDIAN);
    b.put(new byte[] {79,68,67,49,1,0,0,0}).putInt(tenant).putInt(shard)
        .putInt(generation).putInt(sequence).putInt(walBytes.length).putInt(ops);
    b.put(digest(walBytes)).put(indexDigest);
    CRC32C crc = new CRC32C();
    crc.update(b.array(), 0, 96);
    b.putInt((int) crc.getValue());
    return b.array();
  }
  private static ReadOnlyCheckpointManifest.Candidate inspect(Path a, Path b,
                                                                  Path wal) throws Exception {
    try (FileChannel ca = FileChannel.open(a, StandardOpenOption.READ);
         FileChannel cb = FileChannel.open(b, StandardOpenOption.READ);
         FileChannel cw = FileChannel.open(wal, StandardOpenOption.READ)) {
      ca.position(7); cb.position(3); cw.position(10);
      var candidate = ReadOnlyCheckpointManifest.inspectPreopened(ca, cb, cw, 7, 11);
      check(ca.position() == 7 && cb.position() == 3 && cw.position() == 10,
          "positional reads do not disturb caller cursors");
      return candidate;
    }
  }
  private static void reject(Path a, Path b, Path wal, String reason) throws Exception {
    byte[] originalA = Files.readAllBytes(a), originalB = Files.readAllBytes(b);
    byte[] originalWal = Files.readAllBytes(wal);
    try {
      inspect(a, b, wal);
      throw new AssertionError("accepted " + reason);
    } catch (ReadOnlyCheckpointManifest.InvalidCheckpoint e) {
      check(reason.equals(e.reason()), "fail closed on " + reason + ": " + e.reason());
    }
    check(Arrays.equals(originalA, Files.readAllBytes(a)) &&
          Arrays.equals(originalB, Files.readAllBytes(b)) &&
          Arrays.equals(originalWal, Files.readAllBytes(wal)),
          "rejection never writes, truncates or repairs any descriptor");
  }

  private static void run(Path a, Path b, Path wal) throws Exception {
    byte[] one = walFrame(1, 101, 3), two = concat(one, walFrame(2, 102, 4));
    byte[] index1 = digest(new byte[] {10}), index2 = digest(new byte[] {20});
    byte[] first = manifest(7, 11, 3, 1, 1, one, index1);
    byte[] latest = manifest(7, 11, 4, 2, 2, two, index2);
    check(one.length == 32 && two.length == 64 && first.length == 100,
          "independent fixed-width ODB1/ODC1 wire vectors");
    Files.write(wal, one); Files.write(a, first); Files.write(b, new byte[0]);
    var current = inspect(a, b, wal);
    check(current.slot() == 'A' && current.verifiedRecords() == 1,
          "single manifest candidate matches full WAL inspection");
    check(current.manifest().tenant() == 7 && current.manifest().shard() == 11 &&
          current.manifest().sequence() == 1 && current.manifest().generation() == 3,
          "bind immutable tenant, shard and generation metadata");
    byte[] borrowed = current.manifest().indexSha256();
    borrowed[0] ^= 1;
    check(Arrays.equals(index1, current.manifest().indexSha256()),
          "returned index digest never aliases trusted read snapshot");
    byte[] borrowedWal = current.manifest().walSha256();
    borrowedWal[0] ^= 1;
    check(Arrays.equals(digest(one), current.manifest().walSha256()),
          "returned WAL digest never aliases trusted read snapshot");
    check(Arrays.equals(Files.readAllBytes(a), first), "inspection leaves slot unchanged");

    Files.write(wal, two); Files.write(b, latest);
    current = inspect(a, b, wal);
    check(current.slot() == 'B' && current.verifiedRecords() == 2,
          "newer slot candidate wins over valid historical slot");
    check(current.manifest().walBytes() == 64 && current.manifest().operations() == 2,
          "newer candidate binds exact WAL byte count and operation count");
    Files.write(a, latest);
    current = inspect(a, b, wal);
    check(current.slot() == 'A', "identical candidates choose deterministic A slot");

    byte[] conflicting = manifest(7, 11, 4, 2, 2, two, index1);
    Files.write(b, conflicting);
    reject(a, b, wal, "conflicting_checkpoint_state");
    Files.write(b, new byte[] {79,68,67,49,1});
    current = inspect(a, b, wal);
    check(current.slot() == 'A', "torn inactive manifest never masks valid candidate");
    Files.write(b, new byte[0]);

    byte[] badCrc = latest.clone(); badCrc[95] ^= 1;
    Files.write(a, badCrc);
    reject(a, b, wal, "no_valid_slot");
    Files.write(a, latest);
    Files.write(b, manifest(8, 11, 4, 2, 2, two, index1));
    reject(a, b, wal, "foreign_identity");
    Files.write(b, manifest(7, 12, 4, 2, 2, two, index1));
    reject(a, b, wal, "foreign_identity");
    Files.write(b, new byte[0]);

    Files.write(a, manifest(7, 11, 5, 3, 3, two, index2));
    reject(a, b, wal, "checkpoint_ahead_of_wal");
    Files.write(a, manifest(7, 11, 3, 2, 2, two, index2));
    reject(a, b, wal, "checkpoint_generation_conflict");
    Files.write(a, manifest(7, 11, 4, 2, 1, two, index2));
    reject(a, b, wal, "full_wal_fingerprint_mismatch");
    Files.write(a, manifest(7, 11, 4, 2, 2, one, index2));
    reject(a, b, wal, "full_wal_fingerprint_mismatch");
    Files.write(a, latest);

    byte[] modifiedWal = two.clone(); modifiedWal[27] ^= 1;
    Files.write(wal, modifiedWal);
    try { inspect(a, b, wal); throw new AssertionError("corrupt WAL accepted"); }
    catch (ReadOnlyWalReplay.InvalidWal expected) {
      check("checksum_mismatch".equals(expected.reason()),
            "existing ODB1 checker denies corruption before manifest selection");
    }
    Files.write(wal, two);
    Files.write(b, Arrays.copyOf(latest, 99));
    check(inspect(a, b, wal).slot() == 'A',
          "truncated inactive slot never authorizes repair or publication");
    Files.write(a, new byte[0]);
    reject(a, b, wal, "no_valid_slot");

    Files.write(a, latest); Files.write(b, new byte[0]);
    try (FileChannel ca = FileChannel.open(a, StandardOpenOption.READ);
         FileChannel cb = FileChannel.open(b, StandardOpenOption.READ);
         FileChannel cw = FileChannel.open(wal, StandardOpenOption.READ)) {
      boolean denied = false;
      try { ReadOnlyCheckpointManifest.inspectPreopened(ca, ca, cw, 7, 11); }
      catch (IllegalArgumentException expected) { denied = true; }
      check(denied, "refuse aliased slot descriptors");
      denied = false;
      try { ReadOnlyCheckpointManifest.inspectPreopened(ca, cb, cw, 0, 11); }
      catch (IllegalArgumentException expected) { denied = true; }
      check(denied, "refuse untrusted zero tenant metadata");
    }
  }
  public static void main(String[] args) throws Exception {
    if (args.length != 0) throw new IllegalArgumentException("no CLI arguments");
    Path dir = Files.createTempDirectory("ores-db-odc1-");
    Path a = dir.resolve("checkpoint.a"), b = dir.resolve("checkpoint.b");
    Path wal = dir.resolve("log.wal");
    try {
      run(a, b, wal);
      System.out.println("HOST_ODC1_CHECKPOINT_CANDIDATE_PASS checks=" + checks
          + " (host-only read-only candidate; NOT guest, commit, lease or power-loss proof)");
    } finally {
      Files.deleteIfExists(a); Files.deleteIfExists(b);
      Files.deleteIfExists(wal); Files.deleteIfExists(dir);
    }
  }
}
