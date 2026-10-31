# Stage 8C: real host WAL primitive process kill and candidate replay

**Scope: Java host-only regression.** Real `WalFilePrimitive` asynchronous append and `FileChannel.force(true)`, separate child JVM hard-kill, and read-only `ReadOnlyWalReplay` inspection. No actual Oreslang actor, runtime-issued capability, guest ACK, checkpoint replay, live lease, parent-directory sync or power-loss evidence. ODB1 remains single-record; no multi-key transaction proof.

## Local executed evidence on exact source Git blobs (October 31, 2026)
- Host source `WalFilePrimitive.java` blob `044af806aefbffeb6ad440d403a10aa6b0e569aa`, scanner `ReadOnlyWalReplay.java` blob `8da48ec37b5a2ab8cb141c2403669fce6ffb6024`, prior writer-to-replay test blob `2887075e4be088aeef585d23eab79ac7971afc8c` were reconstructed and confirmed using `git hash-object`.
- OpenJDK 21.0.12.1 strict `javac -Xlint:all -Werror` PASS compiling all three files and new `WalPrimitiveProcessKillReplayTest.java` blob `3f1c0ac7acca841ba64de8679d06583b9c162dba`.
- Actual writer-to-replay real filesystem test: **16/16 checks in five consecutive local executions**. Exact stdout: `HOST_WAL_PRIMITIVE_REPLAY_PASS checks=16 (actual host WAL writer and read-only inspector; NOT Oreslang guest or DB ACK)`.
- Actual writer-process-kill test: **14/14 checks in five consecutive local executions**. Child receives a host receipt *after* `force(true)`, prints a marker, is forcibly terminated before hypothetical DB ACK, and parent validates original record and digest via read-only replay. Independent pre-sync child is killed inside a test-only `force` hook after physical append; its outcome remains UNKNOWN and is not promoted. Exact stdout: `HOST_WAL_PRIMITIVE_PROCESS_KILL_PASS checks=14 (real host WAL force/replay; NOT guest ACK, lease or power loss)`.
- This regression is independent of existing stage8 direct-FileChannel `WalPostSyncCrashReplayTest`; it exercises the **production host WAL primitive path**, not guest actor integration.

## CI and transfer gates
The stage8 host workflow now compiles and runs both the 16-assertion writer-to-replay suite and the 14-assertion JVM process-kill test; its source-blob anchors refer only to the inherited exact host producer/inspector blobs. GitHub Actions source-org runs may fail `steps=[]`; such jobs are not tests and must never be counted as passing. No private source was mirrored outside the source organization. Independent review/authorization of the exact commit+tree, private destination ACL and retention policy is still required by DB issue #15 before private cross-org mirroring.

**Not implemented or proven:** native Oreslang compiler/guest actor, opaque capability issuance, stable actor generation after async continuation, commit publication + ACK, OS power-loss recovery, durable operation-ID ledger and Java/LLVM runtime parity. Keep draft; no merge or release claim.
