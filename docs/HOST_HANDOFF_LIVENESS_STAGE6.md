# Native DB WAL host handoff liveness hardening (draft)

Scope: **Java 21 host-only WAL primitive**, stacked on native DB stage-5 PR #12.
Not Oreslang guest execution, a runtime-issued storage capability, a
PostgreSQL wire protocol, crash recovery or proof of durable database ACK.

## The failure mode

The single WAL disk worker is correctly serialized, but the original *single*
completion handoff executor executes arbitrary synchronous CompletableFuture
continuations. If the callback for record 1 waits for the completion of record
2, then record 2 can finish append+force on the disk worker but cannot
complete its future: the only handoff worker is blocked inside callback 1.
This creates a circular wait even though two records were accepted.

The source fix changes **only receipt handoffs** to independently scheduled
Java 21 virtual threads. Physical WAL appends, syncs, and sequence checks
remain on one disk worker. The existing outstanding-admission slot stays
reserved through synchronous callback completion, so at most 32 callbacks
can be active per primitive. The handoff worker is not a global unbounded
executor. Callback thread names remain prefixed with `ores-db-wal-handoff-`.

`WalHandoffLivenessTest` uses explicit latches to prove the first
continuation is blocked waiting for the second accepted completion. The
second continuation must still execute, releasing the first. A single
handoff worker fails this deterministic bounded test. Test uses a real
FileChannel, positional WAL writes and `force(true)` through an isolated,
host-only I/O hook. This does not inject a crash.

## Verification

Run `ci/verify-host-wal-handoff-liveness.sh` in a clean exact-head checkout
with independently supplied `DB_EXPECTED_COMMIT`, `DB_EXPECTED_TREE`,
`JAVA_BIN`, `JAVAC_BIN`, `JAVA_SHA256`, and `JAVAC_SHA256`.
The wrapper executes inherited WAL primitive and runtime-grant host suites,
then the new Java 21 regression with a fixed stdout oracle and a deadline.

A static source change, a Java API probe, a synthetic checkout, and a
GitHub Actions run with zero executed steps **do not** establish a passing
exact-head host test. Retain actual stdout, exit codes, step IDs, tool
hashes, HEAD/tree, and source blob hashes. Keep the PR draft otherwise.


### 32-slot adversarial saturation test (executed locally)

`WalHandoffSaturationTest` attaches 32 synchronous callbacks before allowing
real FileChannel I/O to proceed. Every callback remains blocked at a separate
barrier after `force(true)`, so the owner must reject the 33rd operation even
when all 32 physical writes have completed. Once callbacks are released, the
33rd operation must be accepted, sync, and produce the expected 33-record WAL
length. This explicitly tests **slot ownership through callback completion**,
not just a configured queue capacity. It is a host-only bound, not an actor or
replay test.

The exact source `WalFilePrimitive.java` and `WalHandoffLivenessTest.java`
were locally compiled with JDK21 `-Xlint:all -Werror` after Git blob-SHA
readback; the liveness test passed 25/25 local runs. The new saturation fixture
passed 12/12 local executions against that byte-exact production source; its
uploaded Git blob was separately matched to the locally executed fixture.
GitHub runner evidence and all inherited host suites remain separate gates.


### Rejected handoff / emergency completion close race (new hardening)

When the normal handoff executor rejects a WAL receipt, its error completion
runs on a dedicated virtual thread. Previously the original outstanding slot
was released **before** that emergency callback ran, and the async close path
awaited only the normal handoff executor. Thus shutdown could resolve and
close the descriptor while the emergency future was still running user
callbacks. Receipt delivery no longer had the intended quiescence accounting.

The repaired primitive retains its outstanding slot through emergency
`completeExceptionally` and all synchronously invoked dependents; both
normal and emergency paths release slots in `finally` and notify the admission
monitor. After the disk and normal handoff executors have terminated, close
now waits for any remaining emergency-owned slots to quiesce (bounded 30s).
A timeout fails closed and **preserves the open file descriptor**, so its
owner must not publish a successful close or a durable ACK.

`WalEmergencyQuiescenceTest` uses a real `FileChannel.force(true)` before
shutting down only the normal handoff executor. Its emergency callback is held
at a deterministic latch while `revokeAndCloseAsync` runs. The test rejects
shutdown completion/FD closure before callback release, then requires prompt
closure after the callback finishes and checks preserved WAL bytes. The
standalone Java 21 script and GitHub workflow now include this fifth host suite
with a fixed exact-output oracle.

**Evidence distinction:** this new emergency-close test and revised primitive
are committed but have **not** yet been verified by a Java 21 source execution
receipt for this new PR head. Earlier four host-suite passes refer to the
previous source head. The source org's zero-step Actions admission failures
are not test evidence. Private alternate-runner execution requires independent
approval for its *new* source tree and access scope before any source transfer.

### Late subscribers on a completed CompletionStage: caller-carrier hazard

**P0 runtime integration rule:** returning `CompletableFuture.minimalCompletionStage()`
does **not** ensure callbacks run on the receipt handoff virtual thread.
If a caller registers synchronous `thenApply`, `handle`, or `whenComplete`
**after** that stage completed, Java 21 can execute the callback *inline on
the registering thread*. The host primitive's 32-slot callback bound only
covers callbacks invoked during the owned completion, not arbitrary late
continuations attached after the slot is released. Consequently, an actor
carrier registering a late blocking callback can block itself: the source
comment "no task runs on a caller/actor carrier" is not a blanket guarantee.

Independently executed a source-free OpenJDK 21 probe using the **same JDK
`minimalCompletionStage()` operation**: a completed stage's subsequent
`thenApply` ran on `main`, while `thenApplyAsync` with a dedicated
virtual-thread executor ran off that thread. This is *JDK behavior evidence*,
not execution of Oreslang DB's current source or a guest-side integration.

**Required before guest exposure:** never expose the raw Java completion
stage as an actor-facing suspension contract. The trusted runtime must
register each guest continuation onto a runtime-owned, bounded, authorized
scheduler (never a blocking actor carrier), check tenant/actor generation
at dispatch and immediately before any durable ACK, and handle registration
both before and after completion plus cancellation/shutdown. Using the
unqualified common-pool `thenApplyAsync` is not sufficient tenant authority.
A native guest test must cover both early and late callback registration.
This remains a non-waivable P0, not a reason to count source-free Java tests
as Oreslang guest proof.


### Host-only actor continuation gate: bounded non-inline registration

`WalActorContinuationGate` is a new **package-private HOST-ONLY** boundary;
it is NOT registered as a guest API and does not produce a DB committed ACK.
A completed `CompletionStage` may invoke `whenComplete` on the actor carrier
that registers it. The new gate performs only a nonblocking `ArrayBlockingQueue`
offer of a notice on that path (32 entries). A separate virtual dispatcher
queries a supplied trusted runtime scheduler and hands the continuation to
its actor mailbox; the continuation rechecks activation at actual execution.
If queue admission is exhausted or scheduling fails the gate is poisoned;
a synchronous CallerRuns-style runtime scheduler is quarantined rather than
executing guest code on the bridge dispatcher.

The current scheduler interface is a **host contract and mockable seam**,
not proof of integration into the Graal/LLVM isolate scheduler. Real runtime
integration must separately authenticate the caller and runtime-owned queue,
enforce bounded mailbox admission, nonreentrancy, generation fencing and
never expose the raw `CompletionStage` as a guest suspension handle.
The runtime must treat `gate.isPoisoned()` as fail-closed; no durable ACK
can follow dropped/rejected notices. An actor handler must never block
waiting for a continuation's own shutdown or for a future from this gate.

`WalActorContinuationGateTest` covers early and late completion delivery,
exact-context identity rejection, stale actor turns, executor rejection,
CallerRuns-style misconfiguration, and 33rd-notice overflow. For a **local
isolated Java21 source test**, Git blob hashes for the actual new source and
test matched the current GitHub blob hashes:
- source `fbe8d7c35602628a8cf002f8c009139b91a5c27b`;
- test `40052723624e137638d757047e939f65804d6bb1`.

Those two exact files compiled with `javac -Xlint:all -Werror` and the test
printed `HOST_WAL_ACTOR_CONTINUATION_GATE_PASS (not Oreslang guest)`
**10/10 times**. Only the referenced existing `Context` and `Owner`
types were supplied by minimal compile-time stand-ins for this isolated
test; the full current Java primitive/grant/host CI matrix was **not**
compiled or executed in this local test. The exact-HEAD GitHub Actions
runner in the source organization is still blocked at admission.


### Deferred actor-turn failure after successful scheduler admission (additional P0)

A two-phase scheduler check can succeed before a receipt is enqueued and
later throw when that continuation runs on the actor's own execution thread.
An exception thrown in this **later** turn is outside the virtual bridge
dispatcher's catch block. Previously, this lost the callback but left
`WalActorContinuationGate.isPoisoned()` false: fail-closed runtime
integrations could incorrectly treat the gate as healthy.

The actor-turn wrapper now catches `RuntimeException | Error` around its
second `runtime.isCurrent(context)` check **and the actual continuation**,
poisons the gate, then rethrows for the owning runtime to handle. It never
converts that failure into an authenticated storage receipt or ACK.

`WalActorContinuationGateTest.deferredActorFailurePoisons` covers both an
activation check that throws **after** queue admission and a guest callback
that throws after a successful activation check. It asserts the gate is
poisoned and refuses subsequent registration. The committed source blobs
were locally reconstructed and checked using `git hash-object`:
- `WalActorContinuationGate.java` `58a968257599c179a24974564c7519e30cc1338b`
- `WalActorContinuationGateTest.java` `e7bcf30f9c65e4cfa2ccae1d0adeb8fc04f78c1e`.

Both files compiled on local OpenJDK 21 with `-Xlint:all -Werror`
using only minimal existing-type stand-ins and the actual byte-exact new
source/test. **20/20 regression runs passed**. A red control reverting
just the deferred actor-turn catch **failed as expected** with
`AssertionError: deferred activation failure left gate healthy`.
This is local source-targeted Java evidence, **not** a complete exact-head
PR13 six-suite build, a guest scheduler test or a crash-durability claim.

## Stale-generation receipt drop is a poisoned gate (2026-10-31)

P0 fail-closed correction in `WalActorContinuationGate`: both the gate
dispatcher's initial `RuntimeQueue.isCurrent(context)` check and the
deferred actor-turn second check previously returned silently when stale.
This discarded a WAL result **without poisoning** a gate that might later
be used for committed-state publication or an ACK. An expired actor
activation is not proof that the underlying physical WAL append did not
occur. The gate now poisons itself on either stale-generation check,
refuses new registration and does not execute the unauthorizable callback.

`WalActorContinuationGateTest` covers stale generation before dispatch,
stale generation after scheduler enqueue, and rejection of subsequent
registrations. The *new* stale-actor assertion was first run against
the previous gate implementation and failed deliberately with
`AssertionError: stale generation silently discarded sync evidence`.
The corrected source and tests were then independently read back from
GitHub and matched against locally executed Git blobs:
- `WalActorContinuationGate.java`: `b3a12e76be3f77cddc05fd164d9beb462a995c84`
- `WalActorContinuationGateTest.java`: `b089da66b24b9a304370f8b454de412a891a7158`.

Local OpenJDK 21.0.12.1 compiled both unchanged GitHub files with
`javac -Xlint:all -Werror` against **minimal host-only stand-ins**
for their surrounding runtime types; **100/100 executions passed**
the `HOST_WAL_ACTOR_CONTINUATION_GATE_PASS (not Oreslang guest)` oracle.
This specifically validates the gate's local logic, **not** an exact-head
six-suite build with the real runtime, the real actor admission system,
a hosted source CI run or a database durability guarantee. The runtime
must still use a bounded scheduler with fresh actor/isolate authority
on every turn and separately fence the final durable ACK.

## Outstanding risks

- Client code that blocks *all* 32 receipt handoffs waiting for operations
  never admitted can still deadlock itself. The runtime must forbid blocking
  actor-carrier continuations and handle cancellation/retirement explicitly.
- A completion callback waiting synchronously for its *own*
  `revokeAndCloseAsync()` cannot be made safe by parallel handoff threads:
  close intentionally waits until owned callbacks quiesce. Never perform
  that wait inside an owned handoff callback; expose asynchronous chaining.
- The real runtime must authenticate tenant/isolate/shard/actor generation,
  protect exclusive FD custody, provide a non-DATA-sendable async capability,
  and validate authenticated sync receipts before any guest ACK.
- No native Oreslang compiler or storage execution receipt is generated by
  this host regression. WAL replay/idempotency and power-failure durability
  remain independent P0 gates.
