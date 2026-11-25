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
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Real-file, forked-process host-only recovery admission contract. */
public final class DbShardRecoveryAdmissionTest {
  private static int checks;
  private DbShardRecoveryAdmissionTest() { }
  private interface Throwing { void run() throws Exception; }
  private static void check(boolean success, String message) {
    checks++;
    if (!success) throw new AssertionError(message);
  }
  private static void rejected(Throwing action, Class<? extends Throwable> kind,
                               String why) throws Exception {
    boolean refused = false;
    try { action.run(); }
    catch (Throwable failure) { refused = kind.isInstance(failure); }
    check(refused, why);
  }

  private static final class Host {
    final AtomicBoolean allow = new AtomicBoolean(true);
    final AtomicBoolean current = new AtomicBoolean(true);
    final AtomicReference<DbShardRecoveryAdmission.Admission> identity =
        new AtomicReference<>();
    final DbShardRecoveryAdmission gate = DbShardRecoveryAdmission.fromTrustedRuntime(
        new DbShardRecoveryAdmission.TrustedRuntime() {
          public boolean mayInspect(DbShardRecoveryAdmission.ShardKey key,
                                    int generation, FileChannel descriptor) {
            return allow.get() && key.tenant() == 7 && key.isolate() == 9 &&
                key.shard() == 3 && descriptor.isOpen();
          }
          public boolean isCurrent(DbShardRecoveryAdmission.Admission admitted) {
            return current.get() && identity.get() == admitted;
          }
        });
  }

  private static byte[] frame(int tenant, int generation, int sequence, int op,
                              int kind, byte[] key, byte[] value) {
    var buffer = ByteBuffer.allocate(30 + key.length + value.length)
        .order(ByteOrder.LITTLE_ENDIAN);
    buffer.put(new byte[]{79,68,66,49,1,(byte)kind});
    buffer.putInt(tenant).putInt(generation).putInt(sequence).putInt(op);
    buffer.putShort((short)key.length).putShort((short)value.length);
    buffer.put(key).put(value);
    int a = 1, c = 0;
    for (int i=0; i<buffer.position(); i++) {
      a=(a+Byte.toUnsignedInt(buffer.get(i)))%65521;
      c=(c+a)%65521;
    }
    buffer.putShort((short)a).putShort((short)c);
    return buffer.array();
  }
  private static byte[] concat(byte[]... parts) {
    int total=0;
    for(byte[] part: parts)total+=part.length;
    byte[] result=new byte[total];
    int at=0;
    for(byte[] part: parts){System.arraycopy(part,0,result,at,part.length);at+=part.length;}
    return result;
  }
  private static final DbShardRecoveryAdmission.ShardKey KEY =
      new DbShardRecoveryAdmission.ShardKey(7,9,3);
  private static byte[] sample() {
    return concat(frame(7,3,1,101,1,new byte[]{107},new byte[]{118}),
                  frame(7,3,2,102,2,new byte[]{107},new byte[0]),
                  frame(7,4,3,103,1,new byte[]{108},new byte[]{119}));
  }
  private static FileChannel open(Path path) throws IOException {
    return FileChannel.open(path,StandardOpenOption.READ,StandardOpenOption.WRITE);
  }

  private static void ordinary(Path wal) throws Exception {
    byte[] original=sample();
    Files.write(wal,original);
    Host host=new Host();
    rejected(()->new DbShardRecoveryAdmission.ShardKey(0,9,3),
        IllegalArgumentException.class,"invalid trusted tenant");
    try(FileChannel channel=open(wal)) {
      host.allow.set(false);
      rejected(()->host.gate.admitReadOnly(KEY,5,channel),
          SecurityException.class,"runtime denied guest-authorized admission");
      host.allow.set(true);
      try(FileLock checkLease=channel.tryLock()) {
        check(checkLease.isValid(),"rejected authorization did not take lease");
      }
      rejected(()->host.gate.admitReadOnly(new DbShardRecoveryAdmission.ShardKey(8,9,3),5,channel),
          SecurityException.class,"foreign tenant cannot borrow runtime authority");
      rejected(()->host.gate.admitReadOnly(KEY,4,channel),
          SecurityException.class,"generation must be higher than WAL recorded generation");
      try(FileLock checkLease=channel.tryLock()) {
        check(checkLease.isValid(),"failed generation check releases lease");
      }
      var admitted=host.gate.admitReadOnly(KEY,5,channel);
      host.identity.set(admitted);
      check(admitted.key().equals(KEY)&&admitted.proposedGeneration()==5,
            "host-issued candidate binds shard and proposed generation");
      check(!admitted.canResumeWrites()&&!admitted.canAcknowledgeGuest(),
            "no writer or guest ACK capability");
      var diag=admitted.diagnostics();
      check(diag.lastSequence()==3&&diag.inspectedGeneration()==4&&
            diag.proposedGeneration()==5,"exact generation/sequence boundary");
      check(diag.observedOperations()==3&&diag.lifetimeKeySlots()==2&&
            diag.retainedValueBytes()==1,"bounded replay evidence");
      check(diag.walEndOffset()==original.length,"verified append offset not activated");
      check(Arrays.equals(diag.walSha256(),MessageDigest.getInstance("SHA-256").digest(original)),
            "exact on-disk fingerprint");
      byte[] leaked=diag.walSha256(); leaked[0]^=1;
      check(!Arrays.equals(leaked,diag.walSha256()),"defensive diagnostic fingerprint");
      check(admitted.candidateGet(new byte[]{107}).isEmpty(),"recovered tombstone");
      check(admitted.candidateGet(new byte[]{108}).orElseThrow()[0]==119,
            "candidate PUT reconstructed");
      byte[] value=admitted.candidateGet(new byte[]{108}).orElseThrow();value[0]=1;
      check(admitted.candidateGet(new byte[]{108}).orElseThrow()[0]==119,
            "read candidates cannot mutate stored state");
      check(admitted.operationEvidence(101)==
            DbShardRecoveryAdmission.OperationEvidence.OBSERVED_IN_THIS_WAL,
            "observed operation in this WAL only");
      check(admitted.operationEvidence(999)==
            DbShardRecoveryAdmission.OperationEvidence.NOT_OBSERVED_IN_THIS_WAL,
            "absent WAL ID is not permission to retry");
      rejected(()->admitted.operationEvidence(0),IllegalArgumentException.class,
          "invalid operation ID refused");
      rejected(()->host.gate.admitReadOnly(KEY,6,channel),
          RejectedExecutionException.class,"cannot run two epochs of same shard");
      try(FileChannel alias=open(wal)) {
        Host another=new Host();
        rejected(()->another.gate.admitReadOnly(KEY,6,alias),
            RejectedExecutionException.class,"other registry same inode denied by OS lease");
        rejected(()->alias.tryLock(),OverlappingFileLockException.class,
            "held OS lock is genuine");
      }
      admitted.close();
      check(admitted.isRevoked()&&!channel.isOpen(),"close revokes and closes FD");
      rejected(()->admitted.diagnostics(),SecurityException.class,
          "retired candidate cannot be inspected");
    }
    try(FileChannel next=open(wal)) {
      rejected(()->host.gate.admitReadOnly(KEY,5,next),
          RejectedExecutionException.class,"same generation never reused after close");
      var nextAdmission=host.gate.admitReadOnly(KEY,6,next);
      host.identity.set(nextAdmission);
      check(nextAdmission.diagnostics().proposedGeneration()==6,
          "incremented in-process generation admitted");
      nextAdmission.close();
      check(!next.isOpen(),"new activation also transfers descriptor custody");
    }
    check(Arrays.equals(Files.readAllBytes(wal),original),
          "read-only admissions did not write or repair WAL");
  }

  private static void invalidAndMutated(Path wal) throws Exception {
    Host host=new Host();
    byte[] log=sample();
    byte[] corrupt=log.clone();corrupt[26]^=1;
    Files.write(wal,corrupt);
    try(FileChannel bad=open(wal)) {
      rejected(()->host.gate.admitReadOnly(KEY,7,bad),
          ReadOnlyWalReplay.InvalidWal.class,"corrupt checksum is never admitted");
      try(FileLock test=bad.tryLock()) {
        check(test.isValid(),"corrupt rejection does not strand the WAL lease");
      }
    }
    Files.write(wal,log);
    try(FileChannel valid=open(wal)) {
      var admitted=host.gate.admitReadOnly(KEY,7,valid);
      host.identity.set(admitted);
      host.current.set(false);
      rejected(()->admitted.diagnostics(),SecurityException.class,
          "runtime withdraws recovery authority");
      host.current.set(true);
      rejected(()->admitted.diagnostics(),SecurityException.class,
          "withdrawn authority cannot resurrect");
      check(admitted.isRevoked(),"generation poison is permanent");
      admitted.close();
    }
    Host mutationHost=new Host();
    try(FileChannel channel=open(wal)) {
      var admitted=mutationHost.gate.admitReadOnly(KEY,8,channel);
      mutationHost.identity.set(admitted);
      check(admitted.diagnostics().lastSequence()==3,"pre-mutation WAL valid");
      ByteBuffer byteChange=ByteBuffer.wrap(new byte[]{99});
      channel.write(byteChange,26); // same-size in-place mutation by illicit alias
      rejected(()->admitted.candidateGet(new byte[]{108}),
          ReadOnlyWalReplay.InvalidWal.class,"changed WAL is rejected on reinspection");
      check(admitted.isRevoked(),"WAL mutation permanently poisons handle");
      channel.write(ByteBuffer.wrap(new byte[]{107}),26);
      rejected(()->admitted.diagnostics(),SecurityException.class,
          "restoring WAL bytes cannot resurrect poisoned handle");
      admitted.close();
    }
    check(Arrays.equals(Files.readAllBytes(wal),log),"mutation fixture restored for later checks");
    Host checksumValidMutation=new Host();
    try(FileChannel channel=open(wal)) {
      var admitted=checksumValidMutation.gate.admitReadOnly(KEY,9,channel);
      checksumValidMutation.identity.set(admitted);
      check(admitted.diagnostics().lastSequence()==3,"before valid-byte mutation");
      byte[] replacement=frame(7,4,3,103,1,new byte[]{108},new byte[]{120});
      byte[] original=frame(7,4,3,103,1,new byte[]{108},new byte[]{119});
      long last=log.length-replacement.length;
      channel.write(ByteBuffer.wrap(replacement),last);
      var stillValid=ReadOnlyWalReplay.inspectPreopened(channel,7);
      check(stillValid.lastSequence()==3,"mutated equal-length WAL checksum remains valid");
      rejected(()->admitted.diagnostics(),SecurityException.class,
          "same-size checksum-valid WAL mutation rejected by snapshot SHA");
      check(admitted.isRevoked(),"snapshot fingerprint violation quarantines admission");
      channel.write(ByteBuffer.wrap(original),last);
      rejected(()->admitted.diagnostics(),SecurityException.class,
          "fingerprint-mismatched admission cannot revive after WAL restoration");
      admitted.close();
    }
    check(Arrays.equals(Files.readAllBytes(wal),log),"checksum-valid mutation fixture restored");
  }

  private static void childHold(Path file) throws Exception {
    try(FileChannel fd=open(file);FileLock lease=fd.lock()) {
      if(!lease.isValid())throw new IOException("child WAL lock invalid");
      System.out.println("RECOVERY_LEASE_READY");
      System.out.flush();
      System.in.read();
    }
  }
  private static void processLease(Path wal) throws Exception {
    Files.write(wal,sample());
    Process child=new ProcessBuilder(
        Path.of(System.getProperty("java.home"),"bin","java").toString(),
        "-cp",System.getProperty("java.class.path"),
        DbShardRecoveryAdmissionTest.class.getName(),"--hold-lease",wal.toString())
        .redirectErrorStream(true).start();
    try {
      var first=new java.util.concurrent.FutureTask<String>(() -> {
        try(var reader=new java.io.BufferedReader(
            new java.io.InputStreamReader(child.getInputStream()))) {
          return reader.readLine();
        }
      });
      Thread.ofVirtual().start(first);
      check("RECOVERY_LEASE_READY".equals(first.get(5,TimeUnit.SECONDS)),
            "independent process owns WAL before recovery admission");
      Host blocked=new Host();
      try(FileChannel contender=open(wal)) {
        rejected(()->blocked.gate.admitReadOnly(KEY,5,contender),
            RejectedExecutionException.class,"cross-process lock rejects recovery admission");
      }
      child.getOutputStream().close();
      check(child.waitFor(5,TimeUnit.SECONDS)&&child.exitValue()==0,
            "independent process released its WAL lock");
      try(FileChannel next=open(wal)) {
        var a=blocked.gate.admitReadOnly(KEY,5,next);
        blocked.identity.set(a);
        check(a.diagnostics().lastSequence()==3,
            "failed cross-process admission did not burn generation");
        a.close();
      }
    } finally {
      if(child.isAlive())child.destroyForcibly().waitFor(5,TimeUnit.SECONDS);
    }
  }

  public static void main(String[] args) throws Exception {
    if(args.length==2&&"--hold-lease".equals(args[0])) {
      childHold(Path.of(args[1]));return;
    }
    if(args.length!=0)throw new IllegalArgumentException("no parameters expected");
    Path wal=Files.createTempFile("ores-db-stage16-recovery-",".wal");
    try {ordinary(wal);invalidAndMutated(wal);processLease(wal);}
    finally {Files.deleteIfExists(wal);}
    System.out.println("HOST_SHARD_RECOVERY_ADMISSION_PASS checks="+checks+
        " (read-only runtime-gated candidate; NO writer or guest ACK)");
  }
}
