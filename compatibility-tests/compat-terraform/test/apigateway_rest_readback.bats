#!/usr/bin/env bats
# API Gateway REST Read-Back Compatibility Test
#
# GetMethod dropped requestParameters, GetRestApi dropped policy, and GetStage dropped
# accessLogSettings, tracingEnabled and tags. Every write succeeded, but the reads came back
# without the values, so each plan proposed the same three in-place updates forever. With a
# deployment trigger that hashes the method, the re-apply failed outright with "Provider
# produced inconsistent final plan". This verifies the module applies, reads back what it
# wrote, converges on a re-apply, and survives an in-place method change.

setup_file() {
    load 'test_helper/common-setup'

    APIGW_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/apigateway-rest-readback-tf" && pwd)"
    cd "$APIGW_TF_DIR"

    echo "# === API Gateway REST Read-Back Test ===" >&3
    echo "# Endpoint: $FLOCI_ENDPOINT" >&3
    echo "# Config: $APIGW_TF_DIR" >&3

    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* 2>/dev/null || true

    echo "# --- terraform init ---" >&3
    run terraform init -input=false -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform init failed: $output" >&3
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

    APIGW_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/apigateway-rest-readback-tf" && pwd)"
    cd "$APIGW_TF_DIR"

    terraform destroy -var="endpoint=${FLOCI_ENDPOINT}" -input=false -auto-approve -no-color || true
    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* 2>/dev/null || true
}

setup() {
    load 'test_helper/common-setup'
    APIGW_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/apigateway-rest-readback-tf" && pwd)"
}

@test "API Gateway read-back: GetMethod returns requestParameters" {
    API_ID=$(terraform -chdir="$APIGW_TF_DIR" output -raw rest_api_id)
    RESOURCE_ID=$(terraform -chdir="$APIGW_TF_DIR" output -raw resource_id)
    run aws_cmd apigateway get-method --rest-api-id "$API_ID" --resource-id "$RESOURCE_ID" \
        --http-method ANY --query 'requestParameters."method.request.path.proxy"' --output text
    assert_success
    assert_output "True"
}

@test "API Gateway read-back: GetRestApi returns the policy" {
    API_ID=$(terraform -chdir="$APIGW_TF_DIR" output -raw rest_api_id)
    run aws_cmd apigateway get-rest-api --rest-api-id "$API_ID" --query policy --output text
    assert_success
    assert_output --partial "execute-api:Invoke"
}

@test "API Gateway read-back: GetStage returns access logs, tracing and tags" {
    API_ID=$(terraform -chdir="$APIGW_TF_DIR" output -raw rest_api_id)
    run aws_cmd apigateway get-stage --rest-api-id "$API_ID" --stage-name local \
        --query '[accessLogSettings.format, tracingEnabled, tags.env]' --output text
    assert_success
    assert_output "\$context.requestId \$context.status	True	local"
}

# The failure from the bug report: the method read back without requestParameters, so it
# differed between plan and apply, the deployment trigger hash moved mid-apply, and the
# re-apply died with "Provider produced inconsistent final plan".
#
# The re-apply may still replace the deployment once, whatever the server returns:
# aws_api_gateway_method's create does not read the method back, so unset collections
# (request_models, authorization_scopes) stay null in state until the first refresh fills
# them in as empty, and that moves the trigger hash a single time. The stage then follows the
# new deployment id, so only the method and the policy must stay untouched here.
@test "API Gateway read-back: re-apply succeeds" {
    cd "$APIGW_TF_DIR"
    run terraform apply -var="endpoint=${FLOCI_ENDPOINT}" -input=false -auto-approve -no-color
    assert_success
    refute_output --partial "inconsistent final plan"
    refute_output --partial "aws_api_gateway_method.proxy: Modifying"
    refute_output --partial "aws_api_gateway_rest_api_policy.api: Modifying"
}

# The critical assertion: before the fix every plan proposed the same three updates forever.
@test "API Gateway read-back: plan after re-apply reports no changes" {
    cd "$APIGW_TF_DIR"
    run terraform plan -var="endpoint=${FLOCI_ENDPOINT}" -input=false -no-color -detailed-exitcode
    assert_success
    assert_output --partial "No changes"
}

# Changing a method request parameter goes through UpdateMethod patch operations and changes
# the deployment trigger hash. This is where "inconsistent final plan" used to surface.
@test "API Gateway read-back: changing a request parameter applies and re-plans clean" {
    cd "$APIGW_TF_DIR"
    run terraform apply -var="endpoint=${FLOCI_ENDPOINT}" -var="extra_request_parameter=true" \
        -input=false -auto-approve -no-color
    assert_success
    refute_output --partial "inconsistent final plan"

    run terraform plan -var="endpoint=${FLOCI_ENDPOINT}" -var="extra_request_parameter=true" \
        -input=false -no-color -detailed-exitcode
    assert_success
    assert_output --partial "No changes"
}

# Stage tag changes go through TagResource on the stage ARN, which used to tag the REST API
# instead, so the stage never showed the new value and the plan never settled.
@test "API Gateway read-back: changing a stage tag lands on the stage and re-plans clean" {
    cd "$APIGW_TF_DIR"
    run terraform apply -var="endpoint=${FLOCI_ENDPOINT}" -var="extra_request_parameter=true" \
        -var="stage_env=staging" -input=false -auto-approve -no-color
    assert_success

    API_ID=$(terraform -chdir="$APIGW_TF_DIR" output -raw rest_api_id)
    run aws_cmd apigateway get-stage --rest-api-id "$API_ID" --stage-name local \
        --query 'tags.env' --output text
    assert_success
    assert_output "staging"

    run aws_cmd apigateway get-rest-api --rest-api-id "$API_ID" --query 'tags' --output text
    assert_success
    assert_output "None"

    run terraform plan -var="endpoint=${FLOCI_ENDPOINT}" -var="extra_request_parameter=true" \
        -var="stage_env=staging" -input=false -no-color -detailed-exitcode
    assert_success
    assert_output --partial "No changes"
}
