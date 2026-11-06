#!/usr/bin/env bats
# Lambda tests

setup() {
    load 'test_helper/common-setup'
    FUNC_NAME=""
}

teardown() {
    if [ -n "$FUNC_NAME" ]; then
        aws_cmd lambda delete-function --function-name "$FUNC_NAME" >/dev/null 2>&1 || true
    fi
}

_role="arn:aws:iam::000000000000:role/lambda-role"
_image_uri="000000000000.dkr.ecr.us-east-1.amazonaws.com/fake-repo:latest"

@test "Lambda: ImageConfig.WorkingDirectory is returned in CreateFunction response" {
    FUNC_NAME="bats-imgwd-create-$(unique_name)"

    run aws_cmd lambda create-function \
        --function-name "$FUNC_NAME" \
        --package-type Image \
        --role "$_role" \
        --code "ImageUri=$_image_uri" \
        --image-config 'WorkingDirectory=/app'
    assert_success

    wd=$(json_get "$output" '.ImageConfigResponse.ImageConfig.WorkingDirectory')
    [ "$wd" = "/app" ] || { echo "expected WorkingDirectory=/app, got: $wd"; return 1; }
}

@test "Lambda: ImageConfig.WorkingDirectory is persisted and returned by get-function-configuration" {
    FUNC_NAME="bats-imgwd-get-$(unique_name)"

    aws_cmd lambda create-function \
        --function-name "$FUNC_NAME" \
        --package-type Image \
        --role "$_role" \
        --code "ImageUri=$_image_uri" \
        --image-config 'WorkingDirectory=/workspace' >/dev/null

    run aws_cmd lambda get-function-configuration --function-name "$FUNC_NAME"
    assert_success

    wd=$(json_get "$output" '.ImageConfigResponse.ImageConfig.WorkingDirectory')
    [ "$wd" = "/workspace" ] || { echo "expected WorkingDirectory=/workspace, got: $wd"; return 1; }
}

@test "Lambda: ImageConfig.WorkingDirectory is updated by update-function-configuration" {
    FUNC_NAME="bats-imgwd-upd-$(unique_name)"

    aws_cmd lambda create-function \
        --function-name "$FUNC_NAME" \
        --package-type Image \
        --role "$_role" \
        --code "ImageUri=$_image_uri" \
        --image-config 'WorkingDirectory=/initial' >/dev/null

    run aws_cmd lambda update-function-configuration \
        --function-name "$FUNC_NAME" \
        --image-config 'WorkingDirectory=/updated'
    assert_success

    wd=$(json_get "$output" '.ImageConfigResponse.ImageConfig.WorkingDirectory')
    [ "$wd" = "/updated" ] || { echo "expected WorkingDirectory=/updated, got: $wd"; return 1; }
}
