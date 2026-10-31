package org.oreslang.db.primitive;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;

/**
 * HOST ONLY: retain WAL+ODF1 file leases across an activation's lifetime.
 * The runtime, not this class, MUST authenticate tenant/FD provenance and
 * ensure OS path confinement. A JVM file lock is advisory; neither the ODF1
 * candidate nor this lease authorizes resumed WAL writing or guest ACK.
 * All I/O is synchronous and MUST execute off serialized actor turns.
 */
final class DbShardLifetimeCustody {
  interface TrustedRuntime {
    boolean mayFence(ReadOnlyGenerationFenceLedger.ShardKey key, int generation,
                     FileChannel wal, FileChannel a, FileChannel b);
    boolean isCurrent(Lease lease);
    boolean isBlockingForbiddenThread();
  }

  record Diagnostic(int generation, int previousGeneration, long counter,
                    int walGeneration, int lastSequence, long walEndOffset,
                    byte[] walSha256, byte[] operationIdsSha256) {
    Diagnostic {
      walSha256 = walSha256.clone();
      operationIdsSha256 = operationIdsSha256.clone();
    }
    @Override public byte[] walSha256() { return walSha256.clone(); }
    @Override public byte[] operationIdsSha256() { return operationIdsSha256.clone(); }
  }

  private static final class Slot {
    int highestGeneration;
    Lease active;
    boolean quarantined;
  }

  final class Lease implements AutoCloseable {
    private final ReadOnlyGenerationFenceLedger.ShardKey key;
    private final FileChannel wal;
    private final FileChannel a;
    private final FileChannel b;
    private final FileLock walLock;
    private final FileLock aLock;
    private final FileLock bLock;
    private final ReadOnlyGenerationFenceLedger.Candidate initial;
    private volatile boolean revoked;

    private Lease(ReadOnlyGenerationFenceLedger.ShardKey shard,
        FileChannel log, FileChannel slotA, FileChannel slotB,
        FileLock l0, FileLock l1, FileLock l2,
        ReadOnlyGenerationFenceLedger.Candidate candidate) {
      key = shard;
      wal = log;
      a = slotA;
      b = slotB;
      walLock = l0;
      aLock = l1;
      bLock = l2;
      initial = candidate;
    }

    ReadOnlyGenerationFenceLedger.ShardKey key() { return key; }
    int generation() { return initial.generation(); }
    boolean isRevoked() { return revoked; }
    boolean canResumeWrites() { return false; }
    boolean canAcknowledgeGuest() { return false; }

    /** Off-turn, full-WAL reinspection under all THREE lifetime-long locks. */
    Diagnostic verifyOffActorTurn() throws IOException {
      checkOffActorTurn();
      synchronized (mutex) {
        Slot slot = slots.get(key);
        if (slot == null || slot.active != this || slot.quarantined || revoked)
          throw new SecurityException("stale or foreign shard lease");
        if (!locksHeld()) { revoked = true; throw new SecurityException("lost OS lease"); }
        final boolean current;
        try { current = runtime.isCurrent(this); }
        catch (RuntimeException | Error error) { revoked = true; throw error; }
        if (!current) { revoked = true; throw new SecurityException("runtime activation no longer current"); }
        final ReadOnlyGenerationFenceLedger.Candidate observed;
        try {
          observed = ReadOnlyGenerationFenceLedger.inspectWithHeldLeases(
              wal,a,b,key,walLock,aLock,bLock);
        } catch (IOException | RuntimeException | Error error) {
          revoked = true;
          throw error;
        }
        if (!same(initial,observed)) {
          revoked = true;
          throw new SecurityException("WAL or generation fence changed during activation");
        }
        return new Diagnostic(initial.generation(),initial.previousGeneration(),
            initial.counter(),initial.walGeneration(),initial.lastSequence(),
            initial.walBytes(),initial.walSha256(),initial.operationIdsSha256());
      }
    }

    private boolean locksHeld() {
      return wal.isOpen() && a.isOpen() && b.isOpen() &&
          walLock.isValid() && aLock.isValid() && bLock.isValid() &&
          walLock.channel()==wal && aLock.channel()==a && bLock.channel()==b;
    }

    /** Quarantine before releasing locks; all three descriptor lifetimes end. */
    @Override public void close() throws IOException {
      checkOffActorTurn();
      synchronized (mutex) {
        Slot slot = slots.get(key);
        if (slot == null || slot.active != this) {
          if (revoked && !wal.isOpen() && !a.isOpen() && !b.isOpen()) return;
          throw new SecurityException("foreign recovery lease close");
        }
        revoked = true;
        IOException failed = releaseAndClose(walLock,aLock,bLock,wal,a,b);
        if (failed != null || wal.isOpen() || a.isOpen() || b.isOpen() ||
            walLock.isValid() || aLock.isValid() || bLock.isValid()) {
          slot.quarantined = true;
          if (failed != null) throw failed;
          throw new IOException("shard lease or descriptor survives shutdown");
        }
        slot.active = null;
      }
    }
  }

  private static boolean same(ReadOnlyGenerationFenceLedger.Candidate x,
                              ReadOnlyGenerationFenceLedger.Candidate y) {
    return x.key().equals(y.key()) && x.generation()==y.generation() &&
        x.previousGeneration()==y.previousGeneration() && x.counter()==y.counter() &&
        x.walGeneration()==y.walGeneration() && x.lastSequence()==y.lastSequence() &&
        x.walBytes()==y.walBytes() && x.operations()==y.operations() &&
        x.slot()==y.slot() && Arrays.equals(x.walSha256(),y.walSha256()) &&
        Arrays.equals(x.operationIdsSha256(),y.operationIdsSha256());
  }

  private static IOException releaseAndClose(FileLock walLock, FileLock aLock, FileLock bLock,
                                               FileChannel wal, FileChannel a, FileChannel b) {
    IOException failure = null;
    for (FileLock lock : new FileLock[]{bLock,aLock,walLock}) {
      if (lock==null || !lock.isValid()) continue;
      try { lock.release(); }
      catch (IOException err) {
        if (failure==null) failure=err; else failure.addSuppressed(err);
      }
    }
    for (FileChannel fd : new FileChannel[]{b,a,wal}) {
      if (!fd.isOpen()) continue;
      try { fd.close(); }
      catch (IOException err) {
        if (failure==null) failure=err; else failure.addSuppressed(err);
      }
    }
    return failure;
  }

  private final TrustedRuntime runtime;
  private final Object mutex = new Object();
  private final Map<ReadOnlyGenerationFenceLedger.ShardKey,Slot> slots = new HashMap<>();

  private DbShardLifetimeCustody(TrustedRuntime trusted) { runtime = Objects.requireNonNull(trusted); }
  static DbShardLifetimeCustody fromTrustedRuntime(TrustedRuntime trusted) {
    return new DbShardLifetimeCustody(trusted);
  }

  private void checkOffActorTurn() {
    if (runtime.isBlockingForbiddenThread())
      throw new SecurityException("synchronous storage I/O forbidden on serialized actor turn");
  }

  /**
   * Transfer THREE preopened FDs only on success. On failed admission caller
   * retains ownership; OS locks are released. An uncertain fence write poisons
   * this registry's slot even though the caller still owns its descriptors.
   */
  Lease fenceAndHold(ReadOnlyGenerationFenceLedger.ShardKey key, int proposedGeneration,
                     FileChannel wal, FileChannel a, FileChannel b) throws IOException {
    Objects.requireNonNull(key); Objects.requireNonNull(wal);
    Objects.requireNonNull(a); Objects.requireNonNull(b);
    checkOffActorTurn();
    if (proposedGeneration<=0 || wal==a || wal==b || a==b ||
        !wal.isOpen() || !a.isOpen() || !b.isOpen())
      throw new IllegalArgumentException("invalid activation generation or descriptors");
    if (!runtime.mayFence(key,proposedGeneration,wal,a,b))
      throw new SecurityException("runtime denied shard FD authority");
    synchronized (mutex) {
      Slot slot = slots.computeIfAbsent(key,k->new Slot());
      if (slot.quarantined || slot.active!=null || proposedGeneration<=slot.highestGeneration)
        throw new RejectedExecutionException("active/quarantined/stale shard generation");
      FileLock wl=null,al=null,bl=null;
      boolean transferred=false;
      try {
        wl=lock(wal);
        al=lock(a);
        bl=lock(b);
        // The ODF1 primitive MUST NOT reacquire these locks, because that
        // creates a window or fails with OverlappingFileLockException.
        // Fail closed: a failed record may have partially hit storage.
        slot.quarantined = true;
        ReadOnlyGenerationFenceLedger.Candidate candidate =
            ReadOnlyGenerationFenceLedger.recordWithHeldLeases(wal,a,b,key,
                proposedGeneration,wl,al,bl);
        Lease lease = new Lease(key,wal,a,b,wl,al,bl,candidate);
        slot.active = lease;
        slot.highestGeneration = proposedGeneration;
        slot.quarantined = false;
        transferred=true;
        return lease;
      } finally {
        if (!transferred) {
          IOException failure = releaseLocksOnly(wl,al,bl);
          if (failure!=null) {
            slot.quarantined=true;
            throw failure;
          }
        }
      }
    }
  }

  private static FileLock lock(FileChannel descriptor) throws IOException {
    try {
      FileLock held = descriptor.tryLock();
      if (held==null) throw new RejectedExecutionException("OS file lease unavailable");
      return held;
    } catch (OverlappingFileLockException error) {
      throw new RejectedExecutionException("overlapping OS inode lease",error);
    }
  }
  private static IOException releaseLocksOnly(FileLock... locks) {
    IOException failed=null;
    for (int i=locks.length-1;i>=0;i--) {
      FileLock lock=locks[i];
      if (lock==null || !lock.isValid())continue;
      try { lock.release(); }
      catch(IOException error) {
        if (failed==null)failed=error; else failed.addSuppressed(error);
      }
    }
    return failed;
  }
}
