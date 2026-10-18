# oreslang-db

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
