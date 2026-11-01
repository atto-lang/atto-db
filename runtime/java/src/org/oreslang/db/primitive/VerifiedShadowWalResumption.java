package org.oreslang.db.primitive;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;

/**
 * Stage20: validate an append-boundary by writing a disposable SHADOW WAL.
 * Does not modify the source WAL, ODF1 or ODR1; cannot resume a live writer
 * or issue a client ACK. A real native runtime must authorize fresh requests
 * independently: absence from ODR1 is UNKNOWN, never proof of retry safety.
 * The caller MUST supply a trusted, isolated, empty shadow FileChannel.
 */
final class VerifiedShadowWalResumption {
  private VerifiedShadowWalResumption() { }

  record Probe(int generation, int nextSequence, int operationId,
               long sourceBytes, long shadowBytes, byte[] shadowSha256) {
    Probe { shadowSha256 = shadowSha256.clone(); }
    @Override public byte[] shadowSha256() { return shadowSha256.clone(); }
    boolean canResumeWrites() { return false; }
    boolean canAcknowledgeGuest() { return false; }
  }

  /** This is an OFF-ACTOR, test-only fresh-operation fixture, not guest I/O. */
  static Probe stageFreshFixture(DbShardLifetimeCustody.Lease custody,
      ReadOnlyOperationEvidenceLedger evidence, FileChannel shadow,
      int operationId, int kind, byte[] key, byte[] value) throws IOException {
    Objects.requireNonNull(custody);
    Objects.requireNonNull(evidence);
    Objects.requireNonNull(shadow);
    Objects.requireNonNull(key);
    Objects.requireNonNull(value);
    // Freeze the request before any external lock, runtime, or I/O callback
    // can execute. Caller-owned arrays must not change the operation that
    // was admitted and authenticated. This is still HOST-only.
    byte[] stableKey = key.clone();
    byte[] stableValue = value.clone();
    custody.requireOffActorTurnForEvidence();
    if (operationId <= 0 || (kind != 1 && kind != 2) ||
        stableKey.length < 1 || stableKey.length > 64 ||
        stableValue.length > 1024 || (kind == 2 && stableValue.length != 0))
      throw new IllegalArgumentException("invalid shadow fixture");
    if (!shadow.isOpen()) throw new IllegalArgumentException("closed shadow descriptor");
    FileLock shadowLock;
    try { shadowLock = shadow.tryLock(); }
    catch (OverlappingFileLockException overlap) {
      throw new RejectedExecutionException("shadow inode already leased", overlap);
    }
    if (shadowLock == null) throw new RejectedExecutionException("shadow OS lock unavailable");
    try (shadowLock) {
      if (shadow.size() != 0) throw new RejectedExecutionException("shadow must be empty; never repair");
      var diagnostic = custody.verifyOffActorTurn();
      var scan = custody.inspectVerifiedWalOffActorTurn();
      if (evidence.recordedOperations() != scan.records().size())
        throw new SecurityException("ODR1 and WAL record count disagree");
      // Missing operation IDs are UNKNOWN in production. This API is restricted
      // to an explicit synthetic, never-client-acknowledged fixture.
      if (evidence.reconcile(operationId,kind,stableKey,stableValue) !=
          ReadOnlyOperationEvidenceLedger.Reconciliation.NOT_OBSERVED_OUTCOME_UNKNOWN)
        throw new RejectedExecutionException("duplicate/conflicting fixture operation ID");
      // A full history cannot accept another ODB1 record, even in shadow.
      // Refuse before touching the disposable file.
      if (scan.records().size() >= ReadOnlyWalReplay.MAX_RECORDS)
        throw new RejectedExecutionException("shadow WAL record limit exhausted");
      if (scan.lastSequence() == Integer.MAX_VALUE ||
          diagnostic.generation() <= scan.lastGeneration())
        throw new RejectedExecutionException("unsafe next sequence or epoch");
      int nextSequence = scan.lastSequence() + 1;
      byte[] prefix = canonical(scan,custody.key().tenant());
      if (prefix.length != scan.bytesChecked() ||
          !Arrays.equals(sha256(prefix),scan.snapshotSha256()))
        throw new SecurityException("noncanonical WAL prefix cannot be resumed");
      byte[] appended = frame(custody.key().tenant(),diagnostic.generation(),
          nextSequence,operationId,kind,stableKey,stableValue);
      if (appended.length > ReadOnlyWalReplay.MAX_BYTES - prefix.length)
        throw new RejectedExecutionException("shadow WAL byte budget exhausted");
      byte[] whole = new byte[prefix.length + appended.length];
      System.arraycopy(prefix,0,whole,0,prefix.length);
      System.arraycopy(appended,0,whole,prefix.length,appended.length);
      // Positional writes: no use of the mutable FileChannel cursor and no
      // possibility of writing to the runtime-owned SOURCE WAL descriptor.
      ByteBuffer bytes = ByteBuffer.wrap(whole);
      long offset = 0;
      while (bytes.hasRemaining()) {
        int written = shadow.write(bytes,offset);
        if (written <= 0) throw new IOException("stalled shadow append");
        offset += written;
      }
      shadow.force(true);
      var checked = ReadOnlyWalReplay.inspectPreopened(shadow,custody.key().tenant());
      if (checked.bytesChecked() != whole.length || checked.lastSequence() != nextSequence ||
          checked.lastGeneration() != diagnostic.generation() ||
          checked.records().size() != scan.records().size()+1 ||
          !Arrays.equals(checked.snapshotSha256(),sha256(whole)))
        throw new IOException("shadow WAL post-sync verification failed");
      var last = checked.records().get(checked.records().size()-1);
      if (last.operationId() != operationId || last.kind() != kind ||
          !Arrays.equals(last.key(),stableKey) || !Arrays.equals(last.value(),stableValue))
        throw new IOException("wrong shadow operation after fsync");
      // Byte-valid ODB1 is insufficient: 128 lifetime keys and the live
      // value budget also apply. Invalid shadows never yield a Probe.
      // They are disposable and must not be repaired or promoted.
      var index = ReadOnlyShardRecovery.inspectPreopened(
          shadow,custody.key().tenant());
      if (index.recordCount() != checked.records().size() ||
          index.lastSequence() != nextSequence ||
          index.lastGeneration() != diagnostic.generation() ||
          index.inspectedBytes() != whole.length ||
          !Arrays.equals(index.snapshotSha256(),checked.snapshotSha256()))
        throw new IOException("shadow index materialization mismatch");
      // Verify the frozen source STILL matches its ODF1/ODR1 evidence.
      var after = custody.verifyOffActorTurn();
      if (!Arrays.equals(after.walSha256(),diagnostic.walSha256()) ||
          evidence.recordedOperations() != scan.records().size())
        throw new SecurityException("source WAL changed during shadow probe");
      return new Probe(diagnostic.generation(),nextSequence,operationId,
          prefix.length,whole.length,checked.snapshotSha256());
    }
  }

  private static byte[] canonical(ReadOnlyWalReplay.Inspection scan,int tenant) {
    byte[] bytes = new byte[(int)scan.bytesChecked()];
    int at = 0;
    for (var record : scan.records()) {
      byte[] serialized = frame(tenant,record.generation(),record.sequence(),
          record.operationId(),record.kind(),record.key(),record.value());
      if (serialized.length > bytes.length-at)
        throw new SecurityException("WAL canonical frame bounds exceeded");
      System.arraycopy(serialized,0,bytes,at,serialized.length);
      at += serialized.length;
    }
    if (at != bytes.length) throw new SecurityException("WAL frame length mismatch");
    return bytes;
  }

  private static byte[] frame(int tenant,int generation,int sequence,int operation,
                              int kind,byte[] key,byte[] value) {
    var buffer = ByteBuffer.allocate(30+key.length+value.length)
        .order(ByteOrder.LITTLE_ENDIAN);
    buffer.put(new byte[]{79,68,66,49,1,(byte)kind});
    buffer.putInt(tenant).putInt(generation).putInt(sequence).putInt(operation);
    buffer.putShort((short)key.length).putShort((short)value.length).put(key).put(value);
    int a=1,b=0;
    for (int i=0;i<buffer.position();i++) {
      a=(a+Byte.toUnsignedInt(buffer.get(i)))%65521;
      b=(b+a)%65521;
    }
    buffer.putShort((short)a).putShort((short)b);
    return buffer.array();
  }

  private static byte[] sha256(byte[] bytes) {
    try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
    catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
  }
}
