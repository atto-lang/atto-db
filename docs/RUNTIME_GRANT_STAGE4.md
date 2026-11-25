# DB stage 4 — runtime-issued storage grant seam

Status: host-side Java 21 seam plus fail-closed .ores actor; **not a GraalVM/LLVM runtime registration**, not a native Oreslang execution receipt and not a durable database. This stage stacks on DB PR #10.

## Implemented

- `runtime/java/src/org/oreslang/db/primitive/WalRuntimeGrant.java`: package-private host seam. `Context` and `SyncedBoundary` have private constructors. A trusted runtime (not DATA) would issue a context after validating tenant, isolate, shard and actor-generation ownership. It attaches only an already opened, independently confined `FileChannel`; no guest path or FD accepted. It verifies exact context identity on submission, delegates to off-carrier bounded `append + force(true)` and returns only a host-only synced boundary after receipt-owner and frame-digest comparisons. Revocation fences submission and asynchronous completions. It never emits `Committed`, guest capability, or guest-visible receipt.
- `runtime/java/test/org/oreslang/db/primitive/WalRuntimeGrantTest.java`: Java 21 local host checks on a real file; foreign or numerically equal-but-distinct context rejection, stale context rejection, real append/force, defensive digest copying, ordered sequence, revoke, nonempty-WAL replay admission refusal and package-private visibility.
- `src/wal/wal_actor.ores`: provisional isolated actor with a single `state`, `handle_state(critical-write ...)` and fail-closed receive. No guest DATA can grant storage authority or deliver a `SyncedBoundary`. No ACK path exists.
- `ci/verify-host-storage-grant.sh`: exact source Git commit/tree and host Java/Javac hashes, runs inherited host gate and new grant tests. Native `.ores` compiler/guest gate remains separate and unexecuted.

## Mandatory compiler/runtime integration before this becomes a DB

1. **Java/Graal**: register an AOT-allowlisted *runtime-owned* non-DATA-transferable storage handle in the isolate, bind to verified actor activation generation, tenant and shard, and prohibit guest reflection/host interop access to `WalRuntimeGrant.issueContextFromTrustedRuntime` and any raw `FileChannel`. A package-private host class by itself is **not** an unforgeable Oreslang capability. Establish exclusive directory/FD custody with race-resistant directory-relative open, regular-file checks, no traversal/symlinks/hardlink confusion, per-tenant root and generation-fenced close. Require parent-directory synchronization on initial WAL creation.
2. **LLVM**: implement equivalent isolate-owned FD lifetime with lease/retirement pins, no guest numeric/filepath handles, off-carrier or nonblocking completion, and generation-fenced operation receipts.
3. **Oreslang actor**: preserve `state`/`handle_state`/`critical` ownership and actor non-reentrancy; issue append outside `critical`, `await` without a live mutable state loan, verify runtime-issued sync receipt upon resumption, then publish index state and only then ACK. Cancellation after dispatch returns `Unknown(op_id)` until durable reconciliation. Never permit a guest-created `SyncedBoundary` or numeric IDs to authorize ACK.
4. **Durability**: verify real WAL replay after restart, cross-window and cross-checkpoint idempotency ledger, EOF and durable watermark before torn-tail recovery, quarantine corrupt middle, snapshot temp write + fsync + rename + directory fsync, OS-process crash injection at each boundary. A successful `force(true)` call is not power-failure durability proof, and a host `SyncedBoundary` is not the database's durable commit proof.
5. **CI evidence**: independently pinned compiler and binary hash, pinned source HEAD/tree, all six `.ores` fixtures and WAL actor `--check`, actual nonzero executed Actions steps, plus local Java21 and LLVM evidence separately. Zero-step admission failures are not test results. Keep draft.

## Host grant caveats

- Issuance currently lives in the `org.oreslang.db.primitive` host package and is **not wired** to runtime-authenticated identity, scheduler or Graal guest boundary. Its identity check is necessary but not sufficient.
- The caller supplies an exclusive *preopened* file descriptor and must independently prove confinement, permissions, shard ownership and exclusivity; this host wrapper does not open or lock files.
- Future consumers must re-check runtime activation generation *when using the returned boundary*, because revocation may race with successful host completion. This seam does not make any guest publication decision.
- The host test is an execution of Java code only. No Java compiler-source parity or `.ores` guest proof follows from it.
