#!/usr/bin/env bats
# SSM Parameter Store tests

setup() {
    load 'test_helper/common-setup'
    PARAM_NAME="/bats-test/param-$(unique_name)"
}

teardown() {
    aws_cmd ssm delete-parameter --name "$PARAM_NAME" >/dev/null 2>&1 || true
}

@test "SSM: put parameter" {
    run aws_cmd ssm put-parameter --name "$PARAM_NAME" --value "test-value" --type String
    assert_success
    version=$(json_get "$output" '.Version')
    [ "$version" -gt 0 ]
}

@test "SSM: get parameter" {
    aws_cmd ssm put-parameter --name "$PARAM_NAME" --value "test-value" --type String >/dev/null

    run aws_cmd ssm get-parameter --name "$PARAM_NAME" --no-with-decryption
    assert_success
    value=$(json_get "$output" '.Parameter.Value')
    [ "$value" = "test-value" ]
}

@test "SSM: get parameters by path" {
    aws_cmd ssm put-parameter --name "$PARAM_NAME" --value "test-value" --type String >/dev/null

    run aws_cmd ssm get-parameters-by-path --path "/bats-test"
    assert_success
    # Check that parameters array is not empty
    count=$(echo "$output" | jq '.Parameters | length')
    [ "$count" -gt 0 ]
}

@test "SSM: add and list tags" {
    aws_cmd ssm put-parameter --name "$PARAM_NAME" --value "test-value" --type String >/dev/null

    run aws_cmd ssm add-tags-to-resource \
        --resource-type Parameter \
        --resource-id "$PARAM_NAME" \
        --tags Key=env,Value=test
    assert_success

    run aws_cmd ssm list-tags-for-resource \
        --resource-type Parameter \
        --resource-id "$PARAM_NAME"
    assert_success
    found=$(echo "$output" | jq '.TagList | any(.Key == "env" and .Value == "test")')
    [ "$found" = "true" ]
}

@test "SSM: overwrite parameter" {
    aws_cmd ssm put-parameter --name "$PARAM_NAME" --value "original" --type String >/dev/null

    run aws_cmd ssm put-parameter --name "$PARAM_NAME" --value "updated" --type String --overwrite
    assert_success

    run aws_cmd ssm get-parameter --name "$PARAM_NAME" --no-with-decryption
    assert_success
    value=$(json_get "$output" '.Parameter.Value')
    [ "$value" = "updated" ]
}

@test "SSM: delete parameter" {
    aws_cmd ssm put-parameter --name "$PARAM_NAME" --value "test-value" --type String >/dev/null

    run aws_cmd ssm delete-parameter --name "$PARAM_NAME"
    assert_success
}
