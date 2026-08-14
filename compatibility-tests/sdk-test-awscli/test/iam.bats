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

# ─── OIDC identity providers ────────────────────────────────────────────────

# Creates a provider and sets OIDC_ARN. Each test uses a unique URL so the suite
# stays safe when bats runs test files in parallel.
create_oidc_provider() {
    local url="https://oidc.bats.example.com/id/$(date +%s)-$$-${1:-0}"
    local out
    out=$(aws_cmd iam create-open-id-connect-provider \
        --url "$url" \
        --client-id-list sts.amazonaws.com \
        --thumbprint-list 9e99a48a9960b14926bb7f3b02e22da2b0ab7280)
    OIDC_ARN=$(json_get "$out" '.OpenIDConnectProviderArn')
    OIDC_HOST="${url#https://}"
}

@test "IAM: create and get OIDC provider" {
    create_oidc_provider 1
    [ -n "$OIDC_ARN" ]

    run aws_cmd iam get-open-id-connect-provider --open-id-connect-provider-arn "$OIDC_ARN"
    assert_success
    # AWS reports the URL without its scheme.
    url=$(json_get "$output" '.Url')
    [ "$url" = "$OIDC_HOST" ]
    client=$(json_get "$output" '.ClientIDList[0]')
    [ "$client" = "sts.amazonaws.com" ]

    aws_cmd iam delete-open-id-connect-provider --open-id-connect-provider-arn "$OIDC_ARN" >/dev/null
}

@test "IAM: list OIDC providers includes the created provider" {
    create_oidc_provider 2

    run aws_cmd iam list-open-id-connect-providers
    assert_success
    found=$(echo "$output" | jq --arg arn "$OIDC_ARN" '.OpenIDConnectProviderList | any(.Arn == $arn)')
    [ "$found" = "true" ]

    aws_cmd iam delete-open-id-connect-provider --open-id-connect-provider-arn "$OIDC_ARN" >/dev/null
}

@test "IAM: add and remove an OIDC client id" {
    create_oidc_provider 3

    run aws_cmd iam add-client-id-to-open-id-connect-provider \
        --open-id-connect-provider-arn "$OIDC_ARN" --client-id extra.audience
    assert_success

    run aws_cmd iam get-open-id-connect-provider --open-id-connect-provider-arn "$OIDC_ARN"
    assert_success
    count=$(json_get "$output" '.ClientIDList | length')
    [ "$count" = "2" ]

    run aws_cmd iam remove-client-id-from-open-id-connect-provider \
        --open-id-connect-provider-arn "$OIDC_ARN" --client-id extra.audience
    assert_success

    aws_cmd iam delete-open-id-connect-provider --open-id-connect-provider-arn "$OIDC_ARN" >/dev/null
}

@test "IAM: update OIDC provider thumbprint" {
    create_oidc_provider 4

    run aws_cmd iam update-open-id-connect-provider-thumbprint \
        --open-id-connect-provider-arn "$OIDC_ARN" \
        --thumbprint-list 1111111111111111111111111111111111111111
    assert_success

    run aws_cmd iam get-open-id-connect-provider --open-id-connect-provider-arn "$OIDC_ARN"
    assert_success
    thumb=$(json_get "$output" '.ThumbprintList[0]')
    [ "$thumb" = "1111111111111111111111111111111111111111" ]

    aws_cmd iam delete-open-id-connect-provider --open-id-connect-provider-arn "$OIDC_ARN" >/dev/null
}

@test "IAM: delete OIDC provider" {
    create_oidc_provider 5

    run aws_cmd iam delete-open-id-connect-provider --open-id-connect-provider-arn "$OIDC_ARN"
    assert_success

    run aws_cmd iam get-open-id-connect-provider --open-id-connect-provider-arn "$OIDC_ARN"
    assert_failure
    assert_output --partial "NoSuchEntity"
}

@test "IAM: OIDC provider url must be https" {
    run aws_cmd iam create-open-id-connect-provider \
        --url http://oidc.bats.example.com/id/insecure \
        --thumbprint-list 9e99a48a9960b14926bb7f3b02e22da2b0ab7280
    assert_failure
    assert_output --partial "ValidationError"
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
