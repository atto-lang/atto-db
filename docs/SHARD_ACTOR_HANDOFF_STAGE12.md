# Stage 12 — runtime actor-turn handoff for host WAL publication

**Status: draft host-only integration source.** No authentic Oreslang guest scheduler registration, no authenticated tenant/isolate runtime grants, no crash-safe recovered activation, no durable guest ACK. The only authority to publish later must come from the **trusted actor runtime**, never from a source-actor DATA message or numeric metadata.

## Motivation and hardening changes

The stage-11 shard publication gate had to be invoked manually by a test driver after waiting for WAL sync. Doing this in a guest actor would risk blocking an actor carrier and bypassing nonreentrancy and generation fencing. Stage 12 introduces a host-only `ShardActorHandoffBridge` that encapsulates that asynchronous handoff:

1. The **trusted runtime** creates a `WalRuntimeGrant.Context` and an exclusive, preopened, confined FD. `WalRuntimeGrant.isBoundTo(ctx)` now requires exact Context object identity *and not revoked*; equivalent tenant/isolate/shard/generation numbers are not sufficient.
2. The runtime injects a generation-aware `WalActorContinuationGate.RuntimeQueue` and an **independent** `ShardPublicationGate.RuntimeAuthority` that positively attests the current serialized actor turn. These are deliberately NOT sourced from `.ores` DATA, not guest-callable factories, and not enough without actual backend scheduler/runtime wiring.
3. `appendOnActorTurn` preflights through the stage-11 shard gate and submits one immutable frame through the real `WalRuntimeGrant.appendAndSync`. It exposes **no future or receipt to the guest**, no await, and no ACK. Its continuation is registered with `WalActorContinuationGate`; early and late completions only enqueue a bounded notice off-carrier.
4. When the *runtime executor* actually admits the later serialized actor turn, it rechecks generation and either (a) marks the in-flight write UNKNOWN after an error, or (b) verifies the private `SyncedBoundary` against full staged identity, SHA-256 and WAL end offset, then updates the host-local published index.
5. The shard's diagnostic UNKNOWN phase is volatile for host-side observation; all index mutation must still occur only on the serialized actor turn. Any stale activation, revoked grant, lost/poisoned callback queue, failed registration, caller-runs scheduler, or unexpected post-dispatch throw must not allow a stale read, retry or guest-visible commit. `ShardActorHandoffBridge` fails closed; `ShardPublicationGate` now distinguishes the production primitive's documented **definitive pre-admission** errors from unexpected exceptions that may occur *after* dispatch. The latter quarantine the shard as UNKNOWN instead of restoring OPEN and risking sequence re-use.
6. `retireFromTrustedRuntime` closes the continuation handoff, makes the bridge unusable, and returns the existing **asynchronous** WAL revoke/close stage. The runtime must observe its completion instead of closing the FD directly. Late stage-completion callbacks cannot publish.

## Tests and exact execution boundary

`ShardActorHandoffBridgeTest` uses an actual temporary WAL and the production `WalFilePrimitive`, `WalRuntimeGrant`, `WalActorContinuationGate` and `ShardPublicationGate` classes **when** its GitHub workflow executes. The fake actor scheduler in this test is a Java 21 **single-thread virtual executor**, deliberately not the true Oreslang actor scheduler.

It covers:
- One PUT, overwrite and DELETE, all invisible until a subsequent authorized actor turn processes real host sync evidence
- Same-numeric-ID **different Context** admission denial and revoked-grant denial
- Fresh-values copy isolation, ordered sequence, tombstone history and single-writer admission
- A generation retired before completion delivery: no callback publication, no retry and no reads after unknown outcome
- A mailbox rejecting before handoff: no callback publication or unsafe fallback to caller-runs
- Asynchronous lifetime-pin/shutdown and never any `canAcknowledgeGuest() == true`

A **separate local OpenJDK 21.0.12.1 synthetic-grant smoke harness** compiled the byte-exact GitHub sources for `ShardPublicationGate.java`, `WalActorContinuationGate.java` and `ShardActorHandoffBridge.java` (`git hash-object` matched all three SHA pins), with substitute test-only WAL grant and primitive. `javac -Xlint:all -Werror` and **16 checks × 5 consecutive runs PASS** on real temporary files: ordered PUT/DELETE, no early publication, actor-thread confinement, foreign grant denial, late/retired denial, generation retirement and rejected mailbox quarantine. This **does not compile** production `WalRuntimeGrant.java` or `WalFilePrimitive.java` and is **not full production integration**. Exact local stdout: `LOCAL_SYNTHETIC_SHARD_BRIDGE_SMOKE_PASS checks=16`.

The `.github/workflows/db-shard-actor-handoff.yml` job checks the exact source HEAD plus immutable blob hashes for **all six production/test files**, strict Java21 `javac -Xlint:all -Werror`, 5 rounds of byte-exact real-file host tests, and Java/Javac executable SHA256. A GitHub run with `steps=[]` supplies **no execution evidence**. The source organization has previously been blocked before runner allocation. Cross-organization private mirroring still requires an **independent approval for this exact new Git tree** under issue #15, including destination collaborators and retention. Prior PR authorization requests cannot be reused as approvals.

**No result can be called passing production integration until the exact latest test executes.** Earlier stage-11 results used a separate synthetic grant harness, not the full real source chain. No source transferred across orgs in stage 12.

## Next true native runtime gates

- Java/GraalVM and LLVM issue authenticated, unforgeable non-DATA-sendable `StorageHandle` values from actor runtime context with lifecycle pins and exclusive WAL FD custody. Separate producer/consumer tests must assert no guest-supplied path or FD is trusted.
- The **actual** Oreslang `DbShardActor` must own sealed state and route completions as CONTROL/runtime messages through the real scheduler with nonreentrant message turns. No suspension inside `critical-read` or `critical-write`.
- Add startup recovery with authenticated replay checkpoint and persistent operation-ID ledger. A restart after fsync but before acknowledgment reconciles UNKNOWN instead of blindly retrying.
- Verify copy-on-write page snapshot and index image hash, generation-fenced checkpoint write with file fsync, atomic replacement and parent directory fsync, and separate-process power-loss fault injection.
- Pin and compile real `.ores` guest positives/negatives and run Java/GraalVM and LLVM differential evidence.

**No guest PUT/DELETE/GET, DB ACK, SQL, MVCC or LLVM runtime parity is implemented by this stage. Keep the PR draft; no release or merge claim.**
