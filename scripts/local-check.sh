#!/usr/bin/env bash
# Local replacement for .github/workflows/ci.yml (Actions permanently off, owner 2026-10-30).
# Windows host: the DPAPI token-storage job runs natively (no skip allowed).
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew :latest:build
./gradlew :latest:test --tests "*AccountsTokenStorageTest*" --tests "*AccountClientIdTest*"
f=versions/latest/build/test-results/test/TEST-io.github.axolotlclient.modules.auth.AccountsTokenStorageTest.xml
test -f "$f" || { echo "no test report"; exit 1; }
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$f"
grep -q 'testcase name="windowsFileHoldsNoReadableToken' "$f"
if grep -A1 'testcase name="windows' "$f" | grep -q '<skipped'; then echo "DPAPI tests were skipped"; exit 1; fi
./gradlew :latest:runClientGameTest
echo "LOCAL CHECK: ALL GREEN"
