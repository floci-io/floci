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

@test "S3: metrics configurations round-trip and never touch the bucket" {
    aws_cmd s3api create-bucket --bucket "$BUCKET" >/dev/null

    run aws_cmd s3api put-bucket-metrics-configuration \
        --bucket "$BUCKET" --id EntireBucket --metrics-configuration 'Id=EntireBucket'
    assert_success

    run aws_cmd s3api get-bucket-metrics-configuration --bucket "$BUCKET" --id EntireBucket
    assert_success
    [ "$(json_get "$output" '.MetricsConfiguration.Id')" = "EntireBucket" ]

    run aws_cmd s3api put-bucket-metrics-configuration \
        --bucket "$BUCKET" --id Filtered \
        --metrics-configuration 'Id=Filtered,Filter={And={Prefix=logs/,Tags=[{Key=env,Value=prod}]}}'
    assert_success

    run aws_cmd s3api list-bucket-metrics-configurations --bucket "$BUCKET"
    assert_success
    [ "$(json_get "$output" '.MetricsConfigurationList | length')" = "2" ]
    [ "$(json_get "$output" '.MetricsConfigurationList[1].Filter.And.Prefix')" = "logs/" ]
    [ "$(json_get "$output" '.MetricsConfigurationList[1].Filter.And.Tags[0].Key')" = "env" ]

    # A sub-resource DELETE removes only that configuration; the bucket must survive it.
    run aws_cmd s3api delete-bucket-metrics-configuration --bucket "$BUCKET" --id EntireBucket
    assert_success

    run aws_cmd s3api head-bucket --bucket "$BUCKET"
    assert_success

    run aws_cmd s3api get-bucket-metrics-configuration --bucket "$BUCKET" --id EntireBucket
    assert_failure
    assert_output --partial "NoSuchConfiguration"
}

@test "S3: intelligent-tiering configurations round-trip and never touch the bucket" {
    aws_cmd s3api create-bucket --bucket "$BUCKET" >/dev/null

    run aws_cmd s3api put-bucket-intelligent-tiering-configuration \
        --bucket "$BUCKET" --id EntireBucket \
        --intelligent-tiering-configuration 'Id=EntireBucket,Status=Enabled,Tierings=[{AccessTier=ARCHIVE_ACCESS,Days=90}]'
    assert_success

    run aws_cmd s3api get-bucket-intelligent-tiering-configuration --bucket "$BUCKET" --id EntireBucket
    assert_success
    [ "$(json_get "$output" '.IntelligentTieringConfiguration.Id')" = "EntireBucket" ]
    [ "$(json_get "$output" '.IntelligentTieringConfiguration.Status')" = "Enabled" ]
    [ "$(json_get "$output" '.IntelligentTieringConfiguration.Tierings[0].AccessTier')" = "ARCHIVE_ACCESS" ]
    [ "$(json_get "$output" '.IntelligentTieringConfiguration.Tierings[0].Days')" = "90" ]

    run aws_cmd s3api put-bucket-intelligent-tiering-configuration \
        --bucket "$BUCKET" --id Filtered \
        --intelligent-tiering-configuration 'Id=Filtered,Filter={And={Prefix=logs/,Tags=[{Key=env,Value=prod}]}},Status=Disabled,Tierings=[{AccessTier=ARCHIVE_ACCESS,Days=90},{AccessTier=DEEP_ARCHIVE_ACCESS,Days=180}]'
    assert_success

    run aws_cmd s3api list-bucket-intelligent-tiering-configurations --bucket "$BUCKET"
    assert_success
    [ "$(json_get "$output" '.IntelligentTieringConfigurationList | length')" = "2" ]
    [ "$(json_get "$output" '.IntelligentTieringConfigurationList[1].Filter.And.Prefix')" = "logs/" ]
    [ "$(json_get "$output" '.IntelligentTieringConfigurationList[1].Filter.And.Tags[0].Key')" = "env" ]
    [ "$(json_get "$output" '.IntelligentTieringConfigurationList[1].Status')" = "Disabled" ]
    [ "$(json_get "$output" '.IntelligentTieringConfigurationList[1].Tierings | length')" = "2" ]

    # Putting the same id replaces rather than duplicates.
    run aws_cmd s3api put-bucket-intelligent-tiering-configuration \
        --bucket "$BUCKET" --id EntireBucket \
        --intelligent-tiering-configuration 'Id=EntireBucket,Status=Disabled,Tierings=[{AccessTier=DEEP_ARCHIVE_ACCESS,Days=180}]'
    assert_success

    run aws_cmd s3api get-bucket-intelligent-tiering-configuration --bucket "$BUCKET" --id EntireBucket
    assert_success
    [ "$(json_get "$output" '.IntelligentTieringConfiguration.Status')" = "Disabled" ]
    [ "$(json_get "$output" '.IntelligentTieringConfiguration.Tierings[0].AccessTier')" = "DEEP_ARCHIVE_ACCESS" ]

    run aws_cmd s3api list-bucket-intelligent-tiering-configurations --bucket "$BUCKET"
    assert_success
    [ "$(json_get "$output" '.IntelligentTieringConfigurationList | length')" = "2" ]

    # A sub-resource DELETE removes only that configuration; the bucket must survive it.
    run aws_cmd s3api delete-bucket-intelligent-tiering-configuration --bucket "$BUCKET" --id EntireBucket
    assert_success

    run aws_cmd s3api head-bucket --bucket "$BUCKET"
    assert_success

    run aws_cmd s3api get-bucket-intelligent-tiering-configuration --bucket "$BUCKET" --id EntireBucket
    assert_failure
    assert_output --partial "NoSuchConfiguration"
}

# Checksum helpers: Base64 SHA256 of a file, and the composite S3 stores for a multipart upload
# (SHA256 over the concatenated binary part digests, suffixed with the part count).
sha256_b64() {
    openssl dgst -sha256 -binary "$1" | base64 | tr -d '\n'
}

sha256_composite() {
    local count=$#
    for part in "$@"; do openssl dgst -sha256 -binary "$part"; done \
        | openssl dgst -sha256 -binary | base64 | tr -d '\n'
    echo "-$count"
}

@test "S3: multipart upload with SHA256 stores the composite checksum" {
    aws_cmd s3api create-bucket --bucket "$BUCKET" >/dev/null
    local dir
    dir=$(mktemp -d)
    head -c $((5 * 1024 * 1024)) /dev/urandom > "$dir/part1"
    head -c $((1024 * 1024)) /dev/urandom > "$dir/part2"
    local sha1 sha2 composite
    sha1=$(sha256_b64 "$dir/part1")
    sha2=$(sha256_b64 "$dir/part2")
    composite=$(sha256_composite "$dir/part1" "$dir/part2")

    output=$(aws_cmd s3api create-multipart-upload --bucket "$BUCKET" --key "composite.bin" \
        --checksum-algorithm SHA256)
    upload_id=$(json_get "$output" '.UploadId')
    [ "$(json_get "$output" '.ChecksumAlgorithm')" = "SHA256" ]
    [ "$(json_get "$output" '.ChecksumType')" = "COMPOSITE" ]

    output=$(aws_cmd s3api upload-part --bucket "$BUCKET" --key "composite.bin" --upload-id "$upload_id" \
        --part-number 1 --body "$dir/part1" --checksum-sha256 "$sha1")
    etag1=$(json_get "$output" '.ETag')
    # UploadPart echoes the checksum of the part
    [ "$(json_get "$output" '.ChecksumSHA256')" = "$sha1" ]
    output=$(aws_cmd s3api upload-part --bucket "$BUCKET" --key "composite.bin" --upload-id "$upload_id" \
        --part-number 2 --body "$dir/part2" --checksum-sha256 "$sha2")
    etag2=$(json_get "$output" '.ETag')

    local parts_json
    parts_json=$(jq -n --arg e1 "$etag1" --arg s1 "$sha1" --arg e2 "$etag2" --arg s2 "$sha2" \
        '{Parts: [{PartNumber: 1, ETag: $e1, ChecksumSHA256: $s1}, {PartNumber: 2, ETag: $e2, ChecksumSHA256: $s2}]}')
    run aws_cmd s3api complete-multipart-upload --bucket "$BUCKET" --key "composite.bin" \
        --upload-id "$upload_id" --multipart-upload "$parts_json"
    assert_success
    [ "$(json_get "$output" '.ChecksumSHA256')" = "$composite" ]
    [ "$(json_get "$output" '.ChecksumType')" = "COMPOSITE" ]

    run aws_cmd s3api head-object --bucket "$BUCKET" --key "composite.bin" --checksum-mode ENABLED
    assert_success
    [ "$(json_get "$output" '.ChecksumSHA256')" = "$composite" ]
    [ "$(json_get "$output" '.ChecksumType')" = "COMPOSITE" ]

    # GetObjectAttributes reports the composite without the "-2" suffix and the ETag without quotes
    run aws_cmd s3api get-object-attributes --bucket "$BUCKET" --key "composite.bin" \
        --object-attributes Checksum ObjectParts ETag
    assert_success
    [ "$(json_get "$output" '.Checksum.ChecksumSHA256')" = "${composite%-2}" ]
    [ "$(json_get "$output" '.ObjectParts.TotalPartsCount')" = "2" ]
    [ "$(json_get "$output" '.ObjectParts.Parts[0].ChecksumSHA256')" = "$sha1" ]
    [[ "$(json_get "$output" '.ETag')" =~ ^[0-9a-f]{32}-2$ ]]
    rm -rf "$dir"
}

@test "S3: complete-multipart-upload without part checksums is rejected on a SHA256 upload" {
    aws_cmd s3api create-bucket --bucket "$BUCKET" >/dev/null
    local part_file
    part_file=$(mktemp)
    echo -n "part-one" > "$part_file"
    output=$(aws_cmd s3api create-multipart-upload --bucket "$BUCKET" --key "strict.bin" --checksum-algorithm SHA256)
    upload_id=$(json_get "$output" '.UploadId')
    output=$(aws_cmd s3api upload-part --bucket "$BUCKET" --key "strict.bin" --upload-id "$upload_id" \
        --part-number 1 --body "$part_file" --checksum-sha256 "$(sha256_b64 "$part_file")")
    etag1=$(json_get "$output" '.ETag')

    run aws_cmd s3api complete-multipart-upload --bucket "$BUCKET" --key "strict.bin" --upload-id "$upload_id" \
        --multipart-upload "$(jq -n --arg e1 "$etag1" '{Parts: [{PartNumber: 1, ETag: $e1}]}')"
    assert_failure
    assert_output --partial "InvalidRequest"
    assert_output --partial "must include the checksum for each part"

    aws_cmd s3api abort-multipart-upload --bucket "$BUCKET" --key "strict.bin" --upload-id "$upload_id" >/dev/null 2>&1 || true
    rm -f "$part_file"
}

@test "S3: aws s3 cp --checksum-algorithm uploads a large file with a composite checksum" {
    aws_cmd s3api create-bucket --bucket "$BUCKET" >/dev/null
    local dir
    dir=$(mktemp -d)
    head -c $((12 * 1024 * 1024)) /dev/urandom > "$dir/large.bin"
    # the CLI splits at its default 8 MiB part size, so the composite is over two parts
    split -b 8m "$dir/large.bin" "$dir/piece."
    local composite
    composite=$(sha256_composite "$dir"/piece.*)
    [[ "$composite" == *-2 ]]

    run aws_cmd s3 cp "$dir/large.bin" "s3://$BUCKET/large.bin" --checksum-algorithm SHA256
    assert_success

    run aws_cmd s3api head-object --bucket "$BUCKET" --key "large.bin" --checksum-mode ENABLED
    assert_success
    [ "$(json_get "$output" '.ChecksumSHA256')" = "$composite" ]
    [ "$(json_get "$output" '.ChecksumType')" = "COMPOSITE" ]

    run aws_cmd s3 cp "s3://$BUCKET/large.bin" "$dir/roundtrip.bin"
    assert_success
    same_content "$dir/large.bin" "$dir/roundtrip.bin"
    rm -rf "$dir"
}

# CRC32 (IEEE) checksums as S3 encodes them (Base64 of the big-endian value); python3 ships in the CLI image.
crc32_b64() {
    python3 -c 'import sys, zlib, base64
print(base64.b64encode((zlib.crc32(open(sys.argv[1], "rb").read()) & 0xffffffff).to_bytes(4, "big")).decode())' "$1"
}

crc32_composite() {
    python3 -c 'import sys, zlib, base64
def crc(data): return (zlib.crc32(data) & 0xffffffff).to_bytes(4, "big")
parts = [open(p, "rb").read() for p in sys.argv[1:]]
print(base64.b64encode(crc(b"".join(crc(p) for p in parts))).decode() + f"-{len(parts)}")' "$@"
}

same_content() {
    [ "$(openssl dgst -sha256 -r "$1" | cut -d" " -f1)" = "$(openssl dgst -sha256 -r "$2" | cut -d" " -f1)" ]
}

@test "S3: plain aws s3 cp of a large file uploads in parts and stores the CLI's default checksum" {
    aws_cmd s3api create-bucket --bucket "$BUCKET" >/dev/null
    local dir
    dir=$(mktemp -d)
    head -c $((12 * 1024 * 1024)) /dev/urandom > "$dir/large.bin"
    split -b 8m "$dir/large.bin" "$dir/piece."

    # no --checksum-algorithm: the CLI multipart-uploads at 8 MiB parts with whatever its version declares
    run aws_cmd s3 cp "$dir/large.bin" "s3://$BUCKET/large-default.bin"
    assert_success

    run aws_cmd s3api head-object --bucket "$BUCKET" --key "large-default.bin" --checksum-mode ENABLED
    assert_success
    local etag type crc32
    etag=$(json_get "$output" '.ETag')
    type=$(json_get "$output" '.ChecksumType')
    crc32=$(json_get "$output" '.ChecksumCRC32')
    [[ "$etag" =~ -2\"$ ]]
    if [ -n "$crc32" ] && [ "$crc32" != "null" ]; then
        # current CLIs declare CRC32, which S3 stores as COMPOSITE (or FULL_OBJECT when the CLI asks for it)
        if [ "$type" = "COMPOSITE" ]; then
            [ "$crc32" = "$(crc32_composite "$dir"/piece.*)" ]
        else
            [ "$type" = "FULL_OBJECT" ]
            [ "$crc32" = "$(crc32_b64 "$dir/large.bin")" ]
        fi
    else
        # a CLI that declares no algorithm gets the CRC64NVME full-object checksum S3 attaches itself
        [ "$type" = "FULL_OBJECT" ]
        [ -n "$(json_get "$output" '.ChecksumCRC64NVME')" ]
    fi

    run aws_cmd s3api get-object-attributes --bucket "$BUCKET" --key "large-default.bin" --object-attributes ObjectParts
    assert_success
    [ "$(json_get "$output" '.ObjectParts.TotalPartsCount')" = "2" ]

    run aws_cmd s3 cp "s3://$BUCKET/large-default.bin" "$dir/roundtrip.bin"
    assert_success
    same_content "$dir/large.bin" "$dir/roundtrip.bin"
    rm -rf "$dir"
}
