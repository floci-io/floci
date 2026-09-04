#!/usr/bin/env bats
# Neptune Cluster Compatibility Test

setup_file() {
    load 'test_helper/common-setup'

    NEPTUNE_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/neptune-cluster-tf" && pwd)"
    cd "$NEPTUNE_TF_DIR"

    echo "# === Neptune Cluster Test ===" >&3
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

    NEPTUNE_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/neptune-cluster-tf" && pwd)"
    cd "$NEPTUNE_TF_DIR"

    terraform destroy -var="endpoint=${FLOCI_ENDPOINT}" -input=false -auto-approve -no-color || true
    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* 2>/dev/null || true
}

setup() {
    load 'test_helper/common-setup'
    NEPTUNE_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/neptune-cluster-tf" && pwd)"
}

@test "Neptune cluster: terraform reads back the attributes it manages" {
    run terraform -chdir="$NEPTUNE_TF_DIR" output -raw storage_encrypted
    assert_success
    assert_output "false"

    run terraform -chdir="$NEPTUNE_TF_DIR" output -raw parameter_group_name
    assert_success
    assert_output "default.neptune1.3"

    run terraform -chdir="$NEPTUNE_TF_DIR" output -raw port
    assert_success
    assert_output "8182"

    run terraform -chdir="$NEPTUNE_TF_DIR" output -raw cluster_arn
    assert_success
    assert_output --partial ":cluster:floci-tf-neptune"
}

@test "Neptune cluster: describe reports the configured settings" {
    run aws_cmd neptune describe-db-clusters --db-cluster-identifier floci-tf-neptune
    assert_success
    [ "$(json_get "$output" '.DBClusters[0].Engine')" = "neptune" ]
    [ "$(json_get "$output" '.DBClusters[0].Status')" = "available" ]
    [ "$(json_get "$output" '.DBClusters[0].BackupRetentionPeriod')" = "5" ]
    [ "$(json_get "$output" '.DBClusters[0].PreferredBackupWindow')" = "07:00-09:00" ]
    [ "$(json_get "$output" '.DBClusters[0].StorageEncrypted')" = "false" ]
    [ "$(json_get "$output" '.DBClusters[0].DeletionProtection')" = "false" ]
    [ "$(json_get "$output" '.DBClusters[0].IAMDatabaseAuthenticationEnabled')" = "true" ]
    [ "$(json_get "$output" '.DBClusters[0].DBClusterParameterGroup')" = "default.neptune1.3" ]
    [ "$(json_get "$output" '.DBClusters[0].AvailabilityZones | length')" = "1" ]
}

@test "Neptune cluster: tags round-trip through ListTagsForResource" {
    arn=$(terraform -chdir="$NEPTUNE_TF_DIR" output -raw cluster_arn)
    run aws_cmd neptune list-tags-for-resource --resource-name "$arn"
    assert_success
    [ "$(json_get "$output" '.TagList[] | select(.Key == "Name") | .Value')" = "floci-tf-neptune" ]
}

@test "Neptune cluster: re-plan after apply is clean" {
    cd "$NEPTUNE_TF_DIR"
    run terraform plan -var="endpoint=${FLOCI_ENDPOINT}" -input=false -no-color -detailed-exitcode
    assert_success
    assert_output --partial "No changes"
}
