#!/usr/bin/env bats
# RDS DB Snapshot Tagging Compatibility Test
#
# TagResource/UntagResource used to reject a snapshot ARN outright ("not yet implemented by
# Floci"), and CreateDBSnapshot silently dropped its Tags parameter. This verifies aws_db_snapshot
# can apply with tags, that DescribeDBSnapshots reads them back (both DBSnapshotArn and TagList),
# that a second plan is clean, and that changing a tag updates in place rather than replacing.

setup_file() {
    load 'test_helper/common-setup'

    SNAP_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/rds-snapshot-tags-tf" && pwd)"
    cd "$SNAP_TF_DIR"

    echo "# === RDS Snapshot Tagging Test ===" >&3
    echo "# Endpoint: $FLOCI_ENDPOINT" >&3
    echo "# Config: $SNAP_TF_DIR" >&3

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

    SNAP_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/rds-snapshot-tags-tf" && pwd)"
    cd "$SNAP_TF_DIR"

    terraform destroy -var="endpoint=${FLOCI_ENDPOINT}" -input=false -auto-approve -no-color || true
    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* 2>/dev/null || true
}

setup() {
    load 'test_helper/common-setup'
    SNAP_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/rds-snapshot-tags-tf" && pwd)"
}

@test "RDS snapshot tags: apply reports a snapshot ARN pinned to the provider's region" {
    ARN=$(terraform -chdir="$SNAP_TF_DIR" output -raw db_snapshot_arn)
    OWNER=$(terraform -chdir="$SNAP_TF_DIR" output -raw owner_tag)
    # Exact match, not a wildcard: us-east-1 is also the default region, so a wildcard here would miss a regression.
    assert_equal "$ARN" "arn:aws:rds:us-east-1:000000000000:snapshot:floci-rds-snapshot-tags-snap"
    assert_equal "$OWNER" "platform"
}

@test "RDS snapshot tags: DescribeDBSnapshots reads the tag back" {
    run aws_cmd rds describe-db-snapshots --db-snapshot-identifier floci-rds-snapshot-tags-snap \
        --query "DBSnapshots[0].TagList[?Key=='owner'].Value | [0]" --output text
    assert_success
    assert_output "platform"
}

@test "RDS snapshot tags: second plan reports no changes" {
    cd "$SNAP_TF_DIR"
    run terraform plan -var="endpoint=${FLOCI_ENDPOINT}" -input=false -no-color -detailed-exitcode
    assert_success
    assert_output --partial "No changes"
}

@test "RDS snapshot tags: changing a tag updates in place, not a replacement" {
    cd "$SNAP_TF_DIR"
    run terraform apply -var="endpoint=${FLOCI_ENDPOINT}" -var="owner_tag=data-team" \
        -input=false -auto-approve -no-color
    assert_success
    assert_output --partial "1 changed"
    refute_output --partial "1 destroyed"

    run aws_cmd rds describe-db-snapshots --db-snapshot-identifier floci-rds-snapshot-tags-snap \
        --query "DBSnapshots[0].TagList[?Key=='owner'].Value | [0]" --output text
    assert_success
    assert_output "data-team"
}
