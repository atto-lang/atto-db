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
EXPECTED_HEAD_SHA=YOUR_REVIEWED_40_HEX_SHA \
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

**Distinct-role pool path (host reference only):**
`ActorPgPool(Map<String, PrincipalBinding>, ...)` is an opt-in credential-fenced
constructor. A trusted host must bind each tenant to a different non-superuser
PostgreSQL login role and a role-specific DataSource. Unbound tenants and
duplicate expected roles are rejected. A JDBC owner **closes its checked-out
connection before switching tenants**, opens the new role's DataSource, and
checks server-provided `session_user` and `current_user` before **every**
database operation, including same-tenant reuse. A mismatch or DB error retires
that connection; no guest may provide credentials or create PrincipalBinding.

`ActorPgPrincipalPoolTest` is a synthetic host-provider test of same-tenant
reuse, close-before-switch, unbound/duplicate role rejection and poisoned-role
retirement. `PostgresPrincipalIsolationTest` creates two unrelated disposable
LOGIN roles, FORCE RLS on `current_user`, denies cross-tenant SELECT/UPDATE,
`SET ROLE` and ownership mutation, then exercises **1,000 logical pool
requests with at most eight simultaneously checked-out JDBC connections** and
two credential-bound DataSources. Both remain **unexecuted live PostgreSQL
fixtures** until an approved DB runner executes them.

This is distinct from `PostgresIntegrationTest`, which still exercises the
**legacy single-role pool** and a spoofable tenant GUC. Both APIs remain for
reference parity; callers must **never** treat the legacy single-role API as
independent cross-tenant database isolation. Credential separation additionally
requires DB-role membership restrictions (no cross-tenant `SET ROLE`), no
SUPERUSER or BYPASSRLS, no guest SQL registration, reliable credential
lifecycle/rotation, no untrusted DataSource configuration, and server-side
resource budgets. The host-only pool is still **blocking JDBC**, not an async
Oreslang-native driver, and cannot substitute for executed PostgreSQL/RLS proof.

CI pins the pull request's **head SHA**, verifies the clean checked-out Git
tree, prints Java source digests and runs both PostgreSQL tests in separate
steps. A failed job with `steps=[]` is admission failure, not test evidence.
The two database tests must run only against the disposable GitHub PostgreSQL
service or a dedicated disposable local instance with CREATE ROLE privileges.

Important restrictions: no Oreslang guest binding, GraalVM or LLVM backend integration yet; no true async protocol, pipeline or streaming result protocol. JDBC blocks dedicated host worker threads, not the Oreslang scheduler; its driver socket timeout must also be configured. Arbitrary guest SQL and credential export are forbidden. The test RLS policy depends on a transaction-local custom GUC set by the trusted host. PostgreSQL custom GUCs are settable by a client with the database credential: this does **not** independently authenticate a tenant if that role or SQL-template registry is compromised. Production requires a non-spoofable tenant identity boundary (e.g. distinct least-privilege database roles or an audited privileged tenant binding) and adversarial proof. Unknown COMMIT results must not be retried blindly.
