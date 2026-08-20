#!/usr/bin/env bats
# RDS integration tests

setup() {
    load 'test_helper/common-setup'
    DB_ID="bats-rds-$(unique_name)"
    DB_ID_2="bats-rds-2-$(unique_name)"
}

teardown() {
    aws_cmd rds delete-db-instance --db-instance-identifier "$DB_ID" --skip-final-snapshot >/dev/null 2>&1 || true
    aws_cmd rds delete-db-instance --db-instance-identifier "$DB_ID_2" --skip-final-snapshot >/dev/null 2>&1 || true
    if [ -n "${MANAGED_SECRET_ARN:-}" ]; then
        aws_cmd secretsmanager delete-secret --secret-id "$MANAGED_SECRET_ARN" \
            --force-delete-without-recovery >/dev/null 2>&1 || true
    fi
}

@test "RDS: create db instance returns resource identifiers" {
    run aws_cmd rds create-db-instance \
        --db-instance-identifier "$DB_ID" \
        --engine postgres \
        --db-instance-class db.t3.micro \
        --allocated-storage 10

    assert_success

    dbi_resource_id=$(json_get "$output" '.DBInstance.DbiResourceId')
    db_instance_arn=$(json_get "$output" '.DBInstance.DBInstanceArn')

    [ -n "$dbi_resource_id" ]
    [[ "$dbi_resource_id" =~ ^db- ]]

    [ -n "$db_instance_arn" ]
    [[ "$db_instance_arn" == *":db:$DB_ID" ]]
}

@test "RDS: describe db instances filters by identifier" {
    aws_cmd rds create-db-instance \
        --db-instance-identifier "$DB_ID" \
        --engine postgres \
        --db-instance-class db.t3.micro \
        --allocated-storage 10

    run aws_cmd rds describe-db-instances --db-instance-identifier "$DB_ID"
    assert_success

    count=$(echo "$output" | jq '.DBInstances | length')
    [ "$count" -eq 1 ]

    id=$(json_get "$output" '.DBInstances[0].DBInstanceIdentifier')
    [ "$id" = "$DB_ID" ]
}

@test "RDS: describe db instances is case-insensitive" {
    aws_cmd rds create-db-instance \
        --db-instance-identifier "$DB_ID" \
        --engine postgres \
        --db-instance-class db.t3.micro \
        --allocated-storage 10

    # shellcheck disable=SC2155
    local upper_id=$(echo "$DB_ID" | tr '[:lower:]' '[:upper:]')
    run aws_cmd rds describe-db-instances --db-instance-identifier "$upper_id"
    assert_success

    count=$(echo "$output" | jq '.DBInstances | length')
    [ "$count" -eq 1 ]

    id=$(json_get "$output" '.DBInstances[0].DBInstanceIdentifier')
    [ "$id" = "$DB_ID" ]
}

@test "RDS: describe db instances returns all when no filter" {
    aws_cmd rds create-db-instance \
        --db-instance-identifier "$DB_ID" \
        --engine postgres \
        --db-instance-class db.t3.micro \
        --allocated-storage 10

    aws_cmd rds create-db-instance \
        --db-instance-identifier "$DB_ID_2" \
        --engine postgres \
        --db-instance-class db.t3.micro \
        --allocated-storage 10

    run aws_cmd rds describe-db-instances
    assert_success

    # Might have more from other tests, but at least 2
    count=$(echo "$output" | jq '.DBInstances | length')
    [ "$count" -ge 2 ]
}

@test "RDS: managed master user secret is owned by rds and rotates without a Lambda" {
    run aws_cmd rds create-db-instance \
        --db-instance-identifier "$DB_ID" \
        --engine postgres \
        --db-instance-class db.t3.micro \
        --allocated-storage 10 \
        --master-username admin \
        --manage-master-user-password
    assert_success

    MANAGED_SECRET_ARN=$(json_get "$output" '.DBInstance.MasterUserSecret.SecretArn')
    [ -n "$MANAGED_SECRET_ARN" ]

    run aws_cmd secretsmanager describe-secret --secret-id "$MANAGED_SECRET_ARN"
    assert_success
    [ "$(json_get "$output" '.OwningService')" = "rds" ]

    # RDS rotates this secret itself, so a rotation Lambda is not accepted for it.
    run aws_cmd secretsmanager rotate-secret \
        --secret-id "$MANAGED_SECRET_ARN" \
        --rotation-lambda-arn "arn:aws:lambda:$AWS_DEFAULT_REGION:000000000000:function:absent"
    assert_failure
    assert_output --partial "not supported for a service-managed secret"

    # The call terraform's aws_secretsmanager_secret_rotation makes: rules, no Lambda ARN.
    run aws_cmd secretsmanager rotate-secret \
        --secret-id "$MANAGED_SECRET_ARN" \
        --rotation-rules 'AutomaticallyAfterDays=7'
    assert_success

    run aws_cmd secretsmanager describe-secret --secret-id "$MANAGED_SECRET_ARN"
    assert_success
    [ "$(json_get "$output" '.RotationEnabled')" = "true" ]
    [ "$(json_get "$output" '.RotationRules.AutomaticallyAfterDays')" = "7" ]
}

@test "RDS: describe global clusters returns an empty list" {
    run aws_cmd rds describe-global-clusters
    assert_success
    [ "$(json_get "$output" '.GlobalClusters | length')" = "0" ]
}

@test "DocDB: describe global clusters answers the read every cluster read makes" {
    # DocumentDB signs with the rds scope, so this is the same handler the CLI reaches
    # for either service. Without an answer here a created cluster cannot be read back.
    run aws_cmd docdb describe-global-clusters
    assert_success
    [ "$(json_get "$output" '.GlobalClusters | length')" = "0" ]

    run aws_cmd docdb describe-global-clusters --global-cluster-identifier "bats-absent-gc"
    assert_failure
    assert_output --partial "GlobalClusterNotFoundFault"
}

@test "RDS: cluster parameter group reports its ARN and carries tags" {
    CPG="bats-cpg-$(unique_name)"
    run aws_cmd rds create-db-cluster-parameter-group --db-cluster-parameter-group-name "$CPG" \
        --db-parameter-group-family aurora-postgresql15 --description "bats" \
        --tags Key=team,Value=data
    assert_success
    arn=$(json_get "$output" '.DBClusterParameterGroup.DBClusterParameterGroupArn')
    [ -n "$arn" ]
    [[ "$arn" == *":cluster-pg:$CPG" ]]

    run aws_cmd rds list-tags-for-resource --resource-name "$arn"
    assert_success
    assert_output --partial '"Key": "team"'

    aws_cmd rds delete-db-cluster-parameter-group --db-cluster-parameter-group-name "$CPG" >/dev/null 2>&1 || true
}

@test "DocDB: cluster tags survive create and can be added and removed" {
    # The tag actions carry only the resource ARN, so this is also the check that they reach
    # DocumentDB at all rather than the RDS handler, which does not hold its records.
    CLUSTER_ID="bats-docdb-$(unique_name)"
    run aws_cmd docdb create-db-cluster --db-cluster-identifier "$CLUSTER_ID" \
        --engine docdb --master-username docdbadmin --master-user-password "secret99password" \
        --tags Key=env,Value=bats
    assert_success
    arn=$(json_get "$output" '.DBCluster.DBClusterArn')

    run aws_cmd docdb list-tags-for-resource --resource-name "$arn"
    assert_success
    assert_output --partial '"Key": "env"'

    run aws_cmd docdb add-tags-to-resource --resource-name "$arn" --tags Key=env,Value=changed Key=extra,Value=yes
    assert_success
    run aws_cmd docdb list-tags-for-resource --resource-name "$arn"
    assert_success
    assert_output --partial '"Value": "changed"'

    # Removing a key that is not there is not an error on a live account.
    run aws_cmd docdb remove-tags-from-resource --resource-name "$arn" --tag-keys extra absent
    assert_success
    run aws_cmd docdb list-tags-for-resource --resource-name "$arn"
    assert_success
    refute_output --partial '"Key": "extra"'

    aws_cmd docdb delete-db-cluster --db-cluster-identifier "$CLUSTER_ID" --skip-final-snapshot >/dev/null 2>&1 || true
}
