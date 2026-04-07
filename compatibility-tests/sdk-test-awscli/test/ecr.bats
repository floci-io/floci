#!/usr/bin/env bats
# ECR control-plane integration tests.
#
# Test-first: this file is committed before the server-side ECR implementation
# lands. With ECR unimplemented, every test below should fail.

setup() {
    load 'test_helper/common-setup'
    REPO_NAME="floci-it/app-cli"
}

teardown() {
    aws_cmd ecr delete-repository --repository-name "$REPO_NAME" --force >/dev/null 2>&1 || true
}

@test "ECR: create-repository returns loopback URI" {
    run aws_cmd ecr create-repository --repository-name "$REPO_NAME"
    assert_success
    arn=$(json_get "$output" '.repository.repositoryArn')
    uri=$(json_get "$output" '.repository.repositoryUri')
    name=$(json_get "$output" '.repository.repositoryName')
    [ "$name" = "$REPO_NAME" ]
    [[ "$arn" =~ ^arn:aws:ecr: ]]
    [[ "$arn" == *":repository/$REPO_NAME" ]]
    [[ "$uri" == *"localhost:"* ]]
    [[ "$uri" == *"/$REPO_NAME" ]]
}

@test "ECR: create-repository duplicate fails with RepositoryAlreadyExistsException" {
    aws_cmd ecr create-repository --repository-name "$REPO_NAME" >/dev/null
    run aws_cmd ecr create-repository --repository-name "$REPO_NAME"
    assert_failure
    [[ "$output" == *"RepositoryAlreadyExistsException"* ]]
}

@test "ECR: describe-repositories returns the created repo" {
    aws_cmd ecr create-repository --repository-name "$REPO_NAME" >/dev/null
    run aws_cmd ecr describe-repositories --repository-names "$REPO_NAME"
    assert_success
    name=$(json_get "$output" '.repositories[0].repositoryName')
    [ "$name" = "$REPO_NAME" ]
}

@test "ECR: get-authorization-token returns AWS:<password>" {
    aws_cmd ecr create-repository --repository-name "$REPO_NAME" >/dev/null
    run aws_cmd ecr get-authorization-token
    assert_success
    token=$(json_get "$output" '.authorizationData[0].authorizationToken')
    proxy=$(json_get "$output" '.authorizationData[0].proxyEndpoint')
    [ -n "$token" ]
    [[ "$proxy" =~ ^https?:// ]]
    decoded=$(echo "$token" | base64 -d)
    [[ "$decoded" == AWS:* ]]
}

@test "ECR: list-images on empty repo returns []" {
    aws_cmd ecr create-repository --repository-name "$REPO_NAME" >/dev/null
    run aws_cmd ecr list-images --repository-name "$REPO_NAME"
    assert_success
    count=$(echo "$output" | jq '.imageIds | length')
    [ "$count" = "0" ]
}

@test "ECR: put-image-tag-mutability round-trips IMMUTABLE" {
    aws_cmd ecr create-repository --repository-name "$REPO_NAME" >/dev/null
    run aws_cmd ecr put-image-tag-mutability --repository-name "$REPO_NAME" --image-tag-mutability IMMUTABLE
    assert_success
    mut=$(json_get "$output" '.imageTagMutability')
    [ "$mut" = "IMMUTABLE" ]
}

@test "ECR: put-lifecycle-policy round-trips" {
    aws_cmd ecr create-repository --repository-name "$REPO_NAME" >/dev/null
    policy='{"rules":[{"rulePriority":1,"selection":{"tagStatus":"untagged","countType":"imageCountMoreThan","countNumber":5},"action":{"type":"expire"}}]}'
    aws_cmd ecr put-lifecycle-policy --repository-name "$REPO_NAME" --lifecycle-policy-text "$policy" >/dev/null
    run aws_cmd ecr get-lifecycle-policy --repository-name "$REPO_NAME"
    assert_success
    got=$(json_get "$output" '.lifecyclePolicyText')
    [ "$got" = "$policy" ]
}

@test "ECR: set-repository-policy round-trips" {
    aws_cmd ecr create-repository --repository-name "$REPO_NAME" >/dev/null
    policy='{"Version":"2012-10-17","Statement":[{"Sid":"AllowAll","Effect":"Allow","Principal":"*","Action":"ecr:*"}]}'
    aws_cmd ecr set-repository-policy --repository-name "$REPO_NAME" --policy-text "$policy" >/dev/null
    run aws_cmd ecr get-repository-policy --repository-name "$REPO_NAME"
    assert_success
    got=$(json_get "$output" '.policyText')
    [ "$got" = "$policy" ]
}

@test "ECR: delete-repository force=true removes the repo" {
    aws_cmd ecr create-repository --repository-name "$REPO_NAME" >/dev/null
    run aws_cmd ecr delete-repository --repository-name "$REPO_NAME" --force
    assert_success
    run aws_cmd ecr describe-repositories --repository-names "$REPO_NAME"
    assert_failure
    [[ "$output" == *"RepositoryNotFoundException"* ]]
}

@test "ECR: describe-repositories on missing name fails" {
    run aws_cmd ecr describe-repositories --repository-names "does-not-exist-cli"
    assert_failure
    [[ "$output" == *"RepositoryNotFoundException"* ]]
}
