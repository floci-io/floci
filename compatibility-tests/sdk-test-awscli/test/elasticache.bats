#!/usr/bin/env bats
# ElastiCache tests

setup() {
    load 'test_helper/common-setup'
    PG="bats-pg-$(unique_name)"
}

teardown() {
    aws_cmd elasticache delete-cache-parameter-group --cache-parameter-group-name "$PG" >/dev/null 2>&1 || true
}

@test "ElastiCache: create, describe, modify and delete a cache parameter group" {
    run aws_cmd elasticache create-cache-parameter-group \
        --cache-parameter-group-name "$PG" \
        --cache-parameter-group-family redis7 \
        --description "bats parameter group"
    assert_success
    [ "$(json_get "$output" '.CacheParameterGroup.CacheParameterGroupName')" = "$PG" ]
    [ "$(json_get "$output" '.CacheParameterGroup.CacheParameterGroupFamily')" = "redis7" ]
    [ "$(json_get "$output" '.CacheParameterGroup.Description')" = "bats parameter group" ]
    [ "$(json_get "$output" '.CacheParameterGroup.IsGlobal')" = "false" ]
    arn=$(json_get "$output" '.CacheParameterGroup.ARN')
    [[ "$arn" == *":parametergroup:$PG" ]]

    run aws_cmd elasticache describe-cache-parameter-groups --cache-parameter-group-name "$PG"
    assert_success
    [ "$(json_get "$output" '.CacheParameterGroups | length')" = "1" ]

    run aws_cmd elasticache modify-cache-parameter-group \
        --cache-parameter-group-name "$PG" \
        --parameter-name-values 'ParameterName=maxmemory-policy,ParameterValue=allkeys-lru'
    assert_success
    [ "$(json_get "$output" '.CacheParameterGroupName')" = "$PG" ]

    run aws_cmd elasticache describe-cache-parameters --cache-parameter-group-name "$PG"
    assert_success
    [ "$(json_get "$output" '.Parameters[0].ParameterName')" = "maxmemory-policy" ]
    [ "$(json_get "$output" '.Parameters[0].ParameterValue')" = "allkeys-lru" ]
    [ "$(json_get "$output" '.Parameters[0].Source')" = "user" ]

    run aws_cmd elasticache delete-cache-parameter-group --cache-parameter-group-name "$PG"
    assert_success

    run aws_cmd elasticache describe-cache-parameter-groups --cache-parameter-group-name "$PG"
    assert_failure
    assert_output --partial "CacheParameterGroupNotFound"
}

@test "ElastiCache: the default parameter groups AWS publishes are listed" {
    run aws_cmd elasticache describe-cache-parameter-groups
    assert_success
    names=$(json_get "$output" '.CacheParameterGroups[].CacheParameterGroupName')
    echo "$names" | grep -q '^default\.redis7$'
    echo "$names" | grep -q '^default\.redis7\.cluster\.on$'
    echo "$names" | grep -q '^default\.valkey8$'

    run aws_cmd elasticache describe-cache-parameter-groups --cache-parameter-group-name default.redis7
    assert_success
    [ "$(json_get "$output" '.CacheParameterGroups[0].CacheParameterGroupFamily')" = "redis7" ]
}

@test "ElastiCache: a duplicate parameter group is rejected" {
    aws_cmd elasticache create-cache-parameter-group --cache-parameter-group-name "$PG" \
        --cache-parameter-group-family redis7 --description first >/dev/null

    run aws_cmd elasticache create-cache-parameter-group --cache-parameter-group-name "$PG" \
        --cache-parameter-group-family redis7 --description second
    assert_failure
    assert_output --partial "CacheParameterGroupAlreadyExists"
}

@test "ElastiCache: an unknown family is rejected" {
    run aws_cmd elasticache create-cache-parameter-group \
        --cache-parameter-group-name "$PG" \
        --cache-parameter-group-family redis99 --description x
    assert_failure
    assert_output --partial "not a valid parameter group family"
}

@test "ElastiCache: a default parameter group cannot be deleted" {
    # AWS rejects it on the identifier rule, since a user-supplied name cannot contain dots.
    run aws_cmd elasticache delete-cache-parameter-group --cache-parameter-group-name default.redis7
    assert_failure
    assert_output --partial "not a valid identifier"
}
