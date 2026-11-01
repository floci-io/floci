#!/usr/bin/env bash
# Smoke-tests one standalone floci-aws binary before it is published.
#
#   smoke-binary.sh <binary> <expected-version>
#
# Starts the binary on a free port with in-memory storage and a scratch data
# directory, then checks that:
#   1. /_floci/health answers and reports <expected-version> (never "dev"),
#   2. SQS and DynamoDB round-trip through the AWS CLI (no Docker needed),
#   3. SIGTERM stops it cleanly within the grace period (skipped on Windows,
#      where Git Bash cannot deliver a console signal to a native process).
# Requires bash, curl, Python and the AWS CLI. Exits non-zero on the first failure.
set -euo pipefail

BIN=${1:?usage: smoke-binary.sh <binary> <expected-version>}
EXPECTED=${2:?usage: smoke-binary.sh <binary> <expected-version>}
GRACE_SECONDS=${SMOKE_GRACE_SECONDS:-30}

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) WINDOWS=true ;;
    *) WINDOWS=false ;;
esac

# Windows runners ship python, not python3.
PYTHON=$(command -v python3 || command -v python)

free_port() {
    "$PYTHON" -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()'
}

PORT=${SMOKE_PORT:-$(free_port)}
WORK=$(mktemp -d)
LOG="$WORK/floci.log"
ENDPOINT="http://127.0.0.1:$PORT"
PID=""

cleanup() {
    if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
        kill -9 "$PID" 2>/dev/null || true
    fi
    rm -rf "$WORK"
}
trap cleanup EXIT

fail() {
    echo "FAIL: $*" >&2
    # The first exception of a failed startup is at the top of the log and its cause chain at the
    # bottom, so a long log shows both ends.
    if [ "$(wc -l < "$LOG" 2>/dev/null || echo 0)" -le 200 ]; then
        echo "--- floci log ---" >&2
        cat "$LOG" >&2 || true
    else
        echo "--- floci log (first 100 lines) ---" >&2
        head -n 100 "$LOG" >&2 || true
        echo "--- floci log (last 100 lines) ---" >&2
        tail -n 100 "$LOG" >&2 || true
    fi
    exit 1
}

aws_local() {
    AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=us-east-1 AWS_PAGER="" \
        aws --endpoint-url "$ENDPOINT" "$@"
}

chmod +x "$BIN" 2>/dev/null || true
echo "smoke: $BIN on port $PORT, expecting version $EXPECTED"

FLOCI_STORAGE_MODE=memory \
FLOCI_STORAGE_PERSISTENT_PATH="$WORK/data" \
FLOCI_PORT="$PORT" \
QUARKUS_HTTP_PORT="$PORT" \
    "$BIN" > "$LOG" 2>&1 &
PID=$!

for _ in $(seq 1 120); do
    if curl -sf "$ENDPOINT/_floci/health" > "$WORK/health.json" 2>/dev/null; then
        break
    fi
    kill -0 "$PID" 2>/dev/null || fail "the binary exited before answering /_floci/health"
    sleep 0.5
done
[ -s "$WORK/health.json" ] || fail "/_floci/health did not answer within 60s"

VERSION=$("$PYTHON" -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$WORK/health.json")
[ "$VERSION" = "$EXPECTED" ] || fail "/_floci/health reports version '$VERSION', expected '$EXPECTED'"
echo "ok: version $VERSION"

QUEUE_URL=$(aws_local sqs create-queue --queue-name smoke --query QueueUrl --output text) \
    || fail "sqs create-queue"
aws_local sqs send-message --queue-url "$QUEUE_URL" --message-body hello > /dev/null || fail "sqs send-message"
BODY=$(aws_local sqs receive-message --queue-url "$QUEUE_URL" --query 'Messages[0].Body' --output text) \
    || fail "sqs receive-message"
[ "$BODY" = "hello" ] || fail "sqs round trip returned '$BODY'"
echo "ok: sqs"

aws_local dynamodb create-table --table-name smoke --billing-mode PAY_PER_REQUEST \
    --attribute-definitions AttributeName=id,AttributeType=S --key-schema AttributeName=id,KeyType=HASH \
    > /dev/null || fail "dynamodb create-table"
aws_local dynamodb put-item --table-name smoke --item '{"id":{"S":"1"},"v":{"S":"world"}}' || fail "dynamodb put-item"
VALUE=$(aws_local dynamodb get-item --table-name smoke --key '{"id":{"S":"1"}}' --query 'Item.v.S' --output text) \
    || fail "dynamodb get-item"
[ "$VALUE" = "world" ] || fail "dynamodb round trip returned '$VALUE'"
echo "ok: dynamodb"

if [ "$WINDOWS" = true ]; then
    echo "skip: graceful-shutdown check on Windows"
    exit 0
fi

kill -TERM "$PID"
for _ in $(seq 1 $((GRACE_SECONDS * 2))); do
    kill -0 "$PID" 2>/dev/null || break
    sleep 0.5
done
if kill -0 "$PID" 2>/dev/null; then
    fail "still running ${GRACE_SECONDS}s after SIGTERM"
fi
set +e
wait "$PID"
STATUS=$?
set -e
PID=""
# A JVM-style exit on SIGTERM is 143 (128 + 15); Quarkus exits 0 after a clean shutdown.
case "$STATUS" in
    0|143) echo "ok: stopped on SIGTERM (exit $STATUS)" ;;
    *) fail "exit status $STATUS after SIGTERM" ;;
esac
grep -q "stopped in" "$LOG" || fail "no Quarkus 'stopped in' line after SIGTERM"
echo "smoke passed: $BIN"
