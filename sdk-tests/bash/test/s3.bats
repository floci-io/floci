#!/usr/bin/env bats
# S3 integration tests using AWS CLI and bats-core

load 'test_helper/common-setup'

# Generate unique bucket name for this test run
setup_file() {
    # Check connectivity to floci
    if ! curl -sf "$AWS_ENDPOINT_URL" > /dev/null 2>&1; then
        echo "Cannot connect to floci at $AWS_ENDPOINT_URL" >&3
        echo "Make sure floci is running before running tests." >&3
        return 1
    fi

    # Generate unique bucket name
    export BUCKET_NAME="test-bucket-$(cat /proc/sys/kernel/random/uuid | cut -c1-8 | tr '[:upper:]' '[:lower:]')"

    # Create the main test bucket
    aws s3api create-bucket --bucket "$BUCKET_NAME" >/dev/null 2>&1
}

teardown_file() {
    # Clean up: remove all objects and delete bucket
    aws s3 rm "s3://$BUCKET_NAME" --recursive 2>/dev/null || true
    aws s3api delete-bucket --bucket "$BUCKET_NAME" 2>/dev/null || true
}

# ============================================
# Test Cases
# ============================================

@test "S3: create bucket" {
    local bucket_name="test-create-$(date +%s)"

    run aws s3api create-bucket --bucket "$bucket_name"
    assert_success

    # Cleanup
    aws s3api delete-bucket --bucket "$bucket_name" 2>/dev/null || true
}

@test "S3: list buckets returns Buckets field" {
    run aws s3api list-buckets
    assert_success

    # Verify JSON structure
    run jq -e '.Buckets' <<< "$output"
    assert_success
}

@test "S3: list buckets contains test bucket" {
    run aws s3api list-buckets
    assert_success

    run jq -e ".Buckets[] | select(.Name == \"$BUCKET_NAME\")" <<< "$output"
    assert_success
}

@test "S3: put object" {
    local object_key="test-object-$(date +%s).txt"
    local temp_file=$(mktemp)
    echo -n "Hello, floci!" > "$temp_file"

    run aws s3api put-object \
        --bucket "$BUCKET_NAME" \
        --key "$object_key" \
        --body "$temp_file"
    assert_success

    # Cleanup
    rm -f "$temp_file"
    aws s3api delete-object --bucket "$BUCKET_NAME" --key "$object_key" 2>/dev/null || true
}

@test "S3: get object returns correct content" {
    local object_key="test-get-$(date +%s).txt"
    local put_file=$(mktemp)
    local get_file=$(mktemp)
    local expected_content="Hello, floci!"
    echo -n "$expected_content" > "$put_file"

    # Setup: put an object
    aws s3api put-object \
        --bucket "$BUCKET_NAME" \
        --key "$object_key" \
        --body "$put_file" >/dev/null

    # Test: get the object
    run aws s3api get-object \
        --bucket "$BUCKET_NAME" \
        --key "$object_key" \
        "$get_file"
    assert_success

    # Verify content
    local actual_content=$(cat "$get_file")
    assert_equal "$expected_content" "$actual_content"

    # Cleanup
    rm -f "$put_file" "$get_file"
    aws s3api delete-object --bucket "$BUCKET_NAME" --key "$object_key" 2>/dev/null || true
}

@test "S3: list objects returns Contents field" {
    local object_key="test-list-$(date +%s).txt"
    local temp_file=$(mktemp)
    echo "test content" > "$temp_file"

    # Setup: put an object
    aws s3api put-object \
        --bucket "$BUCKET_NAME" \
        --key "$object_key" \
        --body "$temp_file" >/dev/null
    rm -f "$temp_file"

    # Test: list objects
    run aws s3api list-objects-v2 --bucket "$BUCKET_NAME"
    assert_success

    run jq -e '.Contents' <<< "$output"
    assert_success

    # Cleanup
    aws s3api delete-object --bucket "$BUCKET_NAME" --key "$object_key" 2>/dev/null || true
}

@test "S3: list objects contains test object" {
    local object_key="test-list-contains-$(date +%s).txt"
    local temp_file=$(mktemp)
    echo "test content" > "$temp_file"

    # Setup: put an object
    aws s3api put-object \
        --bucket "$BUCKET_NAME" \
        --key "$object_key" \
        --body "$temp_file" >/dev/null
    rm -f "$temp_file"

    # Test: list objects and find our object
    local list_output=$(aws s3api list-objects-v2 --bucket "$BUCKET_NAME")

    run jq -e ".Contents[] | select(.Key == \"$object_key\")" <<< "$list_output"
    assert_success

    # Cleanup
    aws s3api delete-object --bucket "$BUCKET_NAME" --key "$object_key" 2>/dev/null || true
}

@test "S3: delete object" {
    local object_key="test-delete-obj-$(date +%s).txt"
    local temp_file=$(mktemp)
    echo "test content" > "$temp_file"

    # Setup: put an object
    aws s3api put-object \
        --bucket "$BUCKET_NAME" \
        --key "$object_key" \
        --body "$temp_file" >/dev/null
    rm -f "$temp_file"

    # Test: delete the object
    run aws s3api delete-object \
        --bucket "$BUCKET_NAME" \
        --key "$object_key"
    assert_success

    # Verify deletion - object should not exist
    local list_output=$(aws s3api list-objects-v2 --bucket "$BUCKET_NAME")
    run jq -e ".Contents[] | select(.Key == \"$object_key\")" <<< "$list_output"
    assert_failure  # Should fail because object is gone
}

@test "S3: delete bucket" {
    local bucket_name="test-delete-bucket-$(date +%s | cut -c6-10)"

    # Setup: create a bucket
    aws s3api create-bucket --bucket "$bucket_name" >/dev/null

    # Test: delete the bucket
    run aws s3api delete-bucket --bucket "$bucket_name"
    assert_success

    # Verify deletion - bucket should not exist
    local list_output=$(aws s3api list-buckets)
    run jq -e ".Buckets[] | select(.Name == \"$bucket_name\")" <<< "$list_output"
    assert_failure  # Should fail because bucket is gone
}
