#!/usr/bin/env bash
set -euo pipefail

report_dir="$(mktemp -d /tmp/bats-junit-XXXXXX)"
trap 'rm -rf "$report_dir"' EXIT

set +e
/opt/bats-core/bin/bats --report-formatter junit -o "$report_dir" test/
status=$?
set -e

if [ -f "$report_dir/report.xml" ]; then
    mv "$report_dir/report.xml" /results/junit.xml
fi

exit "$status"
