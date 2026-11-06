#!/usr/bin/env bats
# Security Group Rule Sources Compatibility Test
#
# Terraform-layer regression coverage for
# https://github.com/floci-io/floci/issues/2266
#
# The fix is already covered by Ec2SecurityGroupRuleSourcesIntegrationTest and by an
# AWS CLI case in sdk-test-awscli. This adds the layer where the bug actually bit:
# the Terraform provider polls for the rule it just created and fails the apply when
# it cannot find it, which no API-level assertion reproduces. It also pins the
# quieter half of the same bug -- a rule that comes back without its source applies
# cleanly and then shows as drift on every later plan, which only an empty re-plan
# catches.
#
# This config has its own provider.tf rather than reusing the module's, because it
# must NOT set skip_requesting_account_id. See the comment there: skipping account
# resolution makes every same-account group reference read back as cross-account, and
# the re-plan assertion below fails for a reason that has nothing to do with #2266.

setup_file() {
    load 'test_helper/common-setup'

    SG_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/security-group-rule-sources-tf" && pwd)"
    cd "$SG_TF_DIR"

    echo "# === Security Group Rule Sources Test ===" >&3
    echo "# Endpoint: $FLOCI_ENDPOINT" >&3
    echo "# Config: $SG_TF_DIR" >&3

    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* 2>/dev/null || true

    echo "# --- terraform init ---" >&3
    run terraform init -input=false -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform init failed: $output" >&3
        return 1
    fi

    # This apply is itself the primary assertion. Before #2266 it failed with
    # "waiting for Security Group Rule create: couldn't find resource".
    echo "# --- terraform apply ---" >&3
    run terraform apply -var="endpoint=${FLOCI_ENDPOINT}" -input=false -auto-approve -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform apply failed: $output" >&3
        return 1
    fi
}

teardown_file() {
    load 'test_helper/common-setup'

    SG_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/security-group-rule-sources-tf" && pwd)"
    cd "$SG_TF_DIR"

    terraform destroy -var="endpoint=${FLOCI_ENDPOINT}" -input=false -auto-approve -no-color || true
    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* 2>/dev/null || true
}

setup() {
    load 'test_helper/common-setup'
    SG_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/security-group-rule-sources-tf" && pwd)"
}

@test "SG rule sources: applying a config with group-referencing rules succeeds" {
    # setup_file returns non-zero if the apply failed, which fails every test in the
    # file. Asserting the resulting state here makes the cause obvious in the output.
    run terraform -chdir="$SG_TF_DIR" state list
    assert_success
    assert_output --partial "aws_security_group_rule.backend_from_alb"
    assert_output --partial "aws_security_group_rule.backend_self"
    assert_output --partial "aws_vpc_security_group_ingress_rule.backend_from_alb_v2"
}

@test "SG rule sources: DescribeSecurityGroups reports the referencing group" {
    ALB=$(terraform -chdir="$SG_TF_DIR" output -raw alb_sg_id)
    BACKEND=$(terraform -chdir="$SG_TF_DIR" output -raw backend_sg_id)

    run aws_cmd ec2 describe-security-groups --group-ids "$BACKEND" \
        --query "SecurityGroups[0].IpPermissions[?FromPort==\`8080\`].UserIdGroupPairs[0].GroupId | [0]" \
        --output text
    assert_success
    assert_output "$ALB"
}

@test "SG rule sources: a self-referencing rule keeps its own group as the source" {
    BACKEND=$(terraform -chdir="$SG_TF_DIR" output -raw backend_sg_id)

    run aws_cmd ec2 describe-security-groups --group-ids "$BACKEND" \
        --query "SecurityGroups[0].IpPermissions[?IpProtocol=='-1'].UserIdGroupPairs[0].GroupId | [0]" \
        --output text
    assert_success
    assert_output "$BACKEND"
}

@test "SG rule sources: DescribeSecurityGroupRules reports referencedGroupInfo" {
    ALB=$(terraform -chdir="$SG_TF_DIR" output -raw alb_sg_id)
    RULE_ID=$(terraform -chdir="$SG_TF_DIR" output -raw backend_from_alb_v2_rule_id)

    run aws_cmd ec2 describe-security-group-rules --security-group-rule-ids "$RULE_ID" \
        --query "SecurityGroupRules[0].ReferencedGroupInfo.GroupId" --output text
    assert_success
    assert_output "$ALB"
}

@test "SG rule sources: a CIDR rule keeps its CIDR and gains no phantom reference" {
    RULE_ID=$(terraform -chdir="$SG_TF_DIR" output -raw backend_from_cidr_rule_id)

    run aws_cmd ec2 describe-security-group-rules --security-group-rule-ids "$RULE_ID" \
        --query "SecurityGroupRules[0].CidrIpv4" --output text
    assert_success
    assert_output "10.71.0.0/16"

    run aws_cmd ec2 describe-security-group-rules --security-group-rule-ids "$RULE_ID" \
        --query "SecurityGroupRules[0].ReferencedGroupInfo" --output text
    assert_success
    assert_output "None"
}

# The assertion a successful apply cannot make. A response that reports the rule but
# loses its source still applies cleanly; it only surfaces later, as a plan that never
# converges. Scoped to this configuration, which is self-contained.
@test "SG rule sources: re-planning reports no changes" {
    cd "$SG_TF_DIR"
    run terraform plan -var="endpoint=${FLOCI_ENDPOINT}" -input=false -no-color -detailed-exitcode
    if [ "$status" -eq 2 ]; then
        echo "# drift detected on re-plan:" >&3
        echo "$output" >&3
    fi
    [ "$status" -eq 0 ]
}
