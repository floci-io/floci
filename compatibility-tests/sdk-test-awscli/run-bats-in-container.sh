#!/usr/bin/env bash
set -euo pipefail

report_dir="$(mktemp -d /tmp/bats-junit-XXXXXX)"
trap 'rm -rf "$report_dir"' EXIT

set +e
# --no-parallelize-within-files: bats-core defaults to running tests in parallel
# both across files and within a file when --jobs > 1. Several tests in this
# suite share state across tests in the same file via setup_file/teardown_file
# (e.g. ses.bats, s3-notifications.bats), which races ordering-dependent tests.
# Cross-file parallelism is preserved.
/opt/bats-core/bin/bats --jobs 4 --no-parallelize-within-files \
    --report-formatter junit -o "$report_dir" test/
status=$?
set -e

if [ -f "$report_dir/report.xml" ]; then
    mv "$report_dir/report.xml" /results/junit.xml
fi

exit "$status"
