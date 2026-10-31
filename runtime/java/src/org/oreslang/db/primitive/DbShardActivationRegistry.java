package org.oreslang.db.primitive;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;

/**
 * HOST-ONLY registry for fresh, exclusive shard activations. This class is
 * intentionally package-private and must never be registered for guest interop.
 *
 * The real runtime MUST authenticate the activation, establish OS-level FD
 * custody, enforce scheduler actor turns, and durably fence generations. The
 * registry rejects duplicate *Java channel identity* and stale generations
 * within this process, but cannot detect different FDs aliasing the same file
 * or protect against process restart. It neither recovers WAL nor issues ACK.
 */
final class DbShardActivationRegistry {
  record ShardKey(int tenant, int isolate, int shard) {
    ShardKey {
      if (tenant <= 0 || isolate <= 0 || shard <= 0)
        throw new IllegalArgumentException("invalid trusted shard identity");
    }
  }

  /** Supplied only by a real backend runtime, NOT by actor DATA. */
  interface TrustedRuntime {
    /** Host-side custody/admission check; may not authorize guest file paths. */
    boolean mayActivate(ShardKey shard, int generation, FileChannel preopened);
    /** Generation/activation validity, including when invoked off-carrier. */
    boolean isCurrent(Activation activation);
    /** Must attest the real serialized actor turn, not merely thread name. */
    boolean isActorTurn(Activation activation);
    /** Must asynchronously queue a new runtime actor turn, never CallerRuns. */
    void enqueueActorTurn(Activation activation, Runnable continuation);
  }

  private static final class Slot {
    int highestGeneration;
    Activation active;
    boolean closing;
  }

  /** Opaque JVM-local host activation; never encode or send in actor DATA. */
  final class Activation {
    private final ShardKey key;
    private final int generation;
    private final FileChannel descriptor;
    private final WalRuntimeGrant.Context context;
    private final WalRuntimeGrant writer;
    private final ShardActorHandoffBridge handoff;
    private volatile boolean retired;
    private volatile boolean invalidated;
    private CompletableFuture<Void> closeResult;

    private Activation(ShardKey k, int gen, FileChannel channel,
                       WalRuntimeGrant.Context ctx, WalRuntimeGrant grant) {
      key = k;
      generation = gen;
      descriptor = channel;
      context = ctx;
      writer = grant;
      handoff = ShardActorHandoffBridge.fromTrustedRuntime(ctx, grant,
          new WalActorContinuationGate.RuntimeQueue() {
            public boolean isCurrent(WalRuntimeGrant.Context caller) {
              return caller == context && isActive(Activation.this) &&
                  runtime.isCurrent(Activation.this);
            }
            public void dispatch(WalRuntimeGrant.Context caller, Runnable nextTurn) {
              if (caller != context || !isActive(Activation.this))
                throw new RejectedExecutionException("stale shard handoff");
              runtime.enqueueActorTurn(Activation.this, nextTurn);
            }
          }, caller -> caller == context && isActive(Activation.this) &&
               runtime.isCurrent(Activation.this) &&
               runtime.isActorTurn(Activation.this));
    }

    ShardKey key() { return key; }
    int generation() { return generation; }
    boolean isRetired() { return retired; }

    /** Called on a real runtime actor turn. No synchronous disk I/O or await. */
    void appendOnActorTurn(byte[] boundedFrame) {
      requireActorTurn(this);
      handoff.appendOnActorTurn(context, boundedFrame);
    }
    Optional<byte[]> getOnActorTurn(byte[] keyBytes) {
      requireActorTurn(this);
      return handoff.readOnActorTurn(context, keyBytes);
    }
    ShardPublicationGate.Snapshot snapshotOnActorTurn() {
      requireActorTurn(this);
      return handoff.snapshotOnActorTurn(context);
    }
    /** Host diagnostics only, not permission to ACK. */
    boolean isQuarantinedForDiagnostics() {
      return retired || invalidated || handoff.isQuarantinedForDiagnostics() ||
          !writer.isBoundTo(context);
    }
    boolean canAcknowledgeGuest() { return false; }
    CompletionStage<Void> retireAsync() { return retire(this); }
  }

  private final TrustedRuntime runtime;
  private final Object lock = new Object();
  private final Map<ShardKey, Slot> slots = new HashMap<>();
  private final Set<FileChannel> ownedChannels =
      Collections.newSetFromMap(new IdentityHashMap<>());

  private DbShardActivationRegistry(TrustedRuntime trusted) {
    runtime = Objects.requireNonNull(trusted);
  }

  /** Trusted runtime factory only; NOT guest-callable authority issuance. */
  static DbShardActivationRegistry fromTrustedRuntime(TrustedRuntime trusted) {
    return new DbShardActivationRegistry(trusted);
  }

  /**
   * No guest-supplied path. Fresh EMPTY WAL only; recovered/nonempty WAL must
   * wait for separately authenticated and durable recovery integration.
   */
  Activation activateFresh(ShardKey key, int generation, FileChannel preopened)
      throws IOException {
    Objects.requireNonNull(key);
    Objects.requireNonNull(preopened);
    if (generation <= 0 || !preopened.isOpen() || preopened.size() != 0)
      throw new IllegalArgumentException("invalid generation or nonempty WAL");
    if (!runtime.mayActivate(key, generation, preopened))
      throw new SecurityException("runtime storage custody not granted");
    synchronized (lock) {
      Slot slot = slots.computeIfAbsent(key, ignored -> new Slot());
      if (slot.active != null || slot.closing ||
          generation <= slot.highestGeneration || ownedChannels.contains(preopened))
        throw new RejectedExecutionException("duplicate descriptor, active shard, or stale epoch");
      var owner = new WalFilePrimitive.Owner(
          key.tenant(), key.isolate(), key.shard(), generation);
      var context = WalRuntimeGrant.issueContextFromTrustedRuntime(owner);
      var grant = WalRuntimeGrant.attachPreopenedFromRuntime(preopened, context);
      Activation activation = new Activation(key, generation, preopened, context, grant);
      slot.active = activation;
      slot.highestGeneration = generation;
      ownedChannels.add(preopened);
      return activation;
    }
  }

  private boolean isActive(Activation activation) {
    synchronized (lock) {
      Slot slot = slots.get(activation.key);
      return slot != null && slot.active == activation && !slot.closing &&
          !activation.retired && !activation.invalidated &&
          slot.highestGeneration == activation.generation;
    }
  }

  private void requireActorTurn(Activation activation) {
    if (!isActive(activation))
      throw new SecurityException("retired or poisoned shard activation");
    final boolean current;
    try { current = runtime.isCurrent(activation); }
    catch (RuntimeException | Error runtimeFailure) {
      activation.invalidated = true;
      throw runtimeFailure;
    }
    if (!current) {
      // Once a generation is stale, transient re-enabling cannot resurrect it.
      activation.invalidated = true;
      throw new SecurityException("stale shard activation");
    }
    // A caller on the wrong thread does NOT retire an otherwise valid shard:
    // untrusted code must not be able to DoS a legitimate activation merely
    // by attempting an off-turn read.
    if (!runtime.isActorTurn(activation))
      throw new SecurityException("not a serialized runtime actor turn");
  }

  /**
   * Closing is a fence, NOT an abort. A write could have reached stable storage
   * without an observable callback. Re-admission stays blocked until disk and
   * handoff workers have quiesced and the original FD actually closes.
   * A failed close permanently quarantines the slot in this registry instance.
   */
  private CompletionStage<Void> retire(Activation activation) {
    final Slot slot;
    final CompletableFuture<Void> result;
    synchronized (lock) {
      slot = slots.get(activation.key);
      if (slot == null || slot.active != activation) {
        if (activation.closeResult != null)
          return activation.closeResult.minimalCompletionStage();
        throw new SecurityException("retirement from foreign/stale registry");
      }
      if (activation.closeResult != null)
        return activation.closeResult.minimalCompletionStage();
      slot.closing = true;
      slot.active = null;
      activation.retired = true;
      result = new CompletableFuture<>();
      activation.closeResult = result;
    }
    try {
      activation.handoff.retireFromTrustedRuntime().whenComplete((ignored, failure) -> {
        if (failure != null) {
          // NEVER unblock a new generation if the FD may remain live.
          result.completeExceptionally(failure);
          return;
        }
        synchronized (lock) {
          ownedChannels.remove(activation.descriptor);
          slot.closing = false;
        }
        result.complete(null);
      });
    } catch (RuntimeException | Error unsafeClose) {
      result.completeExceptionally(unsafeClose); // slot remains quarantined
    }
    return result.minimalCompletionStage();
  }
}
