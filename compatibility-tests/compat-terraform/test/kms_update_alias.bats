#!/usr/bin/env bats
# KMS UpdateAlias Compatibility Test
#
# Reproduces https://github.com/floci-io/floci/issues/2291
#
# The Terraform AWS provider treats a target_key_id change on aws_kms_alias
# as an in-place update (not a replacement), which requires the emulator to
# support the UpdateAlias action. This verifies that re-applying with a
# different target key updates the existing alias rather than erroring or
# recreating it.

setup_file() {
    load 'test_helper/common-setup'

    KMS_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/kms-update-alias-tf" && pwd)"
    cd "$KMS_TF_DIR"

    echo "# === KMS UpdateAlias Test ===" >&3
    echo "# Endpoint: $FLOCI_ENDPOINT" >&3
    echo "# Config: $KMS_TF_DIR" >&3

    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* 2>/dev/null || true

    echo "# --- terraform init ---" >&3
    run terraform init -input=false -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform init failed: $output" >&3
        return 1
    fi

    echo "# --- terraform apply (alias -> key A) ---" >&3
    run terraform apply -var="endpoint=${FLOCI_ENDPOINT}" -input=false -auto-approve -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform apply failed: $output" >&3
        return 1
    fi
}

teardown_file() {
    load 'test_helper/common-setup'

    KMS_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/kms-update-alias-tf" && pwd)"
    cd "$KMS_TF_DIR"

    terraform destroy -var="endpoint=${FLOCI_ENDPOINT}" -input=false -auto-approve -no-color || true
    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* 2>/dev/null || true
}

setup() {
    load 'test_helper/common-setup'
    KMS_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/kms-update-alias-tf" && pwd)"
}

@test "KMS UpdateAlias: alias initially targets key A" {
    KEY_A=$(terraform -chdir="$KMS_TF_DIR" output -raw key_a_id)
    run aws_cmd kms list-aliases --key-id "$KEY_A" \
        --query "Aliases[?AliasName=='alias/floci-kms-update-alias-test'].TargetKeyId | [0]" --output text
    assert_success
    assert_output "$KEY_A"
}

# The critical assertion: re-applying with a different target_key_id must
# update the existing alias in place, not destroy/recreate it and not fail.
@test "KMS UpdateAlias: re-apply with a different target updates in place" {
    cd "$KMS_TF_DIR"

    echo "# --- terraform apply (alias -> key B) ---" >&3
    run terraform apply -var="endpoint=${FLOCI_ENDPOINT}" -var="use_second_key=true" \
        -input=false -auto-approve -no-color
    assert_success
    assert_output --partial "1 changed"
    refute_output --partial "1 destroyed"

    KEY_B=$(terraform -chdir="$KMS_TF_DIR" output -raw key_b_id)
    run aws_cmd kms list-aliases --key-id "$KEY_B" \
        --query "Aliases[?AliasName=='alias/floci-kms-update-alias-test'].TargetKeyId | [0]" --output text
    assert_success
    assert_output "$KEY_B"
}

@test "KMS UpdateAlias: alias no longer resolves under key A" {
    KEY_A=$(terraform -chdir="$KMS_TF_DIR" output -raw key_a_id)
    run aws_cmd kms list-aliases --key-id "$KEY_A" \
        --query "Aliases[?AliasName=='alias/floci-kms-update-alias-test']" --output text
    assert_success
    [ -z "$output" ]
}
