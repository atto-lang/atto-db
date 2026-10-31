package org.oreslang.db.primitive;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Host regression only; the executor simulates a serialized actor turn. */
public final class DbShardActivationRegistryTest {
  private static int assertions;
  private DbShardActivationRegistryTest() { }
  private static void check(boolean b, String why) {
    assertions++;
    if (!b) throw new AssertionError(why);
  }
  private static void rejects(Throwing action, Class<? extends Throwable> expected,
                              String why) throws Exception {
    boolean rejected = false;
    try { action.run(); }
    catch (Throwable actual) { rejected = expected.isInstance(actual); }
    check(rejected, why);
  }
  private interface Throwing { void run() throws Exception; }

  private static final class Harness implements AutoCloseable {
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
        Thread.ofVirtual().name("ores-db-stage13-turn-", 0).factory());
    private final ThreadLocal<Boolean> turn = ThreadLocal.withInitial(() -> false);
    final AtomicBoolean permit = new AtomicBoolean(true);
    final AtomicBoolean freshness = new AtomicBoolean(true);
    final AtomicReference<DbShardActivationRegistry.Activation> current =
        new AtomicReference<>();
    final DbShardActivationRegistry registry = DbShardActivationRegistry.fromTrustedRuntime(
        new DbShardActivationRegistry.TrustedRuntime() {
          public boolean mayActivate(DbShardActivationRegistry.ShardKey key,
                                     int generation, FileChannel fd) {
            return permit.get() && fd.isOpen();
          }
          public boolean isCurrent(DbShardActivationRegistry.Activation activation) {
            return freshness.get() && current.get() == activation;
          }
          public boolean isActorTurn(DbShardActivationRegistry.Activation activation) {
            return turn.get() && current.get() == activation;
          }
          public void enqueueActorTurn(DbShardActivationRegistry.Activation a, Runnable action) {
            executor.execute(() -> {
              turn.set(true);
              try { action.run(); }
              finally { turn.remove(); }
            });
          }
        });

    <T> T onActor(Callable<T> action) throws Exception {
      return executor.submit(() -> {
        turn.set(true);
        try { return action.call(); }
        finally { turn.remove(); }
      }).get(10, TimeUnit.SECONDS);
    }
    @Override public void close() { executor.close(); }
  }

  private static byte[] frame(int generation, int sequence, int operation) {
    var b = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
    b.put(new byte[] {79,68,66,49,1,1});
    b.putInt(7).putInt(generation).putInt(sequence).putInt(operation);
    b.putShort((short) 1).putShort((short) 1).put((byte)107).put((byte)118);
    int a = 1, c = 0;
    for (int i = 0; i < 28; ++i) {
      a = (a + Byte.toUnsignedInt(b.get(i))) % 65521;
      c = (c + a) % 65521;
    }
    b.putShort((short)a).putShort((short)c);
    return b.array();
  }

  private static void waitForSequence(Harness h, DbShardActivationRegistry.Activation a,
                                      int sequence) throws Exception {
    for (int i = 0; i < 250; i++) {
      if (h.onActor(() -> a.snapshotOnActorTurn()).sequence() == sequence) return;
      Thread.sleep(12);
    }
    throw new AssertionError("real WAL publication callback never arrived");
  }

  private static void run() throws Exception {
    Path wal = Files.createTempFile("ores-db-stage13-", ".wal");
    Path other = Files.createTempFile("ores-db-stage13-restart-", ".wal");
    try (Harness h = new Harness()) {
      var key = new DbShardActivationRegistry.ShardKey(7,11,5);
      rejects(() -> new DbShardActivationRegistry.ShardKey(0,11,5),
          IllegalArgumentException.class, "invalid key rejected");
      try (FileChannel channel = FileChannel.open(wal,
          StandardOpenOption.READ, StandardOpenOption.WRITE)) {
        h.permit.set(false);
        rejects(() -> h.registry.activateFresh(key,3,channel), SecurityException.class,
            "untrusted runtime cannot issue a grant");
        h.permit.set(true);
        var first = h.registry.activateFresh(key,3,channel);
        h.current.set(first);
        check(first.generation()==3 && first.key().equals(key),"bound shard identity");
        check(!first.canAcknowledgeGuest(),"no guest ACK even for authorized activation");
        check(!first.isRetired(),"new activation open");
        rejects(() -> h.registry.activateFresh(key,4,channel),
            RejectedExecutionException.class, "same shard cannot have concurrent owner");
        var otherKey = new DbShardActivationRegistry.ShardKey(7,11,6);
        rejects(() -> h.registry.activateFresh(otherKey,3,channel),
            RejectedExecutionException.class, "same Java FD cannot be leased twice");
        rejects(() -> first.getOnActorTurn(new byte[]{107}), SecurityException.class,
            "nonactor caller refused");
        check(h.onActor(() -> first.getOnActorTurn(new byte[]{107})).isEmpty(),
            "fresh index empty");
        h.onActor(() -> { first.appendOnActorTurn(frame(3,1,101)); return null; });
        waitForSequence(h,first,1);
        check(h.onActor(() -> first.getOnActorTurn(new byte[]{107})).orElseThrow()[0]==118,
            "real WAL force then actor-turn PUT published");
        byte[] returned = h.onActor(() -> first.getOnActorTurn(new byte[]{107})).orElseThrow();
        returned[0]=99;
        check(h.onActor(() -> first.getOnActorTurn(new byte[]{107})).orElseThrow()[0]==118,
            "read copy prevents aliasing");
        check(Files.size(wal)==32,"real WAL contains exactly one validated frame");
        check(!first.canAcknowledgeGuest(),"sync result still not guest ACK");
        first.retireAsync().toCompletableFuture().get(12, TimeUnit.SECONDS);
        check(first.isRetired() && first.isQuarantinedForDiagnostics(),
            "retired epoch never serves reads");
        rejects(() -> first.getOnActorTurn(new byte[]{107}),SecurityException.class,
            "retired owner cannot query without turn");
        rejects(() -> h.registry.activateFresh(key,3,channel),
            IllegalArgumentException.class, "closed FD cannot be reactivated");
      }
      try(FileChannel oldWal = FileChannel.open(wal, StandardOpenOption.READ)) {
        rejects(() -> h.registry.activateFresh(key,4,oldWal),
            IllegalArgumentException.class, "nonempty WAL requires authenticated recovery");
      }
      try(FileChannel fresh = FileChannel.open(other,
          StandardOpenOption.READ, StandardOpenOption.WRITE)) {
        rejects(() -> h.registry.activateFresh(key,3,fresh),
            RejectedExecutionException.class, "same generation refused after close");
        var second = h.registry.activateFresh(key,4,fresh);
        h.current.set(second);
        check(second.generation()==4,"higher generation admitted only after FD closure");
        h.freshness.set(false);
        rejects(() -> h.onActor(() -> second.getOnActorTurn(new byte[]{107})),
            ExecutionException.class,"stale runtime freshness refused");
        h.freshness.set(true);
        rejects(() -> h.onActor(() -> second.getOnActorTurn(new byte[]{107})),
            ExecutionException.class, "stale activation cannot revive after epoch flip");
        check(second.isQuarantinedForDiagnostics(),
            "stale activation remains permanently quarantined");
        second.retireAsync().toCompletableFuture().get(12, TimeUnit.SECONDS);
        check(second.isRetired() && !second.canAcknowledgeGuest(),
            "new epoch can retire without enabling ACK");
      }
    } finally {
      Files.deleteIfExists(wal);
      Files.deleteIfExists(other);
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 0) throw new IllegalArgumentException("no parameters expected");
    run();
    System.out.println("HOST_SHARD_ACTIVATION_REGISTRY_PASS assertions="+assertions+
        " (host-only runtime harness; NOT native guest or durable recovery)");
  }
}
