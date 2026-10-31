package org.oreslang.db.primitive;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Host only: all 32 synchronous handoffs remain accounted until callbacks exit. */
public final class WalHandoffSaturationTest {
  private static final WalFilePrimitive.Owner OWNER =
      new WalFilePrimitive.Owner(7, 11, 5, 3);

  private static byte[] deletion(int sequence) {
    ByteBuffer b = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN);
    b.put(new byte[] {79, 68, 66, 49, 1, 2});
    b.putInt(7).putInt(3).putInt(sequence).putInt(sequence + 100);
    b.putShort((short)1).putShort((short)0).put((byte)107);
    int a = 1, c = 0;
    for (int i = 0; i < 27; ++i) {
      a = (a + (b.get(i) & 255)) % 65521;
      c = (c + a) % 65521;
    }
    b.putShort((short)a).putShort((short)c);
    return b.array();
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static void run(Path wal) throws Exception {
    CountDownLatch ioEntered = new CountDownLatch(1);
    CountDownLatch allowIo = new CountDownLatch(1);
    CountDownLatch callbacksEntered = new CountDownLatch(32);
    CountDownLatch allowCallbacks = new CountDownLatch(1);
    AtomicBoolean first = new AtomicBoolean(true);
    WalFilePrimitive.IoHooks hooks = new WalFilePrimitive.IoHooks() {
      public void beforeAppend() throws IOException {
        if (first.compareAndSet(true, false)) {
          ioEntered.countDown();
          try {
            if (!allowIo.await(10, TimeUnit.SECONDS))
              throw new IOException("initial I/O gate timed out");
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
          }
        }
      }
      public void force(FileChannel ch) throws IOException { ch.force(true); }
    };
    try (FileChannel file = FileChannel.open(wal, StandardOpenOption.CREATE_NEW,
        StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
      WalFilePrimitive primitive = WalFilePrimitive.forNativeFaultTest(file, OWNER, hooks);
      try {
        var waiting = new ArrayList<CompletionStage<Integer>>();
        for (int i = 1; i <= 32; ++i) {
          final int wanted = i;
          var receipt = primitive.appendAndSync(OWNER, deletion(wanted));
          waiting.add(receipt.thenApply(done -> {
            check(Thread.currentThread().getName().startsWith("ores-db-wal-handoff-"),
                  "callback not on bounded handoff worker");
            callbacksEntered.countDown();
            try {
              if (!allowCallbacks.await(10, TimeUnit.SECONDS))
                throw new IllegalStateException("callback release timed out");
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(e);
            }
            return done.sequence();
          }));
        }
        check(ioEntered.await(5, TimeUnit.SECONDS), "disk owner never admitted work");
        try {
          primitive.appendAndSync(OWNER, deletion(33));
          throw new AssertionError("33rd admission escaped outstanding bound");
        } catch (RejectedExecutionException expected) {
          // The entire bounded queue is retained until handoff completes.
        }
        allowIo.countDown();
        check(callbacksEntered.await(10, TimeUnit.SECONDS),
              "32 handoff callbacks failed to run independently");
        try {
          primitive.appendAndSync(OWNER, deletion(33));
          throw new AssertionError("33rd admission escaped blocked callbacks");
        } catch (RejectedExecutionException expected) { }
        allowCallbacks.countDown();
        for (int i = 0; i < waiting.size(); ++i) {
          check(waiting.get(i).toCompletableFuture().get(10, TimeUnit.SECONDS) == i + 1,
                "ordered completion mismatch");
        }
        // Slots are released after callbacks, not when the disk worker finishes.
        // Wait for the bounded owner to release the final in-flight slot.
        CompletionStage<WalFilePrimitive.SyncedReceipt> next = null;
        for (int attempt = 0; attempt < 300 && next == null; ++attempt) {
          try {
            next = primitive.appendAndSync(OWNER, deletion(33));
          } catch (RejectedExecutionException retry) {
            Thread.sleep(10);
          }
        }
        check(next != null, "slot was not returned after handoff callbacks");
        check(next.toCompletableFuture().get(10, TimeUnit.SECONDS).sequence() == 33,
              "post-release sequence mismatch");
        check(Files.size(wal) == 33L * 31, "WAL record length mismatch");
      } finally {
        allowIo.countDown();
        allowCallbacks.countDown();
        primitive.revokeAndCloseAsync().toCompletableFuture().get(15, TimeUnit.SECONDS);
      }
    }
  }

  public static void main(String[] args) throws Exception {
    Path dir = Files.createTempDirectory("ores-db-handoff-bound-");
    Path wal = dir.resolve("bounded.wal");
    try { run(wal); }
    finally {
      Files.deleteIfExists(wal);
      Files.deleteIfExists(dir);
    }
    System.out.println("HOST_WAL_HANDOFF_BOUND_PASS slots=32 (not Oreslang guest)");
  }
}
