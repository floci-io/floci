#!/usr/bin/env bats
# RDS Proxy EndpointNetworkType/TargetConnectionNetworkType Compatibility Test
#
# CreateDBProxy used to reject any EndpointNetworkType/TargetConnectionNetworkType other than
# IPV4 outright. This verifies aws_db_proxy can apply with endpoint_network_type = "DUAL" and
# target_connection_network_type = "IPV6", that DescribeDBProxies reads the values back, and
# that a second plan is clean (no perpetual diff from a default the provider disagrees with).

setup_file() {
    load 'test_helper/common-setup'

    PROXY_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/rds-proxy-network-type-tf" && pwd)"
    cd "$PROXY_TF_DIR"

    echo "# === RDS Proxy Network Type Test ===" >&3
    echo "# Endpoint: $FLOCI_ENDPOINT" >&3
    echo "# Config: $PROXY_TF_DIR" >&3

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

    PROXY_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/rds-proxy-network-type-tf" && pwd)"
    cd "$PROXY_TF_DIR"

    terraform destroy -var="endpoint=${FLOCI_ENDPOINT}" -input=false -auto-approve -no-color || true
    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* 2>/dev/null || true
}

setup() {
    load 'test_helper/common-setup'
    PROXY_TF_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/rds-proxy-network-type-tf" && pwd)"
}

@test "RDS Proxy network type: apply reports DUAL/IPV6 outputs" {
    ENDPOINT_TYPE=$(terraform -chdir="$PROXY_TF_DIR" output -raw endpoint_network_type)
    TARGET_TYPE=$(terraform -chdir="$PROXY_TF_DIR" output -raw target_connection_network_type)
    assert_equal "$ENDPOINT_TYPE" "DUAL"
    assert_equal "$TARGET_TYPE" "IPV6"
}

@test "RDS Proxy network type: DescribeDBProxies reads the values back" {
    run aws_cmd rds describe-db-proxies --db-proxy-name floci-rds-proxy-network-type \
        --query "DBProxies[0].EndpointNetworkType" --output text
    assert_success
    assert_output "DUAL"

    run aws_cmd rds describe-db-proxies --db-proxy-name floci-rds-proxy-network-type \
        --query "DBProxies[0].TargetConnectionNetworkType" --output text
    assert_success
    assert_output "IPV6"
}

@test "RDS Proxy network type: second plan reports no changes" {
    cd "$PROXY_TF_DIR"
    run terraform plan -var="endpoint=${FLOCI_ENDPOINT}" -input=false -no-color -detailed-exitcode
    assert_success
    assert_output --partial "No changes"
}

@test "RDS Proxy network type: CreateDBProxy rejects DUAL against an IPv4-only VPC" {
    ROLE_ARN=$(terraform -chdir="$PROXY_TF_DIR" output -raw role_arn)
    SECRET_ARN=$(terraform -chdir="$PROXY_TF_DIR" output -raw secret_arn)
    SUBNET_A=$(terraform -chdir="$PROXY_TF_DIR" output -json ipv4_only_subnet_ids | jq -r '.[0]')
    SUBNET_B=$(terraform -chdir="$PROXY_TF_DIR" output -json ipv4_only_subnet_ids | jq -r '.[1]')

    run aws_cmd rds create-db-proxy \
        --db-proxy-name floci-rds-proxy-ipv4-only-rejected \
        --engine-family MYSQL \
        --role-arn "$ROLE_ARN" \
        --vpc-subnet-ids "$SUBNET_A" "$SUBNET_B" \
        --auth "AuthScheme=SECRETS,SecretArn=${SECRET_ARN},IAMAuth=DISABLED" \
        --endpoint-network-type DUAL
    assert_failure
    assert_output --partial "IPv6 CIDR block"

    run aws_cmd rds describe-db-proxies --db-proxy-name floci-rds-proxy-ipv4-only-rejected
    assert_failure
}

@test "RDS Proxy network type: CreateDBProxy rejects DUAL when the VPC is dual-stack but the subnets are not" {
    ROLE_ARN=$(terraform -chdir="$PROXY_TF_DIR" output -raw role_arn)
    SECRET_ARN=$(terraform -chdir="$PROXY_TF_DIR" output -raw secret_arn)
    SUBNET_A=$(terraform -chdir="$PROXY_TF_DIR" output -json ipv6_subnets_missing_subnet_ids | jq -r '.[0]')
    SUBNET_B=$(terraform -chdir="$PROXY_TF_DIR" output -json ipv6_subnets_missing_subnet_ids | jq -r '.[1]')

    run aws_cmd rds create-db-proxy \
        --db-proxy-name floci-rds-proxy-ipv6-subnets-missing-rejected \
        --engine-family MYSQL \
        --role-arn "$ROLE_ARN" \
        --vpc-subnet-ids "$SUBNET_A" "$SUBNET_B" \
        --auth "AuthScheme=SECRETS,SecretArn=${SECRET_ARN},IAMAuth=DISABLED" \
        --endpoint-network-type DUAL
    assert_failure
    assert_output --partial "VpcSubnetIds"
    assert_output --partial "IPv6 CIDR block"

    run aws_cmd rds describe-db-proxies --db-proxy-name floci-rds-proxy-ipv6-subnets-missing-rejected
    assert_failure
}
