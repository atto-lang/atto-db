# Oreslang DB WAL replay-window validator (stage 2, Issue #4)

**Unexecuted pure .ores source. NOT durable recovery.** This stacked
PR adds a bounded corruption/sequence/tenant validation stage after
the WAL v1 frame codec and native storage admission ledger. It deliberately
does not change the provisional StorageActor, runtime provider, filesystem,
authorization, or ACK behavior.

## Present contract

`src/wal/replay_window.ores` validates **at most 32 complete records / 35,776
bytes** in one caller-provided memory buffer. It retains 26-byte minimum
header validation, enforces full-frame checksum through `wal_frame.decode`,
exact sequence continuity, nondecreasing activation generation, exact tenant,
and duplicate operation-ID rejection **inside that window**.

Arguments `tenant`, `first_sequence` and `preceding_generation` must
come from *authenticated replay owner state*, not guest-controlled WAL fields.
An empty window has no effects.

The result contains only verified frame count, consumed bytes, last sequence,
last generation and `pending_tail`. It **never returns a committed index
mutation**, deletes/truncates bytes, promotes a durability watermark, grants a
filesystem capability, or issues a response. All even superficially valid
frames remain provisional until the trusted storage provider verifies sync
history; a checksum alone is not a durability receipt.

## Tail ambiguity: strict fail-closed rule

- A final suffix **shorter than the 26-byte full header** rejects with
  `unverified_short_tail`. It is not silently ignored.
- A validated header that declares a larger frame than the remaining buffer
  returns `pending_tail=true` and the byte offset of *complete preceding*
  records. This is **suspicious**, not authorization to truncate. A corrupted
  length can impersonate a torn tail, and a memory window end is not EOF.
- A full frame whose checksum fails is a hard error, including a full bad
  trailing frame. Corruption in any complete middle frame is a hard error.
- Before any physical truncation, a separate runtime-owned recovery actor
  must prove exclusive tenant/shard/generation ownership, actual EOF,
  independent durable watermark, and absence of a complete later frame.
  When proof is unavailable, quarantine and preserve bytes. This PR
  **never** performs that action.

## Explicit remaining risks/gates

1. This helper does **not** retain operation IDs across windows, WAL rotations,
   process restarts, or checkpoints. A durable idempotency ledger is mandatory
   before publishing any mutation or permitting re-use of an ID.
2. 35,776 bytes is a **single bounded window**, not a whole log. Boundary
   carryover (up to 1,117 bytes), validated continuation, strict stream EOF
   handling, and bounded streaming replay are NOT IMPLEMENTED.
3. The scanner does not validate a trusted sync receipt, authenticate an actor
   or provide physical tenant heap isolation.
4. No actual `.ores` compiler check or guest execution has occurred yet.
   `ci/verify-native-replay-window.sh` requires independently pinned source
   HEAD/tree and independently attested compiler binary SHA-256, then runs all
   inherited gates and both new native guest fixtures with byte-exact stdout.
5. Remaining executable tasks: runtime-owned async `append`, `sync_data`,
   `read_at`, `file_length`, checkpoint rename/dirsync, WAL owner, bounded
   KV index, cross-window dedupe and *real process crash/restart testing*.
   Keep PostgreSQL/JDBC, receipt inspection, guest execution and OS-level
   durability evidence strictly separate. Zero-step runner attempts are not
   test results.

Fixture oracles: `db:replay-window-ok` and
`db:replay-rejections-ok`, each without newline.
