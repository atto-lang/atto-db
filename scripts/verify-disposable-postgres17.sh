#!/usr/bin/env bash
# Local, private, disposable PostgreSQL 17 integration gate. Never targets an
# external database. Requires Docker, Maven, JDK 21, Git and sha256sum/shasum.
set -euo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo"
for tool in docker git java javac mvn openssl; do
  command -v "$tool" >/dev/null || { echo "missing required tool: $tool" >&2; exit 69; }
done
[[ "$(javac -version 2>&1)" == javac\ 21.* ]] || {
  echo "JDK 21 javac required" >&2; exit 69;
}
head="$(git rev-parse HEAD)"
[[ -n "${EXPECTED_HEAD_SHA:-}" && "$head" == "$EXPECTED_HEAD_SHA" ]] || {
  echo "set EXPECTED_HEAD_SHA to the exact reviewed 40-hex PR head" >&2; exit 69;
}
[[ "$head" =~ ^[a-f0-9]{40}$ ]] || { echo "invalid source commit pin" >&2; exit 69; }
[[ -z "$(git status --porcelain --untracked-files=all -- host-reference)" ]] || {
  echo "host-reference tree contains local changes or untracked files" >&2; exit 69;
}
echo "source_commit=$head"
echo "source_tree=$(git rev-parse HEAD^{tree})"

# The workflow source and fixture files are always taken from tracked Git paths.
# macOS ships Bash 3.2, which does not provide mapfile.
sources=()
while IFS= read -r -d '' source; do
  sources+=("$source")
done < <(git ls-files -z -- 'host-reference/src/dev/oreslang/db/*.java' 'host-reference/test/dev/oreslang/db/*.java')
[[ "${#sources[@]}" -ge 9 ]] || { echo "incomplete Java source inventory" >&2; exit 69; }
workdir="$(mktemp -d)"
container=""
cleanup() {
  if [[ -n "$container" ]]; then docker rm -f "$container" >/dev/null 2>&1 || true; fi
  rm -rf -- "$workdir"
}
trap cleanup EXIT
docker info >/dev/null || { echo "Docker daemon unavailable" >&2; exit 69; }
container="ores-pg17-$(openssl rand -hex 8)"
test_password="$(openssl rand -hex 16)"
docker run -d --rm --name "$container" \
  -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD="$test_password" \
  -e POSTGRES_DB=postgres -p 127.0.0.1::5432 postgres:17 >/dev/null
port_mapping="$(docker port "$container" 5432/tcp)"
port="${port_mapping##*:}"
[[ "$port" =~ ^[0-9]{2,5}$ ]] || { echo "invalid loopback Docker port" >&2; exit 69; }
for _ in {1..60}; do
  if docker exec "$container" pg_isready -h 127.0.0.1 -U postgres -d postgres >/dev/null 2>&1; then break; fi
  sleep 1
done
docker exec "$container" pg_isready -h 127.0.0.1 -U postgres -d postgres >/dev/null
echo "postgres_container_image=$(docker image inspect postgres:17 --format '{{.Id}}')"
echo "java_compiler=$(javac -version 2>&1)"
for source in "${sources[@]}"; do
  if command -v sha256sum >/dev/null; then sha256sum "$source"
  else shasum -a 256 "$source"; fi
done
mkdir -p "$workdir/classes" "$workdir/jdbc"
javac -d "$workdir/classes" "${sources[@]}"
for test in PoolLimitsTest ActorPgPoolTest JdbcRollbackSafetyTest JdbcTransactionSafetyTest; do
  java -cp "$workdir/classes" "dev.oreslang.db.$test"
done
mvn -B -ntp -f host-reference/pom.xml dependency:copy-dependencies \
  -DincludeScope=runtime -DoutputDirectory="$workdir/jdbc"
for jar in "$workdir"/jdbc/*.jar; do
  [[ -f "$jar" ]] || { echo "PostgreSQL JDBC dependency absent" >&2; exit 69; }
  if command -v sha256sum >/dev/null; then sha256sum "$jar"
  else shasum -a 256 "$jar"; fi
done
export ORES_TEST_PG_URL="jdbc:postgresql://127.0.0.1:$port/postgres?connectTimeout=10&socketTimeout=40"
export ORES_TEST_PG_ADMIN_USER=postgres
export ORES_TEST_PG_ADMIN_PASSWORD="$test_password"
java -cp "$workdir/classes:$workdir/jdbc/*" dev.oreslang.db.PostgresIntegrationTest
java -cp "$workdir/classes:$workdir/jdbc/*" dev.oreslang.db.PostgresPrincipalIsolationTest
echo "DISPOSABLE_PG17_HOST_AND_RLS_EXECUTION_PASS source_commit=$head"
