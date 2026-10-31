package org.oreslang.db.primitive;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Java 21 HOST-only regression. When the normal handoff executor rejects a
 * completion, the emergency virtual-thread callback still owns an admitted
 * slot. Revoke must not close its descriptor or resolve before that callback
 * finishes. No Oreslang guest, durable ACK, or power-loss claim.
 */
public final class WalEmergencyQuiescenceTest {
  private static final WalFilePrimitive.Owner OWNER =
      new WalFilePrimitive.Owner(7, 11, 5, 3);

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static byte[] deletion() {
    ByteBuffer b = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN);
    b.put(new byte[] {79, 68, 66, 49, 1, 2});
    b.putInt(7).putInt(3).putInt(1).putInt(101);
    b.putShort((short)1).putShort((short)0).put((byte)107);
    int a = 1, c = 0;
    for (int i = 0; i < 27; ++i) {
      a = (a + (b.get(i) & 255)) % 65521;
      c = (c + a) % 65521;
    }
    b.putShort((short)a).putShort((short)c);
    return b.array();
  }

  private static void run(Path wal) throws Exception {
    CountDownLatch forced = new CountDownLatch(1);
    CountDownLatch releaseForce = new CountDownLatch(1);
    CountDownLatch emergencyCallbackEntered = new CountDownLatch(1);
    CountDownLatch releaseCallback = new CountDownLatch(1);
    WalFilePrimitive.IoHooks hooks = new WalFilePrimitive.IoHooks() {
      public void beforeAppend() { }
      public void force(FileChannel ch) throws IOException {
        ch.force(true);
        forced.countDown();
        try {
          if (!releaseForce.await(8, TimeUnit.SECONDS))
            throw new IOException("forced I/O barrier timed out");
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException(e);
        }
      }
    };
    try (FileChannel file = FileChannel.open(wal, StandardOpenOption.CREATE_NEW,
        StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
      WalFilePrimitive owner = WalFilePrimitive.forNativeFaultTest(file, OWNER, hooks);
      try {
        var receipt = owner.appendAndSync(OWNER, deletion());
        var callback = receipt.handle((value, error) -> {
          check(error != null && value == null, "rejected handoff must be unknown");
          check(Thread.currentThread().getName().startsWith("ores-db-wal-handoff-emergency"),
              "failed handoff callback must never execute on disk owner");
          emergencyCallbackEntered.countDown();
          try {
            if (!releaseCallback.await(8, TimeUnit.SECONDS))
              throw new IllegalStateException("emergency callback barrier timed out");
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
          }
          return 1;
        });
        check(forced.await(5, TimeUnit.SECONDS), "physical sync did not finish");
        var handoff = WalFilePrimitive.class.getDeclaredField("handoffWorker");
        handoff.setAccessible(true);
        ((ExecutorService) handoff.get(owner)).shutdown();
        releaseForce.countDown();
        check(emergencyCallbackEntered.await(5, TimeUnit.SECONDS),
            "rejected receipt did not start emergency callback");

        var close = owner.revokeAndCloseAsync();
        try {
          close.toCompletableFuture().get(1, TimeUnit.SECONDS);
          throw new AssertionError("close completed while emergency callback was blocked");
        } catch (TimeoutException expected) {
          // A tracked emergency completion must keep close pending.
        }
        check(file.isOpen(), "descriptor closed before emergency callback completion");
        releaseCallback.countDown();
        check(callback.toCompletableFuture().get(8, TimeUnit.SECONDS) == 1,
            "emergency callback did not finish");
        close.toCompletableFuture().get(8, TimeUnit.SECONDS);
        check(!file.isOpen(), "descriptor not closed after all callbacks quiesced");
        check(Files.size(wal) == 31, "already-synced WAL data unexpectedly changed");
      } finally {
        releaseForce.countDown();
        releaseCallback.countDown();
        owner.revokeAndCloseAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
      }
    }
  }

  public static void main(String[] args) throws Exception {
    Path dir = Files.createTempDirectory("ores-db-emergency-");
    Path wal = dir.resolve("emergency.wal");
    try {
      run(wal);
    } finally {
      Files.deleteIfExists(wal);
      Files.deleteIfExists(dir);
    }
    System.out.println("HOST_WAL_EMERGENCY_QUIESCENCE_PASS (not Oreslang guest)");
  }
}
