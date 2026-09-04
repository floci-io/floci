#!/usr/bin/env bats
# Neptune Cluster Instance Compatibility Test

setup_file() {
    load 'test_helper/common-setup'

    NEPTUNE_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/neptune-cluster-instance-tf" && pwd)"
    cd "$NEPTUNE_TF_DIR"

    echo "# === Neptune Cluster Instance Test ===" >&3
    echo "# Endpoint: $FLOCI_ENDPOINT" >&3
    echo "# Config: $NEPTUNE_TF_DIR" >&3

    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* 2>/dev/null || true

    echo "# --- terraform init ---" >&3
    run terraform init -input=false -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform init failed: $output" >&3
        return 1
    fi

    echo "# --- terraform apply ---" >&3
    run terraform apply -var="endpoint=${FLOCI_ENDPOINT}" -input=false -auto-approve -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform apply failed: $output" >&3
        return 1
    fi
}

teardown_file() {
    load 'test_helper/common-setup'

    NEPTUNE_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/neptune-cluster-instance-tf" && pwd)"
    cd "$NEPTUNE_TF_DIR"

    terraform destroy -var="endpoint=${FLOCI_ENDPOINT}" -input=false -auto-approve -no-color || true
    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* 2>/dev/null || true
}

setup() {
    load 'test_helper/common-setup'
    NEPTUNE_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/neptune-cluster-instance-tf" && pwd)"
}

@test "Neptune cluster instance: terraform reads back the attributes it manages" {
    run terraform -chdir="$NEPTUNE_TF_DIR" output -raw writer_is_writer
    assert_success
    assert_output "true"

    run terraform -chdir="$NEPTUNE_TF_DIR" output -raw reader_is_writer
    assert_success
    assert_output "false"

    run terraform -chdir="$NEPTUNE_TF_DIR" output -raw writer_storage_encrypted
    assert_success
    assert_output "false"

    run terraform -chdir="$NEPTUNE_TF_DIR" output -raw writer_parameter_group_name
    assert_success
    assert_output "default.neptune1.3"

    run terraform -chdir="$NEPTUNE_TF_DIR" output -raw writer_port
    assert_success
    assert_output "8183"

    run terraform -chdir="$NEPTUNE_TF_DIR" output -raw writer_auto_minor_version_upgrade
    assert_success
    assert_output "true"

    run terraform -chdir="$NEPTUNE_TF_DIR" output -raw reader_promotion_tier
    assert_success
    assert_output "2"

    run terraform -chdir="$NEPTUNE_TF_DIR" output -raw writer_endpoint
    assert_success
    assert_output --partial ":8183"
}

@test "Neptune cluster instance: describe reports the configured settings" {
    run aws_cmd neptune describe-db-instances --db-instance-identifier floci-tf-neptune-reader
    assert_success
    [ "$(json_get "$output" '.DBInstances[0].DBClusterIdentifier')" = "floci-tf-neptune-instances" ]
    [ "$(json_get "$output" '.DBInstances[0].DBInstanceClass')" = "db.r5.large" ]
    [ "$(json_get "$output" '.DBInstances[0].DBInstanceStatus')" = "available" ]
    [ "$(json_get "$output" '.DBInstances[0].Endpoint.Port')" = "8183" ]
    [ "$(json_get "$output" '.DBInstances[0].PromotionTier')" = "2" ]
    [ "$(json_get "$output" '.DBInstances[0].AutoMinorVersionUpgrade')" = "false" ]
    [ "$(json_get "$output" '.DBInstances[0].PubliclyAccessible')" = "false" ]
    [ "$(json_get "$output" '.DBInstances[0].StorageEncrypted')" = "false" ]
    [ "$(json_get "$output" '.DBInstances[0].DBSubnetGroup.DBSubnetGroupName')" = "default" ]
    [ "$(json_get "$output" '.DBInstances[0].DBParameterGroups[0].DBParameterGroupName')" = "default.neptune1.3" ]
}

@test "Neptune cluster instance: tags round-trip through ListTagsForResource" {
    arn=$(terraform -chdir="$NEPTUNE_TF_DIR" output -raw writer_arn)
    run aws_cmd neptune list-tags-for-resource --resource-name "$arn"
    assert_success
    [ "$(json_get "$output" '.TagList[] | select(.Key == "Name") | .Value')" = "floci-tf-neptune-writer" ]
}

@test "Neptune cluster instance: the cluster reports exactly one writer" {
    run aws_cmd neptune describe-db-clusters --db-cluster-identifier floci-tf-neptune-instances
    assert_success
    [ "$(json_get "$output" '.DBClusters[0].DBClusterMembers | length')" = "2" ]
    [ "$(json_get "$output" '[.DBClusters[0].DBClusterMembers[] | select(.IsClusterWriter)] | length')" = "1" ]
    [ "$(json_get "$output" '.DBClusters[0].DBClusterMembers[] | select(.IsClusterWriter) | .DBInstanceIdentifier')" = "floci-tf-neptune-writer" ]
}

@test "Neptune cluster instance: re-plan after apply is clean" {
    cd "$NEPTUNE_TF_DIR"
    run terraform plan -var="endpoint=${FLOCI_ENDPOINT}" -input=false -no-color -detailed-exitcode
    assert_success
    assert_output --partial "No changes"
}
