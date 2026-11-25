# Stage 7 — fail-closed read-only WAL inspection (HOST ONLY)

Stacked on draft stage 6 (#13). This is deliberately **not** a running Oreslang database: no `.ores` compiler execution, guest capability, actor-index publication, commit, cross-generation lease, checkpoint, repair, or durable acknowledgment exists here.

## Concrete implementation

`ReadOnlyWalReplay.inspectPreopened(FileChannel, trustedTenant)` reads at most 1 MiB from a runtime-supplied preopened descriptor, validates a *whole* ODB1 stream, and returns an immutable candidate record list and a SHA-256 exact-byte snapshot fingerprint only after the entire stream is accepted. It does **not** open a path, write, truncate, close a descriptor, return filesystem authority, or manufacture a `SyncedBoundary` / `Committed` token. Only the trusted runtime may bind the numeric tenant to a currently authorized isolate, shard, actor generation, and exclusive file lease. An in-place external writer is not detected by size checks; using this API without exclusive descriptor custody is unsafe.

It requires sequence 1 to begin the full WAL and consecutive monotone sequence thereafter. Generation may increase but may not decrease. Operation IDs may not repeat anywhere in the bounded file, **including across generations**: this intentionally stronger first-version deduplication policy prevents replay aliasing but may reject older files built with weaker per-generation ID reuse. Size limit = 1,048,576 bytes, at most 4,096 records, 31–1,118 bytes/frame, key 1–64 bytes, value 0–1,024 bytes, PUT/DELETE only. ODB1 checksum is Adler-32 for accidental corruption, **not** cryptographic authenticity.

Every invalid complete frame fails; a short header or plausible incomplete frame is an error (no guessed truncation or silent skip). A malformed length might mimic a torn tail. Error offsets are diagnostics, not repair authorization. Unknown sync outcome remains **UNKNOWN**. The SHA-256 fingerprint is not an authenticated signature, a file lease, or an fsync/recovery certificate. Every returned byte array is a defensive copy, and no partial candidate list escapes on scan error.

## Local independently executed evidence

A local Java 21 `javac -Xlint:all -Werror` compile and standalone real-read-only-`FileChannel` regression ran successfully: `HOST_WAL_READONLY_REPLAY_PASS checks=67 (read-only diagnostic; not guest, lease, truncation, commit or crash proof)`. Tests cover empty and valid PUT/DELETE/generation progression, independent golden ODB1 vector, byte-copy defenses, corruption, mismatched tenant, gaps, reused operation ID, regression, ambiguous tails, all 4,096 records, a forbidden 4,097th, a 1 MiB + 1 byte WAL, and the 1,118-byte maximum frame. This local execution is **not** a verified GitHub exact-head execution. Reproduce against the PR HEAD and capture git commit/tree, JDK SHA-256, tool exit, output and nonzero executed job steps.

## Nonclaims / hard next gates

1. No `FileChannel.force(true)` or crash durability is established by read-only scanning; existing host append/sync tests have separate scope.
2. Do **not** pass this snapshot directly to a live shard. Recovery must verify trusted sync watermark, durable operation-ID ledger, exclusive lease, generation fencing, ambiguous post-crash suffix handling, and index publication under the owning actor's sealed critical state.
3. Integrate runtime-owned FD grants in Java/Graal and LLVM; pin, compile and run native `.ores` guest actor tests. Record genuine fault-injected process restart and fsync/dirsync outcomes, including failure between sync, index publication and ACK.
4. A subsequent WAL protocol version must define checkpoint boundaries and multi-key transactional semantics; ODB1 cannot provide atomic multi-key commit or MVCC by itself.
5. Keep the PR draft, do not merge/release or mirror private repo sources across organizations without explicit authorization. Zero-step Actions runs do not count as execution evidence.
