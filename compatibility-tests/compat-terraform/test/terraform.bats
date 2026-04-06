#!/usr/bin/env bats
# Terraform Compatibility Tests for floci

setup_file() {
    load 'test_helper/common-setup'

    cd "$TF_DIR"

    echo "# === Terraform Compatibility Test ===" >&3
    echo "# Endpoint: $FLOCI_ENDPOINT" >&3

    # Clean any previous state
    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* 2>/dev/null || true

    echo "# --- Setup: state bucket & lock table ---" >&3
    create_state_backend
    generate_backend_config

    echo "# --- terraform init ---" >&3
    run terraform init -backend-config=/tmp/floci-backend.hcl \
        -var="endpoint=${FLOCI_ENDPOINT}" -input=false -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform init failed: $output" >&3
        return 1
    fi

    echo "# --- terraform validate ---" >&3
    run terraform validate -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform validate failed: $output" >&3
        return 1
    fi

    echo "# --- terraform plan ---" >&3
    run terraform plan -var="endpoint=${FLOCI_ENDPOINT}" -input=false -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform plan failed: $output" >&3
        return 1
    fi

    echo "# --- terraform apply ---" >&3
    run terraform apply -var="endpoint=${FLOCI_ENDPOINT}" -input=false -auto-approve -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform apply failed: $output" >&3
        return 1
    fi
}

teardown_file() {
    load 'test_helper/common-setup'

    cd "$TF_DIR"

    echo "# --- terraform destroy ---" >&3
    terraform destroy -var="endpoint=${FLOCI_ENDPOINT}" -input=false -auto-approve -no-color || true
}

setup() {
    load 'test_helper/common-setup'
}

# --- Spot Checks ---

@test "Terraform: S3 bucket created" {
    run aws_cmd s3api head-bucket --bucket floci-compat-app
    assert_success
}

@test "Terraform: SQS queue created" {
    run aws_cmd sqs get-queue-url --queue-name floci-compat-jobs
    assert_success
    assert_output --partial "QueueUrl"
}

@test "Terraform: SNS topic created" {
    run aws_cmd sns list-topics
    assert_success
    assert_output --partial "floci-compat-events"
}

@test "Terraform: DynamoDB table created" {
    run aws_cmd dynamodb describe-table --table-name floci-compat-items
    assert_success
    assert_output --partial "ACTIVE"
}

@test "Terraform: SSM parameter created" {
    run aws_cmd ssm get-parameter --name /floci-compat/db-url
    assert_success
    assert_output --partial "jdbc:"
}

@test "Terraform: Secrets Manager secret created" {
    run aws_cmd secretsmanager describe-secret --secret-id "floci-compat/db-creds"
    assert_success
    assert_output --partial "floci-compat"
}
