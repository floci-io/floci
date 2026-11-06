#!/bin/bash
set -euo pipefail

# Runs the OpenTofu compatibility suite against a Floci already listening on
# FLOCI_ENDPOINT. Delegates to run-bats-in-container.sh, the same entrypoint the
# Docker image uses, so a local run and a CI run execute identical logic.

export AWS_REGION=us-east-1
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-test}"
export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-test}"
export FLOCI_ENDPOINT="${FLOCI_ENDPOINT:-http://localhost:4566}"
export AWS_ENDPOINT_URL="$FLOCI_ENDPOINT"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [ ! -d "$REPO_ROOT/lib/bats-core" ]; then
    echo "Error: bats-core not found. Run 'just setup-bats' first."
    exit 1
fi

# BATS_JUNIT_XML used to name a single file; the suite now writes one report per
# bats file, so the variable names the directory they land in.
export RESULTS_DIR="${RESULTS_DIR:-$SCRIPT_DIR/test-results}"

exec "$SCRIPT_DIR/run-bats-in-container.sh"
