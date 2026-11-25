#!/bin/sh
# Fails closed without a verified source-provenanced Oreslang compiler.
set -eu
sh ci/verify-native-replay-window.sh
: "${ORESLANG_BIN:?independently attested compiler required}"
if ! timeout 20s "$ORESLANG_BIN" --check src/wal/wal_actor.ores; then
  echo 'WAL_ACTOR_SOURCE_CHECK_FAILED: parser/runtime implementation blocker' >&2
  exit 1
fi
echo 'WAL_ACTOR_CHECK_ONLY; NO_STORAGE_CAPABILITY_OR_SYNC_RECEIPT'
