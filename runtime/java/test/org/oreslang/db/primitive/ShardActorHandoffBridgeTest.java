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

/**
 * HOST-ONLY real producer + grant + continuation gate + serialized actor
 * harness. The executor is test-owned, NOT the Oreslang actor scheduler.
 */
public final class ShardActorHandoffBridgeTest {
  private static int checks;
  private ShardActorHandoffBridgeTest() { }

  private static void check(boolean ok, String why) {
    ++checks;
    if (!ok) throw new AssertionError(why);
  }

  private static byte[] frame(int generation, int sequence, int operation,
                              int kind, byte[] key, byte[] value) {
    ByteBuffer b = ByteBuffer.allocate(30 + key.length + value.length)
        .order(ByteOrder.LITTLE_ENDIAN);
    b.put(new byte[] {79,68,66,49,1,(byte)kind});
    b.putInt(7).putInt(generation).putInt(sequence).putInt(operation);
    b.putShort((short)key.length).putShort((short)value.length);
    b.put(key).put(value);
    int a = 1, c = 0;
    for (int i = 0; i < b.position(); ++i) {
      a = (a + Byte.toUnsignedInt(b.get(i))) % 65521;
      c = (c + a) % 65521;
    }
    b.putShort((short)a).putShort((short)c);
    return b.array();
  }

  private static <T> T actor(ExecutorService executor, Callable<T> turn)
      throws Exception {
    return executor.submit(turn).get(12, TimeUnit.SECONDS);
  }

  private static void denied(ExecutorService executor, Callable<?> turn,
                             Class<? extends Throwable> kind, String why)
      throws Exception {
    boolean rejected = false;
    try { actor(executor, turn); }
    catch (ExecutionException failed) {
      rejected = kind.isInstance(failed.getCause());
    }
    check(rejected, why);
  }

  private static ShardActorHandoffBridge bridge(
      WalRuntimeGrant.Context context, WalRuntimeGrant writer,
      ExecutorService actor, AtomicBoolean current, AtomicBoolean rejectDispatch) {
    var queue = new WalActorContinuationGate.RuntimeQueue() {
      public boolean isCurrent(WalRuntimeGrant.Context caller) {
        return caller == context && current.get();
      }
      public void dispatch(WalRuntimeGrant.Context caller, Runnable continuation) {
        if (rejectDispatch.get()) throw new RejectedExecutionException("test actor mailbox closed");
        actor.execute(continuation);
      }
    };
    return ShardActorHandoffBridge.fromTrustedRuntime(
        context, writer, queue, caller ->
            caller == context && current.get() &&
            Thread.currentThread().getName().startsWith("ores-db-test-shard-"));
  }

  private static void waitSequence(ExecutorService actor,
                                   ShardActorHandoffBridge bridge,
                                   WalRuntimeGrant.Context context, int sequence)
      throws Exception {
    for (int i = 0; i < 350; ++i) {
      var snap = actor(actor, () -> bridge.snapshotOnActorTurn(context));
      if (snap.sequence() == sequence) return;
      Thread.sleep(10);
    }
    throw new AssertionError("timed out waiting for published sequence " + sequence);
  }

  private static void awaitQuarantine(ShardActorHandoffBridge bridge) throws Exception {
    for (int i = 0; i < 350; ++i) {
      if (bridge.isQuarantinedForDiagnostics()) return;
      Thread.sleep(10);
    }
    throw new AssertionError("lost receipt did not quarantine the actor bridge");
  }

  private static void orderedPublication() throws Exception {
    Path wal = Files.createTempFile("ores-db-shard-handoff-", ".wal");
    try (FileChannel channel = FileChannel.open(
             wal, StandardOpenOption.READ, StandardOpenOption.WRITE);
         ExecutorService actor = Executors.newSingleThreadExecutor(
             Thread.ofVirtual().name("ores-db-test-shard-", 0).factory())) {
      var context = WalRuntimeGrant.issueContextFromTrustedRuntime(
          new WalFilePrimitive.Owner(7, 11, 5, 3));
      var equalIdsDifferentContext = WalRuntimeGrant.issueContextFromTrustedRuntime(
          new WalFilePrimitive.Owner(7, 11, 5, 3));
      var grant = WalRuntimeGrant.attachPreopenedFromRuntime(channel, context);
      AtomicBoolean current = new AtomicBoolean(true);
      AtomicBoolean reject = new AtomicBoolean(false);
      var bridge = bridge(context, grant, actor, current, reject);
      byte[] key = {107};
      byte[] one = frame(3, 1, 101, 1, key, new byte[] {118});
      byte[] two = frame(3, 2, 102, 1, key, new byte[] {119});
      byte[] del = frame(3, 3, 103, 2, key, new byte[0]);

      check(!bridge.canAcknowledgeGuest(), "no guest ACK entry point");
      denied(actor, () -> bridge.readOnActorTurn(equalIdsDifferentContext, key),
          SecurityException.class, "numeric-equivalent context refused");
      actor(actor, () -> {
        bridge.appendOnActorTurn(context, one);
        check(bridge.readOnActorTurn(context, key).isEmpty(),
              "no visibility before fsync callback and actor turn");
        check(bridge.snapshotOnActorTurn(context).sequence() == 0,
              "no early index publication");
        return null;
      });
      waitSequence(actor, bridge, context, 1);
      check(actor(actor, () -> bridge.readOnActorTurn(context, key))
          .orElseThrow()[0] == 118, "PUT visible after scheduler-gated receipt");

      byte[] mut = actor(actor, () -> bridge.readOnActorTurn(context, key)).orElseThrow();
      mut[0] = 1;
      check(actor(actor, () -> bridge.readOnActorTurn(context, key))
          .orElseThrow()[0] == 118, "published lookup returns a copied value");
      denied(actor, () -> {
        bridge.appendOnActorTurn(context, frame(3, 2, 101, 1, key, new byte[] {120}));
        return null;
      }, RejectedExecutionException.class, "duplicate operation rejected");

      actor(actor, () -> {
        bridge.appendOnActorTurn(context, two);
        check(bridge.readOnActorTurn(context, key).orElseThrow()[0] == 118,
              "pending overwrite does not publish early");
        return null;
      });
      waitSequence(actor, bridge, context, 2);
      check(actor(actor, () -> bridge.readOnActorTurn(context, key))
          .orElseThrow()[0] == 119, "second write published in order");

      actor(actor, () -> {
        bridge.appendOnActorTurn(context, del);
        check(bridge.readOnActorTurn(context, key).isPresent(),
              "pending DELETE remains invisible");
        return null;
      });
      waitSequence(actor, bridge, context, 3);
      check(actor(actor, () -> bridge.readOnActorTurn(context, key)).isEmpty(),
            "DELETE visible after sync and actor-turn publication");
      var snap = actor(actor, () -> bridge.snapshotOnActorTurn(context));
      check(snap.sequence() == 3 && snap.operations() == 3 &&
            snap.walBytes() == 95 && snap.keySlots() == 1,
            "bounded sequenced WAL and tombstone accounting");
      check(!bridge.isQuarantinedForDiagnostics(), "healthy actor turn bridge");
      check(!bridge.canAcknowledgeGuest(), "host publication cannot ACK guest");
      bridge.retireFromTrustedRuntime().toCompletableFuture()
          .get(12, TimeUnit.SECONDS);
      check(bridge.isQuarantinedForDiagnostics(), "retirement quarantines bridge");
      denied(actor, () -> bridge.readOnActorTurn(context, key),
          RejectedExecutionException.class, "retired shard never serves reads");
    } finally {
      Files.deleteIfExists(wal);
    }
  }

  private static void lostCallbackFailsClosed(boolean staleGeneration) throws Exception {
    Path wal = Files.createTempFile("ores-db-shard-lost-", ".wal");
    try (FileChannel channel = FileChannel.open(
             wal, StandardOpenOption.READ, StandardOpenOption.WRITE);
         ExecutorService actor = Executors.newSingleThreadExecutor(
             Thread.ofVirtual().name("ores-db-test-shard-", 0).factory())) {
      var ctx = WalRuntimeGrant.issueContextFromTrustedRuntime(
          new WalFilePrimitive.Owner(7, 11, 5, 3));
      var grant = WalRuntimeGrant.attachPreopenedFromRuntime(channel, ctx);
      AtomicBoolean current = new AtomicBoolean(true);
      AtomicBoolean reject = new AtomicBoolean(false);
      var bridge = bridge(ctx, grant, actor, current, reject);
      byte[] key = {107};
      actor(actor, () -> {
        bridge.appendOnActorTurn(ctx, frame(3, 1, 111, 1, key, new byte[] {118}));
        // Actor activation is retired or mailbox rejects BEFORE leaving this
        // serialized turn, so a later receipt cannot re-enter a healthy turn.
        if (staleGeneration) current.set(false);
        else reject.set(true);
        return null;
      });
      awaitQuarantine(bridge);
      check(bridge.isQuarantinedForDiagnostics(),
            "stale/rejected receipt leaves quarantine, not a committed read");
      denied(actor, () -> bridge.readOnActorTurn(ctx, key),
          RejectedExecutionException.class, "lost callback refuses reads");
      denied(actor, () -> {
        bridge.appendOnActorTurn(ctx, frame(3, 1, 111, 1, key, new byte[] {118}));
        return null;
      }, RejectedExecutionException.class, "uncertain WAL operation cannot retry");
      check(!bridge.canAcknowledgeGuest(), "lost delivery cannot produce guest ACK");
      bridge.retireFromTrustedRuntime().toCompletableFuture()
          .get(12, TimeUnit.SECONDS);
    } finally {
      Files.deleteIfExists(wal);
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 0) throw new IllegalArgumentException("unexpected arguments");
    orderedPublication();
    lostCallbackFailsClosed(true);
    lostCallbackFailsClosed(false);
    System.out.println("HOST_SHARD_ACTOR_HANDOFF_PASS (host actor-turn harness; NOT Oreslang guest or durable ACK)");
  }
}
