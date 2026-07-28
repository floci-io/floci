#!/usr/bin/env bats
# Secrets Manager tests

setup() {
    load 'test_helper/common-setup'
    SECRET_NAME="bats/test/secret-$(unique_name)"
}

teardown() {
    aws_cmd secretsmanager delete-secret \
        --secret-id "$SECRET_NAME" \
        --force-delete-without-recovery >/dev/null 2>&1 || true
}

@test "Secrets Manager: create secret" {
    run aws_cmd secretsmanager create-secret \
        --name "$SECRET_NAME" \
        --secret-string '{"key":"value"}'
    assert_success
    arn=$(json_get "$output" '.ARN')
    [ -n "$arn" ]
}

@test "Secrets Manager: get secret value" {
    aws_cmd secretsmanager create-secret \
        --name "$SECRET_NAME" \
        --secret-string '{"key":"value"}' >/dev/null

    run aws_cmd secretsmanager get-secret-value --secret-id "$SECRET_NAME"
    assert_success
    value=$(json_get "$output" '.SecretString')
    [ "$value" = '{"key":"value"}' ]
}

@test "Secrets Manager: update secret" {
    aws_cmd secretsmanager create-secret \
        --name "$SECRET_NAME" \
        --secret-string '{"key":"value"}' >/dev/null

    run aws_cmd secretsmanager update-secret \
        --secret-id "$SECRET_NAME" \
        --secret-string '{"key":"updated"}'
    assert_success

    run aws_cmd secretsmanager get-secret-value --secret-id "$SECRET_NAME"
    assert_success
    value=$(json_get "$output" '.SecretString')
    [ "$value" = '{"key":"updated"}' ]
}

@test "Secrets Manager: list secrets" {
    aws_cmd secretsmanager create-secret \
        --name "$SECRET_NAME" \
        --secret-string '{"key":"value"}' >/dev/null

    run aws_cmd secretsmanager list-secrets
    assert_success
    found=$(echo "$output" | jq --arg name "$SECRET_NAME" '.SecretList | any(.Name == $name)')
    [ "$found" = "true" ]
}

@test "Secrets Manager: delete secret" {
    aws_cmd secretsmanager create-secret \
        --name "$SECRET_NAME" \
        --secret-string '{"key":"value"}' >/dev/null

    run aws_cmd secretsmanager delete-secret \
        --secret-id "$SECRET_NAME" \
        --force-delete-without-recovery
    assert_success
}

# --- Secrets Manager Tagging Tests ---

@test "Secrets Manager: tag resource" {
    out=$(aws_cmd secretsmanager create-secret \
        --name "$SECRET_NAME" \
        --secret-string '{"key":"value"}')
    arn=$(json_get "$out" '.ARN')

    run aws_cmd secretsmanager tag-resource \
        --secret-id "$arn" \
        --tags Key=Environment,Value=test Key=Project,Value=bats
    assert_success
}

@test "Secrets Manager: describe secret with tags" {
    out=$(aws_cmd secretsmanager create-secret \
        --name "$SECRET_NAME" \
        --secret-string '{"key":"value"}')
    arn=$(json_get "$out" '.ARN')

    aws_cmd secretsmanager tag-resource \
        --secret-id "$arn" \
        --tags Key=Environment,Value=test >/dev/null

    run aws_cmd secretsmanager describe-secret --secret-id "$SECRET_NAME"
    assert_success
    found=$(echo "$output" | jq '.Tags | any(.Key == "Environment" and .Value == "test")')
    [ "$found" = "true" ]
}

@test "Secrets Manager: untag resource" {
    out=$(aws_cmd secretsmanager create-secret \
        --name "$SECRET_NAME" \
        --secret-string '{"key":"value"}')
    arn=$(json_get "$out" '.ARN')

    aws_cmd secretsmanager tag-resource \
        --secret-id "$arn" \
        --tags Key=Environment,Value=test >/dev/null

    run aws_cmd secretsmanager untag-resource \
        --secret-id "$arn" \
        --tag-keys Environment
    assert_success

    # Verify tag is removed
    run aws_cmd secretsmanager describe-secret --secret-id "$SECRET_NAME"
    found=$(echo "$output" | jq '.Tags // [] | any(.Key == "Environment")')
    [ "$found" = "false" ]
}

# --- Secrets Manager Version Stage Tests ---

@test "Secrets Manager: add custom stage to version" {
    out=$(aws_cmd secretsmanager create-secret \
        --name "$SECRET_NAME" \
        --secret-string '{"key":"value"}')
    v1=$(json_get "$out" '.VersionId')

    run aws_cmd secretsmanager update-secret-version-stage \
        --secret-id "$SECRET_NAME" \
        --version-stage MYSTAGE \
        --move-to-version-id "$v1"
    assert_success

    run aws_cmd secretsmanager describe-secret --secret-id "$SECRET_NAME"
    assert_success
    has_mystage=$(echo "$output" | jq --arg vid "$v1" '.VersionIdsToStages[$vid] | any(. == "MYSTAGE")')
    [ "$has_mystage" = "true" ]
    has_current=$(echo "$output" | jq --arg vid "$v1" '.VersionIdsToStages[$vid] | any(. == "AWSCURRENT")')
    [ "$has_current" = "true" ]
}

@test "Secrets Manager: move custom stage between versions" {
    out=$(aws_cmd secretsmanager create-secret \
        --name "$SECRET_NAME" \
        --secret-string '{"key":"v1"}')
    v1=$(json_get "$out" '.VersionId')

    out=$(aws_cmd secretsmanager update-secret \
        --secret-id "$SECRET_NAME" \
        --secret-string '{"key":"v2"}')
    v2=$(json_get "$out" '.VersionId')

    aws_cmd secretsmanager update-secret-version-stage \
        --secret-id "$SECRET_NAME" \
        --version-stage MYSTAGE \
        --move-to-version-id "$v1" >/dev/null

    run aws_cmd secretsmanager update-secret-version-stage \
        --secret-id "$SECRET_NAME" \
        --version-stage MYSTAGE \
        --move-to-version-id "$v2" \
        --remove-from-version-id "$v1"
    assert_success

    run aws_cmd secretsmanager describe-secret --secret-id "$SECRET_NAME"
    assert_success
    on_v2=$(echo "$output" | jq --arg vid "$v2" '.VersionIdsToStages[$vid] | any(. == "MYSTAGE")')
    [ "$on_v2" = "true" ]
    on_v1=$(echo "$output" | jq --arg vid "$v1" '(.VersionIdsToStages[$vid] // []) | any(. == "MYSTAGE")')
    [ "$on_v1" = "false" ]
}

@test "Secrets Manager: move AWSCURRENT updates AWSPREVIOUS" {
    out=$(aws_cmd secretsmanager create-secret \
        --name "$SECRET_NAME" \
        --secret-string '{"key":"v1"}')
    v1=$(json_get "$out" '.VersionId')

    out=$(aws_cmd secretsmanager update-secret \
        --secret-id "$SECRET_NAME" \
        --secret-string '{"key":"v2"}')
    v2=$(json_get "$out" '.VersionId')

    run aws_cmd secretsmanager update-secret-version-stage \
        --secret-id "$SECRET_NAME" \
        --version-stage AWSCURRENT \
        --move-to-version-id "$v1" \
        --remove-from-version-id "$v2"
    assert_success

    run aws_cmd secretsmanager describe-secret --secret-id "$SECRET_NAME"
    assert_success
    v1_current=$(echo "$output" | jq --arg vid "$v1" '.VersionIdsToStages[$vid] | any(. == "AWSCURRENT")')
    [ "$v1_current" = "true" ]
    v2_previous=$(echo "$output" | jq --arg vid "$v2" '.VersionIdsToStages[$vid] | any(. == "AWSPREVIOUS")')
    [ "$v2_previous" = "true" ]
}

@test "Secrets Manager: remove custom stage from version" {
    out=$(aws_cmd secretsmanager create-secret \
        --name "$SECRET_NAME" \
        --secret-string '{"key":"value"}')
    v1=$(json_get "$out" '.VersionId')

    aws_cmd secretsmanager update-secret-version-stage \
        --secret-id "$SECRET_NAME" \
        --version-stage MYSTAGE \
        --move-to-version-id "$v1" >/dev/null

    run aws_cmd secretsmanager update-secret-version-stage \
        --secret-id "$SECRET_NAME" \
        --version-stage MYSTAGE \
        --remove-from-version-id "$v1"
    assert_success

    run aws_cmd secretsmanager describe-secret --secret-id "$SECRET_NAME"
    assert_success
    has_mystage=$(echo "$output" | jq --arg vid "$v1" '(.VersionIdsToStages[$vid] // []) | any(. == "MYSTAGE")')
    [ "$has_mystage" = "false" ]
    has_current=$(echo "$output" | jq --arg vid "$v1" '.VersionIdsToStages[$vid] | any(. == "AWSCURRENT")')
    [ "$has_current" = "true" ]
}

# --- Batch Get Secret Value Tests ---

@test "Secrets Manager: batch-get-secret-value returns all found secrets" {
    S1="${SECRET_NAME}-batch-a"
    S2="${SECRET_NAME}-batch-b"

    aws_cmd secretsmanager create-secret \
        --name "$S1" \
        --secret-string '{"key":"val-a"}' >/dev/null
    aws_cmd secretsmanager create-secret \
        --name "$S2" \
        --secret-string '{"key":"val-b"}' >/dev/null

    run aws_cmd secretsmanager batch-get-secret-value \
        --secret-id-list "$S1" "$S2"
    assert_success

    count=$(echo "$output" | jq '.SecretValues | length')
    [ "$count" = "2" ]
    errors=$(echo "$output" | jq '.Errors | length')
    [ "$errors" = "0" ]

    aws_cmd secretsmanager delete-secret --secret-id "$S1" --force-delete-without-recovery >/dev/null 2>&1 || true
    aws_cmd secretsmanager delete-secret --secret-id "$S2" --force-delete-without-recovery >/dev/null 2>&1 || true
}

@test "Secrets Manager: batch-get-secret-value with one missing returns partial Errors list" {
    aws_cmd secretsmanager create-secret \
        --name "$SECRET_NAME" \
        --secret-string '{"key":"value"}' >/dev/null

    run aws_cmd secretsmanager batch-get-secret-value \
        --secret-id-list "$SECRET_NAME" "not-exists"
    assert_success

    values=$(echo "$output" | jq '.SecretValues | length')
    [ "$values" = "1" ]
    errors=$(echo "$output" | jq '.Errors | length')
    [ "$errors" = "1" ]
    error_code=$(echo "$output" | jq -r '.Errors[0].ErrorCode')
    [ "$error_code" = "ResourceNotFoundException" ]
    secret_id=$(echo "$output" | jq -r '.Errors[0].SecretId')
    [ "$secret_id" = "not-exists" ]
}
