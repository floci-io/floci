#!/usr/bin/env bats
# STS tests

setup() {
    load 'test_helper/common-setup'
    ROLE_NAME="bats-sts-role-$(unique_name)"
}

teardown() {
    aws_cmd iam delete-role --role-name "$ROLE_NAME" >/dev/null 2>&1 || true
}

@test "STS: get caller identity" {
    run aws_cmd sts get-caller-identity
    assert_success
    account=$(json_get "$output" '.Account')
    [ -n "$account" ]
    user_id=$(json_get "$output" '.UserId')
    [ -n "$user_id" ]
}

@test "STS: assume role" {
    local policy_doc='{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":"*"},"Action":"sts:AssumeRole"}]}'
    aws_cmd iam create-role --role-name "$ROLE_NAME" --assume-role-policy-document "$policy_doc" >/dev/null

    local role_arn="arn:aws:iam::000000000000:role/$ROLE_NAME"

    run aws_cmd sts assume-role \
        --role-arn "$role_arn" \
        --role-session-name "bats-test-session"
    assert_success
    access_key=$(json_get "$output" '.Credentials.AccessKeyId')
    [ -n "$access_key" ]
}

@test "STS: assume nonexistent role is denied" {
    run aws_cmd sts assume-role \
        --role-arn "arn:aws:iam::000000000000:role/nonexistent-role-$(unique_name)" \
        --role-session-name "bats-test-session"
    assert_failure
    assert_output --partial "AccessDenied"
}
