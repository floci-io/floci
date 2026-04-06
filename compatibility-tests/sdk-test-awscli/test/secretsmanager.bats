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
