package org.oreslang.db.primitive;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/** Actual ODB1/ODF1/ODR1 FileChannel test; shadow file only, no client ACK. */
public final class VerifiedShadowWalResumptionIntegrationTest {
  private static int count;
  private VerifiedShadowWalResumptionIntegrationTest() { }
  private static void check(boolean b,String reason) {
    count++; if (!b) throw new AssertionError(reason);
  }
  private static byte[] frame(int seq,int op,int kind,byte[] value) {
    ByteBuffer b=ByteBuffer.allocate(31+value.length).order(ByteOrder.LITTLE_ENDIAN);
    b.put(new byte[]{79,68,66,49,1,(byte)kind}).putInt(7).putInt(3)
        .putInt(seq).putInt(op).putShort((short)1).putShort((short)value.length)
        .put((byte)107).put(value);
    int a=1,c=0;
    for(int i=0;i<b.position();i++) {
      a=(a+Byte.toUnsignedInt(b.get(i)))%65521;
      c=(c+a)%65521;
    }
    b.putShort((short)a).putShort((short)c);
    return b.array();
  }
  private static FileChannel open(Path p) throws Exception {
    return FileChannel.open(p,StandardOpenOption.READ,StandardOpenOption.WRITE);
  }


  @FunctionalInterface private interface CheckedAction {
    void run() throws Exception;
  }

  private static void expectInvalid(CheckedAction test,String message)
      throws Exception {
    boolean refused=false;
    try { test.run(); }
    catch(IllegalArgumentException expected) { refused=true; }
    check(refused,message);
  }

  private static void expectRejected(CheckedAction test,String message)
      throws Exception {
    boolean refused=false;
    try { test.run(); }
    catch(RejectedExecutionException expected) { refused=true; }
    check(refused,message);
  }

  /** Valid ODB1 bytes are insufficient when lifetime key slots are full. */
  private static byte[] keyFrame(int seq,int op,byte[] key) {
    var b=ByteBuffer.allocate(31+key.length).order(ByteOrder.LITTLE_ENDIAN);
    b.put(new byte[]{79,68,66,49,1,1}).putInt(7).putInt(3)
        .putInt(seq).putInt(op).putShort((short)key.length)
        .putShort((short)1).put(key).put((byte)1);
    int a=1,c=0;
    for(int i=0;i<b.position();++i) {
      a=(a+Byte.toUnsignedInt(b.get(i)))%65521;
      c=(c+a)%65521;
    }
    b.putShort((short)a).putShort((short)c);
    return b.array();
  }

  private static void testIndexLimit() throws Exception {
    Path dir=Files.createTempDirectory("ores-stage20-keylimit-");
    Path wal=dir.resolve("wal"),a=dir.resolve("fenceA"),b=dir.resolve("fenceB");
    Path odr=dir.resolve("evidence"),shadow=dir.resolve("shadow");
    try {
      var output=new java.io.ByteArrayOutputStream();
      for(int i=1;i<=128;++i)
        output.write(keyFrame(i,200+i,new byte[]{(byte)i,(byte)(i>>8)}));
      byte[] original=output.toByteArray();
      Files.write(wal,original);
      for(Path p:new Path[]{a,b,odr,shadow}) Files.write(p,new byte[0]);
      var key=new ReadOnlyGenerationFenceLedger.ShardKey(7,11,5);
      AtomicReference<DbShardLifetimeCustody.Lease> current=new AtomicReference<>();
      var gate=DbShardLifetimeCustody.fromTrustedRuntime(
          new DbShardLifetimeCustody.TrustedRuntime() {
            public boolean mayFence(ReadOnlyGenerationFenceLedger.ShardKey k,
                int gen,FileChannel w,FileChannel aa,FileChannel bb) {
              return k.equals(key)&&gen==4;
            }
            public boolean isCurrent(DbShardLifetimeCustody.Lease l) {
              return current.get()==l;
            }
            public boolean isBlockingForbiddenThread() { return false; }
          });
      try(var w=open(wal);var aa=open(a);var bb=open(b)) {
        var lease=gate.fenceAndHold(key,4,w,aa,bb);
        current.set(lease);
        try(lease) {
          try(var odrFd=open(odr);
              var receipt=ReadOnlyOperationEvidenceLedger.recordOrInspect(lease,odrFd);
              var sh=open(shadow)) {
            check(receipt.recordedOperations()==128,
                "source has 128 authenticated candidate records");
            boolean refused=false;
            try {
              VerifiedShadowWalResumption.stageFreshFixture(lease,receipt,sh,
                  900,1,new byte[]{(byte)129,0},new byte[]{2});
            } catch(ReadOnlyShardRecovery.InvalidIndex invalid) {
              refused="key_slot_limit".equals(invalid.reason()) &&
                  invalid.sequence()==129;
            }
            check(refused,"129th key is rejected even with valid WAL checksum");
            check(ReadOnlyWalReplay.inspectPreopened(sh,7).records().size()==129,
                "raw ODB1 alone would have admitted unusable index");
            check(Arrays.equals(Files.readAllBytes(wal),original),
                "failed shadow materialization cannot mutate source WAL");
            check(!lease.canResumeWrites()&&!lease.canAcknowledgeGuest(),
                "no client or live writer authority after index rejection");
          }
        }
      }
    } finally {
      for(Path p:new Path[]{shadow,odr,b,a,wal}) Files.deleteIfExists(p);
      Files.deleteIfExists(dir);
    }
  }

  /** Non-owning view: mutates external caller buffers at FileLock admission. */
  private static final class MutateAtLock extends FileChannel {
    private final FileChannel delegate;
    private final byte[] key, value;
    private boolean mutated;
    MutateAtLock(FileChannel underlying, byte[] keyArg, byte[] valueArg) {
      delegate=underlying; key=keyArg; value=valueArg;
    }
    @Override public java.nio.channels.FileLock tryLock(
        long position,long size,boolean shared) throws java.io.IOException {
      key[0]=(byte)'X';
      value[0]=(byte)'Y';
      mutated=true;
      return delegate.tryLock(position,size,shared);
    }
    @Override public java.nio.channels.FileLock lock(
        long position,long size,boolean shared) throws java.io.IOException {
      return delegate.lock(position,size,shared);
    }
    @Override public int read(ByteBuffer b) throws java.io.IOException {
      return delegate.read(b);
    }
    @Override public long read(ByteBuffer[] b,int offset,int length)
        throws java.io.IOException {return delegate.read(b,offset,length);}
    @Override public int read(ByteBuffer b,long p) throws java.io.IOException {
      return delegate.read(b,p);
    }
    @Override public int write(ByteBuffer b) throws java.io.IOException {
      return delegate.write(b);
    }
    @Override public long write(ByteBuffer[] b,int offset,int length)
        throws java.io.IOException {return delegate.write(b,offset,length);}
    @Override public int write(ByteBuffer b,long p) throws java.io.IOException {
      return delegate.write(b,p);
    }
    @Override public long position() throws java.io.IOException {
      return delegate.position();
    }
    @Override public FileChannel position(long p) throws java.io.IOException {
      delegate.position(p); return this;
    }
    @Override public long size() throws java.io.IOException {
      return delegate.size();
    }
    @Override public FileChannel truncate(long n) throws java.io.IOException {
      delegate.truncate(n); return this;
    }
    @Override public void force(boolean metadata) throws java.io.IOException {
      delegate.force(metadata);
    }
    @Override public long transferTo(long p,long n,
        java.nio.channels.WritableByteChannel target) throws java.io.IOException {
      return delegate.transferTo(p,n,target);
    }
    @Override public long transferFrom(java.nio.channels.ReadableByteChannel source,
        long p,long n) throws java.io.IOException {
      return delegate.transferFrom(source,p,n);
    }
    @Override public java.nio.MappedByteBuffer map(MapMode mode,long p,long n)
        throws java.io.IOException {return delegate.map(mode,p,n);}
    @Override protected void implCloseChannel() { /* non-owning test view */ }
  }

  public static void main(String[] args) throws Exception {
    if(args.length!=0)throw new IllegalArgumentException("no args");
    Path dir=Files.createTempDirectory("ores-stage20-actual-");
    Path wal=dir.resolve("wal"),a=dir.resolve("fenceA"),b=dir.resolve("fenceB");
    Path odr=dir.resolve("evidence"),shadow=dir.resolve("shadow");
    try {
      byte[] first=frame(1,101,1,new byte[]{118});
      byte[] second=frame(2,102,2,new byte[0]);
      byte[] source=new byte[first.length+second.length];
      System.arraycopy(first,0,source,0,first.length);
      System.arraycopy(second,0,source,first.length,second.length);
      Files.write(wal,source);
      for(Path p:new Path[]{a,b,odr,shadow})Files.write(p,new byte[0]);
      var key=new ReadOnlyGenerationFenceLedger.ShardKey(7,11,5);
      AtomicReference<DbShardLifetimeCustody.Lease> current=new AtomicReference<>();
      var gate=DbShardLifetimeCustody.fromTrustedRuntime(
          new DbShardLifetimeCustody.TrustedRuntime() {
            public boolean mayFence(ReadOnlyGenerationFenceLedger.ShardKey k,int gen,
                FileChannel w,FileChannel aa,FileChannel bb) {return k.equals(key)&&gen==4;}
            public boolean isCurrent(DbShardLifetimeCustody.Lease l) {return current.get()==l;}
            public boolean isBlockingForbiddenThread(){return false;}
          });
      try(var w=open(wal);var aa=open(a);var bb=open(b)) {
        var lease=gate.fenceAndHold(key,4,w,aa,bb);
        current.set(lease);
        try(lease) {
          try(var odrFd=open(odr);
              var receipt=ReadOnlyOperationEvidenceLedger.recordOrInspect(lease,odrFd);
              var sh=open(shadow)) {
            var verified=VerifiedShadowWalResumption.stageFreshFixture(lease,receipt,sh,
                103,1,new byte[]{109},new byte[]{122});
            check(verified.generation()==4&&verified.nextSequence()==3,"resume sequence and generation");
            check(verified.sourceBytes()==source.length,"source offset exact");
            check(verified.shadowBytes()==source.length+32,"shadow append size exact");
            check(!verified.canResumeWrites()&&!verified.canAcknowledgeGuest(),"never guest authority");
            var replay=ReadOnlyWalReplay.inspectPreopened(sh,7);
            check(replay.records().size()==3&&replay.lastSequence()==3,"full shadow replay");
            check(replay.records().get(2).operationId()==103,"new fixture ID at end");
            var index=ReadOnlyShardRecovery.inspectPreopened(sh,7);
            check(index.recordCount()==3&&index.lastSequence()==3&&
                !index.canAcknowledgeGuest()&&!index.canResumeWrites(),
                "valid shadow also passes bounded shard materialization");
            check(Arrays.equals(Files.readAllBytes(wal),source),"never modified SOURCE WAL");
            check(Arrays.equals(Arrays.copyOf(Files.readAllBytes(shadow),source.length),source),
                "shadow prefix identical to source");
            check(receipt.recordedOperations()==2,"original ODR1 still verifies");
            check(lease.verifyOffActorTurn().lastSequence()==2,"ODF1 still identifies source");
            boolean refused=false;
            try { VerifiedShadowWalResumption.stageFreshFixture(lease,receipt,sh,
                104,1,new byte[]{109},new byte[]{122}); }
            catch(RejectedExecutionException expected){refused=true;}
            check(refused,"reject second append on nonempty shadow");
            Files.write(shadow,new byte[0]);
            refused=false;
            try { VerifiedShadowWalResumption.stageFreshFixture(lease,receipt,sh,
                101,1,new byte[]{109},new byte[]{122}); }
            catch(RejectedExecutionException expected){refused=true;}
            check(refused,"reject conflicting operation ID from recovered WAL");

            // Validation must reject malformed requests before any physical
            // shadow mutation, and preserve the source WAL and evidence.
            expectInvalid(() -> VerifiedShadowWalResumption.stageFreshFixture(
                lease,receipt,sh,0,1,new byte[]{109},new byte[]{1}),
                "zero operation ID");
            expectInvalid(() -> VerifiedShadowWalResumption.stageFreshFixture(
                lease,receipt,sh,-1,1,new byte[]{109},new byte[]{1}),
                "negative operation ID");
            expectInvalid(() -> VerifiedShadowWalResumption.stageFreshFixture(
                lease,receipt,sh,104,0,new byte[]{109},new byte[]{1}),
                "kind zero rejected");
            expectInvalid(() -> VerifiedShadowWalResumption.stageFreshFixture(
                lease,receipt,sh,104,3,new byte[]{109},new byte[]{1}),
                "unknown operation kind rejected");
            expectInvalid(() -> VerifiedShadowWalResumption.stageFreshFixture(
                lease,receipt,sh,104,1,new byte[0],new byte[]{1}),
                "empty key rejected");
            expectInvalid(() -> VerifiedShadowWalResumption.stageFreshFixture(
                lease,receipt,sh,104,1,new byte[65],new byte[]{1}),
                "65-byte key rejected");
            expectInvalid(() -> VerifiedShadowWalResumption.stageFreshFixture(
                lease,receipt,sh,104,1,new byte[]{109},new byte[1025]),
                "1025-byte value rejected");
            expectInvalid(() -> VerifiedShadowWalResumption.stageFreshFixture(
                lease,receipt,sh,104,2,new byte[]{109},new byte[]{1}),
                "DELETE with nonempty payload rejected");
            check(sh.size()==0,"malformed submissions did not write shadow");
            check(Arrays.equals(Files.readAllBytes(wal),source),
                "malformed submissions left original WAL unchanged");

            // An independently-held OS lock must deny staging without
            // changing the descriptor or consuming an operation ID.
            try(var independentLock=sh.lock()) {
              expectRejected(() -> VerifiedShadowWalResumption.stageFreshFixture(
                  lease,receipt,sh,104,1,new byte[]{109},new byte[]{122}),
                  "overlapping shadow inode lock denied");
              check(independentLock.isValid()&&sh.size()==0,
                  "lock conflict preserves existing holder and empty file");
            }

            // Non-empty garbage is never automatically repaired or truncated.
            Files.write(shadow,new byte[]{1,2,3});
            expectRejected(() -> VerifiedShadowWalResumption.stageFreshFixture(
                lease,receipt,sh,104,1,new byte[]{109},new byte[]{122}),
                "nonempty shadow refused before append");
            check(Arrays.equals(Files.readAllBytes(shadow),new byte[]{1,2,3}),
                "invalid existing shadow bytes preserved untouched");
            Files.write(shadow,new byte[0]);

            // A malicious (or racing) caller mutates its own request arrays
            // when the storage lock is acquired. The admitted request must
            // retain the original bytes, not the later caller mutation.
            byte[] mutableKey=new byte[]{109}, mutableValue=new byte[]{122};
            try(var intercepted=new MutateAtLock(sh,mutableKey,mutableValue)) {
              var probe=VerifiedShadowWalResumption.stageFreshFixture(
                  lease,receipt,intercepted,104,1,mutableKey,mutableValue);
              check(intercepted.mutated&&mutableKey[0]=='X'&&
                  mutableValue[0]=='Y',"lock-time caller mutation executed");
              var frozen=ReadOnlyWalReplay.inspectPreopened(sh,7);
              var frozenLast=frozen.records().get(frozen.records().size()-1);
              check(Arrays.equals(frozenLast.key(),new byte[]{109})&&
                  Arrays.equals(frozenLast.value(),new byte[]{122}),
                  "admitted immutable bytes survive caller mutation");
              check(!probe.canResumeWrites()&&!probe.canAcknowledgeGuest(),
                  "mutation cannot promote shadow into writer or client ACK");
              check(Arrays.equals(Files.readAllBytes(wal),source),
                  "mutation cannot alter original source WAL");
              byte[] digest=probe.shadowSha256();
              check(digest.length==32&&Arrays.equals(digest,frozen.snapshotSha256()),
                  "probe SHA256 equals independent read-only WAL scan");
              digest[0]^=1;
              check(Arrays.equals(probe.shadowSha256(),frozen.snapshotSha256()),
                  "mutating returned hash cannot change probe receipt");
            }

            // DELETE is valid with empty value; it remains a shadow-only
            // materialization, not permission for a production mutation.
            Files.write(shadow,new byte[0]);
            var deleted=VerifiedShadowWalResumption.stageFreshFixture(
                lease,receipt,sh,105,2,new byte[]{109},new byte[0]);
            var deletedScan=ReadOnlyWalReplay.inspectPreopened(sh,7);
            var finalFrame=deletedScan.records().get(deletedScan.records().size()-1);
            check(finalFrame.kind()==2&&finalFrame.operationId()==105&&
                Arrays.equals(finalFrame.key(),new byte[]{109})&&
                finalFrame.value().length==0,"DELETE frame has exact ID, key, and empty value");
            var deletedIndex=ReadOnlyShardRecovery.inspectPreopened(sh,7);
            check(deletedIndex.candidateGet(new byte[]{109}).isEmpty(),
                "DELETE shadow candidate leaves key absent");
            check(deleted.shadowBytes()==sh.size() &&
                !deleted.canResumeWrites()&&!deleted.canAcknowledgeGuest(),
                "DELETE cannot produce a live database write or ACK");
            check(Arrays.equals(Files.readAllBytes(wal),source),
                "DELETE shadow leaves source physical WAL byte-identical");
          }
        }
      }
    } finally {
      for(Path p:new Path[]{shadow,odr,b,a,wal}) Files.deleteIfExists(p);
      Files.deleteIfExists(dir);
    }
    testIndexLimit();
    System.out.println("HOST_SHADOW_WAL_INTEGRATION_PASS checks="+count+
        " (actual WAL/ODF1/ODR1; NO writer promotion or guest ACK)");
  }
}
