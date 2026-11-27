#!/usr/bin/env bats
# Neptune cluster and instance management tests

setup() {
    load 'test_helper/common-setup'
    CLUSTER_ID="bats-neptune-$(unique_name)"
    INSTANCE_ID="bats-neptune-inst-$(unique_name)"
}

teardown() {
    # The server may have created the resource even if the client did not receive a successful response.
    aws_cmd neptune delete-db-instance \
        --db-instance-identifier "$INSTANCE_ID" >/dev/null 2>&1 || true
    aws_cmd neptune delete-db-cluster \
        --db-cluster-identifier "$CLUSTER_ID" \
        --skip-final-snapshot >/dev/null 2>&1 || true
}

@test "Neptune: create DB cluster" {
    run aws_cmd neptune create-db-cluster \
        --db-cluster-identifier "$CLUSTER_ID" \
        --engine neptune
    assert_success

    id=$(json_get "$output" '.DBCluster.DBClusterIdentifier')
    engine=$(json_get "$output" '.DBCluster.Engine')
    status=$(json_get "$output" '.DBCluster.Status')
    arn=$(json_get "$output" '.DBCluster.DBClusterArn')
    port=$(json_get "$output" '.DBCluster.Port')

    [ "$id" = "$CLUSTER_ID" ]
    [ "$engine" = "neptune" ]
    [ "$status" = "available" ]
    [[ "$arn" == arn:aws:neptune:* ]]
    [ "$port" -gt 0 ]
}

@test "Neptune: create duplicate cluster fails" {
    aws_cmd neptune create-db-cluster \
        --db-cluster-identifier "$CLUSTER_ID" \
        --engine neptune >/dev/null

    run aws_cmd neptune create-db-cluster \
        --db-cluster-identifier "$CLUSTER_ID" \
        --engine neptune
    assert_failure
    [[ "$output" == *"DBClusterAlreadyExistsFault"* ]]
}

@test "Neptune: describe DB cluster by ID" {
    aws_cmd neptune create-db-cluster \
        --db-cluster-identifier "$CLUSTER_ID" \
        --engine neptune >/dev/null

    run aws_cmd neptune describe-db-clusters \
        --db-cluster-identifier "$CLUSTER_ID"
    assert_success

    id=$(json_get "$output" '.DBClusters[0].DBClusterIdentifier')
    [ "$id" = "$CLUSTER_ID" ]
}

@test "Neptune: modify DB cluster IAM auth" {
    aws_cmd neptune create-db-cluster \
        --db-cluster-identifier "$CLUSTER_ID" \
        --engine neptune >/dev/null

    run aws_cmd neptune modify-db-cluster \
        --db-cluster-identifier "$CLUSTER_ID" \
        --enable-iam-database-authentication
    assert_success

    iam=$(json_get "$output" '.DBCluster.IAMDatabaseAuthenticationEnabled')
    [ "$iam" = "true" ]
}

@test "Neptune: create DB instance in cluster" {
    aws_cmd neptune create-db-cluster \
        --db-cluster-identifier "$CLUSTER_ID" \
        --engine neptune >/dev/null

    run aws_cmd neptune create-db-instance \
        --db-instance-identifier "$INSTANCE_ID" \
        --db-cluster-identifier "$CLUSTER_ID" \
        --db-instance-class db.r5.large \
        --engine neptune
    assert_success

    id=$(json_get "$output" '.DBInstance.DBInstanceIdentifier')
    cluster=$(json_get "$output" '.DBInstance.DBClusterIdentifier')
    status=$(json_get "$output" '.DBInstance.DBInstanceStatus')

    [ "$id" = "$INSTANCE_ID" ]
    [ "$cluster" = "$CLUSTER_ID" ]
    [ "$status" = "available" ]
}

@test "Neptune: describe DB instance by ID" {
    aws_cmd neptune create-db-cluster \
        --db-cluster-identifier "$CLUSTER_ID" \
        --engine neptune >/dev/null
    aws_cmd neptune create-db-instance \
        --db-instance-identifier "$INSTANCE_ID" \
        --db-cluster-identifier "$CLUSTER_ID" \
        --db-instance-class db.r5.large \
        --engine neptune >/dev/null

    run aws_cmd neptune describe-db-instances \
        --db-instance-identifier "$INSTANCE_ID"
    assert_success

    id=$(json_get "$output" '.DBInstances[0].DBInstanceIdentifier')
    [ "$id" = "$INSTANCE_ID" ]
}

@test "Neptune: delete cluster with instance fails" {
    aws_cmd neptune create-db-cluster \
        --db-cluster-identifier "$CLUSTER_ID" \
        --engine neptune >/dev/null
    aws_cmd neptune create-db-instance \
        --db-instance-identifier "$INSTANCE_ID" \
        --db-cluster-identifier "$CLUSTER_ID" \
        --db-instance-class db.r5.large \
        --engine neptune >/dev/null

    run aws_cmd neptune delete-db-cluster \
        --db-cluster-identifier "$CLUSTER_ID" \
        --skip-final-snapshot
    assert_failure
    [[ "$output" == *"InvalidDBClusterStateFault"* ]]
}

@test "Neptune: delete DB instance then cluster" {
    aws_cmd neptune create-db-cluster \
        --db-cluster-identifier "$CLUSTER_ID" \
        --engine neptune >/dev/null
    aws_cmd neptune create-db-instance \
        --db-instance-identifier "$INSTANCE_ID" \
        --db-cluster-identifier "$CLUSTER_ID" \
        --db-instance-class db.r5.large \
        --engine neptune >/dev/null

    run aws_cmd neptune delete-db-instance \
        --db-instance-identifier "$INSTANCE_ID"
    assert_success

    run aws_cmd neptune delete-db-cluster \
        --db-cluster-identifier "$CLUSTER_ID" \
        --skip-final-snapshot
    assert_success
}
