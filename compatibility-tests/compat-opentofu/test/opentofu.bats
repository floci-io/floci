#!/usr/bin/env bats
# OpenTofu Compatibility Tests for floci

setup_file() {
    load 'test_helper/common-setup'

    cd "$TOFU_DIR"

    echo "# === OpenTofu Compatibility Test ===" >&3
    echo "# Endpoint: $FLOCI_ENDPOINT" >&3

    # Clean any previous state
    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* 2>/dev/null || true

    echo "# --- Setup: state bucket & lock table ---" >&3
    create_state_backend
    generate_backend_config

    echo "# --- tofu init ---" >&3
    run tofu init -backend-config=/tmp/floci-backend.hcl \
        -var="endpoint=${FLOCI_ENDPOINT}" -input=false -no-color
    if [ "$status" -ne 0 ]; then
        echo "# tofu init failed: $output" >&3
        return 1
    fi

    echo "# --- tofu validate ---" >&3
    run tofu validate -no-color
    if [ "$status" -ne 0 ]; then
        echo "# tofu validate failed: $output" >&3
        return 1
    fi

    echo "# --- tofu plan ---" >&3
    run tofu plan -var="endpoint=${FLOCI_ENDPOINT}" -input=false -no-color
    if [ "$status" -ne 0 ]; then
        echo "# tofu plan failed: $output" >&3
        return 1
    fi

    echo "# --- tofu apply ---" >&3
    run tofu apply -var="endpoint=${FLOCI_ENDPOINT}" -input=false -auto-approve -no-color
    if [ "$status" -ne 0 ]; then
        echo "# tofu apply failed: $output" >&3
        return 1
    fi
}

teardown_file() {
    load 'test_helper/common-setup'

    cd "$TOFU_DIR"

    echo "# --- tofu destroy ---" >&3
    tofu destroy -var="endpoint=${FLOCI_ENDPOINT}" -input=false -auto-approve -no-color || true
}

setup() {
    load 'test_helper/common-setup'
}

# --- Spot Checks ---

@test "OpenTofu: S3 bucket created" {
    run aws_cmd s3api head-bucket --bucket floci-compat-app
    assert_success
}

@test "OpenTofu: SQS queue created" {
    run aws_cmd sqs get-queue-url --queue-name floci-compat-jobs
    assert_success
    assert_output --partial "QueueUrl"
}

@test "OpenTofu: SNS topic created" {
    run aws_cmd sns list-topics
    assert_success
    assert_output --partial "floci-compat-events"
}

@test "OpenTofu: DynamoDB table created" {
    run aws_cmd dynamodb describe-table --table-name floci-compat-items
    assert_success
    assert_output --partial "ACTIVE"
}

@test "OpenTofu: SSM parameter created" {
    run aws_cmd ssm get-parameter --name /floci-compat/db-url
    assert_success
    assert_output --partial "jdbc:"
}

@test "OpenTofu: Secrets Manager secret created" {
    run aws_cmd secretsmanager describe-secret --secret-id "floci-compat/db-creds"
    assert_success
    assert_output --partial "floci-compat"
}

@test "OpenTofu: VPC created with custom DNS settings" {
    run aws_cmd ec2 describe-vpcs \
        --filters "Name=tag:Name,Values=floci-compat-vpc"
    assert_success
    assert_output --partial "floci-compat-vpc"
    assert_output --partial "10.0.0.0/16"
}

@test "OpenTofu: VPC enableDnsSupport persisted as false" {
    VPC_ID=$(aws_cmd ec2 describe-vpcs \
        --filters "Name=tag:Name,Values=floci-compat-vpc" \
        --query 'Vpcs[0].VpcId' --output text)
    run aws_cmd ec2 describe-vpc-attribute \
        --vpc-id "$VPC_ID" --attribute enableDnsSupport
    assert_success
    assert_output --partial '"Value": false'
}

@test "OpenTofu: VPC enableDnsHostnames persisted as false" {
    VPC_ID=$(aws_cmd ec2 describe-vpcs \
        --filters "Name=tag:Name,Values=floci-compat-vpc" \
        --query 'Vpcs[0].VpcId' --output text)
    run aws_cmd ec2 describe-vpc-attribute \
        --vpc-id "$VPC_ID" --attribute enableDnsHostnames
    assert_success
    assert_output --partial '"Value": false'
}
