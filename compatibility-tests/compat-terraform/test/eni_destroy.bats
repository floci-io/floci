#!/usr/bin/env bats
# ENI Destroy Compatibility Test
#
# Reproduces https://github.com/floci-io/floci/issues/1031
# Terraform destroy succeeds on VPC+subnet+SG because
# DescribeNetworkInterfaces is now implemented.
#
# This test verifies that terraform destroy on a VPC with
# subnets and security groups completes without error.
# DescribeNetworkInterfaces returns an empty ENI list when
# no instances exist, unblocking the destroy workflow.
#
# FIX VERIFIED: DescribeNetworkInterfaces implemented,
# Test now PASSES. (Previously returned UnsupportedOperation.)

setup_file() {
    load 'test_helper/common-setup'

    # Override TF_DIR to the minimal eni-destroy terraform config
    ENI_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/eni-destroy-tf" && pwd)"
    cd "$ENI_TF_DIR"

    echo "# === ENI Destroy Test ===" >&3
    echo "# Endpoint: $FLOCI_ENDPOINT" >&3
    echo "# Config: $ENI_TF_DIR" >&3

    # Clean any previous state
    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* 2>/dev/null || true

    echo "# --- terraform init ---" >&3
    run terraform init -input=false -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform init failed: $output" >&3
        return 1
    fi

    echo "# --- terraform validate ---" >&3
    run terraform validate -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform validate failed: $output" >&3
        return 1
    fi

    echo "# --- terraform plan ---" >&3
    run terraform plan -var="endpoint=${FLOCI_ENDPOINT}" -input=false -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform plan failed: $output" >&3
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

    ENI_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/eni-destroy-tf" && pwd)"
    cd "$ENI_TF_DIR"

    # Clean up terraform state after test
    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* 2>/dev/null || true
}

setup() {
    load 'test_helper/common-setup'
    ENI_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/eni-destroy-tf" && pwd)"
}

# --- Spot checks: resources were created ---

@test "ENI Destroy: VPC was created" {
    run aws_cmd ec2 describe-vpcs \
        --filters "Name=tag:Name,Values=floci-eni-destroy-vpc"
    assert_success
    assert_output --partial "floci-eni-destroy-vpc"
    assert_output --partial "10.0.0.0/16"
}

@test "ENI Destroy: Subnet was created" {
    run terraform -chdir="$ENI_TF_DIR" state show aws_subnet.test
    assert_success
    assert_output --partial "10.0.1.0/24"
}

@test "ENI Destroy: Security Group was created" {
    run aws_cmd ec2 describe-security-groups \
        --group-names "floci-eni-destroy-sg"
    assert_success
    assert_output --partial "floci-eni-destroy-sg"
}

# --- The critical assertion: terraform destroy must succeed ---
#
# Terraform destroy on a VPC with subnets/SGs calls
# DescribeNetworkInterfaces to check for ENIs. With no
# instances running, the response is an empty set,
# allowing the destroy to proceed cleanly.

@test "ENI Destroy: terraform destroy succeeds" {
    cd "$ENI_TF_DIR"

    echo "# --- terraform destroy ---" >&3
    run terraform destroy -var="endpoint=${FLOCI_ENDPOINT}" -input=false -auto-approve -no-color

    assert_success

    # Verify resources are actually gone
    run aws_cmd ec2 describe-vpcs \
        --filters "Name=tag:Name,Values=floci-eni-destroy-vpc"
    assert_output --partial '"Vpcs": []'  # empty result = VPC deleted
}
