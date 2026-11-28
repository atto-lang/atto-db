package org.oreslang.db.primitive;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;

/**
 * Package-private host-side actor/storage seam. NOT registered as an Oreslang
 * guest API. Only a trusted runtime integration may issue a Context and pass
 * an independently confined, exclusive preopened WAL descriptor.
 *
 * Object identity is an extra local check, not authentication of a tenant or
 * a substitute for Graal/LLVM isolate, scheduler and FD authority checks.
 * No method returns a guest-visible Committed ACK, file path or descriptor.
 */
final class WalRuntimeGrant {
  /** Private construction prevents a guest DATA envelope carrying numeric IDs
   * from manufacturing this Java object via a public API. The runtime calling
   * issueContext MUST additionally perform its own actor/isolate admission. */
  static final class Context {
    private final WalFilePrimitive.Owner owner;
    private Context(WalFilePrimitive.Owner owner) { this.owner = owner; }
    WalFilePrimitive.Owner owner() { return owner; }
  }

  /** Runtime-host-only issuance point. DO NOT register for guest interop. */
  static Context issueContextFromTrustedRuntime(WalFilePrimitive.Owner verified) {
    return new Context(Objects.requireNonNull(verified));
  }

  /** Host-internal sync boundary; no constructor or ACK conversion exposed. */
  static final class SyncedBoundary {
    private final Context context;
    private final int operationId;
    private final int sequence;
    private final long endOffset;
    private final byte[] frameSha256;
    private SyncedBoundary(Context context, WalFilePrimitive.SyncedReceipt receipt) {
      this.context = context;
      this.operationId = receipt.operationId();
      this.sequence = receipt.sequence();
      this.endOffset = receipt.endOffset();
      this.frameSha256 = receipt.frameSha256();
    }
    int operationId() { return operationId; }
    int sequence() { return sequence; }
    long endOffset() { return endOffset; }
    byte[] frameSha256() { return frameSha256.clone(); }
    boolean boundTo(Context trusted) { return context == trusted; }
  }

  private final Context issuedContext;
  private final WalFilePrimitive filePrimitive;
  private volatile boolean revoked;

  private WalRuntimeGrant(Context context, WalFilePrimitive primitive) {
    this.issuedContext = context;
    this.filePrimitive = primitive;
  }

  /** Descriptor custody and race-resistant path resolution are caller duties.
   * This does not open a path, follow symlinks or claim an OS-level file lock. */
  static WalRuntimeGrant attachPreopenedFromRuntime(FileChannel authorizedFile,
                                                    Context issued) throws IOException {
    Objects.requireNonNull(issued);
    return new WalRuntimeGrant(issued,
        WalFilePrimitive.fromRuntimeFile(Objects.requireNonNull(authorizedFile), issued.owner()));
  }

  /**
   * Submit bytes to the existing bounded off-carrier append+force(true) owner.
   * The returned boundary is HOST-ONLY evidence, never a commit ACK. Only a
   * runtime-issued context object matching by identity is accepted; repeating
   * equivalent numeric IDs is not a grant. Revocation dominates late sync.
   */
  CompletionStage<SyncedBoundary> appendAndSync(Context caller, byte[] frameBytes) {
    if (caller != issuedContext) throw new SecurityException("foreign runtime storage context");
    if (revoked) throw new RejectedExecutionException("storage context revoked");
    byte[] immutableFrame = Objects.requireNonNull(frameBytes).clone();
    // Existing primitive validates the entire WAL record, identity, sequence,
    // payload and checksum and independently clones its submitted input.
    CompletionStage<WalFilePrimitive.SyncedReceipt> submitted =
        filePrimitive.appendAndSync(caller.owner(), immutableFrame);
    CompletableFuture<SyncedBoundary> result = new CompletableFuture<>();
    submitted.whenComplete((receipt, error) -> {
      if (error != null) {
        result.completeExceptionally(error);
        return;
      }
      // Never authorize a stale activation after delayed I/O completion.
      if (revoked || receipt == null || !caller.owner().equals(receipt.owner()) ||
          !Arrays.equals(sha256(immutableFrame), receipt.frameSha256())) {
        result.completeExceptionally(new WalFilePrimitive.UnknownOutcome(
            "stale or unverifiable post-sync boundary", null));
        return;
      }
      result.complete(new SyncedBoundary(issuedContext, receipt));
    });
    return result.minimalCompletionStage();
  }

  /** Never closes synchronously on an actor carrier. Existing accepted writes
   * may have reached disk and must be reconciled after any revoke. */
  CompletionStage<Void> revokeAndCloseAsync() {
    revoked = true;
    return filePrimitive.revokeAndCloseAsync();
  }

  private static byte[] sha256(byte[] bytes) {
    try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
    catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
  }
}
