#!/usr/bin/env bats
# Cost anomaly resources must survive provider readback and converge after updates.
# Reproduces https://github.com/floci-io/floci/issues/4106.

ce_terraform() {
    "${IAC_BIN:-terraform}" -chdir="${BATS_FILE_TMPDIR}/cost-anomaly-tf" "$@"
}

setup_file() {
    load 'test_helper/common-setup'

    local fixture_source
    fixture_source="$(dirname "$BATS_TEST_FILENAME")/cost-anomaly-tf"
    mkdir -p "${BATS_FILE_TMPDIR}/cost-anomaly-tf"
    cp "$fixture_source/main.tf" "$fixture_source/updated.tfvars" \
        "${BATS_FILE_TMPDIR}/cost-anomaly-tf/"

    run ce_terraform init -input=false -no-color
    assert_success
}

setup() {
    load 'test_helper/common-setup'
}

teardown_file() {
    load 'test_helper/common-setup'

    if [ -f "${BATS_FILE_TMPDIR}/cost-anomaly-tf/terraform.tfstate" ]; then
        if ! ce_terraform destroy -var="endpoint=${FLOCI_ENDPOINT}" \
            -input=false -auto-approve -no-color; then
            echo "# Cost anomaly fixture cleanup failed; see the destroy output above." >&3
        fi
    fi
}

@test "Cost anomaly monitor and subscription: create, update without drift, and destroy" {
    local monitor_arn subscription_arn

    run ce_terraform apply -var="endpoint=${FLOCI_ENDPOINT}" -input=false -auto-approve -no-color
    assert_success
    assert_output --partial "2 added, 0 changed, 0 destroyed"

    run ce_terraform plan -var="endpoint=${FLOCI_ENDPOINT}" -input=false -no-color -detailed-exitcode
    assert_success

    run ce_terraform output -raw monitor_arn
    assert_success
    assert_output --regexp '^arn:aws:ce::941000000010:anomalymonitor/[a-zA-Z0-9-]+$'
    monitor_arn="$output"
    run ce_terraform output -raw subscription_arn
    assert_success
    assert_output --regexp '^arn:aws:ce::941000000010:anomalysubscription/[a-zA-Z0-9-]+$'
    subscription_arn="$output"

    run ce_terraform apply -var="endpoint=${FLOCI_ENDPOINT}" -var-file=updated.tfvars \
        -input=false -auto-approve -no-color
    assert_success
    assert_output --partial "0 added, 2 changed, 0 destroyed"

    run ce_terraform plan -var="endpoint=${FLOCI_ENDPOINT}" -var-file=updated.tfvars \
        -input=false -no-color -detailed-exitcode
    assert_success

    run ce_terraform output -raw monitor_arn
    assert_success
    assert_output "$monitor_arn"
    run ce_terraform output -raw subscription_arn
    assert_success
    assert_output "$subscription_arn"

    run ce_terraform destroy -var="endpoint=${FLOCI_ENDPOINT}" -var-file=updated.tfvars \
        -input=false -auto-approve -no-color
    assert_success
    assert_output --partial "2 destroyed"
    run ce_terraform state list
    assert_success
    assert_output ''
}
