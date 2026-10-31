# Actor-shared PostgreSQL host reference

This is a JDBC host adapter demonstration, not native Oreslang guest execution. Actors receive revocable, host-issued tenant capabilities rather than direct TCP connections. Each JDBC owner controls exactly one connection; fixed pool limits, per-tenant round-robin, bounded asynchronous completion, read/write templates, transaction-local tenant context, queued-only cancellation and revocation are covered by deterministic tests. `cancel()` returns `false` once a JDBC owner has claimed a job; cancelling an in-flight write must never be reported as a successful abort. Revocation after dispatch reports an indeterminate outcome, not proof of rollback.

**Local Java 21 tests**
```sh
javac -d build/pg-host host-reference/src/dev/oreslang/db/*.java host-reference/test/dev/oreslang/db/*.java
java -cp build/pg-host dev.oreslang.db.PoolLimitsTest
java -cp build/pg-host dev.oreslang.db.ActorPgPoolTest
```

**Real PostgreSQL proof** requires an isolated disposable PostgreSQL server, test-only admin credentials and a PostgreSQL JDBC JAR. The workflow supplies a PostgreSQL 17 service, provisions a fresh RLS role and schema, exercises 1000 logical clients across 8 sockets, checks tenant isolation and update authorization, then drops its test objects. Do NOT run the test against production. Results only count as passing when a GitHub job reaches the Java test and prints `ACTOR_PG_REAL_1000_CLIENT_8_CONNECTION_RLS_PASS`.

Admission additionally caps the aggregate estimated parameter payload at 1 MiB per submission (not 1 MiB per parameter) and rejects extreme decimal scales. This is a host-reference bound, not a complete heap-usage bound; concurrency budgets, credential isolation, transport timeouts and server-side resource limits still apply.

**Local execution without GitHub Actions** (requires a running Docker
daemon, Docker's PostgreSQL 17 image, JDK 21, Git, OpenSSL and Maven/JDBC
dependency access):

```sh
# From the repository root after independently reviewing and checking out
# the exact PR-head commit; do not substitute an unreviewed floating branch.
EXPECTED_HEAD_SHA=<reviewed-40-character-commit> \
  bash scripts/verify-disposable-postgres17.sh
```

The script refuses a mismatched commit or dirty/untracked host-reference
tree, starts **only** a freshly created ephemeral PostgreSQL 17 Docker container
with a random password bound to loopback, compiles tracked Java sources,
records Git tree/source hashes, runs four host test programs and both real
PostgreSQL integration tests, and removes the container afterward. It never
takes an external PostgreSQL URL or production credentials as input. An
inaccessible Docker daemon/JDBC dependency is a *not executed* result, not
evidence of PostgreSQL correctness. This script does not execute Oreslang
guest code.

**Database-principal isolation experiment (not wired into ActorPgPool):**
`PostgresPrincipalIsolationTest` creates two fresh, unrelated LOGIN roles on a
disposable PostgreSQL 17 instance. It uses `current_user` in a FORCE RLS policy
to test that cross-tenant reads and updates return zero rows; the roles cannot
`SET ROLE` to one another or modify row ownership, and changing
`ores.tenant_id` must not alter access. Run it separately from
`PostgresIntegrationTest` because the latter still uses **one shared DB role
and a spoofable tenant GUC**. These two tests have different security claims.
The separate-role test exercises database SQL/roles; it does **not** establish
per-tenant pool ownership or prove guest integration.

CI pins the pull request's **head SHA**, verifies the clean checked-out Git
tree, prints Java source digests and runs both PostgreSQL tests in separate
steps. A failed job with `steps=[]` is admission failure, not test evidence.
The two database tests must run only against the disposable GitHub PostgreSQL
service or a dedicated disposable local instance with CREATE ROLE privileges.

Important restrictions: no Oreslang guest binding, GraalVM or LLVM backend integration yet; no true async protocol, pipeline or streaming result protocol. JDBC blocks dedicated host worker threads, not the Oreslang scheduler; its driver socket timeout must also be configured. Arbitrary guest SQL and credential export are forbidden. The test RLS policy depends on a transaction-local custom GUC set by the trusted host. PostgreSQL custom GUCs are settable by a client with the database credential: this does **not** independently authenticate a tenant if that role or SQL-template registry is compromised. Production requires a non-spoofable tenant identity boundary (e.g. distinct least-privilege database roles or an audited privileged tenant binding) and adversarial proof. Unknown COMMIT results must not be retried blindly.
