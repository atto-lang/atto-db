package org.oreslang.db.primitive;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.zip.CRC32C;

/**
 * Read-only ODC1 two-slot checkpoint candidate reader.
 * A checksum and a SHA-256 snapshot match detect accidental damage only.
 * This does NOT authenticate a storage owner, lease, durable commit or ACK.
 */
final class ReadOnlyCheckpointManifest {
  static final int LENGTH = 100;
  private ReadOnlyCheckpointManifest() { }

  static final class InvalidCheckpoint extends Exception {
    private static final long serialVersionUID = 1L;
    private final String reason;
    InvalidCheckpoint(String reason) {
      super(reason);
      this.reason = reason;
    }
    String reason() { return reason; }
  }

  record Manifest(int tenant, int shard, int generation, int sequence,
                  int walBytes, int operations, byte[] walSha256,
                  byte[] indexSha256) {
    Manifest {
      walSha256 = walSha256.clone();
      indexSha256 = indexSha256.clone();
    }
    @Override public byte[] walSha256() { return walSha256.clone(); }
    @Override public byte[] indexSha256() { return indexSha256.clone(); }
  }

  record Candidate(Manifest manifest, char slot, int verifiedRecords) { }

  static Candidate inspectPreopened(FileChannel slotA, FileChannel slotB,
                                    FileChannel wal, int trustedTenant,
                                    int trustedShard)
      throws IOException, InvalidCheckpoint, ReadOnlyWalReplay.InvalidWal {
    if (slotA == null || slotB == null || wal == null ||
        slotA == slotB || slotA == wal || slotB == wal ||
        trustedTenant <= 0 || trustedShard <= 0) {
      throw new IllegalArgumentException("distinct preopened channels and trusted IDs required");
    }
    // Independent trusted descriptor ownership must be established by caller.
    var replay = ReadOnlyWalReplay.inspectPreopened(wal, trustedTenant);
    Manifest a = readSlot(slotA);
    Manifest b = readSlot(slotB);
    if (a == null && b == null) throw new InvalidCheckpoint("no_valid_slot");
    boolean matchA = matches(a, replay, trustedTenant, trustedShard);
    boolean matchB = matches(b, replay, trustedTenant, trustedShard);
    if (matchA && matchB &&
        !Arrays.equals(a.indexSha256(), b.indexSha256())) {
      throw new InvalidCheckpoint("conflicting_checkpoint_state");
    }
    if (matchA) return new Candidate(a, 'A', replay.records().size());
    if (matchB) return new Candidate(b, 'B', replay.records().size());
    throw new InvalidCheckpoint("no_full_wal_match");
  }

  private static boolean matches(Manifest m, ReadOnlyWalReplay.Inspection scan,
                                 int tenant, int shard) throws InvalidCheckpoint {
    if (m == null) return false;
    if (m.tenant() != tenant || m.shard() != shard)
      throw new InvalidCheckpoint("foreign_identity");
    if (m.sequence() > scan.lastSequence() ||
        m.generation() > scan.lastGeneration())
      throw new InvalidCheckpoint("checkpoint_ahead_of_wal");
    if (m.sequence() == scan.lastSequence() &&
        m.generation() != scan.lastGeneration())
      throw new InvalidCheckpoint("checkpoint_generation_conflict");
    if (m.sequence() != scan.lastSequence()) return false; // historical slot
    if (m.walBytes() != scan.bytesChecked() ||
        m.operations() != scan.records().size() ||
        !Arrays.equals(m.walSha256(), scan.snapshotSha256())) {
      throw new InvalidCheckpoint("full_wal_fingerprint_mismatch");
    }
    return true;
  }

  // Empty or torn inactive slot is ignorable only as a candidate; no repair.
  // Checksum-corrupt complete slots are similarly never treated as proof.
  private static Manifest readSlot(FileChannel channel) throws IOException {
    long size = channel.size();
    if (size != LENGTH) return null;
    byte[] bytes = new byte[LENGTH];
    ByteBuffer out = ByteBuffer.wrap(bytes);
    long offset = 0;
    while (out.hasRemaining()) {
      int n = channel.read(out, offset);
      if (n <= 0) return null;
      offset += n;
    }
    if (channel.size() != LENGTH) return null;
    if (bytes[0] != 'O' || bytes[1] != 'D' ||
        bytes[2] != 'C' || bytes[3] != '1' || bytes[4] != 1 ||
        bytes[5] != 0 || bytes[6] != 0 || bytes[7] != 0) return null;
    CRC32C crc = new CRC32C();
    crc.update(bytes, 0, 96);
    ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    if ((int)crc.getValue() != b.getInt(96)) return null;
    int tenant = b.getInt(8), shard = b.getInt(12), gen = b.getInt(16);
    int seq = b.getInt(20), walBytes = b.getInt(24), ops = b.getInt(28);
    if (tenant <= 0 || shard <= 0 || gen <= 0 || seq <= 0 ||
        walBytes < 31 || walBytes > 1_048_576 ||
        ops <= 0 || ops > 4_096 || seq < ops) return null;
    byte[] walHash = Arrays.copyOfRange(bytes, 32, 64);
    byte[] indexHash = Arrays.copyOfRange(bytes, 64, 96);
    return new Manifest(tenant, shard, gen, seq, walBytes, ops,
                        walHash, indexHash);
  }
}
