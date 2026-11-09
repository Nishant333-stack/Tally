#!/usr/bin/env bash
# Build, then run the test suite against a local Postgres (creates/drops tally_test).
set -euo pipefail
cd "$(dirname "$0")/.."
./scripts/build.sh
java -cp build/classes:build/test-classes:lib/postgresql-42.7.4.jar dev.nishant.tally.TestKit
