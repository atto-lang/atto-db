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
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.zip.CRC32C;

/**
 * Host-only ODR1 operation-evidence SNAPSHOT for an immutable, verified ODB1
 * WAL under an existing runtime-held ODF1/WAL lifetime lease. This stores
 * neither a client ACK result nor permission to retry or resume writes.
 * The supplied sidecar FD needs independent runtime custody/path confinement.
 * A JVM advisory lock and CRC cannot authenticate hostile file owners.
 */
final class ReadOnlyOperationEvidenceLedger implements AutoCloseable {
  static final int HEADER_LENGTH = 120;
  static final int ENTRY_LENGTH = 44;
  private static final int MAX_RECORDS = ReadOnlyWalReplay.MAX_RECORDS;
  private static final int END_MARKER = 0x31524445; // "EDR1" LE

  enum Reconciliation {
    MATCHING_OPERATION_IN_VERIFIED_WAL,
    CONFLICTING_OPERATION_ID_IN_VERIFIED_WAL,
    NOT_OBSERVED_OUTCOME_UNKNOWN
  }

  private record Event(int sequence, int operationId, int kind, byte[] requestSha256) {
    Event { requestSha256 = requestSha256.clone(); }
    @Override public byte[] requestSha256() { return requestSha256.clone(); }
  }

  private final DbShardLifetimeCustody.Lease custody;
  private final FileChannel sidecar;
  private final FileLock lock;
  private final byte[] frozenSnapshot;
  private final Map<Integer,Event> byOperation;
  private boolean revoked;

  private ReadOnlyOperationEvidenceLedger(DbShardLifetimeCustody.Lease owner,
      FileChannel file, FileLock held, byte[] full, Map<Integer,Event> map) {
    custody = owner;
    sidecar = file;
    lock = held;
    frozenSnapshot = full.clone();
    byOperation = Map.copyOf(map);
  }

  /**
   * The sidecar is empty (new evidence) or EXACTLY the canonical evidence
   * snapshot; a torn file is rejected, never repaired. On success the class
   * takes ownership of the sidecar FD and lock, NOT the WAL custody lease.
   * On failure the caller retains the FD. No guest-facing construction.
   */
  static ReadOnlyOperationEvidenceLedger recordOrInspect(
      DbShardLifetimeCustody.Lease owner, FileChannel preopened) throws IOException {
    Objects.requireNonNull(owner);
    Objects.requireNonNull(preopened);
    if (!preopened.isOpen()) throw new IllegalArgumentException("closed evidence descriptor");
    owner.requireOffActorTurnForEvidence();
    var scan = owner.inspectVerifiedWalOffActorTurn();
    var binding = owner.verifyOffActorTurn();
    byte[] expected = encode(owner.key(), binding, scan);
    final FileLock lock;
    try { lock = preopened.tryLock(); }
    catch (OverlappingFileLockException alreadyOwned) {
      throw new RejectedExecutionException("ODR1 inode already leased",alreadyOwned);
    }
    if (lock == null) throw new RejectedExecutionException("ODR1 exclusive lease unavailable");
    boolean transferred = false;
    try {
      long size = preopened.size();
      if (size == 0) {
        ByteBuffer buffer = ByteBuffer.wrap(expected);
        long offset = 0;
        while (buffer.hasRemaining()) {
          int n = preopened.write(buffer, offset);
          if (n <= 0) throw new IOException("stalled ODR1 evidence write");
          offset += n;
        }
        preopened.force(true);
      } else if (size != expected.length) {
        throw new InvalidEvidence("torn_or_foreign_evidence_file");
      }
      requireSameSnapshot(preopened,expected);
      var after = owner.inspectVerifiedWalOffActorTurn();
      var latest = owner.verifyOffActorTurn();
      if (!Arrays.equals(expected,encode(owner.key(),latest,after)))
        throw new InvalidEvidence("WAL_or_generation_changed_during_evidence_record");
      Map<Integer,Event> map = records(scan);
      var evidence = new ReadOnlyOperationEvidenceLedger(owner,preopened,lock,expected,map);
      transferred = true;
      return evidence;
    } catch (IOException | RuntimeException | Error failure) {
      owner.quarantineFromOperationEvidence();
      throw failure;
    } finally {
      if (!transferred && lock.isValid()) lock.release();
    }
  }

  static final class InvalidEvidence extends IOException {
    private static final long serialVersionUID = 1L;
    private final String reason;
    InvalidEvidence(String reason) { super(reason); this.reason=reason; }
    String reason() { return reason; }
  }

  /** Every lookup requires live runtime authorization and entire source recheck. */
  synchronized Reconciliation reconcile(int operationId, int kind, byte[] key, byte[] value)
      throws IOException {
    if (operationId <= 0 || (kind != 1 && kind != 2) ||
        key == null || key.length < 1 || key.length > 64 ||
        value == null || value.length > 1024 || (kind == 2 && value.length != 0))
      throw new IllegalArgumentException("invalid attempted operation");
    requireCurrent();
    Event observed = byOperation.get(operationId);
    if (observed == null) return Reconciliation.NOT_OBSERVED_OUTCOME_UNKNOWN;
    return observed.kind()==kind && Arrays.equals(observed.requestSha256(),requestHash(kind,key,value))
        ? Reconciliation.MATCHING_OPERATION_IN_VERIFIED_WAL
        : Reconciliation.CONFLICTING_OPERATION_ID_IN_VERIFIED_WAL;
  }

  synchronized int recordedOperations() throws IOException {
    requireCurrent();
    return byOperation.size();
  }
  synchronized boolean canAcknowledgeGuest() { return false; }
  synchronized boolean canResumeWrites() { return false; }
  synchronized boolean isRevoked() { return revoked; }

  private void requireCurrent() throws IOException {
    custody.requireOffActorTurnForEvidence();
    if (revoked || !sidecar.isOpen() || !lock.isValid() || lock.channel()!=sidecar || lock.isShared()) {
      revoked = true;
      throw new SecurityException("expired ODR1 sidecar lease");
    }
    try {
      var observed = custody.inspectVerifiedWalOffActorTurn();
      var binding = custody.verifyOffActorTurn();
      if (!Arrays.equals(frozenSnapshot,encode(custody.key(),binding,observed)))
        throw new InvalidEvidence("operation_evidence_WAL_changed");
      requireSameSnapshot(sidecar,frozenSnapshot);
    } catch (IOException | RuntimeException | Error failure) {
      revoked = true;
      custody.quarantineFromOperationEvidence();
      throw failure;
    }
  }

  private static Map<Integer,Event> records(ReadOnlyWalReplay.Inspection scan) {
    Map<Integer,Event> map = new HashMap<>();
    for (var record:scan.records()) {
      Event e = new Event(record.sequence(),record.operationId(),record.kind(),
          requestHash(record.kind(),record.key(),record.value()));
      if (map.putIfAbsent(record.operationId(),e)!=null)
        throw new IllegalStateException("ODB1 scan admitted duplicate operation ID");
    }
    return map;
  }

  private static byte[] encode(ReadOnlyGenerationFenceLedger.ShardKey shard,
      DbShardLifetimeCustody.Diagnostic fence, ReadOnlyWalReplay.Inspection scan) throws IOException {
    if (scan.records().size()>MAX_RECORDS || scan.bytesChecked()>ReadOnlyWalReplay.MAX_BYTES ||
        scan.bytesChecked()!=fence.walEndOffset() ||
        scan.lastSequence()!=fence.lastSequence() ||
        scan.lastGeneration()!=fence.walGeneration() ||
        !Arrays.equals(scan.snapshotSha256(),fence.walSha256()))
      throw new InvalidEvidence("unmatched_ODF1_WAL_binding");
    var map = records(scan);
    if (map.size()!=scan.records().size())
      throw new InvalidEvidence("duplicate_operation_ID");
    ByteBuffer eventBytes = ByteBuffer.allocate(map.size()*ENTRY_LENGTH)
        .order(ByteOrder.LITTLE_ENDIAN);
    for (var record : scan.records()) {
      Event e = map.get(record.operationId());
      eventBytes.putInt(e.sequence()).putInt(e.operationId()).put((byte)e.kind());
      eventBytes.put(new byte[3]);
      eventBytes.put(e.requestSha256());
    }
    byte[] body = eventBytes.array();
    byte[] result = new byte[HEADER_LENGTH+body.length];
    ByteBuffer header = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
    header.put(new byte[]{'O','D','R','1',1,0,0,0});
    header.putInt(shard.tenant()).putInt(shard.isolate()).putInt(shard.shard());
    header.putInt(fence.generation());
    // Bind every ODF1 shard dimension, plus WAL and operation evidence.
    // These bytes are diagnostic; they do not themselves authenticate custody.
    header.putInt(fence.previousGeneration()).putInt(fence.walGeneration());
    header.putInt(fence.lastSequence()).putInt((int)fence.walEndOffset());
    header.putInt(map.size()).putInt(ENTRY_LENGTH);
    header.put(fence.walSha256());
    header.put(sha256(body));
    CRC32C crc = new CRC32C();
    crc.update(result,0,112);
    header.putInt((int)crc.getValue()).putInt(END_MARKER);
    System.arraycopy(body,0,result,HEADER_LENGTH,body.length);
    return result;
  }

  private static byte[] requestHash(int kind,byte[] key,byte[] value) {
    ByteBuffer b=ByteBuffer.allocate(5+key.length+value.length).order(ByteOrder.LITTLE_ENDIAN);
    b.put((byte)kind).putShort((short)key.length).putShort((short)value.length)
        .put(key).put(value);
    return sha256(b.array());
  }

  private static byte[] sha256(byte[] bytes) {
    try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
    catch (NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
  }

  private static void requireSameSnapshot(FileChannel file,byte[] expected) throws IOException {
    if (file.size()!=expected.length)throw new InvalidEvidence("ODR1_length_mismatch");
    byte[] bytes=new byte[expected.length];
    ByteBuffer into=ByteBuffer.wrap(bytes);
    long offset=0;
    while (into.hasRemaining()) {
      int n=file.read(into,offset);
      if (n<=0)throw new InvalidEvidence("ODR1_short_or_stalled_read");
      offset+=n;
    }
    if (file.size()!=expected.length || !Arrays.equals(bytes,expected))
      throw new InvalidEvidence("ODR1_snapshot_content_mismatch");
  }

  /** Sidecar is independently owned; caller retains underlying WAL/ODF custody. */
  @Override public synchronized void close() throws IOException {
    if (revoked && !sidecar.isOpen())return;
    revoked = true;
    IOException error = null;
    try { if (lock.isValid()) lock.release(); }
    catch (IOException failed) { error=failed; }
    try { sidecar.close(); }
    catch (IOException failed) {
      if (error==null)error=failed; else error.addSuppressed(failed);
    }
    if (error!=null)throw error;
    if (sidecar.isOpen() || lock.isValid())
      throw new IOException("ODR1 lease did not release after close");
  }
}
