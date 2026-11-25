# Stage 14 — fail-closed exclusive WAL advisory leases

This is **a narrow hardening PR** stacked on stage-13 PR #21, not evidence of a durable native Oreslang database. It fixes a concrete host admission gap: stage13 rejected duplicate `FileChannel` **object identities** but could accept two separately opened descriptors referencing the **same WAL inode** under different shard keys.

## Change

- `DbShardActivationRegistry.activateFresh` calls **nonblocking** `FileChannel.tryLock()` for a full exclusive OS advisory lock while holding the in-process registry mutex. It happens **before** inspecting WAL size and before issuing the host WAL grant. An overlapping JVM lock becomes `RejectedExecutionException`; a separate process holding the inode lock returns null and is also rejected. Lock failures do not reserve generations or release another owner's state.
- After successfully taking the lock, the registry enforces a **fresh empty WAL**. A rejected nonempty file or failed host admission releases the just-acquired lock in a `finally` block. The lock object is retained inside the opaque activation and is *not* exposed to guests or actor DATA.
- `retireAsync` still fences the shard immediately and revokes/awaits the WAL primitive's asynchronous disk and handoff workers. **Only once the writer reports completed close** does the registry verify `!descriptor.isOpen()` and `!exclusiveLease.isValid()`; otherwise retirement fails and the slot remains permanently quarantined rather than being reused with a live lease.
- The OS lock must be **advisory**. Uncooperative writers, writable hardlinks/symlinks, an FD already shared by an untrusted process, kernel/platform-specific lock behavior, and runtime-issued tenant/isolate authority are outside this layer's guarantees. Real Graal/LLVM runtimes must supply descriptor-relative path confinement, FD custody, durable activation generations and correct platform lock semantics.

## Executed local tests

`DbShardActivationRegistryTest` now covers both **in-process distinct-descriptor inode aliasing** and **independent JVM lock contention**. A child Java process acquires the WAL lock, prints `LEASE_READY`, and holds it until the parent releases it. While the child owns the lock, the parent must reject shard activation. After the child exits, a new activation with the same generation is admitted, proving a failed lock attempt did not consume the epoch.

Other added cases: no lease left behind after trusted runtime denies admission; a lock acquired on an active shard excludes a separately opened FileChannel; lock release after async retirement; nonempty-WAL rejection releases its lock; read-only WAL cannot acquire an exclusive writer lease. Existing stage13 tests still exercise actor-turn fencing, actual host PUT using a synthetic WAL grant, duplicate generation denial, retirement and permanent stale invalidation.

Local **OpenJDK 21.0.12.1** strict `javac -Xlint:all -Werror`: **33 assertions per run × 5 fresh JVM rounds = 165/165 PASS**, actual temporary OS files and a forked JVM lock holder. Exact stdout:
`HOST_SHARD_ACTIVATION_REGISTRY_PASS assertions=33 (host-only runtime harness; NOT native guest or durable recovery)`.

**Evidence boundary:** these locally executed tests include the exact GitHub `DbShardActivationRegistry.java` and `DbShardActivationRegistryTest.java` blobs, but **the other five production host classes are replaced locally by separately authored synthetic stand-ins**. This is an OS lease behavior and registry test, not production end-to-end WAL actor code verification. The SHA-pinned `.github/workflows/db-shard-activation-registry.yml` must execute **all seven real GitHub classes** with nonzero steps to establish that next boundary; a zero-step workflow is no test.

## Promotion / CI controls

The source organization has previously failed to allocate GitHub Actions runners. An alternate private org's **source-free** JDK21 success demonstrates runner admission at one point in time, not remaining budget or private-source transfer approval. [Security issue #15](https://github.com/ores-truffle-oreslang/oreslang-db/issues/15) requires an independent non-author reviewer to approve the **new exact source HEAD/tree and private destination/access/retention policy** before copying any private DB Git objects to another organization. No such approval has been observed, so this PR must not mirror private sources across orgs.

**Remaining critical work:** true Java/Graal and LLVM runtime capability issuance and FD confinement; source-org or independently approved alternate exact-head production compilation; genuine compiled guest `.ores` sealed-state actor; authenticated WAL recovery and persistent operation-ID reconciliation; verified fsync/checkpoint replacement/dirsync with crash injection. No guest committed ACK, DB availability or release claim. Keep this PR **DRAFT**, do not merge.
