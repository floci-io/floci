#!/usr/bin/env bats
# KMS tests

setup() {
    load 'test_helper/common-setup'
    KEY_ID=""
}

teardown() {
    if [ -n "$KEY_ID" ]; then
        aws_cmd kms schedule-key-deletion --key-id "$KEY_ID" --pending-window-in-days 7 >/dev/null 2>&1 || true
    fi
}

@test "KMS: create key" {
    run aws_cmd kms create-key --description "bats-test-key"
    assert_success
    KEY_ID=$(json_get "$output" '.KeyMetadata.KeyId')
    [ -n "$KEY_ID" ]
}

@test "KMS: describe key" {
    out=$(aws_cmd kms create-key --description "bats-test-key")
    KEY_ID=$(json_get "$out" '.KeyMetadata.KeyId')

    run aws_cmd kms describe-key --key-id "$KEY_ID"
    assert_success
    enabled=$(json_get "$output" '.KeyMetadata.Enabled')
    [ "$enabled" = "true" ]
}

@test "KMS: list keys" {
    out=$(aws_cmd kms create-key --description "bats-test-key")
    KEY_ID=$(json_get "$out" '.KeyMetadata.KeyId')

    run aws_cmd kms list-keys
    assert_success
    found=$(echo "$output" | jq --arg id "$KEY_ID" '.Keys | any(.KeyId == $id)')
    [ "$found" = "true" ]
}

@test "KMS: encrypt and decrypt" {
    out=$(aws_cmd kms create-key --description "bats-test-key")
    KEY_ID=$(json_get "$out" '.KeyMetadata.KeyId')

    # Create a temp file with plaintext
    local plaintext_file ciphertext_file decrypted_file
    plaintext_file=$(mktemp)
    ciphertext_file=$(mktemp)
    decrypted_file=$(mktemp)
    echo -n "hello-kms-bats" > "$plaintext_file"

    run aws_cmd kms encrypt \
        --key-id "$KEY_ID" \
        --plaintext "fileb://$plaintext_file" \
        --output text \
        --query CiphertextBlob
    assert_success
    echo "$output" | base64 -d > "$ciphertext_file"

    run aws_cmd kms decrypt \
        --ciphertext-blob "fileb://$ciphertext_file" \
        --output text \
        --query Plaintext
    assert_success
    echo "$output" | base64 -d > "$decrypted_file"

    decrypted=$(cat "$decrypted_file")
    [ "$decrypted" = "hello-kms-bats" ]

    rm -f "$plaintext_file" "$ciphertext_file" "$decrypted_file"
}

@test "KMS: generate data key" {
    out=$(aws_cmd kms create-key --description "bats-test-key")
    KEY_ID=$(json_get "$out" '.KeyMetadata.KeyId')

    run aws_cmd kms generate-data-key --key-id "$KEY_ID" --key-spec AES_256
    assert_success
    plaintext=$(json_get "$output" '.Plaintext')
    [ -n "$plaintext" ]
    ciphertext=$(json_get "$output" '.CiphertextBlob')
    [ -n "$ciphertext" ]
}

# --- KMS Alias Tests ---

@test "KMS: create alias" {
    out=$(aws_cmd kms create-key --description "bats-test-key")
    KEY_ID=$(json_get "$out" '.KeyMetadata.KeyId')
    alias_name="alias/bats-test-$(date +%s)"

    run aws_cmd kms create-alias --alias-name "$alias_name" --target-key-id "$KEY_ID"
    assert_success

    # Cleanup alias
    aws_cmd kms delete-alias --alias-name "$alias_name" >/dev/null 2>&1 || true
}

@test "KMS: list aliases" {
    out=$(aws_cmd kms create-key --description "bats-test-key")
    KEY_ID=$(json_get "$out" '.KeyMetadata.KeyId')
    alias_name="alias/bats-test-$(date +%s)"

    aws_cmd kms create-alias --alias-name "$alias_name" --target-key-id "$KEY_ID" >/dev/null

    run aws_cmd kms list-aliases
    assert_success
    found=$(echo "$output" | jq --arg name "$alias_name" '.Aliases | any(.AliasName == $name)')
    [ "$found" = "true" ]

    # Cleanup alias
    aws_cmd kms delete-alias --alias-name "$alias_name" >/dev/null 2>&1 || true
}

@test "KMS: delete alias" {
    out=$(aws_cmd kms create-key --description "bats-test-key")
    KEY_ID=$(json_get "$out" '.KeyMetadata.KeyId')
    alias_name="alias/bats-test-$(date +%s)"

    aws_cmd kms create-alias --alias-name "$alias_name" --target-key-id "$KEY_ID" >/dev/null

    run aws_cmd kms delete-alias --alias-name "$alias_name"
    assert_success

    # Verify alias is deleted
    run aws_cmd kms list-aliases
    found=$(echo "$output" | jq --arg name "$alias_name" '.Aliases | any(.AliasName == $name)')
    [ "$found" = "false" ]
}
