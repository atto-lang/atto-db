package org.oreslang.db.primitive;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;

/** Real-file, independent ODB1 vectors; HOST diagnostic, never guest ACK. */
public final class ReadOnlyShardRecoveryTest {
  private static int checks;
  private ReadOnlyShardRecoveryTest() { }
  private static void check(boolean b, String why) {
    checks++;
    if (!b) throw new AssertionError(why);
  }
  private static byte[] frame(int tenant, int generation, int sequence,
                              int operation, int kind, byte[] key, byte[] value) {
    var b = ByteBuffer.allocate(30 + key.length + value.length)
        .order(ByteOrder.LITTLE_ENDIAN);
    b.put(new byte[]{79,68,66,49,1,(byte)kind});
    b.putInt(tenant).putInt(generation).putInt(sequence).putInt(operation);
    b.putShort((short)key.length).putShort((short)value.length);
    b.put(key).put(value);
    int a=1,c=0;
    for(int i=0;i<b.position();i++) {
      a=(a+Byte.toUnsignedInt(b.get(i)))%65521;
      c=(c+a)%65521;
    }
    b.putShort((short)a).putShort((short)c);
    return b.array();
  }
  private static byte[] concat(byte[]... items) {
    int size=0; for(byte[] x:items) size+=x.length;
    byte[] all=new byte[size];
    int at=0; for(byte[] x:items){System.arraycopy(x,0,all,at,x.length);at+=x.length;}
    return all;
  }
  private static ReadOnlyShardRecovery.Candidate scan(Path file,byte[] data)
      throws IOException {
    Files.write(file,data);
    try(var channel=FileChannel.open(file,StandardOpenOption.READ,StandardOpenOption.WRITE);
        FileLock lease=channel.tryLock()) {
      check(lease != null && lease.isValid(),"exclusive read inspection lease acquired");
      channel.position(Math.min(7,data.length));
      var inspected=ReadOnlyShardRecovery.inspectPreopened(channel,7);
      check(channel.position()==Math.min(7,data.length),"cursor unchanged");
      return inspected;
    }
  }
  private static void invalid(Path file,byte[] bytes,Class<? extends Exception> kind,
                              String reason) throws Exception {
    Files.write(file,bytes);
    boolean refused=false;
    try(var channel=FileChannel.open(file,StandardOpenOption.READ,StandardOpenOption.WRITE);
        FileLock lease=channel.tryLock()) {
      check(lease != null && lease.isValid(),"exclusive corrupt inspection lease acquired");
      try { ReadOnlyShardRecovery.inspectPreopened(channel,7); }
      catch(Exception failure) {
        refused=kind.isInstance(failure)&&
          (failure instanceof ReadOnlyShardRecovery.InvalidIndex idx ?
           reason.equals(idx.reason()):
           failure instanceof ReadOnlyWalReplay.InvalidWal wal && reason.equals(wal.reason()));
      }
    }
    check(refused,"reject exact invalid reason "+reason);
    check(Arrays.equals(bytes,Files.readAllBytes(file)),"rejection never repairs WAL");
  }
  private static void basics(Path file) throws Exception {
    var empty=scan(file,new byte[0]);
    check(empty.lastSequence()==0 && empty.recordCount()==0,"empty index");
    check(empty.inspectedBytes()==0 && empty.lifetimeKeySlots()==0,"empty stats");
    check(!empty.canAcknowledgeGuest()&&!empty.canResumeWrites(),"empty still cannot ACK/write");
    byte[] a={107},b={108};
    byte[] put1=frame(7,3,1,101,1,a,new byte[]{118});
    byte[] put2=frame(7,4,2,102,1,b,new byte[]{119,120});
    byte[] overwrite=frame(7,4,3,103,1,a,new byte[]{122});
    byte[] remove=frame(7,4,4,104,2,b,new byte[0]);
    byte[] log=concat(put1,put2,overwrite,remove);
    var s=scan(file,log);
    check(s.lastSequence()==4 && s.lastGeneration()==4,"monotone generations");
    check(s.recordCount()==4 && s.inspectedBytes()==log.length,"whole WAL");
    check(s.lifetimeKeySlots()==2 && s.retainedValueBytes()==1,"tombstones retain slots");
    check(s.candidateGet(a).orElseThrow()[0]==122,"overwrite candidate");
    check(s.candidateGet(b).isEmpty(),"delete candidate");
    check(s.observedOperationInWal(101)&&s.observedOperationInWal(104),"operation history");
    check(!s.observedOperationInWal(105),"not observed is not a durable outcome");
    byte[] leaked=s.candidateGet(a).orElseThrow();leaked[0]=0;
    check(s.candidateGet(a).orElseThrow()[0]==122,"value defensive copy");
    byte[] digest=s.snapshotSha256();digest[0]^=1;
    check(!Arrays.equals(digest,s.snapshotSha256()),"digest defensive copy");
    check(Arrays.equals(s.snapshotSha256(),MessageDigest.getInstance("SHA-256").digest(log)),
          "canonical exact-byte fingerprint");
    check(!s.canAcknowledgeGuest()&&!s.canResumeWrites(),"never a writer/ACK");
    check(Arrays.equals(Files.readAllBytes(file),log),"read-only cannot mutate file");
  }
  private static void rejection(Path file) throws Exception {
    byte[] p=frame(7,3,1,101,1,new byte[]{1},new byte[]{1});
    byte[] second=frame(7,3,2,102,1,new byte[]{2},new byte[]{2});
    byte[] clipped=Arrays.copyOf(concat(p,second),p.length+6);
    invalid(file,clipped,ReadOnlyWalReplay.InvalidWal.class,"unverified_short_tail");
    byte[] wrong=frame(8,3,1,101,1,new byte[]{1},new byte[]{1});
    invalid(file,wrong,ReadOnlyWalReplay.InvalidWal.class,"invalid_identity_or_tenant");
    byte[] duplicate=concat(p,frame(7,3,2,101,1,new byte[]{2},new byte[]{2}));
    invalid(file,duplicate,ReadOnlyWalReplay.InvalidWal.class,"duplicate_operation");
    byte[] corrupt=p.clone();corrupt[26]^=3;
    invalid(file,corrupt,ReadOnlyWalReplay.InvalidWal.class,"checksum_mismatch");
    byte[][] many=new byte[129][];
    for(int i=0;i<many.length;i++)
      many[i]=frame(7,3,i+1,i+1,1,new byte[]{(byte)(i+1)},new byte[]{(byte)i});
    // 1-byte keys cannot represent byte 0 for put? 129 distinct positive values work.
    invalid(file,concat(many),ReadOnlyShardRecovery.InvalidIndex.class,"key_slot_limit");
    byte[] reused=concat(p,frame(7,3,2,102,2,new byte[]{1},new byte[0]),
        frame(7,3,3,103,1,new byte[]{1},new byte[]{9}));
    var reinsert=scan(file,reused);
    check(reinsert.lifetimeKeySlots()==1 && reinsert.retainedValueBytes()==1,
          "tombstoned key can be reinserted without budget increase");
  }
  private static void forkedCrashCandidate(Path file) throws Exception {
    Files.write(file, new byte[0]); // Separate fresh WAL from corrupt-tail fixtures.
    // Child does real FileChannel.force(true), then exits with Runtime.halt
    // rather than a graceful writer shutdown. This is a *process-crash*
    // observation, NOT an OS-power-loss or directory-fsync durability test.
    var child=new ProcessBuilder(
        Path.of(System.getProperty("java.home"),"bin","java").toString(),
        "-cp",System.getProperty("java.class.path"),
        ReadOnlyShardRecoveryTest.class.getName(),"--crash-after-sync",file.toString())
        .redirectErrorStream(true).start();
    try {
      check(child.waitFor(10,java.util.concurrent.TimeUnit.SECONDS),
            "forked WAL writer terminated without graceful close");
      check(child.exitValue()==0,"forked force(true) completed before process termination");
      try(var ch=FileChannel.open(file,StandardOpenOption.READ,StandardOpenOption.WRITE);
          FileLock lease=ch.tryLock()) {
        check(lease!=null && lease.isValid(),"parent obtained WAL recovery lease");
        var candidate=ReadOnlyShardRecovery.inspectPreopened(ch,7);
        check(candidate.recordCount()==2&&candidate.lastSequence()==2,
              "complete two-record WAL replay after independent JVM halt");
        check(candidate.candidateGet(new byte[]{107}).isEmpty(),
              "replayed DELETE after independent process termination");
        check(candidate.observedOperationInWal(601)&&candidate.observedOperationInWal(602),
              "operation IDs observed after independent process termination");
        check(!candidate.canResumeWrites()&&!candidate.canAcknowledgeGuest(),
              "process replay still cannot authorize resume or ACK");
      }
    } finally {
      if(child.isAlive())child.destroyForcibly().waitFor();
    }
  }

  private static void childWriteAndHalt(Path file) throws Exception {
    try(var ch=FileChannel.open(file,StandardOpenOption.READ,StandardOpenOption.WRITE);
        FileLock lease=ch.tryLock()) {
      if(lease==null || ch.size()!=0)throw new IllegalStateException("nonempty/unleased child WAL");
      byte[] wal=concat(
          frame(7,9,1,601,1,new byte[]{107},new byte[]{118}),
          frame(7,9,2,602,2,new byte[]{107},new byte[0]));
      ByteBuffer buffer=ByteBuffer.wrap(wal);
      long offset=0;
      while(buffer.hasRemaining()) {
        int written=ch.write(buffer,offset);
        if(written<=0)throw new IOException("failed positional WAL write");
        offset+=written;
      }
      ch.force(true);
      Runtime.getRuntime().halt(0);
    }
  }

  public static void main(String[] args) throws Exception {
    if(args.length==2 && "--crash-after-sync".equals(args[0])) {
      childWriteAndHalt(Path.of(args[1]));
      return;
    }
    if(args.length!=0)throw new IllegalArgumentException("no args expected");
    Path file=Files.createTempFile("ores-db-stage15-",".wal");
    try {basics(file);rejection(file);forkedCrashCandidate(file);}finally{Files.deleteIfExists(file);}
    System.out.println("HOST_READ_ONLY_SHARD_RECOVERY_PASS checks="+checks+
        " (full WAL candidate only; NOT durable ACK or resumed writer)");
  }
}
