package org.oreslang.db.primitive;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * HOST-ONLY diagnostic materialization of an entire verified ODB1 WAL.
 * This is a pure read-only *candidate*, NOT a recovered live shard,
 * storage-authority grant, durability proof, or permission to ACK/retry.
 * The caller MUST independently authenticate the tenant and hold the file
 * under a trusted exclusive lease. No path, truncate, writes or FD close.
 */
final class ReadOnlyShardRecovery {
  static final int MAX_KEY_SLOTS = 128;
  static final int MAX_LIVE_BYTES = 131_072;

  static final class InvalidIndex extends IOException {
    private static final long serialVersionUID = 1L;
    private final String reason;
    private final int sequence;
    InvalidIndex(String why, int sequence) {
      super(why + " at sequence " + sequence);
      reason = why;
      this.sequence = sequence;
    }
    String reason() { return reason; }
    int sequence() { return sequence; }
  }

  private static final class ByteKey {
    private final byte[] bytes;
    ByteKey(byte[] value) { bytes = value.clone(); }
    @Override public int hashCode() { return Arrays.hashCode(bytes); }
    @Override public boolean equals(Object x) {
      return x instanceof ByteKey key && Arrays.equals(bytes, key.bytes);
    }
  }

  /**
   * Candidate observations ONLY. An operation absent from this inspected
   * file may nevertheless have been written to another incarnation, lost
   * before an fsync, or ACKed by a broken runtime. NEVER blindly retry.
   */
  static final class Candidate {
    private final Map<ByteKey, byte[]> live;
    private final Set<Integer> observedOperations;
    private final int lastSequence;
    private final int lastGeneration;
    private final long inspectedBytes;
    private final int liveBytes;
    private final int lifetimeSlots;
    private final byte[] walFingerprint;

    private Candidate(Map<ByteKey, byte[]> values, Set<Integer> operations,
                      ReadOnlyWalReplay.Inspection inspection,
                      int count, int slots) {
      Map<ByteKey, byte[]> safe = new HashMap<>();
      for (var entry : values.entrySet())
        safe.put(new ByteKey(entry.getKey().bytes), entry.getValue().clone());
      live = Map.copyOf(safe);
      observedOperations = Set.copyOf(operations);
      lastSequence = inspection.lastSequence();
      lastGeneration = inspection.lastGeneration();
      inspectedBytes = inspection.bytesChecked();
      liveBytes = count;
      lifetimeSlots = slots;
      walFingerprint = inspection.snapshotSha256();
    }
    Optional<byte[]> candidateGet(byte[] key) {
      Objects.requireNonNull(key);
      if (key.length < 1 || key.length > 64)
        throw new IllegalArgumentException("bad candidate lookup key");
      byte[] value = live.get(new ByteKey(key));
      return value == null ? Optional.empty() : Optional.of(value.clone());
    }
    boolean observedOperationInWal(int operationId) {
      if (operationId <= 0) throw new IllegalArgumentException("bad operation ID");
      return observedOperations.contains(operationId);
    }
    int lastSequence() { return lastSequence; }
    int lastGeneration() { return lastGeneration; }
    long inspectedBytes() { return inspectedBytes; }
    int recordCount() { return observedOperations.size(); }
    int lifetimeKeySlots() { return lifetimeSlots; }
    int retainedValueBytes() { return liveBytes; }
    byte[] snapshotSha256() { return walFingerprint.clone(); }
    /** No guest commit path can be promoted by this diagnostic object. */
    boolean canAcknowledgeGuest() { return false; }
    boolean canResumeWrites() { return false; }
  }

  private ReadOnlyShardRecovery() { }

  /** Only accepts an entire verified WAL; partial logs are NEVER repaired. */
  static Candidate inspectPreopened(FileChannel channel, int trustedTenant)
      throws IOException {
    Objects.requireNonNull(channel);
    var inspected = ReadOnlyWalReplay.inspectPreopened(channel, trustedTenant);
    Map<ByteKey, byte[]> live = new HashMap<>();
    Set<ByteKey> slots = new HashSet<>();
    Set<Integer> operations = new HashSet<>();
    int liveBytes = 0;
    for (var record : inspected.records()) {
      int sequence = record.sequence();
      if (!operations.add(record.operationId()))
        throw new InvalidIndex("duplicate_operation", sequence);
      ByteKey key = new ByteKey(record.key());
      byte[] old = live.get(key);
      int former = old == null ? 0 : old.length;
      if (record.kind() == 1) {
        if (!slots.contains(key) && slots.size() >= MAX_KEY_SLOTS)
          throw new InvalidIndex("key_slot_limit", sequence);
        byte[] value = record.value();
        if (value.length > MAX_LIVE_BYTES - (liveBytes - former))
          throw new InvalidIndex("live_byte_budget", sequence);
        slots.add(key);
        live.put(key, value);
        liveBytes = liveBytes - former + value.length;
      } else if (record.kind() == 2) {
        live.remove(key);
        liveBytes -= former;
      } else {
        throw new InvalidIndex("unknown_operation_kind", sequence);
      }
    }
    // Re-read the same preopened descriptor after materialization to reject
    // an opportunistic same-size edit. Not a substitute for an exclusive lease
    // or protection against ABA/content mutation immediately after return.
    var rechecked = ReadOnlyWalReplay.inspectPreopened(channel, trustedTenant);
    if (!Arrays.equals(inspected.snapshotSha256(), rechecked.snapshotSha256()) ||
        inspected.bytesChecked() != rechecked.bytesChecked())
      throw new InvalidIndex("wal_changed_during_index", inspected.lastSequence());
    return new Candidate(live, operations, inspected, liveBytes, slots.size());
  }
}
