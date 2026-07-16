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

@test "KMS: list grants" {
    out=$(aws_cmd kms create-key --description "bats-test-key")
    KEY_ID=$(json_get "$out" '.KeyMetadata.KeyId')
    [ -n "$KEY_ID" ]

    run aws_cmd kms list-grants --key-id "$KEY_ID"
    assert_success
    has_grants=$(echo "$output" | jq 'has("Grants") and (.Grants | type == "array")')
    [ "$has_grants" = "true" ]
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
    alias_name="alias/bats-test-$(unique_name)"

    run aws_cmd kms create-alias --alias-name "$alias_name" --target-key-id "$KEY_ID"
    assert_success

    # Cleanup alias
    aws_cmd kms delete-alias --alias-name "$alias_name" >/dev/null 2>&1 || true
}

@test "KMS: list aliases" {
    out=$(aws_cmd kms create-key --description "bats-test-key")
    KEY_ID=$(json_get "$out" '.KeyMetadata.KeyId')
    alias_name="alias/bats-test-$(unique_name)"

    aws_cmd kms create-alias --alias-name "$alias_name" --target-key-id "$KEY_ID" >/dev/null

    run aws_cmd kms list-aliases
    assert_success
    found=$(echo "$output" | jq --arg name "$alias_name" '.Aliases | any(.AliasName == $name)')
    [ "$found" = "true" ]

    # Cleanup alias
    aws_cmd kms delete-alias --alias-name "$alias_name" >/dev/null 2>&1 || true
}

@test "KMS: create HMAC key and describe returns MacAlgorithms" {
    out=$(aws_cmd kms create-key \
        --description "bats-hmac-$(unique_name)" \
        --key-spec HMAC_256 \
        --key-usage GENERATE_VERIFY_MAC)
    KEY_ID=$(json_get "$out" '.KeyMetadata.KeyId')
    [ -n "$KEY_ID" ]

    spec=$(json_get "$out" '.KeyMetadata.KeySpec')
    [ "$spec" = "HMAC_256" ]

    run aws_cmd kms describe-key --key-id "$KEY_ID"
    assert_success
    macs=$(echo "$output" | jq -r '.KeyMetadata.MacAlgorithms[0]')
    [ "$macs" = "HMAC_SHA_256" ]
}

@test "KMS: delete alias" {
    out=$(aws_cmd kms create-key --description "bats-test-key")
    KEY_ID=$(json_get "$out" '.KeyMetadata.KeyId')
    alias_name="alias/bats-test-$(unique_name)"

    aws_cmd kms create-alias --alias-name "$alias_name" --target-key-id "$KEY_ID" >/dev/null

    run aws_cmd kms delete-alias --alias-name "$alias_name"
    assert_success

    # Verify alias is deleted
    run aws_cmd kms list-aliases
    found=$(echo "$output" | jq --arg name "$alias_name" '.Aliases | any(.AliasName == $name)')
    [ "$found" = "false" ]
}

@test "KMS: create key with reserved override tag uses pinned ID and strips reserved tag" {
    run aws_cmd kms create-key \
        --description "override-key" \
        --tags TagKey=floci:override-id,TagValue=bats-pinned-key TagKey=env,TagValue=test
    assert_success
    KEY_ID=$(json_get "$output" '.KeyMetadata.KeyId')
    [ "$KEY_ID" = "bats-pinned-key" ]

    run aws_cmd kms list-resource-tags --key-id "$KEY_ID"
    assert_success
    has_reserved=$(echo "$output" | jq 'any(.Tags[]?; .TagKey == "floci:override-id")')
    [ "$has_reserved" = "false" ]
    env_value=$(echo "$output" | jq -r '.Tags[] | select(.TagKey == "env") | .TagValue')
    [ "$env_value" = "test" ]
}

@test "KMS: duplicate reserved override tag fails with AlreadyExistsException" {
    out=$(aws_cmd kms create-key \
        --description "override-key" \
        --tags TagKey=floci:override-id,TagValue=bats-duplicate-key)
    KEY_ID=$(json_get "$out" '.KeyMetadata.KeyId')

    run aws_cmd kms create-key \
        --description "override-key-2" \
        --tags TagKey=floci:override-id,TagValue=bats-duplicate-key
    assert_failure
    [[ "$output" == *"AlreadyExistsException"* ]]
}

@test "KMS: tag-resource rejects reserved override tag after creation" {
    out=$(aws_cmd kms create-key --description "bats-test-key")
    KEY_ID=$(json_get "$out" '.KeyMetadata.KeyId')

    run aws_cmd kms tag-resource \
        --key-id "$KEY_ID" \
        --tags TagKey=floci:override-id,TagValue=late-id
    assert_failure
    [[ "$output" == *"ValidationException"* ]]
}

# Issue #1844: Decrypt must enforce KeyId against wrapping key
@test "KMS: decrypt with matching KeyId returns plaintext" {
    out=$(aws_cmd kms create-key --description "bats-1844-match")
    KEY_ID=$(json_get "$out" '.KeyMetadata.KeyId')

    plaintext_file=$(mktemp)
    ciphertext_file=$(mktemp)
    echo -n "secret data" > "$plaintext_file"

    run aws_cmd kms encrypt \
        --key-id "$KEY_ID" \
        --plaintext "fileb://$plaintext_file" \
        --output text \
        --query CiphertextBlob
    assert_success
    echo "$output" | base64 -d > "$ciphertext_file"

    run aws_cmd kms decrypt \
        --ciphertext-blob "fileb://$ciphertext_file" \
        --key-id "$KEY_ID" \
        --output text \
        --query Plaintext
    assert_success
    [ "$(echo "$output" | base64 -d)" = "secret data" ]

    rm -f "$plaintext_file" "$ciphertext_file"
}

@test "KMS: decrypt with mismatched KeyId returns IncorrectKeyException" {
    out_a=$(aws_cmd kms create-key --description "bats-1844-a")
    key_a=$(json_get "$out_a" '.KeyMetadata.KeyId')
    out_b=$(aws_cmd kms create-key --description "bats-1844-b")
    key_b=$(json_get "$out_b" '.KeyMetadata.KeyId')

    plaintext_file=$(mktemp)
    ciphertext_file=$(mktemp)
    echo -n "secret data" > "$plaintext_file"

    run aws_cmd kms encrypt \
        --key-id "$key_a" \
        --plaintext "fileb://$plaintext_file" \
        --output text \
        --query CiphertextBlob
    assert_success
    echo "$output" | base64 -d > "$ciphertext_file"

    run aws_cmd kms decrypt \
        --ciphertext-blob "fileb://$ciphertext_file" \
        --key-id "$key_b"
    assert_failure
    [[ "$output" == *"IncorrectKeyException"* ]]

    rm -f "$plaintext_file" "$ciphertext_file"
    aws_cmd kms schedule-key-deletion --key-id "$key_a" --pending-window-in-days 7 >/dev/null 2>&1 || true
    aws_cmd kms schedule-key-deletion --key-id "$key_b" --pending-window-in-days 7 >/dev/null 2>&1 || true
}
