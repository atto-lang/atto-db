package org.oreslang.db.primitive;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Real file + generation fence + exact WAL + independent-JVM ODR1 observations. */
public final class ReadOnlyOperationEvidenceLedgerTest {
  private static int checks;
  private ReadOnlyOperationEvidenceLedgerTest() { }
  private interface Throwing { void run() throws Exception; }
  private static void check(boolean value,String why) {
    checks++;
    if (!value) throw new AssertionError(why);
  }
  private static void reject(Throwing run,Class<? extends Throwable> kind,String why)
      throws Exception {
    boolean refused = false;
    try {run.run();} catch (Throwable failure) {refused = kind.isInstance(failure);}
    check(refused,why);
  }

  private static final ReadOnlyGenerationFenceLedger.ShardKey KEY =
      new ReadOnlyGenerationFenceLedger.ShardKey(7,11,5);

  private static final class Host {
    final AtomicBoolean permits = new AtomicBoolean(true);
    final AtomicBoolean current = new AtomicBoolean(true);
    final AtomicBoolean actorTurn = new AtomicBoolean(false);
    final AtomicReference<DbShardLifetimeCustody.Lease> active = new AtomicReference<>();
    final DbShardLifetimeCustody custody = DbShardLifetimeCustody.fromTrustedRuntime(
        new DbShardLifetimeCustody.TrustedRuntime() {
          @Override public boolean mayFence(ReadOnlyGenerationFenceLedger.ShardKey key,
              int gen,FileChannel wal,FileChannel a,FileChannel b) {
            return permits.get() && KEY.equals(key) && gen > 0;
          }
          @Override public boolean isCurrent(DbShardLifetimeCustody.Lease lease) {
            return current.get() && active.get() == lease;
          }
          @Override public boolean isBlockingForbiddenThread() {return actorTurn.get();}
        });
  }
  private static final class Fileset implements AutoCloseable {
    final Path folder,wal,a,b,odr;
    Fileset() throws IOException {
      folder=Files.createTempDirectory("ores-db-odr1-");
      wal=folder.resolve("WAL");a=folder.resolve("FENCE.A");
      b=folder.resolve("FENCE.B");odr=folder.resolve("ODR1");
      Files.write(wal,log());
      Files.write(a,new byte[0]); Files.write(b,new byte[0]);
      Files.write(odr,new byte[0]);
    }
    @Override public void close() throws IOException {
      Files.deleteIfExists(odr);Files.deleteIfExists(wal);
      Files.deleteIfExists(a);Files.deleteIfExists(b);Files.deleteIfExists(folder);
    }
  }
  private static FileChannel open(Path path) throws IOException {
    return FileChannel.open(path,StandardOpenOption.READ,StandardOpenOption.WRITE);
  }
  private static DbShardLifetimeCustody.Lease hold(Host host,Fileset f,int generation)
      throws Exception {
    FileChannel w=open(f.wal),a=open(f.a),b=open(f.b);
    try {
      var lease=host.custody.fenceAndHold(KEY,generation,w,a,b);
      host.active.set(lease);
      return lease;
    } catch(Exception|Error failure) {
      w.close();a.close();b.close();throw failure;
    }
  }
  private static ReadOnlyOperationEvidenceLedger evidence(
      DbShardLifetimeCustody.Lease lease,Path path) throws Exception {
    FileChannel fd=open(path);
    try {return ReadOnlyOperationEvidenceLedger.recordOrInspect(lease,fd);}
    catch(Exception|Error failure) {fd.close();throw failure;}
  }
  private static byte[] frame(int gen,int seq,int op,int kind,byte[] key,byte[] value) {
    var b=ByteBuffer.allocate(30+key.length+value.length).order(ByteOrder.LITTLE_ENDIAN);
    b.put(new byte[]{79,68,66,49,1,(byte)kind}).putInt(7).putInt(gen)
        .putInt(seq).putInt(op).putShort((short)key.length).putShort((short)value.length)
        .put(key).put(value);
    int aa=1,bb=0;
    for(int i=0;i<b.position();i++) {
      aa=(aa+Byte.toUnsignedInt(b.get(i)))%65521;
      bb=(bb+aa)%65521;
    }
    b.putShort((short)aa).putShort((short)bb);
    return b.array();
  }
  private static byte[] log() {
    var p=frame(3,1,101,1,new byte[]{107},new byte[]{118});
    var d=frame(3,2,102,2,new byte[]{107},new byte[0]);
    var q=frame(3,3,103,1,new byte[]{108},new byte[]{119,120});
    byte[] out=new byte[p.length+d.length+q.length];
    System.arraycopy(p,0,out,0,p.length);
    System.arraycopy(d,0,out,p.length,d.length);
    System.arraycopy(q,0,out,p.length+d.length,q.length);
    return out;
  }
  private static void forkedTrySidecar(Path path) throws Exception {
    try (FileChannel descriptor=open(path)) {
      try (FileLock maybe=descriptor.tryLock()) {
        if (maybe==null) Runtime.getRuntime().halt(7);
      }
    }
  }
  private static void checkExternalSidecarLease(Path path,boolean blocked) throws Exception {
    Process child=new ProcessBuilder(
        Path.of(System.getProperty("java.home"),"bin","java").toString(),
        "-cp",System.getProperty("java.class.path"),
        ReadOnlyOperationEvidenceLedgerTest.class.getName(),"--try-sidecar",
        path.toString()).redirectErrorStream(true).start();
    try {
      check(child.waitFor(8,TimeUnit.SECONDS),"independent ODR1 locker exits");
      check(child.exitValue()==(blocked?7:0),
          blocked?"independent JVM denied ODR1 while activation holds lease":
              "independent JVM permitted ODR1 after sidecar close");
    } finally {
      if (child.isAlive()) child.destroyForcibly().waitFor(5,TimeUnit.SECONDS);
    }
  }

  private static void basic() throws Exception {
    try(Fileset f=new Fileset()) {
      byte[] walBefore=Files.readAllBytes(f.wal);
      Host host=new Host();
      try(var lease=hold(host,f,4)) {
        try(var odr=evidence(lease,f.odr)) {
          check(odr.recordedOperations()==3,"three persisted observed records");
          checkExternalSidecarLease(f.odr,true);
          check(Files.size(f.odr)==ReadOnlyOperationEvidenceLedger.HEADER_LENGTH+
              3L*ReadOnlyOperationEvidenceLedger.ENTRY_LENGTH,"bounded canonical ODR1 size");
          check(odr.reconcile(101,1,new byte[]{107},new byte[]{118})==
              ReadOnlyOperationEvidenceLedger.Reconciliation.MATCHING_OPERATION_IN_VERIFIED_WAL,
              "matching replayed PUT identified by ID and request digest");
          check(odr.reconcile(102,2,new byte[]{107},new byte[0])==
              ReadOnlyOperationEvidenceLedger.Reconciliation.MATCHING_OPERATION_IN_VERIFIED_WAL,
              "matching replayed DELETE identified");
          check(odr.reconcile(103,1,new byte[]{108},new byte[]{119,120})==
              ReadOnlyOperationEvidenceLedger.Reconciliation.MATCHING_OPERATION_IN_VERIFIED_WAL,
              "third operation observed");
          check(odr.reconcile(103,1,new byte[]{108},new byte[]{119,121})==
              ReadOnlyOperationEvidenceLedger.Reconciliation.CONFLICTING_OPERATION_ID_IN_VERIFIED_WAL,
              "same ID with different value is conflict");
          check(odr.reconcile(101,1,new byte[]{108},new byte[]{118})==
              ReadOnlyOperationEvidenceLedger.Reconciliation.CONFLICTING_OPERATION_ID_IN_VERIFIED_WAL,
              "same ID with different key is conflict");
          check(odr.reconcile(102,1,new byte[]{107},new byte[0])==
              ReadOnlyOperationEvidenceLedger.Reconciliation.CONFLICTING_OPERATION_ID_IN_VERIFIED_WAL,
              "same ID with different operation kind is conflict");
          check(odr.reconcile(999,1,new byte[]{107},new byte[]{118})==
              ReadOnlyOperationEvidenceLedger.Reconciliation.NOT_OBSERVED_OUTCOME_UNKNOWN,
              "missing ID is unknown, NOT safe-to-retry");
          check(!odr.canResumeWrites()&&!odr.canAcknowledgeGuest(),
              "observed operation does not issue client ACK or write authority");
          reject(()->odr.reconcile(0,1,new byte[]{107},new byte[]{118}),
              IllegalArgumentException.class,"invalid operation ID refused");
          reject(()->odr.reconcile(101,2,new byte[]{107},new byte[]{118}),
              IllegalArgumentException.class,"DELETE with value refused");
          try(FileChannel alias=open(f.odr)) {
            reject(()->ReadOnlyOperationEvidenceLedger.recordOrInspect(lease,alias),
                RejectedExecutionException.class,"another sidecar reader cannot steal active OS lease");
            reject(alias::tryLock,OverlappingFileLockException.class,
                "sidecar actually holds OS file lock");
          }
          host.actorTurn.set(true);
          reject(()->odr.recordedOperations(),SecurityException.class,
              "blocking reconciliation forbidden on actor carrier");
          host.actorTurn.set(false);
          check(odr.recordedOperations()==3,"off-turn denial did not poison valid index");
        }
        check(Files.size(f.odr)>0,"sidecar retained after close");
        checkExternalSidecarLease(f.odr,false);
        try(var again=evidence(lease,f.odr)) {
          check(again.recordedOperations()==3,"reopen existing persisted evidence under same activation");
          byte[] damaged=Files.readAllBytes(f.odr);
          damaged[ReadOnlyOperationEvidenceLedger.HEADER_LENGTH+9]^=1;
          Files.write(f.odr,damaged);
          reject(()->again.reconcile(101,1,new byte[]{107},new byte[]{118}),
              ReadOnlyOperationEvidenceLedger.InvalidEvidence.class,
              "same-size ODR1 tampering cannot pass readback");
          check(again.isRevoked(),"corrupt sidecar permanently poisons evidence handle");
          Files.write(f.odr,Arrays.copyOf(damaged,damaged.length));
          reject(()->again.recordedOperations(),SecurityException.class,
              "restored sidecar cannot revive poisoned handle");
        }
        reject(()->evidence(lease,f.odr),ReadOnlyOperationEvidenceLedger.InvalidEvidence.class,
            "no silent repair of corrupt sidecar");
      }
      check(Arrays.equals(Files.readAllBytes(f.wal),walBefore),"never modified the WAL");
    }
  }
  private static void tamperedWalAndEmpty() throws Exception {
    try (Fileset f=new Fileset()) {
      Host host=new Host();
      try(var lease=hold(host,f,4);var receipt=evidence(lease,f.odr)) {
        check(receipt.recordedOperations()==3,"pre-WAL-tamper evidence valid");
        try(FileChannel hostile=open(f.wal)) {
          hostile.write(ByteBuffer.wrap(frame(3,1,101,1,new byte[]{107},new byte[]{117})),0);
        }
        reject(()->receipt.reconcile(101,1,new byte[]{107},new byte[]{118}),
            ReadOnlyGenerationFenceLedger.InvalidFence.class,
            "checksum-valid changed WAL invalidates ODF1 and ODR1 reconciliation");
        check(receipt.isRevoked(),"WAL tamper irreversibly poisons ODR1 handle");
      }
    }
    try(Fileset f=new Fileset()) {
      Files.write(f.wal,new byte[0]);
      Host host=new Host();
      try(var lease=hold(host,f,4);var receipt=evidence(lease,f.odr)) {
        check(receipt.recordedOperations()==0,"empty WAL produces empty operation evidence");
        check(Files.size(f.odr)==ReadOnlyOperationEvidenceLedger.HEADER_LENGTH,
              "header-only empty ODR1 evidence deterministic");
        check(receipt.reconcile(101,1,new byte[]{107},new byte[]{118})==
              ReadOnlyOperationEvidenceLedger.Reconciliation.NOT_OBSERVED_OUTCOME_UNKNOWN,
              "empty WAL cannot establish negative commit evidence");
        check(!receipt.canAcknowledgeGuest(),"empty ODR1 never establishes ACK");
      }
    }
  }

  private static void staleAndTorn() throws Exception {
    try(Fileset f=new Fileset()) {
      Host host=new Host();
      var lease=hold(host,f,4);
      try(var receipt=evidence(lease,f.odr)) {
        host.current.set(false);
        reject(()->receipt.recordedOperations(),SecurityException.class,
            "revoked activation invalidates sidecar evidence");
        host.current.set(true);
        reject(()->receipt.recordedOperations(),SecurityException.class,
            "revoked activation cannot be revived");
        check(receipt.isRevoked(),"lease failure irreversibly revokes receipt");
      } finally {lease.close();}
      try(FileChannel alias=open(f.odr);FileLock lock=alias.tryLock()) {
        check(lock.isValid(),"sidecar OS lock released on close");
      }
      var fresh=new Host();
      try(var next=hold(fresh,f,5)) {
        reject(()->evidence(next,f.odr),ReadOnlyOperationEvidenceLedger.InvalidEvidence.class,
            "previous-generation receipt cannot silently migrate to next epoch");
        check(next.verifyOffActorTurn().generation()==5,
            "rejected stale receipt does not poison live ODF1 custody");
      }
    }
    try(Fileset f=new Fileset()) {
      Host host=new Host();
      try(var lease=hold(host,f,4)) {
        Files.write(f.odr,new byte[]{'O','D','R','1',1});
        reject(()->evidence(lease,f.odr),ReadOnlyOperationEvidenceLedger.InvalidEvidence.class,
            "torn receipt sidecar refused; never truncated or repaired");
        check(Files.size(f.odr)==5,"failed sidecar admission preserves evidence bytes");
        try(FileChannel checkfd=open(f.odr);FileLock held=checkfd.tryLock()) {
          check(held.isValid(),"invalid receipt attempt released acquired sidecar lease");
        }
      }
    }
  }
  private static void forkedSidecar(Path w,Path a,Path b,Path odr) throws Exception {
    Host host=new Host();
    try(FileChannel wc=open(w);FileChannel ac=open(a);FileChannel bc=open(b)) {
      var lease=host.custody.fenceAndHold(KEY,4,wc,ac,bc);
      host.active.set(lease);
      FileChannel fd=open(odr);
      ReadOnlyOperationEvidenceLedger.recordOrInspect(lease,fd);
      Runtime.getRuntime().halt(0); // no graceful close after FileChannel.force(true)
    }
  }
  private static void independentProcess() throws Exception {
    try(Fileset f=new Fileset()) {
      Process child=new ProcessBuilder(
          Path.of(System.getProperty("java.home"),"bin","java").toString(),
          "-cp",System.getProperty("java.class.path"),
          ReadOnlyOperationEvidenceLedgerTest.class.getName(),"--write-and-halt",
          f.wal.toString(),f.a.toString(),f.b.toString(),f.odr.toString())
          .redirectErrorStream(true).start();
      try {
        check(child.waitFor(12,TimeUnit.SECONDS),"independent receipt writer exited promptly");
        check(child.exitValue()==0,"forked child performed ODR1 force then halted");
        check(Files.size(f.odr)==ReadOnlyOperationEvidenceLedger.HEADER_LENGTH+3L*44,
              "forked ODR1 evidence file persisted through abrupt JVM termination");
        Host restarted=new Host();
        try(var next=hold(restarted,f,5)) {
          reject(()->evidence(next,f.odr),ReadOnlyOperationEvidenceLedger.InvalidEvidence.class,
              "post-process-restart old-generation ODR1 not a new-generation ACK");
          check(next.verifyOffActorTurn().generation()==5,
              "new generation still valid for later authenticated reconciliation");
        }
      } finally {
        if(child.isAlive())child.destroyForcibly().waitFor(5,TimeUnit.SECONDS);
      }
    }
  }
  public static void main(String[] args) throws Exception {
    if(args.length==5&&"--write-and-halt".equals(args[0])) {
      forkedSidecar(Path.of(args[1]),Path.of(args[2]),Path.of(args[3]),Path.of(args[4]));
      return;
    }
    if(args.length==2 && "--try-sidecar".equals(args[0])) {
      forkedTrySidecar(Path.of(args[1]));
      return;
    }
    if(args.length!=0)throw new IllegalArgumentException("invalid test arguments");
    basic();tamperedWalAndEmpty();staleAndTorn();independentProcess();
    System.out.println("HOST_DB_ODR1_RECONCILIATION_PASS checks="+checks+
        " (WAL evidence only; NO client ACK/resumed writer)");
  }
}
