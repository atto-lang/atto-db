# Stage 17 — ODF1 two-slot persistent generation-fence **candidate**

This draft is **host-only**. It introduces a conservative on-disk generation *observation/fencing candidate*, not a complete runtime-authenticated fencing service, a recovered writer, a sealed .ores actor, or a guest ACK. It does not modify the existing **ODC1** checkpoint reader or the previous WAL/recovery code.

## Why ODF1 is distinct from ODC1

ODC1 is a read-only checkpoint *candidate* with an opaque index snapshot digest. It has no writer/atomic replacement protocol, trusted owner, isolate field, operation-ID history digest or persistent monotonic generation counter. Reusing ODC1 as an authorization record would silently promote a diagnostic into a credential.

Stage17 uses two **preopened 128-byte ODF1 metadata slots**, A and B, and a preopened ODB1 WAL descriptor. These are supplied by an independently trusted runtime; guest paths and actor DATA may never select them. The stage takes **three exclusive nonblocking advisory OS locks** in consistent order, scans the complete ODB1 WAL, builds the bounded Stage15 candidate index, and checks their SHA equality before accessing the slots. It NEVER repairs or truncates the WAL.

## ODF1 little-endian candidate bytes (128, experimental)

| Range | Field |
| --- | --- |
| 0..3 | ASCII `ODF1` |
| 4..7 | version 1 + three zero reserved bytes |
| 8..19 | positive tenant, isolate and shard IDs (three int32) |
| 20..27 | positive proposed generation; previous generation (0 initially) |
| 28..43 | last WAL generation, WAL sequence, WAL bytes, and operation count |
| 44..51 | positive int64 monotonic candidate counter |
| 52..83 | SHA-256 of entire bounded ODB1 WAL |
| 84..115 | SHA-256 of canonical sequence/operation-ID pairs (little-endian int32 pairs) |
| 116..119 | reserved, must be all zero |
| 120..123 | little-endian CRC32C over bytes 0..119 |
| 124..127 | little-endian `ENF1` terminator |

A CRC32C + unkeyed SHA-256 detect accidental corruption and mismatched snapshots; they provide **no cryptographic authentication** against a storage writer, do not prevent malicious whole-file rollback, and do not establish tenant authority or power-loss durability. The ODB1 scanner bounds WAL to 1 MiB and 4096 records; the Stage15 index also enforces 128 historical key slots and a 128 KiB live-value budget. The candidate never exposes a live file descriptor to guest code.

## Fail-closed candidate transitions

- A and B empty: **uninitialized**, not a valid inspection. The first proposed generation must exceed the WAL's last generation; it writes only A, `force(true)`, re-reads the complete candidate, re-inspects the WAL, and returns a non-acknowledging observation.
- A valid counter 1 and B empty: acceptable; next higher generation writes B. B valid while A empty is rejected.
- Both valid: counters must be adjacent, and the higher counter's previous generation must equal the lower record's generation. The higher generation is selected; the older slot is the only permissible target of the next strictly increasing candidate. A torn, extra-length, corrupt, foreign or conflicting slot **blocks all promotion**, even if the other slot looks usable. Never silently repair the inactive slot.
- A candidate's WAL fingerprint, sequence, byte offset, observed record count, last WAL generation and operation-ID digest must match a complete new WAL inspection. No rollback, unknown WAL append, partial tail, duplicate ID, mutated valid checksum, or missing index state is accepted.
- The metadata write uses positional writes and calls `FileChannel.force(true)` on the selected pre-existing slot, then verifies the written bytes and entire WAL again while locks remain held.
- Any failure during or after a write is an **UNKNOWN candidate outcome**. No automatic rewrite, retry, guest ACK or writer grant is supported. All descriptors remain caller-owned, and OS advisory locks are released on method exit.

**Important:** ODF1 is *not* a process-spanning lifetime lease. The locks cover only the atomic host invocation and release afterward; a stale, already-running actor is not fenced without real runtime generation validation and permanent custody. Persistence of newly created files' directory entries and storage after real power loss is also not proven: a production protocol must include parent-directory `fsync`, a trusted root directory handle, an externally authenticated durable monotonic authority and crash-injected recovery. An attacker able to rewrite both metadata slots can forge CRC32C and hashes. All production writer and ACK entrypoints stay prohibited.

## Exact-source local verification

OpenJDK 21.0.12.1; strict `javac -Xlint:all -Werror`; all **four actual** GitHub source/test blobs (no synthetic host stand-ins):

| File | Git blob SHA |
| --- | --- |
| `ReadOnlyWalReplay.java` | `8da48ec37b5a2ab8cb141c2403669fce6ffb6024` |
| `ReadOnlyShardRecovery.java` | `5208e8503587eb69aeb908a62bfd223b32961583` |
| `ReadOnlyGenerationFenceLedger.java` | `c313446947defcfbcb3129061a2bea0d8e109b16` |
| `ReadOnlyGenerationFenceLedgerTest.java` | `f739bb4d8535492bb33a291ae6b039ba39fb5482` |

**20 fresh JVM runs × 46 assertions = 920/920 PASS**. Tests cover uninitialized state, first A, second B, third overwrite of older A, restart inspection and stale-epoch rejection, exact byte and digest copies, unchanged WAL, corrupt CRC/terminator/reserved data, torn secondary slot with no autorepair, foreign identity, checksum-invalid WAL, **checksum-valid same-size WAL mutation**, in-process same-inode alias, external JVM holding the slot lock, failed-admission lock cleanup, and a separate JVM that writes a new generation via `force(true)` and calls `Runtime.halt(0)`; a fresh process then observes the forced record and refuses the old generation. These are **process-termination** observations, not power-loss/directory durability or security authentication.

`.github/workflows/db-odf1-generation-fence.yml` pins the exact four Git blob SHAs and PR-head SHA, compiles under Java21 strict warnings, and repeats 5 real-file child-process fixtures with byte-exact stdout. If GitHub Actions reports `runner_id=null, steps=[]`, the hosted test has not executed.

## Next gate

1. Run the inherited **seven-class production WAL + actor handoff integration** and the new four-class ODF1 candidate on an admitted source-org or independently approved private alternate runner; inspect the exact executed steps and exit status. **Issue #15** still requires independent non-author scope approval before copying any OresDB private bytes to another GitHub org.
2. Keep a **persistent trusted generation owner** and a *lifetime* OS file lease across the real actor activation, not just the ODF1 write. Prove stale actor turn rejection, exact WAL append offset fencing, unknown outcome idempotency and reboot recovery with OS/process crash faults.
3. Implement real publisher/ACK protocol only after verifying `fsync`, atomic manifest replacement and directory sync, and then compile native `.ores` actor plus JVM/LLVM differential fixtures.

**This is not a durably writable DB. Keep DRAFT; no merge/release or alternate private-source transfer without approval.**
