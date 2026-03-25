#!/usr/bin/env bash
# Common setup for all bats tests

# Load bats helper libraries from cloned repos
# BATS_TEST_DIRNAME is the directory containing the .bats file (bash/test/)
# We need to go up one level to bash/, then into lib/
load "${BATS_TEST_DIRNAME}/../lib/bats-support/load.bash"
load "${BATS_TEST_DIRNAME}/../lib/bats-assert/load.bash"

# AWS CLI configuration via environment variables
# AWS_ENDPOINT_URL is natively supported by AWS CLI v2
export AWS_ENDPOINT_URL="${FLOCI_ENDPOINT:-http://localhost:4566}"
export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-test}"
export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-test}"
export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-us-east-1}"

# Verify prerequisites
if ! command -v jq &> /dev/null; then
    echo "jq is required but not installed" >&2
    exit 1
fi
if ! command -v aws &> /dev/null; then
    echo "AWS CLI is required but not installed" >&2
    exit 1
fi
