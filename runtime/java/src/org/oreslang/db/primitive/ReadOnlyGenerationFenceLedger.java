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
import java.util.zip.CRC32C;

/**
 * HOST-ONLY, ODF1 experimental persistent generation-fence candidate.
 * Two preopened metadata slots plus a preopened WAL must have independent
 * runtime-verified custody. ODF1 is distinct from checkpoint ODC1.
 * This does NOT grant writer admission, real actor authority or guest ACK.
 */
final class ReadOnlyGenerationFenceLedger {
  static final int LENGTH = 128;
  static final int MAX_WAL_BYTES = 1_048_576;
  private static final int END_MAGIC = 0x31464e45; // little-endian "ENF1"

  record ShardKey(int tenant, int isolate, int shard) {
    ShardKey {
      if (tenant <= 0 || isolate <= 0 || shard <= 0)
        throw new IllegalArgumentException("invalid trusted shard identity");
    }
  }

  static final class InvalidFence extends IOException {
    private static final long serialVersionUID = 1L;
    private final String reason;
    InvalidFence(String reason) { super(reason); this.reason = reason; }
    String reason() { return reason; }
  }

  record Candidate(ShardKey key, int generation, int previousGeneration,
                   long counter, int walGeneration, int lastSequence,
                   int walBytes, int operations, byte[] walSha256,
                   byte[] operationIdsSha256, char slot) {
    Candidate {
      walSha256 = walSha256.clone();
      operationIdsSha256 = operationIdsSha256.clone();
    }
    @Override public byte[] walSha256() { return walSha256.clone(); }
    @Override public byte[] operationIdsSha256() { return operationIdsSha256.clone(); }
    boolean canResumeWrites() { return false; }
    boolean canAcknowledgeGuest() { return false; }
  }

  private ReadOnlyGenerationFenceLedger() { }

  private static final class Leases implements AutoCloseable {
    private FileLock walLock;
    private FileLock aLock;
    private FileLock bLock;
    Leases(FileChannel wal, FileChannel a, FileChannel b) throws IOException {
      try {
        walLock = acquire(wal);
        aLock = acquire(a);
        bLock = acquire(b);
      } catch (IOException | RuntimeException | Error ex) {
        try { close(); }
        catch (IOException failed) { ex.addSuppressed(failed); }
        throw ex;
      }
    }
    private static FileLock acquire(FileChannel channel) throws IOException {
      try {
        FileLock lock = channel.tryLock();
        if (lock == null) throw new RejectedExecutionException("exclusive OS lease unavailable");
        return lock;
      } catch (OverlappingFileLockException ex) {
        throw new RejectedExecutionException("overlapping WAL/metadata inode lease", ex);
      }
    }
    boolean held() {
      return walLock != null && walLock.isValid() &&
          aLock != null && aLock.isValid() && bLock != null && bLock.isValid();
    }
    @Override public void close() throws IOException {
      IOException error = null;
      for (FileLock lock : new FileLock[]{bLock, aLock, walLock}) {
        if (lock == null || !lock.isValid()) continue;
        try { lock.release(); }
        catch (IOException failure) {
          if (error == null) error = failure; else error.addSuppressed(failure);
        }
      }
      if (error != null) throw error;
    }
  }

  private static void checkChannels(FileChannel wal, FileChannel a, FileChannel b) {
    Objects.requireNonNull(wal); Objects.requireNonNull(a); Objects.requireNonNull(b);
    if (wal == a || wal == b || a == b || !wal.isOpen() || !a.isOpen() || !b.isOpen())
      throw new IllegalArgumentException("distinct live preopened descriptors required");
  }

  /** Full file must be empty or precisely one valid ODF1 record. */
  private static Candidate readSlot(FileChannel file, ShardKey trusted, char slot)
      throws IOException {
    long length = file.size();
    if (length == 0) return null;
    if (length != LENGTH) throw new InvalidFence("torn_or_oversized_slot");
    byte[] bytes = new byte[LENGTH];
    ByteBuffer view = ByteBuffer.wrap(bytes);
    long at = 0;
    while (view.hasRemaining()) {
      int n = file.read(view, at);
      if (n <= 0) throw new InvalidFence("short_or_stalled_slot_read");
      at += n;
    }
    if (file.size() != LENGTH) throw new InvalidFence("changed_slot_size");
    for (int i = 116; i < 120; i++) {
      if (bytes[i] != 0) throw new InvalidFence("nonzero_reserved_slot_bytes");
    }
    ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    if (bytes[0] != 'O' || bytes[1] != 'D' || bytes[2] != 'F' || bytes[3] != '1' ||
        bytes[4] != 1 || bytes[5] != 0 || bytes[6] != 0 || bytes[7] != 0 ||
        b.getInt(124) != END_MAGIC)
      throw new InvalidFence("invalid_fence_magic_or_marker");
    CRC32C crc = new CRC32C();
    crc.update(bytes, 0, 120);
    if (b.getInt(120) != (int)crc.getValue())
      throw new InvalidFence("invalid_fence_checksum");
    ShardKey stored;
    try { stored = new ShardKey(b.getInt(8), b.getInt(12), b.getInt(16)); }
    catch (IllegalArgumentException ex) { throw new InvalidFence("invalid_fence_identity"); }
    if (!trusted.equals(stored)) throw new InvalidFence("foreign_fence_identity");
    int generation = b.getInt(20), previous = b.getInt(24);
    int walGeneration = b.getInt(28), seq = b.getInt(32);
    int walBytes = b.getInt(36), operations = b.getInt(40);
    long counter = b.getLong(44);
    if (generation <= 0 || previous < 0 || previous >= generation ||
        walGeneration < 0 || walGeneration >= generation || seq < 0 ||
        walBytes < 0 || walBytes > MAX_WAL_BYTES || operations < 0 ||
        operations > 4096 || seq != operations || counter < 1 ||
        (walBytes == 0 && (walGeneration != 0 || seq != 0 || operations != 0)) ||
        (walBytes != 0 && (walGeneration == 0 || seq == 0)))
      throw new InvalidFence("invalid_fence_fields");
    return new Candidate(stored, generation, previous, counter, walGeneration,
        seq, walBytes, operations, Arrays.copyOfRange(bytes,52,84),
        Arrays.copyOfRange(bytes,84,116), slot);
  }

  private static Candidate latest(Candidate a, Candidate b) throws InvalidFence {
    if (a == null && b == null) return null;
    if (a == null) throw new InvalidFence("missing_primary_slot");
    if (b == null) {
      if (a.counter() != 1 || a.previousGeneration() != 0)
        throw new InvalidFence("missing_generation_history");
      return a;
    }
    Candidate hi = a.counter() > b.counter() ? a : b;
    Candidate lo = hi == a ? b : a;
    if (hi.counter() != lo.counter() + 1 ||
        hi.generation() <= lo.generation() ||
        hi.previousGeneration() != lo.generation())
      throw new InvalidFence("conflicting_fence_history");
    return hi;
  }

  private static byte[] operationDigest(ReadOnlyWalReplay.Inspection wal) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
      for (var rec : wal.records()) {
        b.clear().putInt(rec.sequence()).putInt(rec.operationId());
        digest.update(b.array());
      }
      return digest.digest();
    } catch (NoSuchAlgorithmException error) { throw new AssertionError(error); }
  }

  private static void matchingWal(Candidate candidate, ReadOnlyWalReplay.Inspection scan)
      throws InvalidFence {
    if (candidate == null) return;
    if (candidate.walBytes() != scan.bytesChecked() ||
        candidate.walGeneration() != scan.lastGeneration() ||
        candidate.lastSequence() != scan.lastSequence() ||
        candidate.operations() != scan.records().size() ||
        !Arrays.equals(candidate.walSha256(), scan.snapshotSha256()) ||
        !Arrays.equals(candidate.operationIdsSha256(), operationDigest(scan)))
      throw new InvalidFence("fence_wal_snapshot_mismatch");
  }

  /** Read-only inspection. Does not close or write any supplied descriptor. */
  static Candidate inspect(FileChannel wal, FileChannel slotA, FileChannel slotB,
                           ShardKey trusted) throws IOException {
    Objects.requireNonNull(trusted);
    checkChannels(wal, slotA, slotB);
    try (Leases leases = new Leases(wal, slotA, slotB)) {
      if (!leases.held()) throw new SecurityException("lost metadata/WAL lease");
      var recovery = ReadOnlyShardRecovery.inspectPreopened(wal, trusted.tenant());
      var scan = ReadOnlyWalReplay.inspectPreopened(wal, trusted.tenant());
      if (!Arrays.equals(recovery.snapshotSha256(), scan.snapshotSha256()))
        throw new InvalidFence("wal_changed_during_recovery");
      Candidate a = readSlot(slotA, trusted, 'A');
      Candidate b = readSlot(slotB, trusted, 'B');
      Candidate selected = latest(a,b);
      if (selected == null) throw new InvalidFence("uninitialized_generation_ledger");
      matchingWal(selected,scan);
      return selected;
    }
  }

  /**
   * Stage 17 ONLY: writes a candidate durable fence, NOT a write activation.
   * Preopened descriptors and host ID must be independently authenticated.
   * If any step fails, no automatic retry, repair, truncation, or ACK is safe.
   */
  static Candidate recordCandidate(FileChannel wal, FileChannel slotA,
                                   FileChannel slotB, ShardKey trusted,
                                   int proposedGeneration) throws IOException {
    Objects.requireNonNull(trusted);
    checkChannels(wal, slotA, slotB);
    if (proposedGeneration <= 0)
      throw new IllegalArgumentException("invalid proposed generation");
    try (Leases leases = new Leases(wal,slotA,slotB)) {
      if (!leases.held()) throw new SecurityException("lost metadata/WAL lease");
      var recovery = ReadOnlyShardRecovery.inspectPreopened(wal,trusted.tenant());
      var scan = ReadOnlyWalReplay.inspectPreopened(wal,trusted.tenant());
      if (!Arrays.equals(recovery.snapshotSha256(), scan.snapshotSha256()))
        throw new InvalidFence("wal_changed_during_recovery");
      Candidate a = readSlot(slotA,trusted,'A');
      Candidate b = readSlot(slotB,trusted,'B');
      Candidate previous = latest(a,b);
      matchingWal(previous,scan);
      if (proposedGeneration <= scan.lastGeneration() ||
          (previous != null && proposedGeneration <= previous.generation()))
        throw new RejectedExecutionException("stale proposed generation");
      if (previous != null && previous.counter() == Long.MAX_VALUE)
        throw new RejectedExecutionException("generation ledger counter exhausted");
      if (scan.lastSequence() == Integer.MAX_VALUE)
        throw new RejectedExecutionException("WAL sequence exhausted");
      char nextSlot = previous == null || previous.slot() == 'B' ? 'A' : 'B';
      Candidate next = new Candidate(trusted, proposedGeneration,
          previous == null ? 0 : previous.generation(),
          previous == null ? 1 : previous.counter() + 1,
          scan.lastGeneration(),scan.lastSequence(),(int)scan.bytesChecked(),
          scan.records().size(),scan.snapshotSha256(),operationDigest(scan),nextSlot);
      FileChannel target = nextSlot == 'A' ? slotA : slotB;
      byte[] record = serialize(next);
      ByteBuffer data = ByteBuffer.wrap(record);
      long offset = 0;
      while (data.hasRemaining()) {
        int written = target.write(data,offset);
        if (written <= 0) throw new IOException("stalled_fence_slot_write");
        offset += written;
      }
      // data-only fsync is insufficient for metadata and allocation changes.
      target.force(true);
      Candidate verified = readSlot(target,trusted,nextSlot);
      if (verified == null || !same(next, verified))
        throw new InvalidFence("fence_write_readback_mismatch");
      // Validate WAL again under retained lease; no mutation during publication.
      var after = ReadOnlyWalReplay.inspectPreopened(wal,trusted.tenant());
      matchingWal(verified, after);
      return verified;
    }
  }

  private static boolean same(Candidate x, Candidate y) {
    return x.key().equals(y.key()) && x.generation() == y.generation() &&
        x.previousGeneration() == y.previousGeneration() && x.counter() == y.counter() &&
        x.walGeneration() == y.walGeneration() &&
        x.lastSequence() == y.lastSequence() && x.walBytes() == y.walBytes() &&
        x.operations() == y.operations() &&
        Arrays.equals(x.walSha256(),y.walSha256()) &&
        Arrays.equals(x.operationIdsSha256(),y.operationIdsSha256()) &&
        x.slot() == y.slot();
  }

  private static byte[] serialize(Candidate candidate) {
    byte[] bytes = new byte[LENGTH];
    ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    b.put(new byte[]{'O','D','F','1',1,0,0,0});
    b.putInt(candidate.key().tenant()).putInt(candidate.key().isolate())
        .putInt(candidate.key().shard()).putInt(candidate.generation())
        .putInt(candidate.previousGeneration()).putInt(candidate.walGeneration())
        .putInt(candidate.lastSequence()).putInt(candidate.walBytes())
        .putInt(candidate.operations()).putLong(candidate.counter());
    b.put(candidate.walSha256()).put(candidate.operationIdsSha256());
    // 116..119 reserved zero and included in CRC.
    CRC32C crc = new CRC32C();
    crc.update(bytes,0,120);
    b.putInt(120,(int)crc.getValue());
    b.putInt(124,END_MAGIC);
    return bytes;
  }
}
