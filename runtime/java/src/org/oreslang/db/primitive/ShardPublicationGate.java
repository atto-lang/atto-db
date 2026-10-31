package org.oreslang.db.primitive;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;

/**
 * Host-only, single-writer shard PUBLICATION gate. NOT a guest API or DB ACK.
 * All calls except the async disk work MUST run within the real serialized actor
 * turn; the caller must integrate WalActorContinuationGate and runtime scheduler.
 * A numeric owner or copied receipt can never create a WalRuntimeGrant boundary.
 *
 * This stage supports only a fresh empty WAL. Restart/recovered activation,
 * persisted operation-ID ledger, true runtime authentication and guest execution
 * are not implemented. A poisoned/unknown owner must be reconciled, not retried.
 */
final class ShardPublicationGate {
  static final int MAX_KEYS = 128;
  static final int MAX_LIVE_BYTES = 131_072;
  static final int MAX_HISTORY = 4096;
  static final int MAX_WAL_BYTES = 1_048_576;

  interface RuntimeAuthority {
    /** Authenticated activation check supplied by actual runtime, not DATA. */
    boolean isCurrent(WalRuntimeGrant.Context context);
  }

  private enum Phase { OPEN, PENDING, UNKNOWN, CLOSED }

  record Published(int operationId, int sequence, int generation) { }
  record Snapshot(int sequence, int generation, int operations,
                  int liveBytes, int keySlots, int walBytes, boolean unknown) { }

  private static final class ByteKey {
    private final byte[] bytes;
    ByteKey(byte[] data) { bytes = data.clone(); }
    @Override public int hashCode() { return Arrays.hashCode(bytes); }
    @Override public boolean equals(Object other) {
      return other instanceof ByteKey k && Arrays.equals(bytes, k.bytes);
    }
  }
  private record Frame(int tenant, int generation, int sequence,
                       int operationId, int kind, byte[] key, byte[] value) {
    Frame { key = key.clone(); value = value.clone(); }
    @Override public byte[] key() { return key.clone(); }
    @Override public byte[] value() { return value.clone(); }
  }
  private record Pending(Frame frame, byte[] bytes, int expectedEndOffset) {
    Pending { bytes = bytes.clone(); }
    @Override public byte[] bytes() { return bytes.clone(); }
  }

  private final WalRuntimeGrant.Context context;
  private final WalRuntimeGrant writer;
  private final RuntimeAuthority runtime;
  private final Map<ByteKey, byte[]> values = new HashMap<>();
  private final Set<ByteKey> historicalKeys = new HashSet<>();
  private final Set<Integer> operationIds = new HashSet<>();
  private Phase phase = Phase.OPEN;
  private Pending pending;
  private int sequence;
  private int lastGeneration;
  private int walBytes;
  private int liveBytes;

  private ShardPublicationGate(WalRuntimeGrant.Context trusted,
                               WalRuntimeGrant authorizedWriter,
                               RuntimeAuthority runtimeAuthority) {
    context = Objects.requireNonNull(trusted);
    writer = Objects.requireNonNull(authorizedWriter);
    runtime = Objects.requireNonNull(runtimeAuthority);
    lastGeneration = context.owner().generation();
  }

  /** Does not register an actor or authorize the Context as genuine. */
  static ShardPublicationGate fromTrustedRuntime(WalRuntimeGrant.Context context,
                                                  WalRuntimeGrant writer,
                                                  RuntimeAuthority authority) {
    return new ShardPublicationGate(context, writer, authority);
  }

  /**
   * Called from one authenticated actor turn. Returns a host-only async sync
   * result; actual receipt processing MUST be routed through the already
   * required WalActorContinuationGate to another serialized actor turn.
   */
  CompletionStage<WalRuntimeGrant.SyncedBoundary> stageAndDispatch(
      WalRuntimeGrant.Context caller, byte[] input) {
    checkTurn(caller);
    if (phase != Phase.OPEN) throw new RejectedExecutionException("writer not open");
    byte[] frameBytes = Objects.requireNonNull(input).clone();
    Frame frame = parseAndValidate(frameBytes);
    int id = frame.operationId();
    if (frame.tenant() != context.owner().tenant() ||
        frame.generation() != context.owner().generation())
      throw new SecurityException("frame identity not runtime owner");
    if (sequence == Integer.MAX_VALUE || frame.sequence() != sequence + 1)
      throw new RejectedExecutionException("nonconsecutive shard sequence");
    if (operationIds.contains(id))
      throw new RejectedExecutionException("repeated operation ID");
    if (operationIds.size() >= MAX_HISTORY ||
        frameBytes.length > MAX_WAL_BYTES - walBytes)
      throw new RejectedExecutionException("bounded shard history");
    ByteKey key = new ByteKey(frame.key());
    int old = values.containsKey(key) ? values.get(key).length : 0;
    if (frame.kind() == 1) {
      if (!historicalKeys.contains(key) && historicalKeys.size() >= MAX_KEYS)
        throw new RejectedExecutionException("key slot limit");
      if (frame.value().length > MAX_LIVE_BYTES - (liveBytes - old))
        throw new RejectedExecutionException("value byte budget");
    }
    // Stage is pending BEFORE dispatch. The referenced frame is immutable.
    // A synchronous refusal proves no admitted writer dispatch and restores OPEN.
    pending = new Pending(frame, frameBytes, walBytes + frameBytes.length);
    phase = Phase.PENDING;
    try {
      return writer.appendAndSync(context, frameBytes);
    } catch (SecurityException | RejectedExecutionException |
             IllegalArgumentException definitivePreAdmission) {
      // The current WalRuntimeGrant/WalFilePrimitive contract throws these
      // only before admitting a disk task (the queue-rejection path rolls
      // back its admission counters). This is safe to correct/retry.
      pending = null;
      phase = Phase.OPEN;
      throw definitivePreAdmission;
    } catch (RuntimeException | Error possiblyAfterDispatch) {
      // An unexpected failure may have occurred AFTER disk admission.
      // Returning to OPEN would permit a second frame with the same sequence
      // despite an uncertain first fsync. Preserve UNKNOWN instead.
      pending = null;
      phase = Phase.UNKNOWN;
      throw possiblyAfterDispatch;
    }
  }

  /**
   * Called ONLY on a serialized actor turn after the trusted scheduler gate.
   * Sync evidence is checked against the exact staged immutable frame.
   * Result is a HOST-LOCAL publication marker, never a guest DB committed ACK.
   */
  Published publishOnTrustedTurn(WalRuntimeGrant.Context caller,
                                 WalRuntimeGrant.SyncedBoundary receipt) {
    checkTurn(caller);
    if (phase != Phase.PENDING || pending == null)
      throw new RejectedExecutionException("no pending shard publication");
    Pending op = pending;
    if (receipt == null || !receipt.boundTo(context) ||
        receipt.sequence() != op.frame().sequence() ||
        receipt.operationId() != op.frame().operationId() ||
        receipt.endOffset() != op.expectedEndOffset() ||
        !Arrays.equals(receipt.frameSha256(), sha256(op.bytes()))) {
      phase = Phase.UNKNOWN;
      throw new SecurityException("unverifiable sync; shard outcome unknown");
    }
    // All expected failures checked before modifying the index. Preserve the
    // tombstone slot in the durable-history budget even if its value is absent.
    Frame record = op.frame();
    ByteKey key = new ByteKey(record.key());
    byte[] former = values.get(key);
    if (former != null) liveBytes -= former.length;
    if (record.kind() == 1) {
      byte[] immutable = record.value();
      values.put(key, immutable);
      historicalKeys.add(key);
      liveBytes += immutable.length;
    } else {
      values.remove(key);
    }
    operationIds.add(record.operationId());
    walBytes = op.expectedEndOffset();
    sequence = record.sequence();
    lastGeneration = record.generation();
    pending = null;
    phase = Phase.OPEN;
    return new Published(record.operationId(), record.sequence(), lastGeneration);
  }

  /** Once handed to the writer, errors, cancellation and lost callbacks are UNKNOWN. */
  void markUnknownOnTrustedTurn(WalRuntimeGrant.Context caller) {
    checkTurn(caller);
    if (phase != Phase.PENDING) throw new RejectedExecutionException("not dispatched");
    phase = Phase.UNKNOWN;
    pending = null;
  }

  Optional<byte[]> readPublishedOnTrustedTurn(WalRuntimeGrant.Context caller,
                                               byte[] keyBytes) {
    checkTurn(caller);
    if (phase == Phase.UNKNOWN || phase == Phase.CLOSED)
      throw new RejectedExecutionException("shard not readable");
    Objects.requireNonNull(keyBytes);
    if (keyBytes.length < 1 || keyBytes.length > 64)
      throw new IllegalArgumentException("invalid lookup key");
    byte[] value = values.get(new ByteKey(keyBytes));
    return value == null ? Optional.empty() : Optional.of(value.clone());
  }

  Snapshot snapshotOnTrustedTurn(WalRuntimeGrant.Context caller) {
    checkTurn(caller);
    return new Snapshot(sequence, lastGeneration, operationIds.size(),
                        liveBytes, historicalKeys.size(), walBytes,
                        phase == Phase.UNKNOWN);
  }

  /** Never claim guest-visible ACK, even after an actual host fsync. */
  boolean canAcknowledgeGuest() { return false; }

  /** Diagnostic only; never a guest scheduler or capability API. */
  boolean isQuarantinedForDiagnostics() { return phase == Phase.UNKNOWN; }

  void retireOnTrustedTurn(WalRuntimeGrant.Context caller) {
    checkTurn(caller);
    phase = phase == Phase.PENDING || phase == Phase.UNKNOWN ? Phase.UNKNOWN : Phase.CLOSED;
    pending = null;
  }

  private void checkTurn(WalRuntimeGrant.Context caller) {
    if (caller != context) throw new SecurityException("foreign shard Context");
    final boolean current;
    try { current = runtime.isCurrent(context); }
    catch (RuntimeException | Error admissionFailure) {
      phase = Phase.UNKNOWN;
      pending = null;
      throw admissionFailure;
    }
    if (!current) {
      phase = Phase.UNKNOWN;
      pending = null;
      throw new SecurityException("stale actor activation");
    }
  }

  private static Frame parseAndValidate(byte[] input) {
    if (input.length < 31 || input.length > 1118)
      throw new IllegalArgumentException("invalid WAL frame size");
    ByteBuffer b = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN);
    if (b.get(0) != 'O' || b.get(1) != 'D' || b.get(2) != 'B' ||
        b.get(3) != '1' || b.get(4) != 1)
      throw new IllegalArgumentException("bad WAL magic");
    int kind = Byte.toUnsignedInt(b.get(5));
    if (kind != 1 && kind != 2) throw new IllegalArgumentException("bad WAL kind");
    int tenant = b.getInt(6), generation = b.getInt(10), seq = b.getInt(14), op = b.getInt(18);
    int keyLen = Short.toUnsignedInt(b.getShort(22));
    int valueLen = Short.toUnsignedInt(b.getShort(24));
    if (tenant <= 0 || generation <= 0 || seq <= 0 || op <= 0 ||
        keyLen < 1 || keyLen > 64 || valueLen > 1024 ||
        (kind == 2 && valueLen != 0) || input.length != 30 + keyLen + valueLen)
      throw new IllegalArgumentException("bad WAL identity or length");
    int a = 1, c = 0;
    for (int i = 0; i < input.length - 4; i++) {
      a = (a + Byte.toUnsignedInt(input[i])) % 65521;
      c = (c + a) % 65521;
    }
    if (Short.toUnsignedInt(b.getShort(input.length - 4)) != a ||
        Short.toUnsignedInt(b.getShort(input.length - 2)) != c)
      throw new IllegalArgumentException("bad WAL checksum");
    return new Frame(tenant, generation, seq, op, kind,
        Arrays.copyOfRange(input, 26, 26 + keyLen),
        Arrays.copyOfRange(input, 26 + keyLen, input.length - 4));
  }

  private static byte[] sha256(byte[] bytes) {
    try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
    catch (NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
  }
}
