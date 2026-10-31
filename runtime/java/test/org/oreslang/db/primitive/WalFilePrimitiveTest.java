package org.oreslang.db.primitive;

import java.io.IOException;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Standalone Java 21 host primitive tests. NOT Oreslang guest or crash proof. */
public final class WalFilePrimitiveTest {
  private static int assertions = 0;
  private static final WalFilePrimitive.Owner OWNER = new WalFilePrimitive.Owner(7, 11, 5, 3);

  private static void check(boolean value, String message) {
    ++assertions;
    if (!value) throw new AssertionError(message);
  }
  private static void denied(Class<? extends Throwable> expected, Runnable call) {
    ++assertions;
    try { call.run(); }
    catch (Throwable actual) {
      if (expected.isInstance(actual)) return;
      throw new AssertionError("expected " + expected + ", got " + actual, actual);
    }
    throw new AssertionError("expected failure " + expected);
  }
  private static FileChannel newWal(Path file) throws IOException {
    return FileChannel.open(file, StandardOpenOption.CREATE_NEW,
        StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
  }
  private static byte[] frame(int tenant, int generation, int sequence,
                              int opId, int kind, byte[] key, byte[] value) {
    ByteBuffer b = ByteBuffer.allocate(30 + key.length + value.length)
        .order(ByteOrder.LITTLE_ENDIAN);
    b.put(new byte[] {79, 68, 66, 49, 1, (byte)kind});
    b.putInt(tenant).putInt(generation).putInt(sequence).putInt(opId);
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
  private static byte[] put(int seq, int op) {
    return frame(7, 3, seq, op, 1, new byte[] {107}, new byte[] {118});
  }
  private static byte[] delete(int seq, int op) {
    return frame(7, 3, seq, op, 2, new byte[] {107}, new byte[0]);
  }
  private static Throwable failure(java.util.concurrent.CompletionStage<?> stage) {
    try { stage.toCompletableFuture().join(); }
    catch (CompletionException e) { return e.getCause(); }
    throw new AssertionError("expected exceptional completion");
  }
  private static void testRealSyncAndReopen(Path dir) throws Exception {
    Path wal = dir.resolve("real.wal");
    byte[] first = put(1, 42);
    byte[] golden = new byte[] {
        79,68,66,49,1,1,7,0,0,0,3,0,0,0,1,0,0,0,42,0,0,0,
        1,0,1,0,107,118,33,2,112,31
    };
    check(Arrays.equals(first, golden), "independent WAL v1 golden bytes");
    FileChannel file = newWal(wal);
    var owner = WalFilePrimitive.fromRuntimeFile(file, OWNER);
    var receipt = owner.appendAndSync(OWNER, first).toCompletableFuture().join();
    check(receipt.operationId() == 42 && receipt.sequence() == 1,
          "bound operation receipt");
    check(receipt.endOffset() == first.length && receipt.owner().equals(OWNER),
          "receipt offset and runtime owner");
    check(receipt.frameSha256().length == 32, "frame digest");
    check(Arrays.equals(Files.readAllBytes(wal), first), "file real bytes after force");
    denied(RejectedExecutionException.class,
          () -> owner.appendAndSync(OWNER, put(2,42)));
    denied(SecurityException.class,
          () -> owner.appendAndSync(new WalFilePrimitive.Owner(8, 11, 5, 3), put(2,43)));
    denied(SecurityException.class,
          () -> owner.appendAndSync(new WalFilePrimitive.Owner(7, 11, 5, 4), put(2,43)));
    denied(SecurityException.class,
          () -> owner.appendAndSync(new WalFilePrimitive.Owner(7, 12, 5, 3), put(2,43)));
    denied(SecurityException.class,
          () -> owner.appendAndSync(new WalFilePrimitive.Owner(7, 11, 6, 3), put(2,43)));
    denied(RejectedExecutionException.class,
          () -> owner.appendAndSync(OWNER, put(3,43)));
    byte[] second = delete(2, 43);
    var receipt2 = owner.appendAndSync(OWNER, second).toCompletableFuture().join();
    check(receipt2.sequence() == 2 && receipt2.endOffset() == first.length + second.length,
          "append position");
    owner.revokeAndCloseAsync().toCompletableFuture().join();
    denied(RejectedExecutionException.class,
          () -> owner.appendAndSync(OWNER, put(3,44)));
    byte[] onDisk = Files.readAllBytes(wal);
    check(onDisk.length == first.length + second.length, "reopen exact length");
    check(Arrays.equals(Arrays.copyOfRange(onDisk, 0, first.length), first), "reopen 1st frame");
    check(Arrays.equals(Arrays.copyOfRange(onDisk, first.length, onDisk.length), second), "reopen 2nd frame");
    try (FileChannel reopened = FileChannel.open(wal, StandardOpenOption.READ,
                                               StandardOpenOption.WRITE)) {
      denied(IllegalStateException.class,
            () -> { try { WalFilePrimitive.fromRuntimeFile(reopened, OWNER); }
                    catch (IOException e) { throw new AssertionError(e); } });
    }
  }
  private static void testInvalidFrames(Path dir) throws Exception {
    Path wal = dir.resolve("invalid.wal");
    var owner = WalFilePrimitive.fromRuntimeFile(newWal(wal), OWNER);
    byte[] badChecksum = put(1, 1);
    badChecksum[26] ^= 1;
    denied(IllegalArgumentException.class, () -> owner.appendAndSync(OWNER, badChecksum));
    byte[] invalidKind = frame(7, 3, 1, 1, 3, new byte[] {107}, new byte[0]);
    denied(IllegalArgumentException.class, () -> owner.appendAndSync(OWNER, invalidKind));
    byte[] wrongTenant = frame(8, 3, 1, 1, 1, new byte[] {107}, new byte[0]);
    denied(SecurityException.class, () -> owner.appendAndSync(OWNER, wrongTenant));
    byte[] wrongGeneration = frame(7, 4, 1, 1, 1, new byte[] {107}, new byte[0]);
    denied(SecurityException.class, () -> owner.appendAndSync(OWNER, wrongGeneration));
    byte[] highBit = put(1, 1);
    highBit[9] = (byte)0x80;
    denied(IllegalArgumentException.class, () -> owner.appendAndSync(OWNER, highBit));
    byte[] lengthMismatch = Arrays.copyOf(put(1,1), 31);
    denied(IllegalArgumentException.class, () -> owner.appendAndSync(OWNER, lengthMismatch));
    byte[] malformedDelete = frame(7, 3, 1, 1, 2, new byte[] {107}, new byte[] {1});
    denied(IllegalArgumentException.class, () -> owner.appendAndSync(OWNER, malformedDelete));
    check(Files.size(wal) == 0, "invalid input must not write");
    owner.revokeAndCloseAsync().toCompletableFuture().join();
  }
  private static void testUnknownQuarantine(Path dir) throws Exception {
    Path wal = dir.resolve("unknown.wal");
    AtomicInteger forced = new AtomicInteger();
    var injected = new WalFilePrimitive.IoHooks() {
      public void beforeAppend() { }
      public void force(FileChannel file) throws IOException {
        forced.incrementAndGet();
        throw new IOException("injected post-write sync failure");
      }
    };
    var owner = WalFilePrimitive.forNativeFaultTest(newWal(wal), OWNER, injected);
    Throwable failure = failure(owner.appendAndSync(OWNER, put(1, 77)));
    check(failure instanceof WalFilePrimitive.UnknownOutcome, "sync failure is unknown");
    check(forced.get() == 1, "force attempted once");
    check(Files.size(wal) == 32, "preserve possibly written WAL bytes");
    denied(RejectedExecutionException.class,
          () -> owner.appendAndSync(OWNER, put(2, 78)));
    owner.revokeAndCloseAsync().toCompletableFuture().join();
    check(Files.size(wal) == 32, "never silently truncate on failure");
  }
  private static void testQueuedAfterFailure(Path dir) throws Exception {
    Path wal = dir.resolve("failure-queue.wal");
    CountDownLatch atForce = new CountDownLatch(1);
    CountDownLatch resume = new CountDownLatch(1);
    var injected = new WalFilePrimitive.IoHooks() {
      public void beforeAppend() { }
      public void force(FileChannel file) throws IOException {
        atForce.countDown();
        try { if (!resume.await(10, TimeUnit.SECONDS)) throw new IOException("timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
        throw new IOException("injected sync failure with one queued write");
      }
    };
    var owner = WalFilePrimitive.forNativeFaultTest(newWal(wal), OWNER, injected);
    var first = owner.appendAndSync(OWNER, put(1, 9));
    check(atForce.await(5, TimeUnit.SECONDS), "first force entered");
    var second = owner.appendAndSync(OWNER, put(2, 10));
    resume.countDown();
    check(failure(first) instanceof WalFilePrimitive.UnknownOutcome,
          "first force failure unknown");
    check(failure(second) instanceof WalFilePrimitive.UnknownOutcome,
          "queued write must not escape quarantine");
    check(Files.size(wal) == 32, "quarantine must stop queued second write");
    owner.revokeAndCloseAsync().toCompletableFuture().join();
  }

  private static void testQueuedLimitAndRevocation(Path dir) throws Exception {
    Path wal = dir.resolve("bounded.wal");
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch proceed = new CountDownLatch(1);
    var injected = new WalFilePrimitive.IoHooks() {
      public void beforeAppend() throws IOException {
        entered.countDown();
        try { if (!proceed.await(10, TimeUnit.SECONDS)) throw new IOException("hook timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
      }
      public void force(FileChannel file) throws IOException { file.force(true); }
    };
    var owner = WalFilePrimitive.forNativeFaultTest(newWal(wal), OWNER, injected);
    var tickets = new java.util.ArrayList<java.util.concurrent.CompletionStage<WalFilePrimitive.SyncedReceipt>>();
    tickets.add(owner.appendAndSync(OWNER, delete(1, 101)));
    check(entered.await(5, TimeUnit.SECONDS), "disk worker entered");
    for (int i = 2; i <= 32; i++) tickets.add(owner.appendAndSync(OWNER, delete(i, 100+i)));
    denied(RejectedExecutionException.class,
          () -> owner.appendAndSync(OWNER, delete(33, 133)));
    var stopping = owner.revokeAndCloseAsync();
    proceed.countDown();
    for (var ticket : tickets) {
      check(failure(ticket) instanceof WalFilePrimitive.UnknownOutcome,
            "post-revoke accepted operation cannot receive sync receipt");
    }
    stopping.toCompletableFuture().join();
    check(Files.size(wal) == 0, "revoked before actual write leaves file intact");
  }
  private static void testCopyBeforeDispatch(Path dir) throws Exception {
    Path wal = dir.resolve("copied.wal");
    CountDownLatch before = new CountDownLatch(1);
    CountDownLatch proceed = new CountDownLatch(1);
    var injected = new WalFilePrimitive.IoHooks() {
      public void beforeAppend() throws IOException {
        before.countDown();
        try { if (!proceed.await(5, TimeUnit.SECONDS)) throw new IOException("timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
      }
      public void force(FileChannel file) throws IOException { file.force(true); }
    };
    var owner = WalFilePrimitive.forNativeFaultTest(newWal(wal), OWNER, injected);
    byte[] caller = put(1, 9), original = caller.clone();
    var ticket = owner.appendAndSync(OWNER, caller);
    var callbackThread = ticket.thenApply(receipt -> Thread.currentThread().getName());
    check(before.await(5, TimeUnit.SECONDS), "deterministic copy test barrier");
    Arrays.fill(caller, (byte)0);
    proceed.countDown();
    ticket.toCompletableFuture().join();
    check(callbackThread.toCompletableFuture().join().startsWith("ores-db-wal-handoff"),
          "callbacks never execute on disk owner or actor carrier");
    check(Arrays.equals(Files.readAllBytes(wal), original), "defensive copy stored bytes");
    owner.revokeAndCloseAsync().toCompletableFuture().join();
  }

  private static void testRejectedHandoffNeverRunsOnDiskWorker(Path dir) throws Exception {
    Path wal = dir.resolve("handoff-rejected.wal");
    CountDownLatch atForce = new CountDownLatch(1);
    CountDownLatch proceed = new CountDownLatch(1);
    var injected = new WalFilePrimitive.IoHooks() {
      public void beforeAppend() { }
      public void force(FileChannel file) throws IOException {
        file.force(true);
        atForce.countDown();
        try { if (!proceed.await(10, TimeUnit.SECONDS)) throw new IOException("hook timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
      }
    };
    var owner = WalFilePrimitive.forNativeFaultTest(newWal(wal), OWNER, injected);
    var ticket = owner.appendAndSync(OWNER, put(1, 53));
    var callback = ticket.handle((receipt, error) -> Thread.currentThread().getName());
    check(atForce.await(5, TimeUnit.SECONDS), "sync finished before handoff rejection");
    var handoff = WalFilePrimitive.class.getDeclaredField("handoffWorker");
    handoff.setAccessible(true);
    ((java.util.concurrent.ExecutorService) handoff.get(owner)).shutdown();
    proceed.countDown();
    check(failure(ticket) instanceof WalFilePrimitive.UnknownOutcome,
          "rejected receipt handoff has unknown outcome");
    check(callback.toCompletableFuture().join().startsWith("ores-db-wal-handoff-emergency"),
          "rejected handoff callbacks must not run on disk owner");
    check(Arrays.equals(Files.readAllBytes(wal), put(1, 53)),
          "handoff failure preserves synced bytes for reconciliation");
    denied(RejectedExecutionException.class,
          () -> owner.appendAndSync(OWNER, put(2, 54)));
    owner.revokeAndCloseAsync().toCompletableFuture().join();
  }

  // A real *separate operating-system process* is killed after force(true)
  // has returned. This does not simulate loss of power or prove a DB ACK.
  private static void testProcessKillAfterSync(Path dir) throws Exception {
    Path wal = dir.resolve("child-killed-after-sync.wal");
    String javaExe = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    Process child = new ProcessBuilder(javaExe, "-cp", System.getProperty("java.class.path"),
        WalFilePrimitiveTest.class.getName(), "--child-postsync", wal.toString())
        .redirectErrorStream(true).start();
    try {
      var line = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
        try { return new BufferedReader(new InputStreamReader(child.getInputStream())).readLine(); }
        catch (IOException problem) { throw new RuntimeException(problem); }
      }).get(10, TimeUnit.SECONDS);
      check("HOST_CHILD_FORCED".equals(line), "child reached actual force(true)");
    } finally {
      child.destroyForcibly();
      check(child.waitFor(10, TimeUnit.SECONDS), "child process terminated");
    }
    check(Arrays.equals(Files.readAllBytes(wal), put(1, 42)),
          "separate process kill after sync preserves WAL bytes");
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 2 && "--child-postsync".equals(args[0])) {
      var owner = WalFilePrimitive.fromRuntimeFile(newWal(Path.of(args[1])), OWNER);
      owner.appendAndSync(OWNER, put(1, 42)).toCompletableFuture().join();
      System.out.println("HOST_CHILD_FORCED");
      System.out.flush();
      Thread.sleep(Long.MAX_VALUE); // parent SIGKILLs this *separate* JVM
      return;
    }
    Path dir = Files.createTempDirectory("ores-db-host-primitive-");
    try {
      testRealSyncAndReopen(dir);
      testInvalidFrames(dir);
      testUnknownQuarantine(dir);
      testQueuedAfterFailure(dir);
      testQueuedLimitAndRevocation(dir);
      testCopyBeforeDispatch(dir);
      testRejectedHandoffNeverRunsOnDiskWorker(dir);
      testProcessKillAfterSync(dir);
      System.out.println("HOST_JAVA_PRIMITIVE_PASS assertions=" + assertions + " (not Oreslang guest; process-kill post-sync only)");
    } finally {
      try (var files = Files.list(dir)) { files.forEach(p -> { try { Files.deleteIfExists(p); }
        catch (IOException e) { throw new RuntimeException(e); } }); }
      Files.deleteIfExists(dir);
    }
  }
}
