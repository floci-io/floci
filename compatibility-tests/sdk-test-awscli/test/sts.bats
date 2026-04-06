#!/usr/bin/env bats
# STS tests

setup() {
    load 'test_helper/common-setup'
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
    local role_arn="arn:aws:iam::000000000000:role/test-role"

    run aws_cmd sts assume-role \
        --role-arn "$role_arn" \
        --role-session-name "bats-test-session"
    assert_success
    access_key=$(json_get "$output" '.Credentials.AccessKeyId')
    [ -n "$access_key" ]
}
