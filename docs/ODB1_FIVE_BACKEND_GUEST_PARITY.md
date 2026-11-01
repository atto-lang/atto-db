# ODB1 five-backend guest codec parity — authored gate, not executed

Eight source-identical native Oreslang guest cases are now declared by `ci/verify-native-wal.sh`:
1. Existing exact independent 32-byte PUT round-trip.
2. Existing torn, corrupted, wrong-kind, wrong-identity and length rejections.
3. Existing full-size 1118-byte, signed-limb checksum and 31-byte DELETE bounds.
4. **New** signed-int32 maximum identities and four high-bit-u32-forgery rejection cases.
5. **New** every one of the 32 ODB1 wire bytes independently incremented, all 32 must fail decode; accidental checksum protection only, not a MAC.
6. **New** exact PUT/DELETE minimum frame and 64-byte key/1024-byte value maximum; 65-byte key, 1025-byte value, empty key and DELETE payload refusal.
7. **New** seven syntactically malformed complete headers with Adler32 recomputed after tampering (version, kind, high-bit identity, key/value length and magic), proving structural rejection independent of checksum mismatch.
8. **New** caller-owned mutable key/value arrays do not change encoded bytes; mutating encoded bytes after decode cannot alter decoded copies.

**Promotion gate:** `ci/verify-five-backend-wal-parity.sh` demands independently pinned exact OresDB commit/tree and five independently SHA256-attested compiler adapter executables: Java/Graal, LLVM, WASM, Cranelift and .NET. Every adapter must accept the identical `.ores` guest source and compile-check plus execute it with byte-exact stdout and no newline. Missing adapter, unsupported syntax, unsigned CLI semantics, missing binary hash, nonzero compiler exit, absent guest oracle or any skipped backend fails the entire matrix. A compiler's native Wasm or NativeAOT packaging must be bound by an adapter to its produced binary digest. **Do not simply run a host reference program and mark a native backend as passed.** Cranelift currently has limited scalar support, so it is a known parity gap until its actual frontend/stdlib/module runtime executes these fixtures.

Five targets × eight cases = **40 required compiler-backed guest executions**; no backend has been credited yet. This change authors source and test gates, but CI source org may fail runner admission with `steps=[]`. Do not claim OresDB durable WAL/ACK/OS fsync or runtime-capability parity from a codec fixture. The live engine requires separately verified generation-fenced actor runtime and replay under each backend. Private-source cross-org transfer remains blocked pending independently approved exact-tree and destination scope in [issue #15](https://github.com/ores-truffle-oreslang/oreslang-db/issues/15). KEEP DRAFT.
