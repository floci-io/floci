#!/bin/bash
set -euo pipefail

# Environment setup for Docker container
export AWS_REGION=us-east-1
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-test}"
export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-test}"
export FLOCI_ENDPOINT="${FLOCI_ENDPOINT:-http://localhost:4566}"
export AWS_ENDPOINT_URL="$FLOCI_ENDPOINT"
export AWS_ENDPOINT_URL_S3="$FLOCI_ENDPOINT"

# CDK-specific: derive hostname and port from the endpoint URL.
read -r LOCALSTACK_HOSTNAME EDGE_PORT < <(python3 - "$FLOCI_ENDPOINT" <<'PY'
import sys
from urllib.parse import urlsplit

endpoint = sys.argv[1]
parsed = urlsplit(endpoint if "://" in endpoint else "//" + endpoint)
hostname = parsed.hostname
if not hostname:
    raise SystemExit(f"Cannot determine hostname from FLOCI_ENDPOINT: {endpoint}")
try:
    port = parsed.port or 4566
except ValueError as error:
    raise SystemExit(f"Invalid port in FLOCI_ENDPOINT: {endpoint}") from error
print(hostname, port)
PY
)
export LOCALSTACK_HOSTNAME EDGE_PORT

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Ensure bats is available
if [ ! -d "$REPO_ROOT/lib/bats-core" ]; then
    echo "Error: bats-core not found. Run 'just setup-bats' first."
    exit 1
fi

# Run bats tests
exec "$REPO_ROOT/lib/run-bats-with-junit.sh" \
    "$SCRIPT_DIR/test/" \
    "${BATS_JUNIT_XML:-$SCRIPT_DIR/test-results/junit.xml}"
