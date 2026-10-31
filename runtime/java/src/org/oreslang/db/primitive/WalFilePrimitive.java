package org.oreslang.db.primitive;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Package-private host primitive, NOT a guest-visible DB implementation.
 * A trusted runtime must open and confine the file descriptor beforehand;
 * no guest-supplied path, file descriptor or alleged capability is accepted.
 * This class is not registered in any Oreslang backend yet.
 */
final class WalFilePrimitive {
  static final int MAX_OUTSTANDING = 32;
  static final int MAX_FRAME = 1118;
  static final int MAX_HISTORY = 4096;
  static final long MAX_WAL_BYTES = 1024L * 1024;

  /** Test substitution is host-only; production always uses FileChannel.force(true). */
  interface IoHooks {
    void beforeAppend() throws IOException;
    void force(FileChannel channel) throws IOException;
  }

  private static final IoHooks REAL_IO = new IoHooks() {
    public void beforeAppend() { }
    public void force(FileChannel channel) throws IOException { channel.force(true); }
  };

  /** Never construct these IDs from guest WAL fields or arbitrary actor DATA. */
  record Owner(int tenant, int isolate, int shard, int generation) {
    Owner {
      if (tenant <= 0 || isolate <= 0 || shard <= 0 || generation <= 0)
        throw new IllegalArgumentException("invalid runtime owner identity");
    }
  }

  /** Runtime-only sync evidence, NOT a guest-constructible Committed ACK. */
  static final class SyncedReceipt {
    private final Owner owner;
    private final int operationId;
    private final int sequence;
    private final long endOffset;
    private final byte[] frameSha256;

    private SyncedReceipt(Owner owner, int operationId, int sequence,
                          long endOffset, byte[] frameSha256) {
      this.owner = owner;
      this.operationId = operationId;
      this.sequence = sequence;
      this.endOffset = endOffset;
      this.frameSha256 = frameSha256.clone();
    }
    Owner owner() { return owner; }
    int operationId() { return operationId; }
    int sequence() { return sequence; }
    long endOffset() { return endOffset; }
    byte[] frameSha256() { return frameSha256.clone(); }
  }

  /** Once dispatched, failure/revocation may have modified disk; never retry blindly. */
  static final class UnknownOutcome extends IOException {
    private static final long serialVersionUID = 1L;
    UnknownOutcome(String message, Throwable cause) { super(message, cause); }
  }

  private record FrameInfo(int tenant, int generation, int sequence, int operationId) { }

  private final Owner owner;
  private final FileChannel channel;
  private final IoHooks ioHooks;
  private final ThreadPoolExecutor diskWorker;
  private final ExecutorService handoffWorker;
  private final Object admissionLock = new Object();
  private final Set<Integer> operationIds = new HashSet<>();
  private int nextSequence = 1;
  private int outstanding = 0;
  private long acceptedWalBytes = 0;
  // Disk-worker-only state. Never assume that a file grew durably merely
  // because an admission slot or a queued write advanced.
  private long syncedFileEnd = 0;
  private volatile boolean poisoned = false;
  private volatile boolean revoked = false;
  private CompletableFuture<Void> shutdown;

  private WalFilePrimitive(FileChannel preopenedFile, Owner trustedOwner,
                           IoHooks hostHooks) throws IOException {
    owner = Objects.requireNonNull(trustedOwner);
    channel = Objects.requireNonNull(preopenedFile);
    ioHooks = Objects.requireNonNull(hostHooks);
    if (!channel.isOpen() || channel.size() != 0L)
      throw new IllegalStateException("nonempty WAL requires trusted recovery; no append");
    // Deliberately rejects append to an existing WAL until verified replay.
    ThreadFactory diskThreads = task -> {
      Thread thread = new Thread(task, "ores-db-wal-io");
      thread.setDaemon(true);
      return thread;
    };
    ThreadFactory handoffThreads = task -> {
      Thread thread = new Thread(task, "ores-db-wal-handoff");
      thread.setDaemon(true);
      return thread;
    };
    diskWorker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(MAX_OUTSTANDING), diskThreads,
        new ThreadPoolExecutor.AbortPolicy()); // NEVER CallerRunsPolicy
    handoffWorker = Executors.newSingleThreadExecutor(handoffThreads);
  }

  /** Package-local entrypoint: runtime passes an already-authorized FD. */
  static WalFilePrimitive fromRuntimeFile(FileChannel preopenedFile,
                                          Owner trustedOwner) throws IOException {
    return new WalFilePrimitive(preopenedFile, trustedOwner, REAL_IO);
  }

  /** Test hook does not bypass envelope validation or receipt issuance rules. */
  static WalFilePrimitive forNativeFaultTest(FileChannel preopenedFile,
                                              Owner trustedOwner,
                                              IoHooks hook) throws IOException {
    return new WalFilePrimitive(preopenedFile, trustedOwner, hook);
  }

  /**
   * Off-carrier append then force(true). The only success value is SyncedReceipt,
   * not a DB commit. Futures cannot be used to abort an already dispatched write.
   * The separate handoff thread prevents client callbacks on the disk owner.
   */
  CompletionStage<SyncedReceipt> appendAndSync(Owner runtimeCaller, byte[] untrustedFrame) {
    Objects.requireNonNull(runtimeCaller);
    Objects.requireNonNull(untrustedFrame);
    byte[] frame = untrustedFrame.clone(); // mutation-after-submit is ineffective
    FrameInfo info = inspectFrame(frame);
    CompletableFuture<SyncedReceipt> result = new CompletableFuture<>();
    synchronized (admissionLock) {
      if (revoked || poisoned) throw new RejectedExecutionException("owner closed/quarantined");
      if (!owner.equals(runtimeCaller)) throw new SecurityException("stale or foreign owner");
      if (info.tenant() != owner.tenant() || info.generation() != owner.generation())
        throw new SecurityException("WAL identity mismatches runtime owner");
      if (operationIds.contains(info.operationId()))
        throw new RejectedExecutionException("duplicate operation in current epoch");
      if (nextSequence > Integer.MAX_VALUE - 1 || info.sequence() != nextSequence)
        throw new RejectedExecutionException("nonconsecutive sequence");
      if (outstanding >= MAX_OUTSTANDING || operationIds.size() >= MAX_HISTORY ||
          frame.length > MAX_WAL_BYTES - acceptedWalBytes)
        throw new RejectedExecutionException("bounded WAL or admission limit");
      ++outstanding;
      ++nextSequence;
      acceptedWalBytes += frame.length;
      operationIds.add(info.operationId());
      try {
        diskWorker.execute(() -> writeThenSync(frame, info, result));
      } catch (RejectedExecutionException rejected) {
        --outstanding;
        --nextSequence;
        acceptedWalBytes -= frame.length;
        operationIds.remove(info.operationId());
        throw rejected;
      }
    }
    return result.minimalCompletionStage();
  }

  private void writeThenSync(byte[] frame, FrameInfo info,
                             CompletableFuture<SyncedReceipt> result) {
    SyncedReceipt synced = null;
    Throwable problem = null;
    try {
      if (revoked || poisoned) throw new IOException("runtime owner closed/quarantined before dispatch");
      ioHooks.beforeAppend();
      if (revoked || poisoned) throw new IOException("runtime owner closed/quarantined before write");
      long begin = channel.size();
      if (begin != syncedFileEnd)
        throw new IOException("WAL file length changed outside exclusive owner");
      // Refuse mutations to the preopened descriptor by an external writer.
      // A real runtime must hold an exclusive shard lease for this channel.
      // Sequential worker prevents two local operations from racing.
      ByteBuffer bytes = ByteBuffer.wrap(frame);
      long written = 0;
      while (bytes.hasRemaining()) {
        int n = channel.write(bytes, begin + written);
        if (n <= 0) throw new IOException("zero/negative positional WAL write");
        written += n;
      }
      ioHooks.force(channel); // real hook performs FileChannel.force(true)
      syncedFileEnd = begin + written;
      if (revoked) throw new IOException("revoked after sync: outcome unknown");
      synced = new SyncedReceipt(owner, info.operationId(), info.sequence(),
                                 begin + written, sha256(frame));
    } catch (IOException | RuntimeException failure) {
      problem = new UnknownOutcome("WAL dispatch/sync outcome unknown; quarantine", failure);
      synchronized (admissionLock) { poisoned = true; }
    }
    final SyncedReceipt receipt = synced;
    final Throwable error = problem;
    try {
      handoffWorker.execute(() -> {
        // Completion may run untrusted callbacks, never on disk I/O worker.
        // A late revocation dominates even a completed sync. Only the
        // WAL owner after a fresh generation-fence check may publish a DB ACK.
        if (error == null && revoked)
          result.completeExceptionally(new UnknownOutcome("receipt revoked before handoff", null));
        else if (error == null) result.complete(receipt);
        else result.completeExceptionally(error);
        synchronized (admissionLock) { --outstanding; }
      });
    } catch (RejectedExecutionException handoffFailure) {
      // Completing a CompletableFuture may invoke user continuations inline.
      // A rejected handoff must NEVER run such callbacks on the disk worker.
      // Quarantine first, then publish Unknown on a separate virtual thread.
      synchronized (admissionLock) { poisoned = true; --outstanding; }
      Thread.ofVirtual().name("ores-db-wal-handoff-emergency").start(() ->
          result.completeExceptionally(new UnknownOutcome(
              "receipt delivery unavailable", handoffFailure)));
    }
  }

  /**
   * Fail-closed revocation. Accepted operations can still have physical side
   * effects, but no success receipt is emitted once revocation is visible.
   * Do NOT call this as a blocking actor operation: it returns an awaitable.
   */
  CompletionStage<Void> revokeAndCloseAsync() {
    synchronized (admissionLock) {
      if (shutdown != null) return shutdown.minimalCompletionStage();
      revoked = true;
      diskWorker.shutdown();
      shutdown = new CompletableFuture<>();
      CompletableFuture<Void> completion = shutdown;
      Thread.ofVirtual().name("ores-db-wal-close").start(() -> {
        try {
          if (!diskWorker.awaitTermination(30, TimeUnit.SECONDS))
            throw new IllegalStateException("disk I/O not quiescent; preserve open FD");
          handoffWorker.shutdown();
          if (!handoffWorker.awaitTermination(30, TimeUnit.SECONDS))
            throw new IllegalStateException("handoff not quiescent; preserve open FD");
          // Only disk and owned handoff-executor tasks are now quiescent.
          // A client may schedule its own CompletionStage continuations on
          // other executors. Those NEVER receive this FD; the actual runtime
          // must independently generation-fence any actor-state reentry.
          channel.close();
          completion.complete(null);
        } catch (Exception failure) {
          completion.completeExceptionally(failure);
        }
      });
      return shutdown.minimalCompletionStage();
    }
  }

  private static FrameInfo inspectFrame(byte[] frame) {
    if (frame.length < 31 || frame.length > MAX_FRAME)
      throw new IllegalArgumentException("invalid frame byte length");
    if (u8(frame, 0) != 79 || u8(frame, 1) != 68 ||
        u8(frame, 2) != 66 || u8(frame, 3) != 49 || u8(frame, 4) != 1)
      throw new IllegalArgumentException("invalid WAL magic/version");
    int kind = u8(frame, 5);
    if (kind != 1 && kind != 2) throw new IllegalArgumentException("invalid WAL kind");
    int tenant = checkedI31(frame, 6);
    int generation = checkedI31(frame, 10);
    int sequence = checkedI31(frame, 14);
    int operation = checkedI31(frame, 18);
    if (tenant == 0 || generation == 0 || sequence == 0 || operation == 0)
      throw new IllegalArgumentException("invalid WAL identity");
    int keyLen = u16(frame, 22), valLen = u16(frame, 24);
    if (keyLen < 1 || keyLen > 64 || valLen > 1024 ||
        (kind == 2 && valLen != 0) || frame.length != 30 + keyLen + valLen)
      throw new IllegalArgumentException("invalid WAL payload lengths");
    int a = 1, b = 0, payloadEnd = frame.length - 4;
    for (int i = 0; i < payloadEnd; i++) {
      a = (a + u8(frame, i)) % 65521;
      b = (b + a) % 65521;
    }
    if (u16(frame, payloadEnd) != a || u16(frame, payloadEnd + 2) != b)
      throw new IllegalArgumentException("invalid WAL checksum");
    return new FrameInfo(tenant, generation, sequence, operation);
  }

  private static int u8(byte[] b, int at) { return b[at] & 0xff; }
  private static int u16(byte[] b, int at) { return u8(b, at) | u8(b, at+1) << 8; }
  private static int checkedI31(byte[] b, int at) {
    if (u8(b, at+3) > 127) throw new IllegalArgumentException("high-bit WAL ID");
    return u8(b, at) | u8(b, at+1) << 8 | u8(b, at+2) << 16 | u8(b, at+3) << 24;
  }
  private static byte[] sha256(byte[] b) {
    try { return MessageDigest.getInstance("SHA-256").digest(b); }
    catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
  }
}
