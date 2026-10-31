# Native WAL recovery: stage 1.7 host-only preflight

**Status: HOST-ONLY source and local execution evidence; not a native `.ores` database, no committed ACK.** This is a dependency of DB draft PR #8; do not merge or present as production durability.

## Implemented in backend drafts

- Java/GraalVM draft [#2362](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2362): `DbWalReplayV1.scan` uses one bounded 1118-byte buffer to read complete ODB1 frames and return an immutable scan report. `DbDurableWalFile.scanRecovery` exposes read-only scanning through the existing physically locked file owner.
- LLVM draft [#1232](https://github.com/ores-truffle-oreslang/oreslang-source.llvm/pull/1232): `DbWalReplayV1::scan` uses a bounded stack buffer and `pread`; `DbDurableWalFile::scan_recovery` runs it through its trusted `openat`-opened, `flock`-owned file. A visitor must stage any provisional changes until the *whole* scan passes.
- For stage 1, **one segment contains exactly one generation**. Its sequence begins at 1 and increases contiguously; operation IDs increase strictly. This is *not* support for arbitrary operation-ID reuse, multigeneration segments, snapshots, checkpoints, or compaction.
- Every complete frame must have correct ODB1 magic/version/kind, bounded key/value sizes, positive signed-31-bit tenant/generation/sequence/operation, and matching Adler32. Wrong tenant/generation, fully present corrupted records, duplicate operation IDs and sequence gaps fail closed.
- A short final header or frame is reported as an incomplete tail with the last complete offset. The scanner **never truncates** and returns **no permission to ACK**, promote a staging index or change on-disk bytes. A separate exclusive, authenticated recovery lease is required before any repair.
- Neither backend permits guest-supplied file descriptors/paths in a compiled database actor yet; no validated native `.ores` durable receipt/ACK exists. The `DbStorageActor` remains reject-all.

## Executed local host evidence (31 Oct 2026)

- Java: exact Git blob SHAs `DbWalReplayV1.java` `7aa518e3525cc70dbbd2bb36f554a025c6e42351`, `DbWalFrameV1.java` `dfe689a40cca7c52f3134d4ad23bfab634c572b5`, `DbDurableWalFile.java` `21b8fc43478c4852cc3c4833adf13f9fabf826f5`. SHA checked with `git hash-object`; Java 21 `javac --release 21 -Xlint:all -Werror` PASS. Separate host recovery harness PASS including fresh reopen, torn tail, full-frame corruption, duplicate/reordered op, sequence gap, cross-tenant mismatch, and locked owner. Existing frame and physical file host regression PASS.
- LLVM: `DbWalReplayV1.hpp` `6eaf33ca8c75bbf0ce04a6099e0be9c2cef12c16`, tracked test `add092f24eb6a83c69378bb50f6dde467ab6e8a8`, locked WAL file header `e137bf1838faba27d38cc067f460a2f53892d6df`, validated frame header `3d5964b1b09f1cee4b0469c12f8594b70558bf3f`. SHA verified by `git hash-object`. Tracked replay+independent reopening+locked owner test PASS under C++17 `-Wall -Wextra -Wpedantic -Werror -UNDEBUG` and ASan+UBSan with leak detection. Prior tracked strict validated-append and shard-grant tests still PASS under the same sanitizers.
- A test defect was exposed and repaired during local execution: after reopening an independent file descriptor, the torn-tail injection had to explicitly seek EOF. The corrected tracked test passed; only corrected evidence counts.

**Not executed:** full exact-tree JDK25/Maven/JUnit/Graal, full LLVM CMake/CTest/TSan, compiled Oreslang guest, authenticated storage-handle ABI, transaction staging/commit, post-ACK crash recovery, directory sync, fsync fault injection or power-cut.

## Alternative private CI capacity

A fresh **source-free** capacity canary in private `benefactor-cc/benefactor-infra`, run [41234531401](https://github.com/benefactor-cc/benefactor-infra/actions/runs/41234531401), concluded failure with `runner_id=0`, 0 executed steps. Previous same-day private canaries in `akrion-sim/akrion-sim-sync` and `agent-pontifex/agent-pontifex-docs`, as well as recent `voxletra-e2e` and `scintilla-run-e2e` runs, also did not admit runners. These failures alone do not prove billing exhaustion.

Because no private runner was proven to execute, **no private repository tree was mirrored to another organization**, no source ref changed for the purpose of testing, and no public runner was given private source. The source-free canary branch remains in the private carrier for auditing.

## Next required gates

1. Obtain an executing private runner (source-free canary with nonzero executed steps), mirror an exact private Git tree with per-blob SHA checks and no secrets, and execute the full-source backend tests.
2. Wire real authenticated tenant/shard/actor/incarnation capability issuance and trusted physical I/O retirement to the source-generated `.ores` actor; no await inside `critical-write`.
3. Verify WAL sync receipt binding to actual frame digest, sequence, tenant/shard and operation ID; introduce only a runtime-authenticated `Committed` outcome.
4. Add staged streaming apply with duplicate/idempotency history, checkpoint generation, atomic publication, parent dirsync, and invalid-middle/torn-tail crash oracles.
5. Independently prove `put -> sync -> ACK -> kill -> restart -> replay -> get` on Java/GraalVM and LLVM. Keep all draft PRs unmerged until then.
