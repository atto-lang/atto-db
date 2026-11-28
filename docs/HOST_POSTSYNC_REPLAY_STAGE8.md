# Stage 8: JVM process-kill / read-only candidate recovery (host only)

**Draft; no database commit, guest, or power-loss durability evidence.** Stacked on DB PR #14's bounded `ReadOnlyWalReplay` inspector. Does not edit production compiler, runtime, WAL writer, actor or guest code.

## Executed local evidence
- Java 21 `javac -Xlint:all -Werror` compiled new test with the exact GitHub `ReadOnlyWalReplay.java` blob `a0db92f5c916590d79c9e0e7d087d29e9e081f5a`.
- New test blob `193e949ad54be5911c324f1c79a0d1252b309984` is byte-identical to the locally compiled/tested source.
- 18 checks PASS in five complete successive local runs, exact stdout `HOST_WAL_POSTSYNC_CRASH_REPLAY_PASS checks=18 (JVM SIGKILL and read-only candidate replay; NOT guest, DB commit, or power loss)`.
- Separate JVM writes independently formed ODB1 PUT and DELETE records through a real host `FileChannel`, calls `force(true)`, prints a post-force marker, remains alive, and is killed forcibly by the parent before any hypothetical database ACK. Parent reopens the file read-only and verifies complete sequence, operation IDs, kinds and bytes.
- A checksum mutation to the complete post-kill WAL is rejected without automatic repair or truncate.
- Second child is killed **before sync**; its on-disk bytes are left unknown/quarantined, not interpreted as durable even if page cache shows them.

## Explicit boundaries
This test is NOT using `WalFilePrimitive` or `WalRuntimeGrant`; it directly invokes JDK `FileChannel` for the producer. Separate PR #13 covers the host primitive and callback handoff. It does not prove a physically durable transaction, actor scheduler integration, guest compiler, runtime-owned non-DATA-transferable handle, parent-directory fsync, Oreslang/LLVM parity, power-loss persistence, or durable operation idempotency across checkpoints. `ReadOnlyWalReplay` returns candidate records only and is not sufficient to publish an index.

## Security and CI
- `ci/verify-host-postsync-crash-replay.sh` requires an independently supplied source commit/tree, JDK executable hashes, clean checkout and exact byte-oracle output.
- Source-local pull-request workflow uses pinned checkout and Temurin 21, compares exact PR head and both source Git blobs. Any GitHub runner with zero executed steps is an admission failure, not a host test result.
- Do not mirror this private tree across organizations without an independent explicit approval of the exact tree/destination/retention policy. No secrets or external checkout tokens.
- Keep PR draft. Next P0 is an authenticated runtime-issued WAL authority, guest `.ores` execution and SIGKILL-after-sync-before-ACK recovery driven by the **real Oreslang actor** with persistent idempotency.

## 2026-10-31 upstream reconciliation

Source blob is now the updated stage-7 read-only verifier SHA `8da48ec37b5a2ab8cb141c2403669fce6ffb6024`, which adds a defensively-copied snapshot SHA-256 fingerprint. Exact-blob recheck plus local OpenJDK 21 `-Xlint:all -Werror` compile and 3/3 independent stage-8 process-kill/replay runs passed against the updated **actual** scanner blob. No Oreslang guest or durable ACK claim. PR #16 is carried onto the upstream stage-7 Git tree using a two-parent non-force merge commit, preserving the original stage-8 history.
