#!/usr/bin/env bats
# Cognito tests

setup() {
    load 'test_helper/common-setup'
    POOL_ID=""
    CLIENT_ID=""
}

teardown() {
    if [ -n "$POOL_ID" ]; then
        aws_cmd cognito-idp delete-user-pool --user-pool-id "$POOL_ID" >/dev/null 2>&1 || true
    fi
}

@test "Cognito: create user pool" {
    run aws_cmd cognito-idp create-user-pool --pool-name "bats-test-pool-$(unique_name)"
    assert_success
    POOL_ID=$(json_get "$output" '.UserPool.Id')
    [ -n "$POOL_ID" ]
}

@test "Cognito: create user pool client" {
    out=$(aws_cmd cognito-idp create-user-pool --pool-name "bats-test-pool-$(unique_name)")
    POOL_ID=$(json_get "$out" '.UserPool.Id')

    run aws_cmd cognito-idp create-user-pool-client \
        --user-pool-id "$POOL_ID" \
        --client-name "bats-test-client"
    assert_success
    CLIENT_ID=$(json_get "$output" '.UserPoolClient.ClientId')
    [ -n "$CLIENT_ID" ]
}

@test "Cognito: list user pool clients returns only description fields" {
    out=$(aws_cmd cognito-idp create-user-pool --pool-name "bats-test-pool-$(unique_name)")
    POOL_ID=$(json_get "$out" '.UserPool.Id')

    aws_cmd cognito-idp create-user-pool-client \
        --user-pool-id "$POOL_ID" \
        --client-name "bats-list-client" \
        --generate-secret >/dev/null

    run aws_cmd cognito-idp list-user-pool-clients --user-pool-id "$POOL_ID"
    assert_success

    # Should have the required fields
    client_id=$(echo "$output" | jq -r '.UserPoolClients[0].ClientId')
    [ -n "$client_id" ]
    client_name=$(echo "$output" | jq -r '.UserPoolClients[0].ClientName')
    [ "$client_name" = "bats-list-client" ]

    # Must NOT have fields that belong to the full UserPoolClient type
    has_secret=$(echo "$output" | jq 'any(.UserPoolClients[]; has("ClientSecret"))')
    [ "$has_secret" = "false" ]
    has_generate=$(echo "$output" | jq 'any(.UserPoolClients[]; has("GenerateSecret"))')
    [ "$has_generate" = "false" ]
    has_flows=$(echo "$output" | jq 'any(.UserPoolClients[]; has("AllowedOAuthFlows"))')
    [ "$has_flows" = "false" ]
}

@test "Cognito: admin create user" {
    out=$(aws_cmd cognito-idp create-user-pool --pool-name "bats-test-pool-$(unique_name)")
    POOL_ID=$(json_get "$out" '.UserPool.Id')

    run aws_cmd cognito-idp admin-create-user \
        --user-pool-id "$POOL_ID" \
        --username "testuser" \
        --temporary-password "Temp123!" \
        --user-attributes Name=email,Value=test@example.com
    assert_success
    username=$(json_get "$output" '.User.Username')
    [ "$username" = "testuser" ]
}

@test "Cognito: admin set user password" {
    out=$(aws_cmd cognito-idp create-user-pool --pool-name "bats-test-pool-$(unique_name)")
    POOL_ID=$(json_get "$out" '.UserPool.Id')

    aws_cmd cognito-idp admin-create-user \
        --user-pool-id "$POOL_ID" \
        --username "testuser" \
        --temporary-password "Temp123!" \
        --user-attributes Name=email,Value=test@example.com >/dev/null

    run aws_cmd cognito-idp admin-set-user-password \
        --user-pool-id "$POOL_ID" \
        --username "testuser" \
        --password "Perm456!" \
        --permanent
    assert_success
}

@test "Cognito: admin get user" {
    out=$(aws_cmd cognito-idp create-user-pool --pool-name "bats-test-pool-$(unique_name)")
    POOL_ID=$(json_get "$out" '.UserPool.Id')

    aws_cmd cognito-idp admin-create-user \
        --user-pool-id "$POOL_ID" \
        --username "testuser" \
        --temporary-password "Temp123!" \
        --user-attributes Name=email,Value=test@example.com >/dev/null

    aws_cmd cognito-idp admin-set-user-password \
        --user-pool-id "$POOL_ID" \
        --username "testuser" \
        --password "Perm456!" \
        --permanent >/dev/null

    run aws_cmd cognito-idp admin-get-user \
        --user-pool-id "$POOL_ID" \
        --username "testuser"
    assert_success
    status=$(json_get "$output" '.UserStatus')
    [ "$status" = "CONFIRMED" ]
}

@test "Cognito: list users" {
    out=$(aws_cmd cognito-idp create-user-pool --pool-name "bats-test-pool-$(unique_name)")
    POOL_ID=$(json_get "$out" '.UserPool.Id')

    aws_cmd cognito-idp admin-create-user \
        --user-pool-id "$POOL_ID" \
        --username "testuser" \
        --temporary-password "Temp123!" >/dev/null

    run aws_cmd cognito-idp list-users --user-pool-id "$POOL_ID"
    assert_success
    found=$(echo "$output" | jq '.Users | any(.Username == "testuser")')
    [ "$found" = "true" ]
}

@test "Cognito: JWKS endpoint" {
    out=$(aws_cmd cognito-idp create-user-pool --pool-name "bats-test-pool-$(unique_name)")
    POOL_ID=$(json_get "$out" '.UserPool.Id')

    run curl -sS "$FLOCI_ENDPOINT/$POOL_ID/.well-known/jwks.json"
    assert_success

    keys_count=$(echo "$output" | jq '.keys | length')
    [ "$keys_count" -gt 0 ]

    key_type=$(echo "$output" | jq -r '.keys[0].kty')
    [ "$key_type" = "RSA" ]

    alg=$(echo "$output" | jq -r '.keys[0].alg')
    [ "$alg" = "RS256" ]
}

@test "Cognito: delete user pool" {
    out=$(aws_cmd cognito-idp create-user-pool --pool-name "bats-test-pool-$(unique_name)")
    POOL_ID=$(json_get "$out" '.UserPool.Id')

    run aws_cmd cognito-idp delete-user-pool --user-pool-id "$POOL_ID"
    assert_success
    POOL_ID=""
}

@test "Cognito: create user pool with reserved override tag uses pinned ID and strips reserved tag" {
    run aws_cmd cognito-idp create-user-pool \
        --pool-name "bats-test-pool-$(unique_name)" \
        --user-pool-tags floci:override-id=us-east-1_batspool1,env=test
    assert_success
    POOL_ID=$(json_get "$output" '.UserPool.Id')
    [ "$POOL_ID" = "us-east-1_batspool1" ]

    has_reserved=$(echo "$output" | jq '.UserPool.UserPoolTags | has("floci:override-id")')
    [ "$has_reserved" = "false" ]

    run aws_cmd cognito-idp describe-user-pool --user-pool-id "$POOL_ID"
    assert_success
    has_reserved=$(echo "$output" | jq '.UserPool.UserPoolTags | has("floci:override-id")')
    [ "$has_reserved" = "false" ]
    env_value=$(json_get "$output" '.UserPool.UserPoolTags.env')
    [ "$env_value" = "test" ]
}

@test "Cognito: duplicate reserved override tag fails with ResourceConflictException" {
    out=$(aws_cmd cognito-idp create-user-pool \
        --pool-name "bats-test-pool-$(unique_name)" \
        --user-pool-tags floci:override-id=us-east-1_batsdup01)
    POOL_ID=$(json_get "$out" '.UserPool.Id')

    run aws_cmd cognito-idp create-user-pool \
        --pool-name "bats-test-pool-$(unique_name)" \
        --user-pool-tags floci:override-id=us-east-1_batsdup01
    assert_failure
    [[ "$output" == *"ResourceConflictException"* ]]
}

@test "Cognito: tag-resource list-tags-for-resource and untag-resource manage user pool tags" {
    out=$(aws_cmd cognito-idp create-user-pool --pool-name "bats-test-pool-$(unique_name)")
    POOL_ID=$(json_get "$out" '.UserPool.Id')
    RESOURCE_ARN=$(json_get "$out" '.UserPool.Arn')

    run aws_cmd cognito-idp tag-resource \
        --resource-arn "$RESOURCE_ARN" \
        --tags env=test,team=platform
    assert_success

    run aws_cmd cognito-idp list-tags-for-resource --resource-arn "$RESOURCE_ARN"
    assert_success
    env_value=$(json_get "$output" '.Tags.env')
    [ "$env_value" = "test" ]
    team_value=$(json_get "$output" '.Tags.team')
    [ "$team_value" = "platform" ]

    run aws_cmd cognito-idp untag-resource \
        --resource-arn "$RESOURCE_ARN" \
        --tag-keys team
    assert_success

    run aws_cmd cognito-idp list-tags-for-resource --resource-arn "$RESOURCE_ARN"
    assert_success
    env_value=$(json_get "$output" '.Tags.env')
    [ "$env_value" = "test" ]
    has_team=$(echo "$output" | jq '.Tags | has("team")')
    [ "$has_team" = "false" ]
}

@test "Cognito: standalone tag-resource rejects reserved floci tags" {
    out=$(aws_cmd cognito-idp create-user-pool --pool-name "bats-test-pool-$(unique_name)")
    POOL_ID=$(json_get "$out" '.UserPool.Id')
    RESOURCE_ARN=$(json_get "$out" '.UserPool.Arn')

    run aws_cmd cognito-idp tag-resource \
        --resource-arn "$RESOURCE_ARN" \
        --tags floci:override-id=late-id
    assert_failure
    [[ "$output" == *"ValidationException"* ]]
}
