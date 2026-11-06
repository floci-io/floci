#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 2 ]; then
    echo "Usage: $0 <test-path> <junit-xml-path> [bats-args...]" >&2
    exit 1
fi

TEST_PATH="$1"
JUNIT_XML_PATH="$2"
shift 2

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BATS_BIN="$REPO_ROOT/lib/bats-core/bin/bats"

if [ ! -x "$BATS_BIN" ]; then
    echo "Error: bats-core not found at $BATS_BIN. Run 'just setup-bats' first." >&2
    exit 1
fi

mkdir -p "$(dirname "$JUNIT_XML_PATH")"
REPORT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/bats-junit-XXXXXX")"
trap 'rm -rf "$REPORT_DIR"' EXIT

set +e
"$BATS_BIN" --report-formatter junit -o "$REPORT_DIR" "$@" "$TEST_PATH"
BATS_STATUS=$?
set -e

if [ ! -f "$REPORT_DIR/report.xml" ]; then
    echo "Error: expected JUnit report at $REPORT_DIR/report.xml" >&2
    exit 1
fi

mv "$REPORT_DIR/report.xml" "$JUNIT_XML_PATH"
exit "$BATS_STATUS"
