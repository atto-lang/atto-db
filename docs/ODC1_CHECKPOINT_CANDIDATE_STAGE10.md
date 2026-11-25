# Stage 10: ODC1 dual-slot checkpoint **candidate** inspection

This repository is still a **draft native Oreslang database**. The new host-only `ReadOnlyCheckpointManifest` is a read-only validator for proposed checkpoint slot metadata; it is deliberately NOT a checkpoint writer, proof of fsync, owner credential, or authorization to serve a read/ACK. It must not be used for repair/truncation or source-actor DATA mail.

## Motivating designs

- **Rust redb/LMDB**: copy-on-write trees and snapshot publication are separate from mutable builder state. OresDB should eventually use immutable page generations; this stage validates metadata **only**, and offers neither MVCC nor copy-on-write pages.
- **C++ RocksDB**: WAL replay and indexed state require a deterministic restart boundary; never infer durable commit from an in-memory index mutation.
- **SQLite WAL**: single writer, bounded log/checkpoint interactions, explicit reader end marks; checkpoint starvation must not cause unbounded WAL growth.
- **C# Orleans**: persistent state is independent of actor activation. A stale generation must not publish, and an uncertain write is reconciled with its original operation ID, not blindly retried.
- **Kotlin Exposed/R2DBC**: make asynchronous storage boundaries explicit; no suspension inside `critical-write`, no blocking carrier.

## Experimental ODC1 fixed-width slot format (100 bytes, little endian)

| Offset | Length | Field | Condition |
| --- | ---: | --- | --- |
| 0 | 4 | ASCII `ODC1` | exact |
| 4 | 1 | version | 1 |
| 5 | 3 | reserved | all zero |
| 8 | 4 | signed tenant ID | > 0 |
| 12 | 4 | signed shard ID | > 0 |
| 16 | 4 | signed activation generation | > 0 |
| 20 | 4 | signed last sequence | > 0 |
| 24 | 4 | total WAL bytes | 31..1,048,576 |
| 28 | 4 | applied operation count | 1..4,096 and <= last sequence |
| 32 | 32 | SHA-256 of entire checked WAL file | byte equality only |
| 64 | 32 | SHA-256 of canonical index snapshot | opaque, not verified yet |
| 96 | 4 | CRC32C over bytes 0..95 | accidental damage only |

This provisional format is *not* a stable persistence contract, not a signed/authenticated manifest, and lacks the canonical index-image writer/validator. A malicious actor with file write access can forge CRC32C and hashes. Never treat these bytes as a capability.

## Read-only candidate selection

1. The caller supplies three **distinct preopened FileChannels** (`A`, `B`, `WAL`) and trusted tenant/shard metadata. The caller must separately establish exclusive descriptor custody, path confinement and runtime generation identity.
2. Validate the **entire ODB1 WAL** using existing stage-7 `ReadOnlyWalReplay.inspectPreopened` first. Any corrupt frame, sequence gap, duplicate operation, ambiguous tail or bounds violation rejects the scan. No partial index publication.
3. Each slot is either exactly 100 bytes with valid version/reserved/CRC32C/metadata, or it is treated as an invalid candidate. Empty, torn or corrupt inactive slots do not authorize truncation or recreation.
4. Valid slots with foreign tenant or shard, sequence ahead of WAL, generation ahead of WAL, or same-sequence conflicting generation **fail closed**; they are not silently bypassed because a second slot looks valid.
5. A full-match candidate must have the **exact replayed sequence, generation, complete file byte size, operation count, and entire WAL SHA-256**. Older historical slots may coexist but cannot independently authorize recovery of a newer WAL.
6. If both slots match but disagree on the index snapshot SHA-256, reject ambiguity. If identical, choose slot A deterministically.
7. Return a defensively copied, immutable `Candidate` for *diagnostics only*. No `publish`, `commit`, `ack`, `repair`, `truncate`, or `create` functions.

The reader is not race-safe against same-length concurrent modifications; a trusted runtime must hold an exclusive file lease and prevent writes during inspection. A checksum and SHA-256 do not certify power-loss persistence or eliminate rollback attacks.

## Verified host tests

Local Java 21 `javac -Xlint:all -Werror` + real `FileChannel` files, actual stage-7 replay class from matching GitHub source SHA `8da48ec37b5a2ab8cb141c2403669fce6ffb6024`.

- 37 checks per run; five consecutive rounds PASS.
- Both new source and test uploaded with byte-exact locally computed Git blob SHAs: reader `a3ad714942edecbc8030a52685f62deeae80aaf7`; test `7d16e128fd6ac80bb98a3133f00707f93155e519`.
- Exact stdout: `HOST_ODC1_CHECKPOINT_CANDIDATE_PASS checks=37 (host-only read-only candidate; NOT guest, commit, lease or power-loss proof)`.
- GitHub-hosted source runner evidence is separate: any job with `steps=[]` is **NOT EXECUTED**.
- No Oreslang `.ores` guest compiled or executed by this host test; no LLVM parity proven.

## Next P0: a real engine boundary

The next stage must implement (1) runtime-issued generation-fenced, non-DATA-sendable storage ownership in Java/Graal and LLVM, (2) sealed Oreslang shard state and serialized writer, (3) durable WAL-before-index-before-ACK with explicit UNKNOWN reconciliation, (4) canonical on-disk snapshot writer and image digest verification, and (5) atomic checkpoint generation replacement **including directory fsync** and separate-process / fault-injected crash tests. Do not merge or release this candidate-only stage as a durable database.
