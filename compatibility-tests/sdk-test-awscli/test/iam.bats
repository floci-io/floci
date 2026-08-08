#!/usr/bin/env bats
# IAM tests

setup() {
    load 'test_helper/common-setup'
    ROLE_NAME="bats-test-role-$(unique_name)"
    POLICY_ARN=""
    ACCOUNT_ALIAS=""
}

teardown() {
    if [ -n "$POLICY_ARN" ]; then
        aws_cmd iam detach-role-policy --role-name "$ROLE_NAME" --policy-arn "$POLICY_ARN" >/dev/null 2>&1 || true
        aws_cmd iam delete-policy --policy-arn "$POLICY_ARN" >/dev/null 2>&1 || true
    fi
    aws_cmd iam delete-role --role-name "$ROLE_NAME" >/dev/null 2>&1 || true
    # An account holds one alias, so a leaked one would fail every later create.
    if [ -n "$ACCOUNT_ALIAS" ]; then
        aws_cmd iam delete-account-alias --account-alias "$ACCOUNT_ALIAS" >/dev/null 2>&1 || true
    fi
}

@test "IAM: create role" {
    local policy_doc='{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}'

    run aws_cmd iam create-role \
        --role-name "$ROLE_NAME" \
        --assume-role-policy-document "$policy_doc"
    assert_success
    arn=$(json_get "$output" '.Role.Arn')
    [ -n "$arn" ]
}

@test "IAM: get role" {
    local policy_doc='{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}'
    aws_cmd iam create-role --role-name "$ROLE_NAME" --assume-role-policy-document "$policy_doc" >/dev/null

    run aws_cmd iam get-role --role-name "$ROLE_NAME"
    assert_success
    name=$(json_get "$output" '.Role.RoleName')
    [ "$name" = "$ROLE_NAME" ]
}

@test "IAM: list roles" {
    local policy_doc='{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}'
    aws_cmd iam create-role --role-name "$ROLE_NAME" --assume-role-policy-document "$policy_doc" >/dev/null

    run aws_cmd iam list-roles
    assert_success
    found=$(echo "$output" | jq --arg name "$ROLE_NAME" '.Roles | any(.RoleName == $name)')
    [ "$found" = "true" ]
}

@test "IAM: create and delete policy" {
    local policy_doc='{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:GetObject","Resource":"*"}]}'

    run aws_cmd iam create-policy \
        --policy-name "bats-test-policy-$(unique_name)" \
        --policy-document "$policy_doc"
    assert_success
    POLICY_ARN=$(json_get "$output" '.Policy.Arn')
    [ -n "$POLICY_ARN" ]

    run aws_cmd iam delete-policy --policy-arn "$POLICY_ARN"
    assert_success
    POLICY_ARN=""
}

@test "IAM: attach and detach role policy" {
    local role_policy_doc='{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}'
    local policy_doc='{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:GetObject","Resource":"*"}]}'

    aws_cmd iam create-role --role-name "$ROLE_NAME" --assume-role-policy-document "$role_policy_doc" >/dev/null

    out=$(aws_cmd iam create-policy --policy-name "bats-test-policy-$(unique_name)" --policy-document "$policy_doc")
    POLICY_ARN=$(json_get "$out" '.Policy.Arn')

    run aws_cmd iam attach-role-policy --role-name "$ROLE_NAME" --policy-arn "$POLICY_ARN"
    assert_success

    run aws_cmd iam detach-role-policy --role-name "$ROLE_NAME" --policy-arn "$POLICY_ARN"
    assert_success
}

@test "IAM: delete role" {
    local policy_doc='{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}'
    aws_cmd iam create-role --role-name "$ROLE_NAME" --assume-role-policy-document "$policy_doc" >/dev/null

    run aws_cmd iam delete-role --role-name "$ROLE_NAME"
    assert_success
}

@test "IAM: list account aliases is empty by default" {
    run aws_cmd iam list-account-aliases
    assert_success
    count=$(json_get "$output" '.AccountAliases | length')
    [ "$count" = "0" ]
}

@test "IAM: create and list account alias" {
    ACCOUNT_ALIAS="$(unique_name bats-alias)"

    run aws_cmd iam create-account-alias --account-alias "$ACCOUNT_ALIAS"
    assert_success

    run aws_cmd iam list-account-aliases
    assert_success
    alias=$(json_get "$output" '.AccountAliases[0]')
    [ "$alias" = "$ACCOUNT_ALIAS" ]
}

@test "IAM: creating another account alias replaces the current one" {
    ACCOUNT_ALIAS="$(unique_name bats-alias)"
    aws_cmd iam create-account-alias --account-alias "$ACCOUNT_ALIAS" >/dev/null

    local replacement
    replacement="$(unique_name bats-alias-two)"
    run aws_cmd iam create-account-alias --account-alias "$replacement"
    assert_success
    ACCOUNT_ALIAS="$replacement"

    run aws_cmd iam list-account-aliases
    assert_success
    alias=$(json_get "$output" '.AccountAliases[0]')
    [ "$alias" = "$replacement" ]
}

@test "IAM: re-creating the alias the account already holds fails" {
    ACCOUNT_ALIAS="$(unique_name bats-alias)"
    aws_cmd iam create-account-alias --account-alias "$ACCOUNT_ALIAS" >/dev/null

    run aws_cmd iam create-account-alias --account-alias "$ACCOUNT_ALIAS"
    assert_failure
    assert_output --partial "EntityAlreadyExists"
}

@test "IAM: deleting a mismatched account alias fails" {
    ACCOUNT_ALIAS="$(unique_name bats-alias)"
    aws_cmd iam create-account-alias --account-alias "$ACCOUNT_ALIAS" >/dev/null

    run aws_cmd iam delete-account-alias --account-alias "bats-alias-not-set-$$"
    assert_failure
    assert_output --partial "NoSuchEntity"
}

@test "IAM: delete account alias" {
    ACCOUNT_ALIAS="$(unique_name bats-alias)"
    aws_cmd iam create-account-alias --account-alias "$ACCOUNT_ALIAS" >/dev/null

    run aws_cmd iam delete-account-alias --account-alias "$ACCOUNT_ALIAS"
    assert_success

    run aws_cmd iam list-account-aliases
    assert_success
    count=$(json_get "$output" '.AccountAliases | length')
    [ "$count" = "0" ]
}

@test "IAM: malformed account alias is rejected" {
    run aws_cmd iam create-account-alias --account-alias "Upper-Case"
    assert_failure
    assert_output --partial "ValidationError"
}
