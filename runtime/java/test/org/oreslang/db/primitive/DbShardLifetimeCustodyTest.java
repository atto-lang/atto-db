package org.oreslang.db.primitive;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Real host OS locks, persistent ODF1 writes, and independent JVM contention. */
public final class DbShardLifetimeCustodyTest {
  private static final ReadOnlyGenerationFenceLedger.ShardKey KEY =
      new ReadOnlyGenerationFenceLedger.ShardKey(7,9,3);
  private static int assertions;
  private DbShardLifetimeCustodyTest() { }
  private interface Throwing { void run() throws Exception; }
  private static void check(boolean b,String why) {
    assertions++;
    if (!b) throw new AssertionError(why);
  }
  private static void rejects(Throwing call,Class<? extends Throwable> klass,String why)
      throws Exception {
    boolean refused=false;
    try { call.run(); } catch (Throwable actual) { refused=klass.isInstance(actual); }
    check(refused,why);
  }
  private static final class RuntimeHost {
    final AtomicBoolean permit=new AtomicBoolean(true);
    final AtomicBoolean current=new AtomicBoolean(true);
    final AtomicBoolean actorTurn=new AtomicBoolean(false);
    final AtomicReference<DbShardLifetimeCustody.Lease> identity=new AtomicReference<>();
    final DbShardLifetimeCustody registry=DbShardLifetimeCustody.fromTrustedRuntime(
        new DbShardLifetimeCustody.TrustedRuntime() {
          @Override public boolean mayFence(ReadOnlyGenerationFenceLedger.ShardKey key,
              int generation,FileChannel wal,FileChannel a,FileChannel b) {
            return permit.get() && key.equals(KEY) && generation>0;
          }
          @Override public boolean isCurrent(DbShardLifetimeCustody.Lease lease) {
            return current.get() && identity.get()==lease;
          }
          @Override public boolean isBlockingForbiddenThread() { return actorTurn.get(); }
        });
  }
  private static FileChannel open(Path path) throws IOException {
    return FileChannel.open(path,StandardOpenOption.READ,StandardOpenOption.WRITE);
  }
  private static byte[] frame(int sequence,int op,int kind,byte[] value) {
    ByteBuffer bytes=ByteBuffer.allocate(31+value.length).order(ByteOrder.LITTLE_ENDIAN);
    bytes.put(new byte[]{79,68,66,49,1,(byte)kind});
    bytes.putInt(7).putInt(3).putInt(sequence).putInt(op);
    bytes.putShort((short)1).putShort((short)value.length).put((byte)107).put(value);
    int a=1,b=0;
    for(int i=0;i<bytes.position();i++) {
      a=(a+Byte.toUnsignedInt(bytes.get(i)))%65521;
      b=(b+a)%65521;
    }
    bytes.putShort((short)a).putShort((short)b);
    return bytes.array();
  }
  private static byte[] wal() {
    byte[] p=frame(1,101,1,new byte[]{118});
    byte[] d=frame(2,102,2,new byte[0]);
    byte[] b=Arrays.copyOf(p,p.length+d.length);
    System.arraycopy(d,0,b,p.length,d.length);
    return b;
  }
  private static final class Fileset implements AutoCloseable {
    final Path folder;
    final Path w,a,b;
    Fileset() throws IOException {
      folder=Files.createTempDirectory("ores-db-stage18-");
      w=folder.resolve("wal");a=folder.resolve("fence-a");b=folder.resolve("fence-b");
      Files.write(w,wal());Files.write(a,new byte[0]);Files.write(b,new byte[0]);
    }
    @Override public void close() throws IOException {
      Files.deleteIfExists(w);Files.deleteIfExists(a);Files.deleteIfExists(b);
      Files.deleteIfExists(folder);
    }
  }
  private static DbShardLifetimeCustody.Lease admit(RuntimeHost host,Fileset f,int gen)
      throws Exception {
    FileChannel w=open(f.w),a=open(f.a),b=open(f.b);
    try {
      DbShardLifetimeCustody.Lease lease=host.registry.fenceAndHold(KEY,gen,w,a,b);
      host.identity.set(lease);
      return lease;
    } catch (Exception|Error failure) {
      w.close();a.close();b.close();
      throw failure;
    }
  }

  private static void checkOutsideProcess(Fileset f,boolean expectedBlocked) throws Exception {
    Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
        "-cp",System.getProperty("java.class.path"),
        DbShardLifetimeCustodyTest.class.getName(),"--try-lease",f.w.toString(),
        f.a.toString(),f.b.toString()).redirectErrorStream(true).start();
    try {
      boolean exited=child.waitFor(6,TimeUnit.SECONDS);
      check(exited,"independent JVM terminated normally");
      check(child.exitValue()==(expectedBlocked?7:0),
          expectedBlocked?"independent JVM denied while lifetime lease held":
              "independent JVM acquires all three locks after close");
    } finally { if(child.isAlive()) child.destroyForcibly().waitFor(); }
  }
  private static void childTry(Path w,Path a,Path b) throws Exception {
    try(FileChannel wc=open(w);FileChannel ac=open(a);FileChannel bc=open(b)) {
      try(FileLock wl=wc.tryLock()) {
        if(wl==null)Runtime.getRuntime().halt(7);
        try(FileLock al=ac.tryLock()) {
          if(al==null)Runtime.getRuntime().halt(7);
          try(FileLock bl=bc.tryLock()) {
            if(bl==null)Runtime.getRuntime().halt(7);
          }
        }
      }
    }
  }
  private static void basic() throws Exception {
    try(Fileset f=new Fileset()) {
      RuntimeHost host=new RuntimeHost();
      host.permit.set(false);
      rejects(()->admit(host,f,4),SecurityException.class,"denied runtime credential");
      check(Files.size(f.a)==0&&Files.size(f.b)==0,"denied request never writes a fence");
      host.permit.set(true);
      host.actorTurn.set(true);
      rejects(()->admit(host,f,4),SecurityException.class,"actor-carrier blocking refused");
      check(Files.size(f.a)==0,"forbidden actor turn did not write");
      host.actorTurn.set(false);
      DbShardLifetimeCustody.Lease lease=admit(host,f,4);
      check(lease.generation()==4&&!lease.isRevoked(),"new host activation has epoch four");
      check(!lease.canResumeWrites()&&!lease.canAcknowledgeGuest(),"no writer/ACK");
      var d=lease.verifyOffActorTurn();
      check(d.generation()==4&&d.previousGeneration()==0&&d.counter()==1,
          "fenced generation recorded in primary slot");
      check(d.lastSequence()==2&&d.walGeneration()==3&&d.walEndOffset()==wal().length,
          "replay identity and end offset retained");
      byte[] digest=d.walSha256();digest[0]^=1;
      check(!Arrays.equals(digest,d.walSha256()),"diagnostics defensive SHA");
      check(Files.size(f.a)==128&&Files.size(f.b)==0,"ODF1 primary slot persisted");
      checkOutsideProcess(f,true);
      try(FileChannel wc=open(f.w);FileChannel ac=open(f.a);FileChannel bc=open(f.b)) {
        rejects(()->ReadOnlyGenerationFenceLedger.inspect(wc,ac,bc,KEY),
            RejectedExecutionException.class,"standalone reader cannot steal held locks");
        rejects(()->ReadOnlyGenerationFenceLedger.recordCandidate(wc,ac,bc,KEY,5),
            RejectedExecutionException.class,"standalone writer cannot take locks");
      }
      rejects(()->admit(host,f,5),RejectedExecutionException.class,
          "same runtime cannot create competing shard epoch");
      host.actorTurn.set(true);
      rejects(lease::verifyOffActorTurn,SecurityException.class,"actor-turn verification denied");
      rejects(lease::close,SecurityException.class,"actor-turn close denied");
      host.actorTurn.set(false);
      check(lease.verifyOffActorTurn().generation()==4,"denial did not poison healthy lease");
      lease.close();
      check(lease.isRevoked(),"close permanently revokes lease");
      rejects(lease::verifyOffActorTurn,SecurityException.class,"closed lease refused");
      checkOutsideProcess(f,false);
      rejects(()->admit(host,f,4),RejectedExecutionException.class,"same in-process epoch cannot repeat");
      RuntimeHost freshRuntime=new RuntimeHost();
      rejects(()->admit(freshRuntime,f,4),RejectedExecutionException.class,
          "fresh runtime also rejects generation rollback by on-disk ODF1");
      var second=admit(host,f,5);
      check(second.verifyOffActorTurn().previousGeneration()==4,
          "new epoch links durable predecessor without restart reset");
      check(second.verifyOffActorTurn().counter()==2,"second epoch in B slot");
      second.close();
      RuntimeHost nextRuntime=new RuntimeHost();
      var third=admit(nextRuntime,f,6);
      check(third.verifyOffActorTurn().counter()==3,"restarted JVM-style new registry observes next counter");
      third.close();
      check(Arrays.equals(Files.readAllBytes(f.w),wal()),"no stage18 WAL bytes changed");
    }
  }
  private static void staleAndCorrupt() throws Exception {
    try(Fileset f=new Fileset()) {
      RuntimeHost host=new RuntimeHost();
      var lease=admit(host,f,4);
      host.current.set(false);
      rejects(lease::verifyOffActorTurn,SecurityException.class,"runtime invalidation fenced");
      host.current.set(true);
      rejects(lease::verifyOffActorTurn,SecurityException.class,"stale lease never resurrected");
      lease.close();
    }
    try(Fileset f=new Fileset()) {
      RuntimeHost host=new RuntimeHost();
      var lease=admit(host,f,4);
      byte[] replacement=frame(1,101,1,new byte[]{119});
      // Simulate a breach by modifying the live descriptor through a second alias.
      try(FileChannel alias=open(f.w)) {
        alias.write(ByteBuffer.wrap(replacement),0);
      }
      rejects(lease::verifyOffActorTurn,ReadOnlyGenerationFenceLedger.InvalidFence.class,
          "checksum-valid WAL change rejected against fence fingerprint");
      check(lease.isRevoked(),"observed storage corruption poisons lifetime lease");
      Files.write(f.w,wal());
      rejects(lease::verifyOffActorTurn,SecurityException.class,
          "restoring WAL bytes cannot revive poisoned lease");
      lease.close();
    }
    try(Fileset f=new Fileset()) {
      RuntimeHost host=new RuntimeHost();
      Files.write(f.a,new byte[]{'O','D','F','1',1});
      rejects(()->admit(host,f,4),ReadOnlyGenerationFenceLedger.InvalidFence.class,
          "torn metadata rejected before writer promotion");
      Files.write(f.a,new byte[0]);
      rejects(()->admit(host,f,4),RejectedExecutionException.class,
          "uncertain metadata failure poisons in-process registration");
      checkOutsideProcess(f,false);
    }
    try(Fileset f=new Fileset()) {
      RuntimeHost host=new RuntimeHost();
      // Lock WAL in a separately opened descriptor in this JVM.
      try(FileChannel alias=open(f.w);FileLock held=alias.lock()) {
        check(held.isValid(),"competing local writer holds exclusive WAL lease");
        rejects(()->admit(host,f,4),RejectedExecutionException.class,
            "early admission refusal on already-leased WAL inode");
      }
      // Pre-lock rejection does not burn the generation or poison registry.
      var after=admit(host,f,4);
      check(after.verifyOffActorTurn().generation()==4,"pre-lock denial may retry safely");
      after.close();
    }
  }
  public static void main(String[] args) throws Exception {
    if(args.length==4&&"--try-lease".equals(args[0])){
      childTry(Path.of(args[1]),Path.of(args[2]),Path.of(args[3]));
      return;
    }
    if(args.length!=0)throw new IllegalArgumentException("unexpected arguments");
    basic();staleAndCorrupt();
    System.out.println("HOST_DB_LIFETIME_CUSTODY_PASS assertions="+assertions+
        " (real ODF1 WAL locks; NO recovered writer or guest ACK)");
  }
}
