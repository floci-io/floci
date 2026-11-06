#!/usr/bin/env bats
# Lambda durable_config compatibility test
#
# aws_lambda_function.durable_config creates a durable function, reads it back without a
# diff, updates execution_timeout in place, and on destroy stops a RUNNING durable
# execution before it deletes the function.

TF_DIR_NAME="lambda-durable-config-tf"

setup_file() {
    load 'test_helper/common-setup'

    DURABLE_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/$TF_DIR_NAME" && pwd)"
    cd "$DURABLE_TF_DIR"

    echo "# === Lambda durable_config Test ===" >&3
    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* handler.zip 2>/dev/null || true

    run terraform init -input=false -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform init failed: $output" >&3
        return 1
    fi

    run terraform apply -var="endpoint=${FLOCI_ENDPOINT}" -input=false -auto-approve -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform apply failed: $output" >&3
        return 1
    fi
}

teardown_file() {
    load 'test_helper/common-setup'

    DURABLE_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/$TF_DIR_NAME" && pwd)"
    cd "$DURABLE_TF_DIR"

    terraform destroy -var="endpoint=${FLOCI_ENDPOINT}" -input=false -auto-approve -no-color || true
    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* handler.zip 2>/dev/null || true
}

setup() {
    load 'test_helper/common-setup'
    DURABLE_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/$TF_DIR_NAME" && pwd)"
}

@test "durable_config: the function is durable with the default retention" {
    run aws_cmd lambda get-function-configuration --function-name floci-tf-lambda-durable
    assert_success
    assert_equal "$(json_get "$output" '.DurableConfig.ExecutionTimeout')" "60"
    assert_equal "$(json_get "$output" '.DurableConfig.RetentionPeriodInDays')" "14"
}

@test "durable_config: a second plan has no changes" {
    run terraform -chdir="$DURABLE_TF_DIR" plan -var="endpoint=${FLOCI_ENDPOINT}" \
        -input=false -no-color -detailed-exitcode
    assert_success
}

@test "durable_config: execution_timeout updates the function in place" {
    run terraform -chdir="$DURABLE_TF_DIR" apply -var="endpoint=${FLOCI_ENDPOINT}" \
        -var="execution_timeout=120" -input=false -auto-approve -no-color
    assert_success
    assert_output --partial "0 added, 1 changed, 0 destroyed"

    run aws_cmd lambda get-function-configuration --function-name floci-tf-lambda-durable
    assert_success
    assert_equal "$(json_get "$output" '.DurableConfig.ExecutionTimeout')" "120"
}

@test "durable_config: destroy stops a running durable execution" {
    run aws_cmd lambda invoke --function-name 'floci-tf-lambda-durable:$LATEST' \
        --invocation-type Event --cli-binary-format raw-in-base64-out --payload '{}' /dev/null
    assert_success
    execution_arn="$(json_get "$output" '.DurableExecutionArn')"
    [ -n "$execution_arn" ] && [ "$execution_arn" != "null" ]

    run terraform -chdir="$DURABLE_TF_DIR" destroy -var="endpoint=${FLOCI_ENDPOINT}" \
        -var="execution_timeout=120" -input=false -auto-approve -no-color
    assert_success

    run aws_cmd lambda get-durable-execution --durable-execution-arn "$execution_arn"
    assert_success
    assert_equal "$(json_get "$output" '.Status')" "STOPPED"
}
