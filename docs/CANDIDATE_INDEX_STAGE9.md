# Stage 9 — bounded candidate replay index (native Oreslang source)

**Scope:** `src/shard/candidate_index.ores` implements a pure, in-memory key/value *candidate* index for ODB1 record frames. This is not a live actor-owned database, not a durable write path, and not a runtime-issued storage capability. The index is only a potential materialization target after an independently trusted, complete, exclusive-lease recovery scan. No method can issue an ACK or promote candidate data to committed state.

## Safety and resource contract

- Each input is decoded and checksum/length validated through `wal_frame.decode`; tenant must equal the index's supplied tenant metadata (which is **not authentication**).
- Sequence starts at 1 and is consecutive; generations may increase but not regress; operation IDs cannot repeat anywhere in this bounded index.
- Reject with no index state changes on corrupt frames, gap, owner regression, duplicate op ID, too many distinct key slots, an exceeded live-value budget or total WAL-byte budget.
- At most **1,048,576 cumulative WAL frame bytes**, **4,096 accepted records**, **128 distinct key slots** (tombstones keep their slots), and **131,072 live value bytes**. Each ODB1 key is 1–64 bytes and each value 0–1,024 bytes, inherited from the codec.
- Repeated PUT overwrites a key and accounts for the old live bytes; DELETE removes the live value but does not add a slot if the key is absent. Every stored byte list and every `candidate_get` result is independently copied.
- A failed frame must leave the sequence, operation ledger and index unmodified. A failed key-slot-limit frame does not consume its sequence or operation ID, enabling a corrected frame at the same position.
- `can_acknowledge()` always returns `false`. The API names `apply_candidate` and `candidate_get` intentionally avoid implying committed updates or reads.

## Proposed compiler-backed verification (not yet executed)

Run `ci/verify-native-candidate-index-stage9.sh` with an independently pinned Oreslang executable, compiler SHA-256, Git HEAD and tree pins. The script inherits the earlier WAL, replay-window, storage and transaction source/guest gates before checking this module and executing the two stage-9 fixtures. Expected exact (no trailing newline):

- `tests/guest/positive/candidate-index-lifecycle.ores` → `db:candidate-index-lifecycle-ok`
- `tests/guest/negative/candidate-index-rejections.ores` → `db:candidate-index-rejections-ok`
- `tests/guest/negative/candidate-index-wal-budget.ores` → `db:candidate-index-wal-budget-ok` (937 maximum-sized frames fit; the next does not; a smaller replacement at the same sequence and operation ID succeeds)

These fixtures are **source only until the actual Oreslang compiler executes them**. Host JVM tests, synthetic models or a clean syntax shell check do not constitute guest backend parity.

## Important limitations and next gates

1. This candidate index is not yet in a sealed `DbShardActor`. The next stage must let **only a runtime-authenticated actor owner** apply and publish a completed, independently attested WAL snapshot.
2. The scanner's SHA-256 digest proves only byte equality of one read snapshot. It does not authenticate tenant ownership, prevent in-place mutation without exclusive FD custody, prove `force(true)`, or authorize truncate/recovery.
3. Recovery must validate durable sync watermark, generation fences, persistent operation IDs, safe tail handling, and physical replay/checkpoint before exposing any read or ACK. Neither ODB1 nor this index commits an atomic multi-key transaction.
4. Java/Graal and LLVM runtime-issued FD capabilities, scheduler integration, crash/restart tests and native Oreslang guest compile/run are **unimplemented or unverified** at this stage.
5. Do not merge or release; keep this stacked PR draft. No private source may be mirrored across organizations without independent approval of the current exact source tree and destination access.

This is a narrowly scoped Oreslang-language foundation toward issue #4, not completion of that issue.
