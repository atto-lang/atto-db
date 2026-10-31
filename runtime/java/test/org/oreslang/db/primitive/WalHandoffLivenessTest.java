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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Host-only liveness regression: a callback awaiting a second admitted
 * completion must not monopolize all receipt delivery. Not a guest, actor,
 * crash, or durability test. The actual disk writes remain serialized.
 */
public final class WalHandoffLivenessTest {
  private static final WalFilePrimitive.Owner OWNER =
      new WalFilePrimitive.Owner(7, 11, 5, 3);

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static byte[] deletion(int sequence, int operation) {
    ByteBuffer b = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN);
    b.put(new byte[] {79, 68, 66, 49, 1, 2});
    b.putInt(7).putInt(3).putInt(sequence).putInt(operation);
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
    CountDownLatch firstIoEntered = new CountDownLatch(1);
    CountDownLatch allowFirstIo = new CountDownLatch(1);
    CountDownLatch firstCallbackEntered = new CountDownLatch(1);
    CountDownLatch secondCallbackCompleted = new CountDownLatch(1);
    AtomicBoolean firstDispatch = new AtomicBoolean(true);
    WalFilePrimitive.IoHooks hooks = new WalFilePrimitive.IoHooks() {
      public void beforeAppend() throws IOException {
        if (firstDispatch.compareAndSet(true, false)) {
          firstIoEntered.countDown();
          try {
            if (!allowFirstIo.await(8, TimeUnit.SECONDS))
              throw new IOException("first I/O barrier timed out");
          } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new IOException(interruption);
          }
        }
      }
      public void force(FileChannel file) throws IOException { file.force(true); }
    };

    try (FileChannel file = FileChannel.open(wal, StandardOpenOption.CREATE_NEW,
        StandardOpenOption.READ, StandardOpenOption.WRITE,
        LinkOption.NOFOLLOW_LINKS)) {
      WalFilePrimitive primitive = WalFilePrimitive.forNativeFaultTest(file, OWNER, hooks);
      try {
        var first = primitive.appendAndSync(OWNER, deletion(1, 101));
        // The first I/O barrier guarantees registration before completion.
        var firstCallback = first.thenApply(receipt -> {
          firstCallbackEntered.countDown();
          try {
            if (!secondCallbackCompleted.await(8, TimeUnit.SECONDS))
              throw new IllegalStateException("second receipt blocked by first callback");
          } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interruption);
          }
          check(Thread.currentThread().getName().startsWith("ores-db-wal-handoff-"),
              "first callback was not off-carrier");
          return receipt.sequence();
        });
        check(firstIoEntered.await(5, TimeUnit.SECONDS), "first write never started");
        allowFirstIo.countDown();
        check(firstCallbackEntered.await(5, TimeUnit.SECONDS),
            "first completion callback never began");

        // The original single-thread handoff implementation cannot deliver
        // this receipt until firstCallback returns, which itself awaits this
        // second completion: circular wait / liveness failure.
        var second = primitive.appendAndSync(OWNER, deletion(2, 102));
        var secondCallback = second.thenApply(receipt -> {
          check(Thread.currentThread().getName().startsWith("ores-db-wal-handoff-"),
              "second callback was not off-carrier");
          secondCallbackCompleted.countDown();
          return receipt.sequence();
        });
        check(secondCallback.toCompletableFuture().get(8, TimeUnit.SECONDS) == 2,
            "second independent handoff never completed");
        check(firstCallback.toCompletableFuture().get(8, TimeUnit.SECONDS) == 1,
            "first continuation did not finish");
        check(Files.size(wal) == 62, "serialized WAL bytes changed unexpectedly");
      } finally {
        allowFirstIo.countDown();
        secondCallbackCompleted.countDown();
        primitive.revokeAndCloseAsync().toCompletableFuture().get(15, TimeUnit.SECONDS);
      }
    }
  }

  public static void main(String[] args) throws Exception {
    Path dir = Files.createTempDirectory("ores-db-handoff-liveness-");
    Path wal = dir.resolve("ordered.wal");
    try { run(wal); }
    finally {
      Files.deleteIfExists(wal);
      Files.deleteIfExists(dir);
    }
    System.out.println("HOST_WAL_HANDOFF_LIVENESS_PASS (not Oreslang guest)");
  }
}
