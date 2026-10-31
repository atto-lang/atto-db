package org.oreslang.db.primitive;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Host-only: actual WAL primitive child process killed after sync/before DB ACK. */
public final class WalPrimitiveProcessKillReplayTest {
  private static final WalFilePrimitive.Owner OWNER =
      new WalFilePrimitive.Owner(7, 11, 5, 3);
  private static int checks;
  private WalPrimitiveProcessKillReplayTest() { }

  private static void check(boolean condition, String detail) {
    ++checks;
    if (!condition) throw new AssertionError(detail);
  }

  private static byte[] frame(int sequence, int operation) {
    ByteBuffer b = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
    b.put(new byte[] {79, 68, 66, 49, 1, 1});
    b.putInt(7).putInt(3).putInt(sequence).putInt(operation);
    b.putShort((short) 1).putShort((short) 1).put((byte) 107).put((byte) 118);
    int a = 1, c = 0;
    for (int i = 0; i < 28; ++i) {
      a = (a + (b.get(i) & 255)) % 65521;
      c = (c + a) % 65521;
    }
    b.putShort((short) a).putShort((short) c);
    return b.array();
  }

  private static void child(Path wal, String phase) throws Exception {
    // CREATE_NEW, NOFOLLOW_LINKS is only a local regression choice. It does
    // not establish production directory confinement or an exclusive lease.
    FileChannel file = FileChannel.open(wal, StandardOpenOption.CREATE_NEW,
        StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    WalFilePrimitive writer;
    if ("after-sync".equals(phase)) {
      writer = WalFilePrimitive.fromRuntimeFile(file, OWNER);
    } else if ("before-sync".equals(phase)) {
      // Fault-injection hook is package-private and HOST-ONLY. The physical
      // append completed, but force(true) cannot complete until killed.
      writer = WalFilePrimitive.forNativeFaultTest(file, OWNER, new WalFilePrimitive.IoHooks() {
        public void beforeAppend() { }
        public void force(FileChannel channel) throws IOException {
          System.out.println("PHYSICAL_APPEND_BEFORE_FORCE");
          System.out.flush();
          try { Thread.sleep(60_000); }
          catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("pre-sync interrupted", interrupted);
          }
        }
      });
    } else {
      throw new IllegalArgumentException("unknown phase");
    }
    var pending = writer.appendAndSync(OWNER, frame(1, 101));
    if ("after-sync".equals(phase)) {
      var receipt = pending.toCompletableFuture().get(15, TimeUnit.SECONDS);
      if (receipt.sequence() != 1 || receipt.operationId() != 101 ||
          receipt.endOffset() != 32) throw new IllegalStateException("invalid host receipt");
      // Only real append + FileChannel.force(true) through WalFilePrimitive
      // can reach this marker. It is NOT an Oreslang database ACK.
      System.out.println("HOST_PRIMITIVE_SYNC_RECEIPT_BEFORE_ACK");
      System.out.flush();
    }
    Thread.sleep(60_000); // parent must SIGKILL; never issue a DB ACK
  }

  private static void killAt(Path wal, String phase, String marker) throws Exception {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
        WalPrimitiveProcessKillReplayTest.class.getName(), "child", wal.toString(), phase)
        .redirectErrorStream(true).start();
    try {
      var message = CompletableFuture.supplyAsync(() -> {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(child.getInputStream()))) {
          return in.readLine();
        } catch (IOException io) {
          throw new IllegalStateException(io);
        }
      });
      check(marker.equals(message.get(15, TimeUnit.SECONDS)), "child reached selected physical boundary");
      check(child.isAlive(), "child still live before forced termination");
    } finally {
      child.destroyForcibly();
      check(child.waitFor(15, TimeUnit.SECONDS), "child actually terminated");
    }
    check(child.exitValue() != 0, "child did not exit cleanly");
  }

  private static void run(Path dir) throws Exception {
    Path synced = dir.resolve("synced.wal");
    killAt(synced, "after-sync", "HOST_PRIMITIVE_SYNC_RECEIPT_BEFORE_ACK");
    byte[] expected = frame(1, 101);
    check(Arrays.equals(Files.readAllBytes(synced), expected), "synced frame survived writer SIGKILL");
    try (FileChannel read = FileChannel.open(synced, StandardOpenOption.READ)) {
      var recovered = ReadOnlyWalReplay.inspectPreopened(read, 7);
      check(recovered.records().size() == 1 && recovered.lastSequence() == 1 &&
          recovered.lastGeneration() == 3, "read-only candidate replay of real writer");
      check(recovered.records().get(0).operationId() == 101 &&
          Arrays.equals(recovered.records().get(0).key(), new byte[] {107}) &&
          Arrays.equals(recovered.records().get(0).value(), new byte[] {118}),
          "candidate operation and bytes survived process restart");
      check(Arrays.equals(recovered.snapshotSha256(),
          MessageDigest.getInstance("SHA-256").digest(expected)),
          "read-only snapshot hash matches real physical WAL bytes");
    }
    check(Arrays.equals(Files.readAllBytes(synced), expected), "replay never rewrote WAL bytes");

    Path unsynced = dir.resolve("unsynced.wal");
    killAt(unsynced, "before-sync", "PHYSICAL_APPEND_BEFORE_FORCE");
    check(Files.exists(unsynced), "possible pre-force WAL bytes retained for quarantine");
    // Intentionally DO NOT inspect or publish the unsynced WAL, even when
    // its page-cache contents appear valid after a process kill.
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 3 && "child".equals(args[0])) {
      child(Path.of(args[1]), args[2]);
      return;
    }
    if (args.length != 0) throw new IllegalArgumentException("unexpected test args");
    Path dir = Files.createTempDirectory("ores-db-primitive-kill-");
    try {
      run(dir);
      System.out.println("HOST_WAL_PRIMITIVE_PROCESS_KILL_PASS checks=" + checks
          + " (real host WAL force/replay; NOT guest ACK, lease or power loss)");
    } finally {
      try (var entries = Files.list(dir)) {
        for (Path entry : entries.toList()) Files.deleteIfExists(entry);
      }
      Files.deleteIfExists(dir);
    }
  }
}
