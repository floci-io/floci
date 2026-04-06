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
