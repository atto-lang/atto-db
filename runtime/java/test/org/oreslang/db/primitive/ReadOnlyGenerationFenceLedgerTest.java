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

/** Host-only, real preopened FileChannel/force(true) and forked JVM fixture. */
public final class ReadOnlyGenerationFenceLedgerTest {
  private static final ReadOnlyGenerationFenceLedger.ShardKey KEY =
      new ReadOnlyGenerationFenceLedger.ShardKey(7, 9, 3);
  private static int checks;
  private ReadOnlyGenerationFenceLedgerTest() { }
  private interface Action { void run() throws Exception; }
  private static void check(boolean value, String why) {
    checks++;
    if (!value) throw new AssertionError(why);
  }
  private static void rejects(Action action, Class<? extends Throwable> kind,
                              String reason) throws Exception {
    boolean refused = false;
    try { action.run(); }
    catch (Throwable failure) {
      refused = kind.isInstance(failure) &&
          (!(failure instanceof ReadOnlyGenerationFenceLedger.InvalidFence fence) ||
           fence.reason().equals(reason));
    }
    check(refused, reason);
  }
  private static FileChannel open(Path path) throws IOException {
    return FileChannel.open(path,StandardOpenOption.READ,StandardOpenOption.WRITE);
  }
  private static byte[] frame(int generation, int sequence, int operation,
                              int kind, byte key, byte[] value) {
    ByteBuffer b = ByteBuffer.allocate(31 + value.length).order(ByteOrder.LITTLE_ENDIAN);
    b.put(new byte[]{79,68,66,49,1,(byte)kind});
    b.putInt(7).putInt(generation).putInt(sequence).putInt(operation);
    b.putShort((short)1).putShort((short)value.length).put(key).put(value);
    int a=1,c=0;
    for (int i=0;i<b.position();i++) {
      a=(a+Byte.toUnsignedInt(b.get(i)))%65521;
      c=(c+a)%65521;
    }
    b.putShort((short)a).putShort((short)c);
    return b.array();
  }
  private static byte[] concat(byte[]... parts) {
    int total=0;for(byte[] part:parts)total+=part.length;
    byte[] result=new byte[total];int at=0;
    for(byte[] part:parts){System.arraycopy(part,0,result,at,part.length);at+=part.length;}
    return result;
  }
  private static byte[] wal() {
    return concat(frame(3,1,101,1,(byte)107,new byte[]{118}),
                  frame(3,2,102,2,(byte)107,new byte[0]));
  }
  private static ReadOnlyGenerationFenceLedger.Candidate inspect(Path w,Path a,Path b)
      throws IOException {
    try(var wal=open(w);var slotA=open(a);var slotB=open(b)) {
      wal.position(3);slotA.position(2);slotB.position(4);
      var found=ReadOnlyGenerationFenceLedger.inspect(wal,slotA,slotB,KEY);
      check(wal.position()==3&&slotA.position()==2&&slotB.position()==4,
          "positional read does not change shared descriptor cursors");
      return found;
    }
  }
  private static ReadOnlyGenerationFenceLedger.Candidate issue(Path w,Path a,Path b,int gen)
      throws IOException {
    try(var wal=open(w);var slotA=open(a);var slotB=open(b)) {
      wal.position(1);slotA.position(2);slotB.position(3);
      var receipt=ReadOnlyGenerationFenceLedger.recordCandidate(wal,slotA,slotB,KEY,gen);
      check(wal.position()==1&&slotA.position()==2&&slotB.position()==3,
          "positional writes do not alter shared descriptor cursors");
      return receipt;
    }
  }
  private static void basic(Path w,Path a,Path b) throws Exception {
    Files.write(w,wal());
    rejects(()->inspect(w,a,b),ReadOnlyGenerationFenceLedger.InvalidFence.class,
        "uninitialized_generation_ledger");
    var first=issue(w,a,b,4);
    check(first.generation()==4&&first.previousGeneration()==0&&first.counter()==1,
          "first ODF1 candidate persisted generation four");
    check(first.slot()=='A'&&first.walGeneration()==3&&first.lastSequence()==2,
          "first fence targets A and retains verified WAL sequence");
    check(first.walBytes()==wal().length&&first.operations()==2,
          "WAL length and observed-operation bound");
    check(Files.size(a)==128&&Files.size(b)==0,"first write only touches primary slot");
    check(!first.canResumeWrites()&&!first.canAcknowledgeGuest(),"no guest ACK or writer");
    byte[] h=first.walSha256(); h[0]^=1;
    check(!Arrays.equals(h,first.walSha256()),"defensive WAL digest");
    byte[] ids=first.operationIdsSha256(); ids[0]^=1;
    check(!Arrays.equals(ids,first.operationIdsSha256()),"defensive ID-ledger digest");
    rejects(()->issue(w,a,b,4),RejectedExecutionException.class,"stale proposed generation");
    var second=issue(w,a,b,5);
    check(second.generation()==5&&second.previousGeneration()==4&&second.counter()==2,
          "second linked generation");
    check(second.slot()=='B'&&Files.size(b)==128,"alternate inactive B slot forced");
    var restored=inspect(w,a,b);
    check(restored.generation()==5&&restored.slot()=='B'&&restored.counter()==2,
          "restart inspector selects latest generation");
    var third=issue(w,a,b,6);
    check(third.slot()=='A'&&third.previousGeneration()==5&&third.counter()==3,
          "third generation safely reuses older A slot");
    check(inspect(w,a,b).generation()==6,"later restart sees newest fenced epoch");
    check(Arrays.equals(Files.readAllBytes(w),wal()),"generation fence never mutates WAL");
  }
  private static void corruption(Path w,Path a,Path b) throws Exception {
    byte[] goodA=Files.readAllBytes(a),goodB=Files.readAllBytes(b);
    Files.write(b,Arrays.copyOf(goodB,7));
    rejects(()->inspect(w,a,b),ReadOnlyGenerationFenceLedger.InvalidFence.class,
        "torn_or_oversized_slot");
    rejects(()->issue(w,a,b,7),ReadOnlyGenerationFenceLedger.InvalidFence.class,
        "torn_or_oversized_slot");
    Files.write(b,goodB);
    byte[] mangled=goodA.clone();mangled[51]^=1;Files.write(a,mangled);
    rejects(()->inspect(w,a,b),ReadOnlyGenerationFenceLedger.InvalidFence.class,
        "invalid_fence_checksum");
    Files.write(a,goodA);
    byte[] noncanonical=goodA.clone();
    noncanonical[117]=1;
    Files.write(a,noncanonical);
    rejects(()->inspect(w,a,b),ReadOnlyGenerationFenceLedger.InvalidFence.class,
        "nonzero_reserved_slot_bytes");
    Files.write(a,goodA);
    byte[] newWal=wal().clone();newWal[26]^=1;Files.write(w,newWal);
    rejects(()->inspect(w,a,b),ReadOnlyWalReplay.InvalidWal.class,"checksum_mismatch");
    Files.write(w,wal());
    byte[] checksumValid=concat(frame(3,1,101,1,(byte)107,new byte[]{119}),
        frame(3,2,102,2,(byte)107,new byte[0]));
    Files.write(w,checksumValid);
    rejects(()->inspect(w,a,b),ReadOnlyGenerationFenceLedger.InvalidFence.class,
        "fence_wal_snapshot_mismatch");
    Files.write(w,wal());
    check(inspect(w,a,b).generation()==6,"restored fixtures are valid for next checks");
    var foreign=new ReadOnlyGenerationFenceLedger.ShardKey(7,10,3);
    try(var fw=open(w);var fa=open(a);var fb=open(b)) {
      rejects(()->ReadOnlyGenerationFenceLedger.inspect(fw,fa,fb,foreign),
          ReadOnlyGenerationFenceLedger.InvalidFence.class,"foreign_fence_identity");
      rejects(()->ReadOnlyGenerationFenceLedger.recordCandidate(fw,fa,fb,foreign,7),
          ReadOnlyGenerationFenceLedger.InvalidFence.class,"foreign_fence_identity");
      check(Arrays.equals(goodA, Files.readAllBytes(a)),"foreign access never clears slot");
    }
    check(Arrays.equals(goodA,Files.readAllBytes(a))&&Arrays.equals(goodB,Files.readAllBytes(b)),
          "all rejected corruption remains non-repairing");
  }
  private static void competingLease(Path w,Path a,Path b) throws Exception {
    try(var wal=open(w);var firstA=open(a);var slotB=open(b);var aliasA=open(a)) {
      try(FileLock held=firstA.lock()) {
        rejects(()->ReadOnlyGenerationFenceLedger.inspect(wal,aliasA,slotB,KEY),
            RejectedExecutionException.class,"overlapping WAL/metadata inode lease");
        check(held.isValid(),"competitor cannot steal original lock");
      }
      try(FileLock free=wal.tryLock()) {
        check(free!=null&&free.isValid(),"failed admission released acquired WAL lock");
      }
      rejects(()->ReadOnlyGenerationFenceLedger.inspect(wal,aliasA,aliasA,KEY),
          IllegalArgumentException.class,"distinct live preopened descriptors required");
    }
  }
  private static void childHold(Path path) throws Exception {
    try(var fd=open(path);var lease=fd.lock()) {
      if (!lease.isValid()) throw new IOException("child lease invalid");
      System.out.println("ODF1_LEASE_READY");
      System.out.flush();
      System.in.read();
    }
  }
  private static void crossProcessLease(Path w,Path a,Path b) throws Exception {
    var process=new ProcessBuilder(
        Path.of(System.getProperty("java.home"),"bin","java").toString(),
        "-cp",System.getProperty("java.class.path"),
        ReadOnlyGenerationFenceLedgerTest.class.getName(),"--hold-lease",b.toString())
        .redirectErrorStream(true).start();
    try {
      var ready=new java.util.concurrent.FutureTask<String>(() -> {
        try(var reader=new java.io.BufferedReader(
            new java.io.InputStreamReader(process.getInputStream()))) {
          return reader.readLine();
        }
      });
      Thread.ofVirtual().start(ready);
      check("ODF1_LEASE_READY".equals(ready.get(5,TimeUnit.SECONDS)),
          "external JVM acquired inactive slot before attempt");
      rejects(()->issue(w,a,b,7),RejectedExecutionException.class,
          "exclusive OS lease unavailable");
      check(inspectAfterReleaseWithoutAction(w,a,b,process),
          "cross-process refusal never consumes generation");
    } finally {
      if(process.isAlive())process.destroyForcibly().waitFor(5,TimeUnit.SECONDS);
    }
  }
  private static boolean inspectAfterReleaseWithoutAction(Path w,Path a,Path b,
                                                            Process process) throws Exception {
    process.getOutputStream().close();
    if (!process.waitFor(5,TimeUnit.SECONDS) || process.exitValue()!=0) return false;
    return inspect(w,a,b).generation()==6;
  }
  private static void childHalt(Path w,Path a,Path b) throws Exception {
    var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
        "-cp",System.getProperty("java.class.path"),
        ReadOnlyGenerationFenceLedgerTest.class.getName(),"--record-and-halt",
        w.toString(),a.toString(),b.toString()).redirectErrorStream(true).start();
    try {
      check(process.waitFor(8,TimeUnit.SECONDS),"external JVM finished candidate fence");
      check(process.exitValue()==0,"external JVM force(true) finished before Runtime.halt");
    } finally {if(process.isAlive())process.destroyForcibly().waitFor(5,TimeUnit.SECONDS);}
    var recovered=inspect(w,a,b);
    check(recovered.generation()==7&&recovered.counter()==4,
          "independent JVM recovered forced next-generation ODF1 metadata");
    rejects(()->issue(w,a,b,7),RejectedExecutionException.class,"stale proposed generation");
    check(!recovered.canResumeWrites()&&!recovered.canAcknowledgeGuest(),
          "process crash verification cannot promote writer or ACK");
  }
  private static void all() throws Exception {
    Path dir=Files.createTempDirectory("ores-db-odf1-");
    Path w=dir.resolve("wal.odb"),a=dir.resolve("fence-a.odf"),b=dir.resolve("fence-b.odf");
    Files.createFile(w);Files.createFile(a);Files.createFile(b);
    try {basic(w,a,b);corruption(w,a,b);competingLease(w,a,b);crossProcessLease(w,a,b);childHalt(w,a,b);}
    finally {Files.deleteIfExists(w);Files.deleteIfExists(a);Files.deleteIfExists(b);Files.deleteIfExists(dir);}
  }
  public static void main(String[] args) throws Exception {
    if(args.length==2&&args[0].equals("--hold-lease")) {
      childHold(Path.of(args[1]));
      return;
    }
    if(args.length==4&&args[0].equals("--record-and-halt")) {
      issue(Path.of(args[1]),Path.of(args[2]),Path.of(args[3]),7);
      Runtime.getRuntime().halt(0);
    }
    if(args.length!=0)throw new IllegalArgumentException("unexpected arguments");
    all();
    System.out.println("HOST_ODF1_GENERATION_FENCE_PASS checks="+checks+
        " (durable-candidate records only; NO writer, actor runtime or guest ACK)");
  }
}
