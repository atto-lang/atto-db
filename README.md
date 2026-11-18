# oreslang-db

## Oreslang to AttoLang migration (2026-11-18)

**AttoLang** (formerly **Oreslang**) is the current public language identity. This `ores-truffle-oreslang/oreslang-db` checkout retains old naming. Its AttoLang counterpart for new contributions is [atto-lang/atto-db](https://github.com/atto-lang/atto-db); this link **does not imply an in-place transfer, identical history, migrated issues/PRs or feature parity**. The old source and commands below remain useful as historical checkout documentation, not a guarantee of current language conformance.

**[atto-lang/atto-spec](https://github.com/atto-lang/atto-spec)** is the canonical language specification. The portable library source belongs in [atto-common-source](https://github.com/atto-lang/atto-common-source), shared positive/negative guest tests in [atto-platform-conformance](https://github.com/atto-lang/atto-platform-conformance), and compiler-specific implementations in `atto-source.*`. The language's current proposed direction includes canonical `.atto`, `snake_case`, `borrow!`/`take!`/`share!`/`copy!`/`copy!!`, [implicit borrow defaults](https://github.com/atto-lang/atto-spec/blob/main/language/implicit-argument-borrow-v1.md), [`define const` including permitted `os.read_env`](https://github.com/atto-lang/atto-spec/blob/main/language/file-module-const-v1.md), and [`<<<`/`>>>`/`ashr` shifts vs. `<<`/`>>` streams](https://github.com/atto-lang/atto-spec/blob/main/language/bitwise-and-stream-operators-v1.md). Check individual spec admission status; **a design target is not proof of execution**.

Keep legacy `.ores` fixtures, CLI/package/ABI names, and version-pinned commands where they document code still in this tree. Avoid Python in new AttoLang tooling/tests/CI; Rust support tools are preferred. Only claim cross-backend support with exact SHA/tree, target/toolchain, real executed positive and negative guest tests; report failed, unsupported and not-executed explicitly. Empty-step/host-only CI is not guest conformance.

**Status: design-only bootstrap.** This repository contains no Oreslang DB driver, query engine, compiled guest tests, or production database implementation yet.

## Integration contract

- Oreslang owns its DB-facing typed API and actor-confined connection/session ownership; database engine and wire protocol are adapters, not compiler intrinsics.
- Proposed upstreams: [Oreslang serialization and validation](https://github.com/ores-truffle-oreslang/oreslang-serialization-and-validation) for bounded codecs and schema refinements; [Java reference compiler](https://github.com/ores-truffle-oreslang/oreslang-source.java) for guest language/ownership semantics.
- Prepared statements and typed positional parameters must not interpolate untrusted data as SQL code.
- Transactions have explicit begin/commit/rollback states; cancellation/restart must close ownership and pending resources exactly once.
- Privileged capabilities and connections cannot be sent as ordinary DATA messages. Connection credentials are never logged or included in test fixtures.

## First executable milestone

1. Define Oreslang-native `DbError`, `DbValue`, `DbSession`, and bounded row-stream contracts under `src/`.
2. Add a deterministic fake-provider test only as **host/contract evidence**, clearly separated from actual guest compiled execution.
3. Pin a reference compiler commit; compile and execute a small prepared-select plus failure/rollback guest fixture, with positive and negative oracles.
4. Run real backend integration tests only against isolated disposable databases, never production credentials.

Tracked by the private [organization integration registry PR #79](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/79). No private source mirroring without independent approval.
