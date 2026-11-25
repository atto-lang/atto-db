# Unified 16-fixture ODB1 five-backend evidence gate

**Draft only — host-side verification infrastructure, not five-backend compiler parity.**

This PR is stacked on [OresDB #35](https://github.com/ores-truffle-oreslang/oreslang-db/pull/35) and reconciles the overlapping source-only changes in [#32](https://github.com/ores-truffle-oreslang/oreslang-db/pull/32) (guest receipt verification) and [#33](https://github.com/ores-truffle-oreslang/oreslang-db/pull/33) (post-execution source/binary revalidation). Both older branches expected **15** fixtures; #35 now requires **16 per backend** after adding the high-bit identity-forgery oracle.

## Security and correctness fixes

- The bounded `ci/assert-native-wal-receipts.sh` accepts **exactly 16** ordered fixture hash announcements, 16 ordered native execution receipts, one exact 16-case terminal marker, and the pinned DB Git HEAD/tree/compiler binary digest. It independently invokes `sha256sum` against **all 16** fixture paths, including the last `frame-highbit-identity-checksums.ores`. During audit, a stale `fixtureCount <= 15` bug was found and fixed: merely counting the 16th receipt but not independently hashing it is insufficient.
- Every configured backend invokes the real native WAL verifier into a bounded local receipt log. **Exit 0 alone cannot grant credit**. The independent receipt checker must pass before the coordinator publishes `BACKEND_GUEST_CODEC_PASS=<backend> cases=16`. Missing, duplicate, forged, wrongly ordered, oversized, or incomplete logs fail closed.
- The existing #35 **five independent physical checkout roots** and unique compiler binary digests are preserved. A shared backend source root, symlink alias, or the DB fixture repository masquerading as a backend is rejected.
- The DB source checkout, the active backend checkout, and the actual compiler binary are checked again **after guest execution**; a final five-backend iteration checks each compiler and backend source again before the terminal `cases=80` receipt. A guest modifying the last compiler or its source cannot earn a final 80-case claim.
- Host-only regressions now exercise the full 16-record receipt corpus and the coordinator, including deliberate last-backend source/binary mutation after otherwise valid mocked guest receipts.
- `ci/test-native-wal-receipts.sh` invokes the helper with `sh`, because a file created by the GitHub contents API has mode `100644` and is not directly executable. Audit caught and repaired the previous direct-exec error.
- The new 16th-fixture negative test originally used an overescaped `awk` regular expression, causing a syntax failure before exercising its checksum challenge. It now uses exact string matching and a verified checksum-forgery test.
- The existing five-backend ODB1 malformed-source positive/negative challenge and 16 shared fixtures remain unchanged.

## Exact local execution evidence (host only)

The four edited/new shell sources below were reconstructed byte-exactly in the local test container, with local `git hash-object` matching their actual GitHub blob SHAs. All passed `sh -n`:
- `ci/assert-native-wal-receipts.sh` — `a160561653b240b23529cbd85d6c6c80b0e167ac`
- `ci/test-native-wal-receipts.sh` — `7194156321a4689d710229c546f960aa1a76552a`
- `ci/verify-five-backend-wal-parity.sh` — `23252bd19ef148096cb16504f13345411c3b329f`
- `ci/test-five-backend-preflight.sh` — `ac6307c0c3fa92f96d42afd5cbefcf4bbc3b5b29`

Executed locally on synthetic/standalone fixture data (NOT the true DB fixture bytes or authentic Oreslang guest compiler artifacts):
- `sh ci/test-native-wal-receipts.sh`: **20 adversarial cases × 5 successive runs = 100 negative-case checks passed**; each run also verified one valid, 16-record receipt.
- `sh ci/test-five-backend-preflight.sh`: **5 successive complete host test suites passed**, including five distinct mock backend adapters, checkout identity failures, forged terminal output, and last .NET source/binary alteration.
- Independent local 16-fixture receipt matrix: **109/109 cases** passed, explicitly forging, omitting, or duplicating **each** fixture announcement and execution receipt, including checksum mutation of the final fixture.
- Separate independent coordinator harness: **8/8 scenarios** passed, including complete 80-case mock corpus, fake-only summary rejection, duplicate receipt, late source and compiler modification, recovery, and duplicate checkout roots.

**Important provenance boundary:** The source/test **scripts** are byte-exact GitHub copies. The local `.ores` fixture bytes, mock compiler binaries and source checkouts in these tests are SYNTHETIC. These results prove receipt/coordinator logic only; they **do not** prove a true Oreslang compiler can parse, build or execute any guest program.

## Promotion / private CI gates

- Source-org GitHub Actions has repeatedly failed with `runner_id=null, steps=[]`. A job without execution steps is runner-admission evidence only, never a compiler result.
- The private alternate `zed-pkg/zed-docs` has a fresh **SOURCE-FREE** Java21 [run 41234081313 attempt 6](https://github.com/zed-pkg/zed-docs/actions/runs/41234081313) with **8 executed successful steps**, runner `1000120914`. That shows an alternate job can be admitted; it does not prove remaining budget or authorize transferring private OresDB source.
- [Security approval issue #15](https://github.com/ores-truffle-oreslang/oreslang-db/issues/15) still requires independent non-author review of the **exact new commit/tree and selected private blobs**, destination collaborator visibility, no-secrets workflow, logs/artifact retention, cleanup and residual Git-object persistence. No private source has been copied to another GitHub organization.
- Production parity must use five independently built and SHA-pinned compiler artifacts and execute all 16 guest fixtures on each backend with checked stdout/exit/receipts. In particular, WASM guest I/O ABI and Cranelift/.NET feature coverage are not yet certified.
- The ODB1 codec is not a storage-authority grant, WAL durability proof, idempotency/UNKNOWN resolution, or committed guest ACK path. No DB release or merge is implied by the host tests.

Keep this PR **DRAFT** until hosted exact-head tests have actually executed and applicable review/branch-protection gates are satisfied.
