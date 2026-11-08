# atto-db

Database access abstractions and persistence infrastructure for AttoLang.

## Responsibilities

Portable database interfaces, transactions, connection lifecycles, and error reporting.

## Design guidelines

- Keep application-facing contracts separate from hosted Atto services.
- Explicitly specify error, cancellation, concurrency, and resource-lifecycle behavior where applicable.
- Document supported compiler backends, host restrictions, wire formats, and compatibility constraints.

## Development and verification

Use the source tree and CI for verified build/test commands. Tests should cover positive cases, invalid inputs, lifecycle behavior, and cross-backend interoperability where relevant.

## Status

This README describes the project's intended role. It does not imply that the listed APIs or platforms are already implemented or supported.

## Related

- [AttoLang](https://github.com/atto-lang)
- [Language specification](https://github.com/atto-lang/atto-spec)
- [Platform conformance](https://github.com/atto-lang/atto-platform-conformance)

## Repository ownership and contribution routing

**Belongs here:** Portable database contracts, connection/transaction abstractions, persistence interfaces and owned async resource lifetimes.

**Belongs elsewhere:** PostgreSQL-specific ORM mappings in atto-postgres-orm.atto; commercial hosted database service in atto-co; compiler I/O primitives in backend repos.

**Security and portability boundary:** Document WAL/recovery consistency, async cancellation, tenant isolation, quotas and credential handling; never leak borrowed buffers across actor boundaries.

AttoLang is the public name; preserve existing Oreslang/.ores compatibility and use canonical .atto for new portable library source. This README defines scope, not feature readiness.

**Acceptance:** name exact source SHA/tree, runtime/toolchain, positive and negative checks and execution receipt before claiming support. Host-only tests and empty CI steps do not prove guest execution.
