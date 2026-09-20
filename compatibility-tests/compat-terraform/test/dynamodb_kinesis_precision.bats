#!/usr/bin/env bats
# DynamoDB Kinesis Streaming Destination Precision Compatibility Test
#
# EnableKinesisStreamingDestination ignored ApproximateCreationDateTimePrecision, and
# DescribeKinesisStreamingDestination always reported MILLISECOND. The provider marks
# approximate_creation_date_time_precision as ForceNew, so a MICROSECOND destination read back
# as MILLISECOND and was destroyed and recreated on every apply. Enabling the destination also
# switched on DynamoDB Streams for the table, so aws_dynamodb_table planned to turn it back off
# on every run. This verifies the precision reads back as written, a second plan is clean for
# both resources, and changing the precision replaces the destination once.

setup_file() {
    load 'test_helper/common-setup'

    DDB_KINESIS_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/dynamodb-kinesis-precision-tf" && pwd)"
    cd "$DDB_KINESIS_TF_DIR"

    echo "# === DynamoDB Kinesis Destination Precision Test ===" >&3
    echo "# Endpoint: $FLOCI_ENDPOINT" >&3
    echo "# Config: $DDB_KINESIS_TF_DIR" >&3

    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* 2>/dev/null || true

    echo "# --- terraform init ---" >&3
    run terraform init -input=false -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform init failed: $output" >&3
        return 1
    fi

    echo "# --- terraform apply (MICROSECOND) ---" >&3
    run terraform apply -var="endpoint=${FLOCI_ENDPOINT}" -input=false -auto-approve -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform apply failed: $output" >&3
        return 1
    fi
}

teardown_file() {
    load 'test_helper/common-setup'

    DDB_KINESIS_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/dynamodb-kinesis-precision-tf" && pwd)"
    cd "$DDB_KINESIS_TF_DIR"

    terraform destroy -var="endpoint=${FLOCI_ENDPOINT}" -input=false -auto-approve -no-color || true
    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* 2>/dev/null || true
}

setup() {
    load 'test_helper/common-setup'
    DDB_KINESIS_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/dynamodb-kinesis-precision-tf" && pwd)"
}

describe_precision() {
    local table_name stream_arn
    table_name=$(terraform -chdir="$DDB_KINESIS_TF_DIR" output -raw table_name)
    stream_arn=$(terraform -chdir="$DDB_KINESIS_TF_DIR" output -raw stream_arn)
    aws_cmd dynamodb describe-kinesis-streaming-destination --table-name "$table_name" \
        --query "KinesisDataStreamDestinations[?StreamArn=='${stream_arn}'].ApproximateCreationDateTimePrecision | [0]" \
        --output text
}

@test "DynamoDB Kinesis precision: destination reads back as MICROSECOND" {
    run describe_precision
    assert_success
    assert_output "MICROSECOND"
}

# The critical assertion: before the fix the precision read back as MILLISECOND, and because
# the attribute is ForceNew every plan proposed replacing the destination. The table here does
# not enable streams, so this also catches stream_enabled drift on aws_dynamodb_table.
@test "DynamoDB Kinesis precision: second plan reports no changes" {
    cd "$DDB_KINESIS_TF_DIR"
    run terraform plan -var="endpoint=${FLOCI_ENDPOINT}" -input=false -no-color -detailed-exitcode
    assert_success
    assert_output --partial "No changes"
}

# A real precision change is still ForceNew: it must replace the destination once, then settle.
@test "DynamoDB Kinesis precision: changing the precision replaces once and re-plans clean" {
    cd "$DDB_KINESIS_TF_DIR"
    run terraform apply -var="endpoint=${FLOCI_ENDPOINT}" -var="precision=MILLISECOND" \
        -input=false -auto-approve -no-color
    assert_success
    assert_output --partial "1 added, 0 changed, 1 destroyed"

    run describe_precision
    assert_success
    assert_output "MILLISECOND"

    run terraform plan -var="endpoint=${FLOCI_ENDPOINT}" -var="precision=MILLISECOND" \
        -input=false -no-color -detailed-exitcode
    assert_success
    assert_output --partial "No changes"
}
