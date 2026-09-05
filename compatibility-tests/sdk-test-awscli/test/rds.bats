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
    # EC2 fixtures of the docdb security-group case, whether or not its assertions passed
    if [ -n "${SG_ID:-}" ]; then
        aws_cmd docdb delete-db-cluster --db-cluster-identifier "$CLUSTER_ID" --skip-final-snapshot >/dev/null 2>&1 || true
        aws_cmd ec2 delete-security-group --group-id "$SG_ID" >/dev/null 2>&1 || true
    fi
    if [ -n "${VPC_ID:-}" ]; then
        aws_cmd ec2 delete-vpc --vpc-id "$VPC_ID" >/dev/null 2>&1 || true
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

@test "rds: tags given to create-db-subnet-group are readable back" {
    VPC_ID=$(aws_cmd ec2 create-vpc --cidr-block 10.77.0.0/16 --query Vpc.VpcId --output text)
    S1=$(aws_cmd ec2 create-subnet --vpc-id "$VPC_ID" --cidr-block 10.77.1.0/24 --availability-zone us-east-1a --query Subnet.SubnetId --output text)
    S2=$(aws_cmd ec2 create-subnet --vpc-id "$VPC_ID" --cidr-block 10.77.2.0/24 --availability-zone us-east-1b --query Subnet.SubnetId --output text)
    GROUP="bats-sng-$(unique_name)"

    run aws_cmd rds create-db-subnet-group --db-subnet-group-name "$GROUP" --db-subnet-group-description d \
        --subnet-ids "$S1" "$S2" --tags "Key=Name,Value=$GROUP" Key=env,Value=tst
    assert_success
    arn=$(json_get "$output" '.DBSubnetGroup.DBSubnetGroupArn')

    run aws_cmd rds list-tags-for-resource --resource-name "$arn"
    assert_success
    assert_output --partial '"Key": "env"'
    assert_output --partial "\"Value\": \"$GROUP\""

    # the DocumentDB CLI reaches the same group through the same scope
    run aws_cmd docdb list-tags-for-resource --resource-name "$arn"
    assert_success
    assert_output --partial '"Key": "env"'

    aws_cmd rds delete-db-subnet-group --db-subnet-group-name "$GROUP" >/dev/null 2>&1 || true
    aws_cmd ec2 delete-subnet --subnet-id "$S1" >/dev/null 2>&1 || true
    aws_cmd ec2 delete-subnet --subnet-id "$S2" >/dev/null 2>&1 || true
    aws_cmd ec2 delete-vpc --vpc-id "$VPC_ID" >/dev/null 2>&1 || true
}

@test "docdb: an engine version a live account does not list is refused" {
    CLUSTER_ID="bats-docdb-version-$(unique_name)"
    run aws_cmd docdb create-db-cluster --db-cluster-identifier "$CLUSTER_ID" --engine docdb \
        --engine-version 9.9.9 --master-username u --master-user-password pw12345678
    assert_failure
    assert_output --partial 'InvalidParameterCombination'
    assert_output --partial 'Cannot find version 9.9.9 for docdb'

    run aws_cmd docdb describe-db-clusters --db-cluster-identifier "$CLUSTER_ID"
    assert_failure
    assert_output --partial 'DBClusterNotFoundFault'
}

@test "docdb: a security group that exists is accepted on create" {
    CLUSTER_ID="bats-docdb-sg-$(unique_name)"
    run aws_cmd ec2 create-vpc --cidr-block 10.42.0.0/16
    assert_success
    VPC_ID=$(json_get "$output" '.Vpc.VpcId')
    run aws_cmd ec2 create-security-group --group-name "$CLUSTER_ID" --description d --vpc-id "$VPC_ID"
    assert_success
    SG_ID=$(json_get "$output" '.GroupId')

    run aws_cmd docdb create-db-cluster --db-cluster-identifier "$CLUSTER_ID" --engine docdb \
        --master-username docdbadmin --master-user-password "secret99password" \
        --vpc-security-group-ids "$SG_ID"
    assert_success
    run aws_cmd docdb describe-db-clusters --db-cluster-identifier "$CLUSTER_ID"
    assert_success
    assert_output --partial "\"VpcSecurityGroupId\": \"$SG_ID\""
}

@test "docdb: cluster settings given on create are returned by describe" {
    CLUSTER_ID="bats-docdb-settings-$(unique_name)"
    run aws_cmd kms create-key --description "$CLUSTER_ID"
    assert_success
    KEY_ARN=$(json_get "$output" '.KeyMetadata.Arn')

    run aws_cmd docdb create-db-cluster --db-cluster-identifier "$CLUSTER_ID" \
        --engine docdb --master-username docdbadmin --master-user-password "secret99password" \
        --storage-encrypted --kms-key-id "$KEY_ARN" --backup-retention-period 5 \
        --preferred-backup-window 23:30-00:00 --preferred-maintenance-window sun:03:00-sun:04:00 \
        --tags "Key=Name,Value=$CLUSTER_ID"
    assert_success

    run aws_cmd docdb describe-db-clusters --db-cluster-identifier "$CLUSTER_ID" \
        --query 'DBClusters[0].[DBSubnetGroup,DBClusterParameterGroup,StorageEncrypted,KmsKeyId,BackupRetentionPeriod,PreferredBackupWindow,PreferredMaintenanceWindow,VpcSecurityGroups[0].VpcSecurityGroupId]'
    assert_success
    assert_output --partial '"default"'
    assert_output --partial '"default.docdb5.0"'
    assert_output --partial 'true'
    assert_output --partial "$KEY_ARN"
    assert_output --partial '5'
    assert_output --partial '"23:30-00:00"'
    assert_output --partial '"sun:03:00-sun:04:00"'
    assert_output --partial '"sg-'

    run aws_cmd docdb create-db-cluster --db-cluster-identifier "$CLUSTER_ID-x" \
        --engine docdb --master-username docdbadmin --master-user-password "secret99password" \
        --kms-key-id "$KEY_ARN"
    assert_failure
    assert_output --partial 'You cannot specify KMS key for unencrypted clusters.'

    aws_cmd docdb delete-db-cluster --db-cluster-identifier "$CLUSTER_ID" --skip-final-snapshot >/dev/null 2>&1 || true
}

@test "docdb: a DocumentDB cluster is listed by describe-db-clusters on both endpoints" {
    CLUSTER_ID="bats-docdb-list-$(unique_name)"
    aws_cmd docdb create-db-cluster --db-cluster-identifier "$CLUSTER_ID" \
        --engine docdb --master-username docdbadmin --master-user-password "secret99password" >/dev/null

    run aws_cmd docdb describe-db-clusters --query 'DBClusters[].DBClusterIdentifier'
    assert_success
    assert_output --partial "$CLUSTER_ID"

    run aws_cmd rds describe-db-clusters --query 'DBClusters[].DBClusterIdentifier'
    assert_success
    assert_output --partial "$CLUSTER_ID"

    run aws_cmd docdb describe-db-clusters --filters Name=engine,Values=docdb --query 'DBClusters[].DBClusterIdentifier'
    assert_success
    assert_output --partial "$CLUSTER_ID"

    run aws_cmd rds describe-db-clusters --filters Name=engine,Values=aurora-postgresql --query 'DBClusters[].DBClusterIdentifier'
    assert_success
    refute_output --partial "$CLUSTER_ID"

    run aws_cmd rds describe-db-clusters --filters Name=engine,Values=nothing
    assert_failure
    assert_output --partial 'Unrecognized engine name: nothing'

    aws_cmd docdb delete-db-cluster --db-cluster-identifier "$CLUSTER_ID" --skip-final-snapshot >/dev/null 2>&1 || true
}

@test "rds: storage and backup settings given on create are returned by describe and modify" {
    run aws_cmd kms create-key --description "$DB_ID"
    assert_success
    KEY_ARN=$(json_get "$output" '.KeyMetadata.Arn')
    KEY_ID=$(json_get "$output" '.KeyMetadata.KeyId')
    aws_cmd kms create-alias --alias-name "alias/$DB_ID" --target-key-id "$KEY_ID" >/dev/null

    # the key is given as an alias and must come back as the key ARN, as on AWS
    run aws_cmd rds create-db-instance \
        --db-instance-identifier "$DB_ID" \
        --engine postgres \
        --db-instance-class db.t3.micro \
        --allocated-storage 10 \
        --storage-encrypted --kms-key-id "alias/$DB_ID" \
        --backup-retention-period 7 --copy-tags-to-snapshot \
        --preferred-backup-window 23:30-00:00 --preferred-maintenance-window Sun:03:08-Sun:03:38
    assert_success

    run aws_cmd rds describe-db-instances --db-instance-identifier "$DB_ID" \
        --query 'DBInstances[0].[StorageEncrypted,KmsKeyId,BackupRetentionPeriod,PreferredBackupWindow,CopyTagsToSnapshot,PreferredMaintenanceWindow]'
    assert_success
    assert_output --partial 'true'
    assert_output --partial "$KEY_ARN"
    assert_output --partial '7'
    assert_output --partial '"23:30-00:00"'
    assert_output --partial '"sun:03:08-sun:03:38"'

    run aws_cmd rds modify-db-instance --db-instance-identifier "$DB_ID" \
        --backup-retention-period 3 --preferred-backup-window 01:00-01:30 --apply-immediately
    assert_success

    run aws_cmd rds describe-db-instances --db-instance-identifier "$DB_ID" \
        --query 'DBInstances[0].[BackupRetentionPeriod,PreferredBackupWindow,CopyTagsToSnapshot]'
    assert_success
    assert_output --partial '3'
    assert_output --partial '"01:00-01:30"'
    assert_output --partial 'true'
}

@test "rds: a KmsKeyId that names no key is refused as on AWS" {
    run aws_cmd rds create-db-instance \
        --db-instance-identifier "$DB_ID" \
        --engine postgres \
        --db-instance-class db.t3.micro \
        --allocated-storage 10 \
        --storage-encrypted --kms-key-id "alias/does-not-exist-$DB_ID"
    assert_failure
    assert_output --partial 'KMSKeyNotAccessibleFault'
}

@test "rds: KmsKeyId without StorageEncrypted is refused as on AWS" {
    run aws_cmd rds create-db-instance \
        --db-instance-identifier "$DB_ID" \
        --engine postgres \
        --db-instance-class db.t3.micro \
        --allocated-storage 10 \
        --no-storage-encrypted --kms-key-id "arn:aws:kms:us-east-1:000000000000:key/00000000-0000-0000-0000-000000000000"
    assert_failure
    assert_output --partial 'InvalidParameterCombination'
}
