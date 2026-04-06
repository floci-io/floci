#!/usr/bin/env bats
# IAM tests

setup() {
    load 'test_helper/common-setup'
    ROLE_NAME="bats-test-role-$(unique_name)"
    POLICY_ARN=""
}

teardown() {
    if [ -n "$POLICY_ARN" ]; then
        aws_cmd iam detach-role-policy --role-name "$ROLE_NAME" --policy-arn "$POLICY_ARN" >/dev/null 2>&1 || true
        aws_cmd iam delete-policy --policy-arn "$POLICY_ARN" >/dev/null 2>&1 || true
    fi
    aws_cmd iam delete-role --role-name "$ROLE_NAME" >/dev/null 2>&1 || true
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
