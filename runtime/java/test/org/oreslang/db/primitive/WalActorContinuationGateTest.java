package org.oreslang.db.primitive;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Java 21 HOST-only test of early/late stage registration and bounded
 * activation dispatch. No Oreslang guest scheduler or ACK is implemented.
 */
public final class WalActorContinuationGateTest {
  private static final WalFilePrimitive.Owner OWNER =
      new WalFilePrimitive.Owner(7, 11, 5, 3);
  private static final WalRuntimeGrant.Context CONTEXT =
      WalRuntimeGrant.issueContextFromTrustedRuntime(OWNER);

  private static void check(boolean ok, String why) {
    if (!ok) throw new AssertionError(why);
  }
  private static void waitFor(CountDownLatch latch, String why) throws Exception {
    check(latch.await(5, TimeUnit.SECONDS), why);
  }

  private static void earlyAndLate() throws Exception {
    AtomicBoolean current = new AtomicBoolean(true);
    try (ExecutorService actor = Executors.newSingleThreadExecutor(
             Thread.ofVirtual().name("ores-db-test-actor-", 0).factory())) {
      WalActorContinuationGate.RuntimeQueue runtime = new WalActorContinuationGate.RuntimeQueue() {
        public boolean isCurrent(WalRuntimeGrant.Context ctx) {
          return ctx == CONTEXT && current.get();
        }
        public void dispatch(WalRuntimeGrant.Context ctx, Runnable task) {
          actor.execute(task);
        }
      };
      try (WalActorContinuationGate gate =
             WalActorContinuationGate.fromTrustedRuntime(CONTEXT, runtime)) {
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger total = new AtomicInteger();
        var notYetCompleted = new CompletableFuture<Integer>();
        gate.register(CONTEXT, notYetCompleted.minimalCompletionStage(), (v, e) -> {
          check(e == null && v == 41, "early value mismatch");
          check(Thread.currentThread().getName().startsWith("ores-db-test-actor-"),
                "early callback executed on registering carrier");
          total.addAndGet(v);
          done.countDown();
        });
        notYetCompleted.complete(41);
        var alreadyCompleted = CompletableFuture.completedFuture(1).minimalCompletionStage();
        gate.register(CONTEXT, alreadyCompleted, (v, e) -> {
          check(e == null && v == 1, "late value mismatch");
          check(Thread.currentThread().getName().startsWith("ores-db-test-actor-"),
                "late callback executed on registering carrier");
          total.addAndGet(v);
          done.countDown();
        });
        waitFor(done, "early/late callbacks were not dispatched");
        check(total.get() == 42 && !gate.isPoisoned(), "unexpected delivery/poison");
        var sameIdsDifferentIdentity = WalRuntimeGrant.issueContextFromTrustedRuntime(OWNER);
        try {
          gate.register(sameIdsDifferentIdentity,
              CompletableFuture.completedFuture(0), (v, e) -> {});
          throw new AssertionError("forged-equivalent Context was admitted");
        } catch (SecurityException expected) { }
      }
    }
  }

  private static void staleDeliveryFailsClosed() throws Exception {
    AtomicBoolean current = new AtomicBoolean(true);
    LinkedBlockingQueue<Runnable> actorMailbox = new LinkedBlockingQueue<>();
    CountDownLatch dispatched = new CountDownLatch(1);
    AtomicInteger called = new AtomicInteger();
    var runtime = new WalActorContinuationGate.RuntimeQueue() {
      public boolean isCurrent(WalRuntimeGrant.Context ctx) { return current.get(); }
      public void dispatch(WalRuntimeGrant.Context ctx, Runnable task) {
        actorMailbox.add(task);
        dispatched.countDown();
      }
    };
    try (WalActorContinuationGate gate =
           WalActorContinuationGate.fromTrustedRuntime(CONTEXT, runtime)) {
      gate.register(CONTEXT, CompletableFuture.completedFuture(5),
          (v, e) -> called.incrementAndGet());
      waitFor(dispatched, "runtime never accepted the callback");
      current.set(false); // retire actor generation before mailbox execution
      Runnable action = actorMailbox.poll(2, TimeUnit.SECONDS);
      check(action != null, "missing mailbox action");
      action.run();
      check(called.get() == 0, "stale actor callback was executed");
    }
  }

  private static void rejectedRuntimePoisons() throws Exception {
    CountDownLatch rejected = new CountDownLatch(1);
    var runtime = new WalActorContinuationGate.RuntimeQueue() {
      public boolean isCurrent(WalRuntimeGrant.Context ctx) { return true; }
      public void dispatch(WalRuntimeGrant.Context ctx, Runnable task) {
        rejected.countDown();
        throw new RejectedExecutionException("runtime mailbox full");
      }
    };
    try (WalActorContinuationGate gate =
           WalActorContinuationGate.fromTrustedRuntime(CONTEXT, runtime)) {
      gate.register(CONTEXT, CompletableFuture.completedFuture(9), (v, e) -> {
        throw new AssertionError("rejected runtime ran actor callback");
      });
      waitFor(rejected, "runtime rejection was never exercised");
      for (int i = 0; i < 200 && !gate.isPoisoned(); ++i)
        Thread.sleep(5);
      check(gate.isPoisoned(), "dispatch rejection did not fail shut");
    }
  }

  private static void callerRunsRejected() throws Exception {
    AtomicInteger called = new AtomicInteger();
    var callerRuns = new WalActorContinuationGate.RuntimeQueue() {
      public boolean isCurrent(WalRuntimeGrant.Context ctx) { return true; }
      public void dispatch(WalRuntimeGrant.Context ctx, Runnable task) {
        task.run(); // broken actor scheduler, not an authorized actor turn
      }
    };
    try (WalActorContinuationGate gate =
           WalActorContinuationGate.fromTrustedRuntime(CONTEXT, callerRuns)) {
      gate.register(CONTEXT, CompletableFuture.completedFuture(22),
          (v, e) -> called.incrementAndGet());
      for (int i = 0; i < 200 && !gate.isPoisoned(); ++i)
        Thread.sleep(5);
      check(gate.isPoisoned(), "CallerRuns runtime was not quarantined");
      check(called.get() == 0, "guest callback ran on handoff dispatcher");
    }
  }

  private static void uncheckedRuntimeErrorPoisons() throws Exception {
    CountDownLatch lookupEntered = new CountDownLatch(1);
    AtomicInteger invoked = new AtomicInteger();
    var broken = new WalActorContinuationGate.RuntimeQueue() {
      public boolean isCurrent(WalRuntimeGrant.Context context) {
        lookupEntered.countDown();
        throw new AssertionError("injected scheduler-isolate failure");
      }
      public void dispatch(WalRuntimeGrant.Context context, Runnable action) {
        invoked.incrementAndGet();
      }
    };
    try (WalActorContinuationGate gate =
           WalActorContinuationGate.fromTrustedRuntime(CONTEXT, broken)) {
      gate.register(CONTEXT, CompletableFuture.completedFuture(8), (v, e) -> {
        throw new AssertionError("guest callback must not run");
      });
      waitFor(lookupEntered, "trusted runtime lookup did not execute");
      for (int i = 0; i < 200 && !gate.isPoisoned(); ++i)
        Thread.sleep(5);
      check(gate.isPoisoned(), "scheduler AssertionError lost fail-closed state");
      check(invoked.get() == 0, "scheduler action escaped failed admission");
      try {
        gate.register(CONTEXT, CompletableFuture.completedFuture(9), (v, e) -> {});
        throw new AssertionError("poisoned scheduler accepted new callback");
      } catch (RejectedExecutionException expected) { }
    }

    CountDownLatch dispatchEntered = new CountDownLatch(1);
    var failedDispatch = new WalActorContinuationGate.RuntimeQueue() {
      public boolean isCurrent(WalRuntimeGrant.Context context) { return true; }
      public void dispatch(WalRuntimeGrant.Context context, Runnable action) {
        dispatchEntered.countDown();
        throw new AssertionError("injected dispatch backend failure");
      }
    };
    try (WalActorContinuationGate gate =
           WalActorContinuationGate.fromTrustedRuntime(CONTEXT, failedDispatch)) {
      gate.register(CONTEXT, CompletableFuture.completedFuture(10), (v, e) -> {
        throw new AssertionError("guest callback must not run");
      });
      waitFor(dispatchEntered, "broken dispatch not reached");
      for (int i = 0; i < 200 && !gate.isPoisoned(); ++i)
        Thread.sleep(5);
      check(gate.isPoisoned(), "dispatch AssertionError lost fail-closed state");
    }
  }

  private static void deferredActorFailurePoisons() throws Exception {
    // The first freshness check runs on the gate's bridge thread and passes.
    // The second executes later on the actor and raises Error. The bridge
    // cannot catch it: the wrapped actor task must poison the gate itself.
    LinkedBlockingQueue<Runnable> mailbox = new LinkedBlockingQueue<>();
    CountDownLatch queued = new CountDownLatch(1);
    AtomicInteger checks = new AtomicInteger();
    var runtime = new WalActorContinuationGate.RuntimeQueue() {
      public boolean isCurrent(WalRuntimeGrant.Context context) {
        if (checks.incrementAndGet() == 1) return true;
        throw new AssertionError("actor activation retired during mailbox execution");
      }
      public void dispatch(WalRuntimeGrant.Context context, Runnable task) {
        mailbox.add(task);
        queued.countDown();
      }
    };
    try (WalActorContinuationGate gate =
           WalActorContinuationGate.fromTrustedRuntime(CONTEXT, runtime)) {
      gate.register(CONTEXT, CompletableFuture.completedFuture(6), (v, e) -> {
        throw new AssertionError("stale guest callback was executed");
      });
      waitFor(queued, "deferred actor task missing");
      Runnable action = mailbox.poll(5, TimeUnit.SECONDS);
      check(action != null, "no deferred task");
      try {
        action.run();
        throw new AssertionError("deferred isCurrent error was suppressed");
      } catch (AssertionError expected) {
        check(expected.getMessage().contains("actor activation retired"),
              "wrong actor error");
      }
      check(gate.isPoisoned(), "deferred activation failure left gate healthy");
    }

    // A guest callback that fails after successful admission also poisons
    // the gate. No subsequent receipt may be turned into a durable ACK.
    LinkedBlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
    CountDownLatch emitted = new CountDownLatch(1);
    var runtime2 = new WalActorContinuationGate.RuntimeQueue() {
      public boolean isCurrent(WalRuntimeGrant.Context context) { return true; }
      public void dispatch(WalRuntimeGrant.Context context, Runnable task) {
        callbacks.add(task);
        emitted.countDown();
      }
    };
    try (WalActorContinuationGate gate =
           WalActorContinuationGate.fromTrustedRuntime(CONTEXT, runtime2)) {
      gate.register(CONTEXT, CompletableFuture.completedFuture(7), (v, e) -> {
        throw new AssertionError("injected actor callback failure");
      });
      waitFor(emitted, "actor callback task missing");
      Runnable action = callbacks.poll(5, TimeUnit.SECONDS);
      check(action != null, "no actor callback task");
      try {
        action.run();
        throw new AssertionError("actor callback failure swallowed");
      } catch (AssertionError expected) {
        check(expected.getMessage().contains("injected actor callback"),
              "wrong callback error");
      }
      check(gate.isPoisoned(), "failed actor callback left gate healthy");
      try {
        gate.register(CONTEXT, CompletableFuture.completedFuture(8), (v, e) -> {});
        throw new AssertionError("gate admitted callback after actor failure");
      } catch (RejectedExecutionException expected) { }
    }
  }

  private static void boundedQueuePoisons() throws Exception {
    CountDownLatch occupied = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    var runtime = new WalActorContinuationGate.RuntimeQueue() {
      public boolean isCurrent(WalRuntimeGrant.Context ctx) { return true; }
      public void dispatch(WalRuntimeGrant.Context ctx, Runnable task) {
        occupied.countDown();
        try {
          if (!release.await(5, TimeUnit.SECONDS))
            throw new RejectedExecutionException("test dispatch gate timed out");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new RejectedExecutionException(interrupted);
        }
      }
    };
    try (WalActorContinuationGate gate =
           WalActorContinuationGate.fromTrustedRuntime(CONTEXT, runtime)) {
      try {
        gate.register(CONTEXT, CompletableFuture.completedFuture(0), (v, e) -> {});
        waitFor(occupied, "dispatch worker never reached saturation barrier");
        for (int i = 0; i < WalActorContinuationGate.MAX_PENDING; ++i)
          gate.register(CONTEXT, CompletableFuture.completedFuture(i), (v, e) -> {});
        check(gate.queuedNotices() == WalActorContinuationGate.MAX_PENDING,
              "pending queue did not fill to hard bound");
        gate.register(CONTEXT, CompletableFuture.completedFuture(99), (v, e) -> {});
        check(gate.isPoisoned(), "33rd pending notice escaped bounded admission");
        try {
          gate.register(CONTEXT, CompletableFuture.completedFuture(100), (v, e) -> {});
          throw new AssertionError("poisoned gate accepted a further notice");
        } catch (RejectedExecutionException expected) { }
      } finally {
        release.countDown();
      }
    }
  }

  public static void main(String[] args) throws Exception {
    earlyAndLate();
    staleDeliveryFailsClosed();
    rejectedRuntimePoisons();
    callerRunsRejected();
    uncheckedRuntimeErrorPoisons();
    deferredActorFailurePoisons();
    boundedQueuePoisons();
    System.out.println("HOST_WAL_ACTOR_CONTINUATION_GATE_PASS (not Oreslang guest)");
  }
}
