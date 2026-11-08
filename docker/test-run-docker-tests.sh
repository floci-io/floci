#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
LOG_FILE=$(mktemp)
trap 'rm -f "$LOG_FILE"' EXIT

export DOCKER_TEST_LOG="$LOG_FILE"
export FAIL_RUN_SUITE=sdk-test-node
export FAIL_BUILD_SUITE=

docker() {
  if [[ "$1" == compose && "$2" == up ]]; then
    printf 'compose-up\n' >> "$DOCKER_TEST_LOG"
    return 0
  fi

  if [[ "$1" == compose && "$2" == ps ]]; then
    printf 'floci-container\n'
    return 0
  fi

  if [[ "$1" == inspect ]]; then
    printf '172.20.0.2\n'
    return 0
  fi

  if [[ "$1" == build ]]; then
    suite="${!#}"
    suite="${suite#compatibility-tests/}"
    printf 'build:%s\n' "$suite" >> "$DOCKER_TEST_LOG"
    [[ "$suite" != "$FAIL_BUILD_SUITE" ]]
    return
  fi

  if [[ "$1" == run ]]; then
    suite="${!#}"
    suite="${suite#compat-}"
    printf 'run:%s\n' "$suite" >> "$DOCKER_TEST_LOG"
    [[ "$suite" != "$FAIL_RUN_SUITE" ]]
    return
  fi

  printf 'unexpected docker command: %s\n' "$*" >&2
  return 1
}

curl() {
  return 0
}

export -f docker curl

if bash "$REPO_ROOT/docker/run-docker-tests.sh"; then
  status=0
else
  status=$?
fi

if [[ "$status" -eq 0 ]]; then
  echo "expected a failed suite to produce a non-zero exit status" >&2
  exit 1
fi

expected_suites=(
  sdk-test-python
  sdk-test-node
  sdk-test-java
  sdk-test-go
  sdk-test-awscli
  compat-cdk
  compat-terraform
  compat-opentofu
)

for suite in "${expected_suites[@]}"; do
  grep -qx "run:$suite" "$LOG_FILE" || {
    echo "expected suite to run after failure: $suite" >&2
    exit 1
  }
done

: > "$LOG_FILE"
export FAIL_RUN_SUITE=

bash "$REPO_ROOT/docker/run-docker-tests.sh" || {
  echo "expected all successful suites to produce a zero exit status" >&2
  exit 1
}

: > "$LOG_FILE"
export FAIL_BUILD_SUITE=compat-cdk

if bash "$REPO_ROOT/docker/run-docker-tests.sh"; then
  status=0
else
  status=$?
fi

if [[ "$status" -eq 0 ]]; then
  echo "expected a failed image build to produce a non-zero exit status" >&2
  exit 1
fi

for suite in "${expected_suites[@]}"; do
  if [[ "$suite" == "compat-cdk" ]]; then
    grep -q "run:$suite" "$LOG_FILE" && {
      echo "failed image build should not run suite: $suite" >&2
      exit 1
    }
    continue
  fi
  grep -q "run:$suite" "$LOG_FILE" || {
    echo "expected suite to run after failed image build: $suite" >&2
    exit 1
  }
done

# A subset with FLOCI_START=0 runs only the named suites and never starts compose.
: > "$LOG_FILE"
export FAIL_BUILD_SUITE=

FLOCI_START=0 COMPAT_SUITES="sdk-test-java compat-cdk" bash "$REPO_ROOT/docker/run-docker-tests.sh" || {
  echo "expected the selected suites to pass" >&2
  exit 1
}

grep -q "compose-up" "$LOG_FILE" && {
  echo "FLOCI_START=0 must not start compose" >&2
  exit 1
}
for suite in sdk-test-java compat-cdk; do
  grep -qx "run:$suite" "$LOG_FILE" || {
    echo "expected selected suite to run: $suite" >&2
    exit 1
  }
done
for suite in sdk-test-python sdk-test-node sdk-test-go sdk-test-awscli compat-terraform compat-opentofu; do
  grep -q "run:$suite" "$LOG_FILE" && {
    echo "unselected suite must not run: $suite" >&2
    exit 1
  }
done

# An unknown suite name is refused before anything runs.
: > "$LOG_FILE"
if COMPAT_SUITES="sdk-test-rust" bash "$REPO_ROOT/docker/run-docker-tests.sh" 2>/dev/null; then
  echo "expected an unknown suite name to be refused" >&2
  exit 1
fi
grep -q "run:" "$LOG_FILE" && {
  echo "an unknown suite name must not run anything" >&2
  exit 1
}

echo "run-docker-tests.sh: all checks passed"
