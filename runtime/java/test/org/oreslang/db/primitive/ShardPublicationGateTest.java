package org.oreslang.db.primitive;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** Host actor-turn state-machine regression. Not backend/guest or ACK proof. */
public final class ShardPublicationGateTest {
  private static int checks;
  private ShardPublicationGateTest() { }
  private static void check(boolean valid, String message) {
    ++checks;
    if (!valid) throw new AssertionError(message);
  }
  private static byte[] frame(int tenant, int generation, int seq, int op,
                              int kind, byte[] key, byte[] value) {
    ByteBuffer b = ByteBuffer.allocate(30 + key.length + value.length)
        .order(ByteOrder.LITTLE_ENDIAN);
    b.put(new byte[] {79,68,66,49,1,(byte)kind}).putInt(tenant)
        .putInt(generation).putInt(seq).putInt(op)
        .putShort((short)key.length).putShort((short)value.length)
        .put(key).put(value);
    int a=1,c=0;
    for(int i=0;i<b.position();i++) {
      a=(a+Byte.toUnsignedInt(b.get(i)))%65521;
      c=(c+a)%65521;
    }
    b.putShort((short)a).putShort((short)c);
    return b.array();
  }
  private static void rejects(Runnable fn, Class<? extends Throwable> kind, String reason) {
    boolean denied=false;
    try { fn.run(); } catch(Throwable e) { denied=kind.isInstance(e); }
    check(denied,reason);
  }
  private static void run() throws Exception {
    Path wal=Files.createTempFile("ores-shard-gate-",".wal");
    try(FileChannel channel=FileChannel.open(wal,StandardOpenOption.READ,StandardOpenOption.WRITE)) {
      var ctx=WalRuntimeGrant.issueContextFromTrustedRuntime(new WalFilePrimitive.Owner(7,11,5,3));
      var foreign=WalRuntimeGrant.issueContextFromTrustedRuntime(new WalFilePrimitive.Owner(7,11,5,3));
      final boolean[] active={true};
      var grant=WalRuntimeGrant.attachPreopenedFromRuntime(channel,ctx);
      var shard=ShardPublicationGate.fromTrustedRuntime(ctx,grant,c->active[0]);
      byte[] key={107}, original={118}, nextValue={119};
      byte[] one=frame(7,3,1,101,1,key,original);
      byte[] two=frame(7,3,2,102,1,key,nextValue);
      byte[] del=frame(7,3,3,103,2,key,new byte[0]);
      check(!shard.canAcknowledgeGuest(),"no guest ack capability");
      check(shard.readPublishedOnTrustedTurn(ctx,key).isEmpty(),"initially no data");
      rejects(()->shard.readPublishedOnTrustedTurn(foreign,key),SecurityException.class,"context identity not numeric IDs");
      rejects(()->shard.stageAndDispatch(ctx,frame(8,3,1,101,1,key,original)),SecurityException.class,"tenant rejected");
      rejects(()->shard.stageAndDispatch(ctx,frame(7,4,1,101,1,key,original)),SecurityException.class,"stale generation rejected");
      rejects(()->shard.stageAndDispatch(ctx,frame(7,3,2,101,1,key,original)),RejectedExecutionException.class,"sequence gap denied");
      byte[] bad=one.clone();bad[26]^=1;
      rejects(()->shard.stageAndDispatch(ctx,bad),IllegalArgumentException.class,"bad checksum preflight");
      check(shard.snapshotOnTrustedTurn(ctx).sequence()==0,"no state on invalid requests");
      CompletionStage<WalRuntimeGrant.SyncedBoundary> s1=shard.stageAndDispatch(ctx,one);
      Arrays.fill(one,(byte)0);
      check(shard.readPublishedOnTrustedTurn(ctx,key).isEmpty(),"pending not readable");
      rejects(()->shard.stageAndDispatch(ctx,two),RejectedExecutionException.class,"single pending write");
      var snap=shard.snapshotOnTrustedTurn(ctx);
      check(snap.sequence()==0&&snap.liveBytes()==0&&snap.operations()==0,"no early publication");
      var synced=s1.toCompletableFuture().get(10,TimeUnit.SECONDS);
      check(synced.sequence()==1&&synced.operationId()==101,"host sync identity");
      rejects(()->shard.publishOnTrustedTurn(foreign,synced),SecurityException.class,"cannot publish foreign Context");
      check(shard.readPublishedOnTrustedTurn(ctx,key).isEmpty(),"foreign attempt didn't publish");
      var p1=shard.publishOnTrustedTurn(ctx,synced);
      check(p1.operationId()==101&&p1.sequence()==1,"first host-local publication marker");
      var got=shard.readPublishedOnTrustedTurn(ctx,key);
      check(got.isPresent()&&got.get()[0]==118,"first published value");
      got.get()[0]=1;
      check(shard.readPublishedOnTrustedTurn(ctx,key).orElseThrow()[0]==118,"defensive read copy");
      check(shard.snapshotOnTrustedTurn(ctx).walBytes()==32,"exact end offset");
      check(!shard.canAcknowledgeGuest(),"synced host publication still not guest ACK");
      rejects(()->shard.stageAndDispatch(ctx,frame(7,3,2,101,1,key,nextValue)),RejectedExecutionException.class,"duplicate op ID rejected");
      var s2=shard.stageAndDispatch(ctx,two);
      check(shard.readPublishedOnTrustedTurn(ctx,key).orElseThrow()[0]==118,"old value remains while second pending");
      var p2=shard.publishOnTrustedTurn(ctx,s2.toCompletableFuture().get(10,TimeUnit.SECONDS));
      check(p2.sequence()==2,"second host publication marker");
      check(shard.readPublishedOnTrustedTurn(ctx,key).orElseThrow()[0]==119,"overwrite visible only after sync");
      check(shard.snapshotOnTrustedTurn(ctx).liveBytes()==1,"overwrite byte accounting");
      var s3=shard.stageAndDispatch(ctx,del);
      check(shard.readPublishedOnTrustedTurn(ctx,key).isPresent(),"DELETE pending still visible");
      shard.publishOnTrustedTurn(ctx,s3.toCompletableFuture().get(10,TimeUnit.SECONDS));
      check(shard.readPublishedOnTrustedTurn(ctx,key).isEmpty(),"DELETE visible only after sync");
      snap=shard.snapshotOnTrustedTurn(ctx);
      check(snap.sequence()==3&&snap.operations()==3&&snap.keySlots()==1&&snap.liveBytes()==0,"tombstone history retained");
      check(snap.walBytes()==95,"frame stream byte budget");
      byte[] four=frame(7,3,4,104,1,key,original);
      var s4=shard.stageAndDispatch(ctx,four);
      shard.markUnknownOnTrustedTurn(ctx);
      check(s4.toCompletableFuture().get(10,TimeUnit.SECONDS).sequence()==4,"sync may complete after unknown");
      rejects(()->shard.stageAndDispatch(ctx,frame(7,3,4,104,1,key,original)),RejectedExecutionException.class,"unknown prohibits retry");
      rejects(()->shard.readPublishedOnTrustedTurn(ctx,key),RejectedExecutionException.class,"unknown hides read service");
      check(shard.snapshotOnTrustedTurn(ctx).unknown(),"unknown represented");
      rejects(()->shard.publishOnTrustedTurn(ctx,s4.toCompletableFuture().join()),RejectedExecutionException.class,"late receipt cannot publish unknown");
      check(!shard.canAcknowledgeGuest(),"never emit guest ack");
      shard.retireOnTrustedTurn(ctx);
      check(shard.snapshotOnTrustedTurn(ctx).unknown(),"retirement preserves uncertain outcome");
      grant.revokeAndCloseAsync().toCompletableFuture().get(10,TimeUnit.SECONDS);

      var ctx2=WalRuntimeGrant.issueContextFromTrustedRuntime(new WalFilePrimitive.Owner(7,11,5,8));
      var second=ShardPublicationGate.fromTrustedRuntime(ctx2,grant,c->active[0]);
      active[0]=false;
      rejects(()->second.readPublishedOnTrustedTurn(ctx2,key),SecurityException.class,"stale activation denied");
      check(second.isQuarantinedForDiagnostics(),"stale read poisons gate");
    } finally { Files.deleteIfExists(wal); }
  }
  public static void main(String[] argv) throws Exception {
    if(argv.length!=0)throw new IllegalArgumentException("unexpected args");
    run();
    System.out.println("HOST_SHARD_PUBLICATION_GATE_PASS checks="+checks+
        " (local actor-gate harness; NOT guest, runtime-integrated ACK or recovery proof)");
  }
}
