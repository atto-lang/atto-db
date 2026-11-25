package org.oreslang.db.primitive;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Host-only real JVM process-kill and read-only WAL replay diagnostic.
 * Does NOT use an Oreslang guest, DbWalActor, runtime-issued storage grant,
 * guest ACK, durable idempotency ledger, or a simulated power failure.
 */
public final class WalPostSyncCrashReplayTest {
  private static int checks;
  private WalPostSyncCrashReplayTest() { }

  private static void check(boolean good, String why) {
    checks++;
    if (!good) throw new AssertionError(why);
  }

  private static byte[] frame(int sequence, int op, int kind,
                              byte[] key, byte[] value) {
    ByteBuffer b = ByteBuffer.allocate(30 + key.length + value.length)
        .order(ByteOrder.LITTLE_ENDIAN);
    b.put(new byte[] {79, 68, 66, 49, 1, (byte) kind});
    b.putInt(7).putInt(3).putInt(sequence).putInt(op);
    b.putShort((short) key.length).putShort((short) value.length);
    b.put(key).put(value);
    int a = 1, c = 0;
    for (int i = 0; i < b.position(); ++i) {
      a = (a + (b.get(i) & 255)) % 65521;
      c = (c + a) % 65521;
    }
    b.putShort((short) a).putShort((short) c);
    return b.array();
  }

  private static void writeFully(FileChannel file, byte[] frame) throws Exception {
    ByteBuffer b = ByteBuffer.wrap(frame);
    while (b.hasRemaining()) {
      int n = file.write(b);
      if (n <= 0) throw new IllegalStateException("WAL write made no progress");
    }
  }

  private static void child(Path file, String phase) throws Exception {
    try (FileChannel wal = FileChannel.open(file, StandardOpenOption.CREATE_NEW,
        StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
      writeFully(wal, frame(1, 101, 1, new byte[] {107}, new byte[] {118}));
      writeFully(wal, frame(2, 102, 2, new byte[] {107}, new byte[0]));
      if ("post-sync".equals(phase)) {
        wal.force(true);
        // Parent receives this marker only after a successful real force(true).
        System.out.println("HOST_FORCE_TRUE_RETURNED");
      } else if ("pre-sync".equals(phase)) {
        // The resulting bytes are never classified as durable after kill.
        System.out.println("HOST_BEFORE_SYNC_UNKNOWN");
      } else {
        throw new IllegalArgumentException("unknown phase");
      }
      System.out.flush();
      // Parent SIGKILLs this JVM before a hypothetical DB ACK.
      Thread.sleep(60_000);
    }
  }

  private static void killAfterMarker(Path wal, String phase, String marker) throws Exception {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
        WalPostSyncCrashReplayTest.class.getName(), "child", wal.toString(), phase)
        .redirectErrorStream(true).start();
    try {
      var markerFuture = CompletableFuture.supplyAsync(() -> {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream()))) {
          return reader.readLine();
        } catch (Exception failure) {
          throw new IllegalStateException(failure);
        }
      });
      check(marker.equals(markerFuture.get(12, TimeUnit.SECONDS)),
          "child must reach explicitly named WAL I/O boundary");
      check(child.isAlive(), "child must still be running before forced kill");
    } finally {
      child.destroyForcibly();
      check(child.waitFor(12, TimeUnit.SECONDS), "child must be reaped after SIGKILL");
    }
    check(child.exitValue() != 0, "child must not report normal exit");
  }

  private static void run(Path dir) throws Exception {
    Path synced = dir.resolve("synced.wal");
    killAfterMarker(synced, "post-sync", "HOST_FORCE_TRUE_RETURNED");
    byte[] recorded = Files.readAllBytes(synced);
    check(recorded.length == 63, "POST-SYNC WAL expected PUT+DELETE frame lengths");
    try (FileChannel read = FileChannel.open(synced, StandardOpenOption.READ)) {
      var scan = ReadOnlyWalReplay.inspectPreopened(read, 7);
      check(scan.bytesChecked() == recorded.length && scan.records().size() == 2,
          "read-only replay sees both previously synchronized frames");
      check(scan.lastSequence() == 2 && scan.lastGeneration() == 3,
          "recovered candidate has exact sequence and activation generation");
      check(scan.records().get(0).operationId() == 101 &&
          scan.records().get(1).operationId() == 102,
          "operation identity survives process exit");
      check(scan.records().get(0).kind() == 1 && scan.records().get(1).kind() == 2,
          "PUT then DELETE semantics preserved");
      check(Arrays.equals(scan.records().get(0).key(), new byte[] {107}) &&
          Arrays.equals(scan.records().get(0).value(), new byte[] {118}),
          "verified candidate payload exact");
    }
    check(Arrays.equals(Files.readAllBytes(synced), recorded),
        "read-only inspection did not mutate WAL");

    byte[] corrupt = recorded.clone();
    corrupt[26] ^= 1;
    Files.write(synced, corrupt);
    try (FileChannel read = FileChannel.open(synced, StandardOpenOption.READ)) {
      try {
        ReadOnlyWalReplay.inspectPreopened(read, 7);
        throw new AssertionError("in-place corrupted committed candidate accepted");
      } catch (ReadOnlyWalReplay.InvalidWal expected) {
        check("checksum_mismatch".equals(expected.reason()) && expected.offset() == 0,
            "corrupt complete frame rejected at precise first-record offset");
      }
    }
    check(Arrays.equals(Files.readAllBytes(synced), corrupt),
        "scanner did not repair/truncate corrupted WAL");

    Path unsynced = dir.resolve("unsynced.wal");
    killAfterMarker(unsynced, "pre-sync", "HOST_BEFORE_SYNC_UNKNOWN");
    // Deliberately do not parse or publish bytes from the pre-sync case:
    // a process-killed write may appear intact in page cache yet be non-durable.
    check(Files.exists(unsynced), "pre-sync candidate file retained for quarantine");
    Files.delete(synced);
    Files.delete(unsynced);
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 3 && "child".equals(args[0])) {
      child(Path.of(args[1]), args[2]);
      return;
    }
    if (args.length != 0) throw new IllegalArgumentException("unexpected test arguments");
    Path dir = Files.createTempDirectory("ores-db-kill-replay-");
    try {
      run(dir);
      System.out.println("HOST_WAL_POSTSYNC_CRASH_REPLAY_PASS checks=" + checks
          + " (JVM SIGKILL and read-only candidate replay; NOT guest, DB commit, or power loss)");
    } finally {
      try (var paths = Files.list(dir)) {
        for (Path path : paths.toList()) Files.deleteIfExists(path);
      }
      Files.deleteIfExists(dir);
    }
  }
}
