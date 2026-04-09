#!/usr/bin/env bats
# RDS integration tests

setup() {
    load 'test_helper/common-setup'
}

teardown() {
    # Delete test instance if it exists
    aws_cmd rds delete-db-instance --db-instance-identifier "test-cli-db" --skip-final-snapshot >/dev/null 2>&1 || true
    aws_cmd rds delete-db-instance --db-instance-identifier "test-cli-db-2" --skip-final-snapshot >/dev/null 2>&1 || true
}

@test "RDS: create db instance returns resource identifiers" {
    run aws_cmd rds create-db-instance \
        --db-instance-identifier "test-cli-db" \
        --engine postgres \
        --db-instance-class db.t3.micro \
        --allocated-storage 10
    
    assert_success
    
    dbi_resource_id=$(json_get "$output" '.DBInstance.DbiResourceId')
    db_instance_arn=$(json_get "$output" '.DBInstance.DBInstanceArn')
    
    [ -n "$dbi_resource_id" ]
    [[ "$dbi_resource_id" =~ ^db- ]]
    
    [ -n "$db_instance_arn" ]
    [[ "$db_instance_arn" =~ ^arn:aws:rds:.*:db:test-cli-db$ ]]
}

@test "RDS: describe db instances filters by identifier" {
    aws_cmd rds create-db-instance \
        --db-instance-identifier "test-cli-db" \
        --engine postgres \
        --db-instance-class db.t3.micro \
        --allocated-storage 10

    run aws_cmd rds describe-db-instances --db-instance-identifier "test-cli-db"
    assert_success
    
    count=$(echo "$output" | jq '.DBInstances | length')
    [ "$count" -eq 1 ]
    
    id=$(json_get "$output" '.DBInstances[0].DBInstanceIdentifier')
    [ "$id" = "test-cli-db" ]
}

@test "RDS: describe db instances is case-insensitive" {
    aws_cmd rds create-db-instance \
        --db-instance-identifier "test-cli-db" \
        --engine postgres \
        --db-instance-class db.t3.micro \
        --allocated-storage 10

    run aws_cmd rds describe-db-instances --db-instance-identifier "TEST-CLI-DB"
    assert_success
    
    count=$(echo "$output" | jq '.DBInstances | length')
    [ "$count" -eq 1 ]
    
    id=$(json_get "$output" '.DBInstances[0].DBInstanceIdentifier')
    [ "$id" = "test-cli-db" ]
}

@test "RDS: describe db instances returns all when no filter" {
    aws_cmd rds create-db-instance \
        --db-instance-identifier "test-cli-db" \
        --engine postgres \
        --db-instance-class db.t3.micro \
        --allocated-storage 10
    
    aws_cmd rds create-db-instance \
        --db-instance-identifier "test-cli-db-2" \
        --engine postgres \
        --db-instance-class db.t3.micro \
        --allocated-storage 10

    run aws_cmd rds describe-db-instances
    assert_success
    
    # Might have more from other tests, but at least 2
    count=$(echo "$output" | jq '.DBInstances | length')
    [ "$count" -ge 2 ]
}
