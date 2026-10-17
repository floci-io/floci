#!/usr/bin/env bats
# Lambda Web Functions integration tests
#
# The `lambda-web` service model signs as lambda and is served under the lambda
# service. Code comes from an S3 object, so each test seeds one first.

setup() {
    load 'test_helper/common-setup'
    FUNCTION_NAME=""
    BUCKET="cli-lambda-web-code-$$"
    ROLE_ARN="arn:aws:iam::000000000000:role/lambda-web"
    aws_cmd s3api create-bucket --bucket "$BUCKET" >/dev/null 2>&1 || true
    printf 'zip' > "$BATS_TEST_TMPDIR/app.zip"
    aws_cmd s3api put-object --bucket "$BUCKET" --key app.zip --body "$BATS_TEST_TMPDIR/app.zip" >/dev/null
}

teardown() {
    if [ -n "$FUNCTION_NAME" ]; then
        aws_cmd lambda-web delete-web-function --function-name "$FUNCTION_NAME" >/dev/null 2>&1 || true
        aws_cmd iam delete-role-policy --role-name "awscli-lambdaweb-$FUNCTION_NAME" --policy-name awscli-lambdaweb-logs >/dev/null 2>&1 || true
        aws_cmd iam delete-role --role-name "awscli-lambdaweb-$FUNCTION_NAME" >/dev/null 2>&1 || true
    fi
    aws_cmd s3 rb "s3://$BUCKET" --force >/dev/null 2>&1 || true
}

build_config() {
    printf '{"codeConfig":{"s3Object":{"bucket":"%s","key":"app.zip"}},"runtimeConfig":{"runtime":"nodejs24.x"}}' "$BUCKET"
}

create_function() {
    local name="$1"
    aws_cmd lambda-web create-web-function \
        --function-name "$name" \
        --revision-config "{\"buildConfig\":$(build_config),\"serviceConfig\":{\"executionRoleArn\":\"$ROLE_ARN\"}}" \
        --endpoint-config '{"endpointName":"dev","endpointType":"HomeRegion","authType":"ApplicationManaged","autoDeploymentMode":"LatestRevision"}'
}

@test "Lambda Web: create a function with its first revision and endpoint" {
    FUNCTION_NAME=$(unique_name "cli-web")
    run create_function "$FUNCTION_NAME"
    assert_success
    state=$(json_get "$output" '.state')
    revision_state=$(json_get "$output" '.revision.state')
    endpoint_state=$(json_get "$output" '.endpoint.state')
    domain=$(json_get "$output" '.endpoint.domainName')
    [ "$state" = "Active" ]
    [ "$revision_state" = "Pending" ]
    [ "$endpoint_state" = "Pending" ]
    [[ "$domain" =~ \.lambda-web\.us-east-1\. ]]

    run aws_cmd lambda-web wait web-function-endpoint-active --function-name "$FUNCTION_NAME" --endpoint-name dev
    assert_success
}

@test "Lambda Web: a new revision moves a LatestRevision endpoint" {
    FUNCTION_NAME=$(unique_name "cli-web")
    create_function "$FUNCTION_NAME" >/dev/null

    run aws_cmd lambda-web create-web-function-revision \
        --function-name "$FUNCTION_NAME" \
        --build-config "$(build_config)" \
        --service-config "{\"executionRoleArn\":\"$ROLE_ARN\",\"timeoutSeconds\":60}"
    assert_success
    revision_id=$(json_get "$output" '.revisionId')
    [[ "$revision_id" =~ ^rev-[a-z0-9]{5}$ ]]

    run aws_cmd lambda-web wait web-function-revision-active --function-name "$FUNCTION_NAME" --revision-id "$revision_id"
    assert_success

    run aws_cmd lambda-web get-web-function-endpoint --function-name "$FUNCTION_NAME" --endpoint-name dev
    assert_success
    routed=$(json_get "$output" '.revisionWeights[0].revisionId')
    [ "$routed" = "$revision_id" ]
}

@test "Lambda Web: weighted routing and the in-use revision guard" {
    FUNCTION_NAME=$(unique_name "cli-web")
    create_function "$FUNCTION_NAME" >/dev/null
    first=$(aws_cmd lambda-web list-web-function-revisions --function-name "$FUNCTION_NAME" | jq -r '.revisions[0].revisionId')

    run aws_cmd lambda-web update-web-function-endpoint \
        --function-name "$FUNCTION_NAME" --endpoint-name dev \
        --auto-deployment-mode Disabled \
        --revision-weights "[{\"revisionId\":\"$first\",\"weight\":100}]"
    assert_success
    status=$(json_get "$output" '.updateStatus')
    [ "$status" = "InProgress" ]

    run aws_cmd lambda-web wait web-function-endpoint-updated --function-name "$FUNCTION_NAME" --endpoint-name dev
    assert_success

    run aws_cmd lambda-web delete-web-function-revision --function-name "$FUNCTION_NAME" --revision-id "$first"
    assert_failure
    assert_output --partial "ConflictException"
    assert_output --partial "the endpoints [dev] are using it"
}

@test "Lambda Web: tags and the resource policy" {
    FUNCTION_NAME=$(unique_name "cli-web")
    create_function "$FUNCTION_NAME" >/dev/null
    arn="arn:aws:lambda:us-east-1:000000000000:web-function/$FUNCTION_NAME"

    run aws_cmd lambda-web tag-resource --resource "$arn" --tags env=test
    assert_success
    run aws_cmd lambda-web list-tags --resource "$arn"
    assert_success
    env_tag=$(json_get "$output" '.tags.env')
    [ "$env_tag" = "test" ]

    run aws_cmd lambda-web put-resource-policy --resource-arn "$arn" \
        --policy "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::000000000000:root\"},\"Action\":\"lambda:InvokeWebFunctionEndpoint\",\"Resource\":\"$arn/endpoint/*\"}]}"
    assert_success
    revision_id=$(json_get "$output" '.revisionId')
    [ -n "$revision_id" ]

    run aws_cmd lambda-web delete-resource-policy --resource-arn "$arn" --revision-id "$revision_id"
    assert_success
    run aws_cmd lambda-web get-resource-policy --resource-arn "$arn"
    assert_failure
    assert_output --partial "does not have a policy"
}

@test "Lambda Web: invalid names and missing functions" {
    run aws_cmd lambda-web get-web-function --function-name cli-no-such-web-function
    assert_failure
    assert_output --partial "ResourceNotFoundException"

    # The = form keeps the CLI from reading a name that starts with a dash as an option.
    run aws_cmd lambda-web create-web-function --function-name=-bad \
        --revision-config "{\"buildConfig\":$(build_config),\"serviceConfig\":{\"executionRoleArn\":\"$ROLE_ARN\"}}" \
        --endpoint-config '{"endpointName":"dev","endpointType":"HomeRegion","authType":"ApplicationManaged"}'
    assert_failure
    assert_output --partial "Invalid function name or ARN '-bad'"
}

@test "Lambda Web: deploy the hello world app and invoke its endpoint" {
    FUNCTION_NAME=$(unique_name "cli-web")
    # deploy passes --endpoint-url only to its Lambda Web client, so the environment variable routes the rest.
    run bash -c "cd '$BATS_TEST_TMPDIR' && AWS_ENDPOINT_URL='$FLOCI_ENDPOINT' aws lambda-web deploy --name '$FUNCTION_NAME' --hello-world --create"
    assert_success
    domain=$(aws_cmd lambda-web get-web-function-endpoint --function-name "$FUNCTION_NAME" --endpoint-name dev | jq -r '.domainName')

    run curl -sS -i -H "Host: $domain" "$FLOCI_ENDPOINT/hello?from=bats"
    assert_success
    assert_output --partial "HTTP/1.1 200"
    assert_output --partial "lambda-web-request-id: "
    assert_output --partial "lambda-web-logstream: "
    assert_output --partial "Hello from Lambda Web!"

    # A domain Floci never minted, in the shape it mints.
    run curl -sS -i -H "Host: nopenopenopenope.001.lambda-web.us-east-1.localhost:4566" "$FLOCI_ENDPOINT/"
    assert_success
    assert_output --partial "HTTP/1.1 404"
    assert_output --partial "lambda-web-error-type: Platform.InvalidEndpoint"
}

@test "Lambda Web: delete removes the function and the waiter settles" {
    FUNCTION_NAME=$(unique_name "cli-web")
    create_function "$FUNCTION_NAME" >/dev/null

    run aws_cmd lambda-web delete-web-function --function-name "$FUNCTION_NAME"
    assert_success
    run aws_cmd lambda-web wait web-function-deleted --function-name "$FUNCTION_NAME"
    assert_success
    FUNCTION_NAME=""
}
