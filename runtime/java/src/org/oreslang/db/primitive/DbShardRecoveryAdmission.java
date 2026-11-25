package org.oreslang.db.primitive;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

/**
 * HOST-ONLY read-only admission of a complete, validated ODB1 WAL candidate.
 * An independently authenticated runtime MUST supply the trusted identity,
 * generation/lease issuance, and descriptor custody. This layer has no guest
 * interop, durable epoch ledger, append resumption, or ACK capability.
 */
final class DbShardRecoveryAdmission {
  record ShardKey(int tenant, int isolate, int shard) {
    ShardKey {
      if (tenant <= 0 || isolate <= 0 || shard <= 0)
        throw new IllegalArgumentException("invalid trusted shard identity");
    }
  }

  /** Not implemented by guest code; no authentication is created by this interface. */
  interface TrustedRuntime {
    boolean mayInspect(ShardKey key, int requestedGeneration, FileChannel descriptor);
    boolean isCurrent(Admission admission);
  }

  enum OperationEvidence {
    OBSERVED_IN_THIS_WAL,
    NOT_OBSERVED_IN_THIS_WAL
  }

  record Diagnostic(int inspectedGeneration, int proposedGeneration, int lastSequence,
                    long walEndOffset, int observedOperations, int lifetimeKeySlots,
                    int retainedValueBytes, byte[] walSha256) {
    Diagnostic { walSha256 = walSha256.clone(); }
    @Override public byte[] walSha256() { return walSha256.clone(); }
  }

  private static final class Slot {
    int highestGeneration;
    Admission active;
    boolean closeFailed;
  }

  /** Opaque runtime-local observation handle. Never send to actor DATA. */
  final class Admission implements AutoCloseable {
    private final ShardKey key;
    private final int generation;
    private final FileChannel descriptor;
    private final FileLock lease;
    private final ReadOnlyShardRecovery.Candidate candidate;
    private volatile boolean revoked;

    private Admission(ShardKey key, int generation, FileChannel channel,
                      FileLock lock, ReadOnlyShardRecovery.Candidate recovered) {
      this.key = key;
      this.generation = generation;
      descriptor = channel;
      lease = lock;
      candidate = recovered;
    }

    ShardKey key() { return key; }
    int proposedGeneration() { return generation; }
    boolean isRevoked() { return revoked; }
    boolean canResumeWrites() { return false; }
    boolean canAcknowledgeGuest() { return false; }

    Diagnostic diagnostics() throws IOException {
      synchronized (mutex) {
        requireCurrentAndUnchanged(this);
        return new Diagnostic(candidate.lastGeneration(), generation,
            candidate.lastSequence(), candidate.inspectedBytes(),
            candidate.recordCount(), candidate.lifetimeKeySlots(),
            candidate.retainedValueBytes(), candidate.snapshotSha256());
      }
    }

    Optional<byte[]> candidateGet(byte[] keyBytes) throws IOException {
      Objects.requireNonNull(keyBytes);
      synchronized (mutex) {
        requireCurrentAndUnchanged(this);
        return candidate.candidateGet(keyBytes);
      }
    }

    OperationEvidence operationEvidence(int operationId) throws IOException {
      if (operationId <= 0) throw new IllegalArgumentException("invalid operation ID");
      synchronized (mutex) {
        requireCurrentAndUnchanged(this);
        return candidate.observedOperationInWal(operationId)
            ? OperationEvidence.OBSERVED_IN_THIS_WAL
            : OperationEvidence.NOT_OBSERVED_IN_THIS_WAL;
      }
    }

    /** Runtime-owned descriptor is closed; failures permanently quarantine its slot. */
    @Override public void close() throws IOException {
      synchronized (mutex) {
        if (revoked && !descriptor.isOpen()) return;
        Slot slot = slots.get(key);
        if (slot == null || slot.active != this)
          throw new SecurityException("foreign recovery admission closure");
        revoked = true; // fence before attempting either release or close
        IOException error = null;
        try { if (lease.isValid()) lease.release(); }
        catch (IOException ex) { error = ex; }
        try { descriptor.close(); }
        catch (IOException ex) {
          if (error != null) error.addSuppressed(ex);
          else error = ex;
        }
        if (error != null || descriptor.isOpen() || lease.isValid()) {
          slot.closeFailed = true;
          if (error != null) throw error;
          throw new IOException("recovery descriptor/lease still live after close");
        }
        slot.active = null;
      }
    }
  }

  private final TrustedRuntime runtime;
  private final Object mutex = new Object();
  private final Map<ShardKey, Slot> slots = new HashMap<>();

  private DbShardRecoveryAdmission(TrustedRuntime adapter) {
    runtime = Objects.requireNonNull(adapter);
  }
  static DbShardRecoveryAdmission fromTrustedRuntime(TrustedRuntime runtime) {
    return new DbShardRecoveryAdmission(runtime);
  }

  /**
   * Ownership of the already-open writable FileChannel transfers on success.
   * The caller retains ownership on failure. The descriptor is NEVER written
   * or truncated by this stage; the exclusive OS lock is advisory.
   */
  Admission admitReadOnly(ShardKey key, int generation, FileChannel preopened)
      throws IOException {
    Objects.requireNonNull(key);
    Objects.requireNonNull(preopened);
    if (generation <= 0 || !preopened.isOpen())
      throw new IllegalArgumentException("invalid candidate generation or descriptor");
    if (!runtime.mayInspect(key, generation, preopened))
      throw new SecurityException("runtime did not authorize recovery inspection");
    synchronized (mutex) {
      Slot slot = slots.computeIfAbsent(key, ignored -> new Slot());
      if (slot.active != null || slot.closeFailed || generation <= slot.highestGeneration)
        throw new RejectedExecutionException("active, quarantined, or stale shard epoch");
      final FileLock lease;
      try { lease = preopened.tryLock(); }
      catch (OverlappingFileLockException alreadyOwned) {
        throw new RejectedExecutionException("WAL inode already leased", alreadyOwned);
      }
      if (lease == null) throw new RejectedExecutionException("WAL exclusive lease unavailable");
      boolean attached = false;
      try {
        var candidate = ReadOnlyShardRecovery.inspectPreopened(preopened, key.tenant());
        if (candidate.lastGeneration() >= generation)
          throw new SecurityException("recovery generation cannot fence recorded WAL");
        if (candidate.lastSequence() == Integer.MAX_VALUE)
          throw new RejectedExecutionException("WAL sequence exhausted");
        Admission admitted = new Admission(key, generation, preopened, lease, candidate);
        slot.active = admitted;
        slot.highestGeneration = generation;
        attached = true;
        return admitted;
      } finally {
        if (!attached && lease.isValid()) lease.release();
      }
    }
  }

  /** A lost lease or changed WAL poisons this admission irreversibly. */
  private void requireCurrentAndUnchanged(Admission admitted) throws IOException {
    Slot slot = slots.get(admitted.key);
    if (admitted.revoked || slot == null || slot.active != admitted || slot.closeFailed ||
        slot.highestGeneration != admitted.generation)
      throw new SecurityException("retired or foreign recovery admission");
    if (!admitted.descriptor.isOpen() || !admitted.lease.isValid()) {
      admitted.revoked = true;
      throw new SecurityException("lost recovery FD custody");
    }
    final boolean current;
    try { current = runtime.isCurrent(admitted); }
    catch (RuntimeException | Error failure) {
      admitted.revoked = true;
      throw failure;
    }
    if (!current) {
      admitted.revoked = true;
      throw new SecurityException("stale runtime recovery activation");
    }
    try {
      var fingerprint = ReadOnlyWalReplay.inspectPreopened(admitted.descriptor,
          admitted.key.tenant());
      if (fingerprint.bytesChecked() != admitted.candidate.inspectedBytes() ||
          !Arrays.equals(fingerprint.snapshotSha256(), admitted.candidate.snapshotSha256())) {
        admitted.revoked = true;
        throw new SecurityException("WAL bytes changed after recovery inspection");
      }
    } catch (IOException failure) {
      admitted.revoked = true;
      throw failure;
    }
  }
}
