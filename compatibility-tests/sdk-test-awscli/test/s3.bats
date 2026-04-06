#!/usr/bin/env bats
# S3 tests

setup() {
    load 'test_helper/common-setup'
    BUCKET="bats-test-bucket-$(unique_name)"
}

teardown() {
    # Clean up all objects in bucket
    aws_cmd s3 rm "s3://$BUCKET" --recursive >/dev/null 2>&1 || true
    aws_cmd s3api delete-bucket --bucket "$BUCKET" >/dev/null 2>&1 || true
}

@test "S3: create bucket" {
    run aws_cmd s3api create-bucket --bucket "$BUCKET"
    assert_success
}

@test "S3: list buckets" {
    aws_cmd s3api create-bucket --bucket "$BUCKET" >/dev/null

    run aws_cmd s3api list-buckets
    assert_success
    found=$(echo "$output" | jq --arg name "$BUCKET" '.Buckets | any(.Name == $name)')
    [ "$found" = "true" ]
}

@test "S3: put object" {
    aws_cmd s3api create-bucket --bucket "$BUCKET" >/dev/null

    local body_file
    body_file=$(mktemp)
    echo -n "hello-s3-bats" > "$body_file"

    run aws_cmd s3api put-object --bucket "$BUCKET" --key "test.txt" --body "$body_file"
    assert_success
    rm -f "$body_file"
}

@test "S3: get object" {
    aws_cmd s3api create-bucket --bucket "$BUCKET" >/dev/null

    local body_file get_file
    body_file=$(mktemp)
    get_file=$(mktemp)
    echo -n "hello-s3-bats" > "$body_file"

    aws_cmd s3api put-object --bucket "$BUCKET" --key "test.txt" --body "$body_file" >/dev/null

    run aws_cmd s3api get-object --bucket "$BUCKET" --key "test.txt" "$get_file"
    assert_success

    content=$(cat "$get_file")
    [ "$content" = "hello-s3-bats" ]

    rm -f "$body_file" "$get_file"
}

@test "S3: head object" {
    aws_cmd s3api create-bucket --bucket "$BUCKET" >/dev/null

    local body_file
    body_file=$(mktemp)
    echo -n "hello-s3-bats" > "$body_file"
    aws_cmd s3api put-object --bucket "$BUCKET" --key "test.txt" --body "$body_file" >/dev/null

    run aws_cmd s3api head-object --bucket "$BUCKET" --key "test.txt"
    assert_success
    length=$(json_get "$output" '.ContentLength')
    [ "$length" = "13" ]

    rm -f "$body_file"
}

@test "S3: list objects" {
    aws_cmd s3api create-bucket --bucket "$BUCKET" >/dev/null

    local body_file
    body_file=$(mktemp)
    echo -n "hello" > "$body_file"
    aws_cmd s3api put-object --bucket "$BUCKET" --key "test.txt" --body "$body_file" >/dev/null

    run aws_cmd s3api list-objects-v2 --bucket "$BUCKET"
    assert_success
    found=$(echo "$output" | jq '.Contents | any(.Key == "test.txt")')
    [ "$found" = "true" ]

    rm -f "$body_file"
}

@test "S3: copy object" {
    aws_cmd s3api create-bucket --bucket "$BUCKET" >/dev/null

    local body_file
    body_file=$(mktemp)
    echo -n "hello" > "$body_file"
    aws_cmd s3api put-object --bucket "$BUCKET" --key "src.txt" --body "$body_file" >/dev/null

    run aws_cmd s3api copy-object --bucket "$BUCKET" --copy-source "$BUCKET/src.txt" --key "dst.txt"
    assert_success

    rm -f "$body_file"
}

@test "S3: put and get object tagging" {
    aws_cmd s3api create-bucket --bucket "$BUCKET" >/dev/null

    local body_file
    body_file=$(mktemp)
    echo -n "hello" > "$body_file"
    aws_cmd s3api put-object --bucket "$BUCKET" --key "test.txt" --body "$body_file" >/dev/null

    run aws_cmd s3api put-object-tagging \
        --bucket "$BUCKET" \
        --key "test.txt" \
        --tagging 'TagSet=[{Key=env,Value=test}]'
    assert_success

    run aws_cmd s3api get-object-tagging --bucket "$BUCKET" --key "test.txt"
    assert_success
    found=$(echo "$output" | jq '.TagSet | any(.Key == "env" and .Value == "test")')
    [ "$found" = "true" ]

    rm -f "$body_file"
}

@test "S3: delete object" {
    aws_cmd s3api create-bucket --bucket "$BUCKET" >/dev/null

    local body_file
    body_file=$(mktemp)
    echo -n "hello" > "$body_file"
    aws_cmd s3api put-object --bucket "$BUCKET" --key "test.txt" --body "$body_file" >/dev/null

    run aws_cmd s3api delete-object --bucket "$BUCKET" --key "test.txt"
    assert_success

    rm -f "$body_file"
}

@test "S3: delete bucket" {
    aws_cmd s3api create-bucket --bucket "$BUCKET" >/dev/null

    run aws_cmd s3api delete-bucket --bucket "$BUCKET"
    assert_success
}

# --- S3 Versioning Tests ---

@test "S3: put bucket versioning" {
    aws_cmd s3api create-bucket --bucket "$BUCKET" >/dev/null

    run aws_cmd s3api put-bucket-versioning \
        --bucket "$BUCKET" \
        --versioning-configuration Status=Enabled
    assert_success
}

@test "S3: versioned objects have version IDs" {
    aws_cmd s3api create-bucket --bucket "$BUCKET" >/dev/null
    aws_cmd s3api put-bucket-versioning \
        --bucket "$BUCKET" \
        --versioning-configuration Status=Enabled >/dev/null

    local body_file
    body_file=$(mktemp)
    echo -n "version-one" > "$body_file"

    run aws_cmd s3api put-object --bucket "$BUCKET" --key "ver.txt" --body "$body_file"
    assert_success
    v1=$(json_get "$output" '.VersionId')
    [ -n "$v1" ]

    echo -n "version-two" > "$body_file"
    run aws_cmd s3api put-object --bucket "$BUCKET" --key "ver.txt" --body "$body_file"
    assert_success
    v2=$(json_get "$output" '.VersionId')
    [ -n "$v2" ]
    [ "$v1" != "$v2" ]

    rm -f "$body_file"
}

# --- S3 Multipart Upload Tests ---

@test "S3: create multipart upload" {
    aws_cmd s3api create-bucket --bucket "$BUCKET" >/dev/null

    run aws_cmd s3api create-multipart-upload \
        --bucket "$BUCKET" \
        --key "multipart.bin"
    assert_success
    upload_id=$(json_get "$output" '.UploadId')
    [ -n "$upload_id" ]

    # Cleanup: abort the upload
    aws_cmd s3api abort-multipart-upload \
        --bucket "$BUCKET" \
        --key "multipart.bin" \
        --upload-id "$upload_id" >/dev/null 2>&1 || true
}

@test "S3: upload part" {
    aws_cmd s3api create-bucket --bucket "$BUCKET" >/dev/null

    output=$(aws_cmd s3api create-multipart-upload \
        --bucket "$BUCKET" \
        --key "multipart.bin")
    upload_id=$(json_get "$output" '.UploadId')

    local part_file
    part_file=$(mktemp)
    echo -n "part-one-data" > "$part_file"

    run aws_cmd s3api upload-part \
        --bucket "$BUCKET" \
        --key "multipart.bin" \
        --upload-id "$upload_id" \
        --part-number 1 \
        --body "$part_file"
    assert_success
    etag=$(json_get "$output" '.ETag')
    [ -n "$etag" ]

    rm -f "$part_file"
    aws_cmd s3api abort-multipart-upload \
        --bucket "$BUCKET" \
        --key "multipart.bin" \
        --upload-id "$upload_id" >/dev/null 2>&1 || true
}

@test "S3: complete multipart upload" {
    aws_cmd s3api create-bucket --bucket "$BUCKET" >/dev/null

    output=$(aws_cmd s3api create-multipart-upload \
        --bucket "$BUCKET" \
        --key "multipart.bin")
    upload_id=$(json_get "$output" '.UploadId')

    local part1_file part2_file
    part1_file=$(mktemp)
    part2_file=$(mktemp)
    echo -n "part-one" > "$part1_file"
    echo -n "part-two" > "$part2_file"

    output=$(aws_cmd s3api upload-part \
        --bucket "$BUCKET" \
        --key "multipart.bin" \
        --upload-id "$upload_id" \
        --part-number 1 \
        --body "$part1_file")
    etag1=$(json_get "$output" '.ETag')

    output=$(aws_cmd s3api upload-part \
        --bucket "$BUCKET" \
        --key "multipart.bin" \
        --upload-id "$upload_id" \
        --part-number 2 \
        --body "$part2_file")
    etag2=$(json_get "$output" '.ETag')

    local mp_file
    mp_file=$(mktemp)
    cat > "$mp_file" <<EOF
{
  "Parts": [
    { "ETag": $etag1, "PartNumber": 1 },
    { "ETag": $etag2, "PartNumber": 2 }
  ]
}
EOF

    run aws_cmd s3api complete-multipart-upload \
        --bucket "$BUCKET" \
        --key "multipart.bin" \
        --upload-id "$upload_id" \
        --multipart-upload "file://$mp_file"
    assert_success

    rm -f "$part1_file" "$part2_file" "$mp_file"
}

# --- S3 Large File Test ---

@test "S3: put object 25 MB" {
    aws_cmd s3api create-bucket --bucket "$BUCKET" >/dev/null

    local large_file
    large_file=$(mktemp)
    dd if=/dev/zero of="$large_file" bs=1048576 count=25 2>/dev/null

    run aws_cmd s3api put-object \
        --bucket "$BUCKET" \
        --key "large-25mb.bin" \
        --body "$large_file"
    assert_success

    run aws_cmd s3api head-object \
        --bucket "$BUCKET" \
        --key "large-25mb.bin"
    assert_success
    length=$(json_get "$output" '.ContentLength')
    [ "$length" = "26214400" ]

    rm -f "$large_file"
}
