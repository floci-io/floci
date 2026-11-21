#!/usr/bin/env bats

setup_file() {
    load 'test_helper/common-setup'

    DP_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/rds-cluster-deletion-protection-tf" && pwd)"
    cd "$DP_TF_DIR"

    echo "# === RDS Cluster Deletion Protection Test ===" >&3
    echo "# Endpoint: $FLOCI_ENDPOINT" >&3
    echo "# Config: $DP_TF_DIR" >&3

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

    DP_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/rds-cluster-deletion-protection-tf" && pwd)"
    cd "$DP_TF_DIR"

    terraform apply -var="endpoint=${FLOCI_ENDPOINT}" -var="deletion_protection=false" \
        -input=false -auto-approve -no-color || true
    terraform destroy -var="endpoint=${FLOCI_ENDPOINT}" -var="deletion_protection=false" \
        -input=false -auto-approve -no-color || true
    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* 2>/dev/null || true
}

setup() {
    load 'test_helper/common-setup'
    DP_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/rds-cluster-deletion-protection-tf" && pwd)"
}

@test "RDS cluster deletion protection: DescribeDBClusters returns DeletionProtection" {
    run aws_cmd rds describe-db-clusters --db-cluster-identifier floci-rds-cluster-deletion-protection \
        --query 'DBClusters[0].DeletionProtection' --output text
    assert_success
    assert_output "True"
}

@test "RDS cluster deletion protection: plan after apply reports no changes" {
    cd "$DP_TF_DIR"
    run terraform plan -var="endpoint=${FLOCI_ENDPOINT}" -input=false -no-color -detailed-exitcode
    assert_success
    assert_output --partial "No changes"
}

@test "RDS cluster deletion protection: turning it off applies and re-plans clean" {
    cd "$DP_TF_DIR"
    run terraform apply -var="endpoint=${FLOCI_ENDPOINT}" -var="deletion_protection=false" \
        -input=false -auto-approve -no-color
    assert_success
    assert_output --partial "1 changed"

    run aws_cmd rds describe-db-clusters --db-cluster-identifier floci-rds-cluster-deletion-protection \
        --query 'DBClusters[0].DeletionProtection' --output text
    assert_success
    assert_output "False"

    run terraform plan -var="endpoint=${FLOCI_ENDPOINT}" -var="deletion_protection=false" \
        -input=false -no-color -detailed-exitcode
    assert_success
    assert_output --partial "No changes"
}
