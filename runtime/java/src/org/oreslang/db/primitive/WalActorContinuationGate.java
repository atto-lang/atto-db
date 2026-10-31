package org.oreslang.db.primitive;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * HOST-ONLY continuation handoff. Not an Oreslang guest API and not a DB ACK.
 *
 * CompletionStage.whenComplete may execute synchronously on the registering
 * thread if the stage has already completed. This gate therefore performs
 * only a bounded, nonblocking offer from that callback. Runtime authorization
 * and actor dispatch happen on the gate's separate virtual thread.
 *
 * Real backend integration MUST provide a trusted, bounded RuntimeQueue that
 * retains the activation generation and enforces the actor nonreentrancy fence.
 */
final class WalActorContinuationGate implements AutoCloseable {
  static final int MAX_PENDING = 32;

  interface RuntimeQueue {
    boolean isCurrent(WalRuntimeGrant.Context context);
    /** Must enqueue to an authorized actor scheduler, never run guest code here. */
    void dispatch(WalRuntimeGrant.Context context, Runnable continuation);
  }

  private final WalRuntimeGrant.Context context;
  private final RuntimeQueue runtime;
  private final ArrayBlockingQueue<Runnable> pending =
      new ArrayBlockingQueue<>(MAX_PENDING);
  private final Thread dispatcher;
  private volatile boolean retired;
  private volatile boolean poisoned;

  private WalActorContinuationGate(WalRuntimeGrant.Context trusted,
                                   RuntimeQueue authorizedRuntime) {
    context = Objects.requireNonNull(trusted);
    runtime = Objects.requireNonNull(authorizedRuntime);
    dispatcher = Thread.ofVirtual().name("ores-db-wal-actor-dispatch").start(() -> {
      while (!retired) {
        try {
          Runnable notice = pending.poll(100, TimeUnit.MILLISECONDS);
          if (notice != null && !retired && !poisoned) notice.run();
        } catch (InterruptedException interrupted) {
          if (retired) break;
          Thread.currentThread().interrupt();
          poisoned = true; // no untrusted fallback inline
          break;
        } catch (RuntimeException | Error dispatchFailure) {
          // Trusted runtime hooks can throw unchecked errors (including
          // AssertionError). Never let the worker die with isPoisoned()==false:
          // lost receipts must not be mistaken for an admitted DB ACK.
          poisoned = true;
          break;
        }
      }
      pending.clear();
    });
  }

  /** Only a runtime already holding the exact issued context may create this. */
  static WalActorContinuationGate fromTrustedRuntime(
      WalRuntimeGrant.Context trusted, RuntimeQueue authorizedRuntime) {
    return new WalActorContinuationGate(trusted, authorizedRuntime);
  }

  /**
   * Guest actor code must never see the raw CompletionStage. Both early and
   * late registration enqueue bounded notifications; no user continuation is
   * called by the registering thread, disk worker, or receipt handoff worker.
   *
   * Failure to enqueue poisons this gate. Runtime MUST check this fail-closed
   * status before attempting any committed-state publication or ACK.
   */
  <T> void register(WalRuntimeGrant.Context caller, CompletionStage<T> source,
                    BiConsumer<? super T, ? super Throwable> continuation) {
    if (caller != context) throw new SecurityException("foreign actor activation");
    Objects.requireNonNull(source);
    Objects.requireNonNull(continuation);
    if (retired || poisoned) throw new RejectedExecutionException("actor gate retired");
    source.whenComplete((value, error) -> {
      // whenComplete can be inline for a completed stage. Only a bounded offer
      // happens here, never guest callback, scheduler admission, or blocking I/O.
      Runnable notice = () -> {
        if (retired || poisoned || !runtime.isCurrent(context)) return;
        runtime.dispatch(context, () -> {
          // Reject a broken CallerRuns-style scheduler rather than executing
          // actor code on this handoff worker. The real runtime must also
          // verify its own actor-turn identity and generation fencing.
          if (Thread.currentThread() == dispatcher) {
            poisoned = true;
            return;
          }
          // Recheck at actual actor turn execution, not just dispatch.
          if (!retired && !poisoned && runtime.isCurrent(context))
            continuation.accept(value, error);
        });
      };
      if (!retired && !poisoned && !pending.offer(notice)) poisoned = true;
    });
  }

  boolean isPoisoned() { return poisoned; }
  boolean isRetired() { return retired; }
  int queuedNotices() { return pending.size(); }

  @Override public void close() {
    retired = true;
    dispatcher.interrupt();
    pending.clear();
  }
}
