#!/usr/bin/env bash
# Common setup for OpenTofu bats tests

TOFU_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# Load bats helpers - support both local and Docker environments
if [[ -d "${TOFU_DIR}/../lib/bats-support" ]]; then
    load "${TOFU_DIR}/../lib/bats-support/load"
    load "${TOFU_DIR}/../lib/bats-assert/load"
elif [[ -d "${TOFU_DIR}/lib/bats-support" ]]; then
    load "${TOFU_DIR}/lib/bats-support/load"
    load "${TOFU_DIR}/lib/bats-assert/load"
elif [[ -n "${BATS_LIB_PATH:-}" ]]; then
    load "${BATS_LIB_PATH}/bats-support/load"
    load "${BATS_LIB_PATH}/bats-assert/load"
else
    echo "Error: Cannot find bats-support/bats-assert libraries" >&2
    exit 1
fi

# Shared test helpers kept local to this module so Docker and local runs behave the same.
export FLOCI_ENDPOINT="${FLOCI_ENDPOINT:-http://localhost:4566}"
export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-us-east-1}"
export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-test}"
export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-test}"
export AWS_ENDPOINT_URL="$FLOCI_ENDPOINT"

aws_cmd() {
    aws --endpoint-url "$FLOCI_ENDPOINT" --region "$AWS_DEFAULT_REGION" --output json "$@"
}

json_get() {
    local json="$1"
    local path="$2"
    echo "$json" | jq -r "$path" 2>/dev/null || echo ""
}

# OpenTofu-specific helpers
create_state_backend() {
    # Create S3 state bucket if not exists
    if ! aws_cmd s3api head-bucket --bucket tfstate 2>/dev/null; then
        aws_cmd s3api create-bucket --bucket tfstate
    fi

    # Create DynamoDB lock table if not exists
    if ! aws_cmd dynamodb describe-table --table-name tflock 2>/dev/null | grep -q ACTIVE; then
        aws_cmd dynamodb create-table \
            --table-name tflock \
            --attribute-definitions AttributeName=LockID,AttributeType=S \
            --key-schema AttributeName=LockID,KeyType=HASH \
            --billing-mode PAY_PER_REQUEST
    fi
}

generate_backend_config() {
    cat > /tmp/floci-backend.hcl <<EOF
bucket = "tfstate"
key    = "floci-compat.tfstate"
region = "us-east-1"

endpoint                    = "${FLOCI_ENDPOINT}"
access_key                  = "test"
secret_key                  = "test"
skip_credentials_validation = true
skip_region_validation      = true
use_path_style              = true

dynamodb_endpoint = "${FLOCI_ENDPOINT}"
dynamodb_table    = "tflock"
EOF
}
