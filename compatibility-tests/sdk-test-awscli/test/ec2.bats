#!/usr/bin/env bats
# EC2 tests

setup() {
    load 'test_helper/common-setup'
    PREFIX_LIST_NAME="bats-prefix-list-$(unique_name)"
    PREFIX_LIST_ID=""
}

teardown() {
    if [ -n "$PREFIX_LIST_ID" ]; then
        aws_cmd ec2 delete-managed-prefix-list --prefix-list-id "$PREFIX_LIST_ID" >/dev/null 2>&1 || true
    fi
}

# Creates a prefix list holding 10.0.0.0/8 and sets PREFIX_LIST_ID.
create_prefix_list() {
    local out
    out=$(aws_cmd ec2 create-managed-prefix-list \
        --prefix-list-name "$PREFIX_LIST_NAME" \
        --address-family IPv4 \
        --max-entries 5 \
        --entries 'Cidr=10.0.0.0/8,Description=corporate')
    PREFIX_LIST_ID=$(json_get "$out" '.PrefixList.PrefixListId')
}

@test "EC2: create managed prefix list" {
    run aws_cmd ec2 create-managed-prefix-list \
        --prefix-list-name "$PREFIX_LIST_NAME" \
        --address-family IPv4 \
        --max-entries 5 \
        --entries 'Cidr=10.0.0.0/8,Description=corporate'
    assert_success
    PREFIX_LIST_ID=$(json_get "$output" '.PrefixList.PrefixListId')
    [ -n "$PREFIX_LIST_ID" ]

    state=$(json_get "$output" '.PrefixList.State')
    [ "$state" = "create-complete" ]
    version=$(json_get "$output" '.PrefixList.Version')
    [ "$version" = "1" ]
}

@test "EC2: describe managed prefix list by id" {
    create_prefix_list

    run aws_cmd ec2 describe-managed-prefix-lists --prefix-list-ids "$PREFIX_LIST_ID"
    assert_success
    name=$(json_get "$output" '.PrefixLists[0].PrefixListName')
    [ "$name" = "$PREFIX_LIST_NAME" ]
}

@test "EC2: describe managed prefix lists exposes the AWS-managed S3 list" {
    run aws_cmd ec2 describe-managed-prefix-lists \
        --filters "Name=prefix-list-name,Values=com.amazonaws.${AWS_DEFAULT_REGION}.s3"
    assert_success
    id=$(json_get "$output" '.PrefixLists[0].PrefixListId')
    [ "$id" = "pl-63a5400a" ]
    owner=$(json_get "$output" '.PrefixLists[0].OwnerId')
    [ "$owner" = "AWS" ]
}

@test "EC2: get managed prefix list entries" {
    create_prefix_list

    run aws_cmd ec2 get-managed-prefix-list-entries --prefix-list-id "$PREFIX_LIST_ID"
    assert_success
    cidr=$(json_get "$output" '.Entries[0].Cidr')
    [ "$cidr" = "10.0.0.0/8" ]
    desc=$(json_get "$output" '.Entries[0].Description')
    [ "$desc" = "corporate" ]
}

@test "EC2: modify managed prefix list bumps version and keeps history" {
    create_prefix_list

    run aws_cmd ec2 modify-managed-prefix-list \
        --prefix-list-id "$PREFIX_LIST_ID" \
        --add-entries 'Cidr=192.168.0.0/16,Description=lab'
    assert_success
    version=$(json_get "$output" '.PrefixList.Version')
    [ "$version" = "2" ]

    run aws_cmd ec2 get-managed-prefix-list-entries --prefix-list-id "$PREFIX_LIST_ID"
    assert_success
    count=$(json_get "$output" '.Entries | length')
    [ "$count" = "2" ]

    run aws_cmd ec2 get-managed-prefix-list-entries --prefix-list-id "$PREFIX_LIST_ID" --target-version 1
    assert_success
    count=$(json_get "$output" '.Entries | length')
    [ "$count" = "1" ]
}

@test "EC2: modify with a stale current version is rejected" {
    create_prefix_list
    aws_cmd ec2 modify-managed-prefix-list --prefix-list-id "$PREFIX_LIST_ID" \
        --add-entries 'Cidr=192.168.0.0/16' >/dev/null

    run aws_cmd ec2 modify-managed-prefix-list \
        --prefix-list-id "$PREFIX_LIST_ID" \
        --current-version 1 \
        --add-entries 'Cidr=172.16.0.0/12'
    assert_failure
    assert_output --partial "PrefixListVersionMismatch"
}

@test "EC2: AWS-managed prefix list cannot be modified" {
    run aws_cmd ec2 modify-managed-prefix-list \
        --prefix-list-id pl-63a5400a \
        --add-entries 'Cidr=10.1.0.0/16'
    assert_failure
    assert_output --partial "UnsupportedOperation"
}

@test "EC2: delete managed prefix list" {
    create_prefix_list
    local created_id="$PREFIX_LIST_ID"

    run aws_cmd ec2 delete-managed-prefix-list --prefix-list-id "$created_id"
    assert_success
    state=$(json_get "$output" '.PrefixList.State')
    [ "$state" = "delete-complete" ]
    PREFIX_LIST_ID=""

    run aws_cmd ec2 describe-managed-prefix-lists --prefix-list-ids "$created_id"
    assert_failure
    assert_output --partial "InvalidPrefixListID.NotFound"
}

@test "EC2: legacy describe-prefix-lists still serves the gateway lists" {
    run aws_cmd ec2 describe-prefix-lists \
        --filters "Name=prefix-list-name,Values=com.amazonaws.${AWS_DEFAULT_REGION}.s3"
    assert_success
    id=$(json_get "$output" '.PrefixLists[0].PrefixListId')
    [ "$id" = "pl-63a5400a" ]
}
