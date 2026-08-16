#!/usr/bin/env bats
# EC2 tests

setup() {
    load 'test_helper/common-setup'
    PREFIX_LIST_NAME="bats-prefix-list-$(unique_name)"
    PREFIX_LIST_ID=""
    SG_VPC_ID=""
    SG_SOURCE_ID=""
    SG_TARGET_ID=""
    TRANSIT_GATEWAY_ID=""
}

teardown() {
    if [ -n "$PREFIX_LIST_ID" ]; then
        aws_cmd ec2 delete-managed-prefix-list --prefix-list-id "$PREFIX_LIST_ID" >/dev/null 2>&1 || true
    fi
    if [ -n "$TRANSIT_GATEWAY_ID" ]; then
        aws_cmd ec2 delete-transit-gateway --transit-gateway-id "$TRANSIT_GATEWAY_ID" >/dev/null 2>&1 || true
    fi
    for sg in "$SG_TARGET_ID" "$SG_SOURCE_ID"; do
        if [ -n "$sg" ]; then
            aws_cmd ec2 delete-security-group --group-id "$sg" >/dev/null 2>&1 || true
        fi
    done
    if [ -n "$SG_VPC_ID" ]; then
        aws_cmd ec2 delete-vpc --vpc-id "$SG_VPC_ID" >/dev/null 2>&1 || true
    fi
}

# Creates a prefix list holding 10.0.0.0/8 and sets PREFIX_LIST_ID.
create_prefix_list() {
    local out
    out=$(aws_cmd ec2 create-managed-prefix-list \
        --prefix-list-name "$PREFIX_LIST_NAME" \
        --address-family IPv4 \
        --max-entries 5 \
        --entries 'Cidr=10.0.0.0/8,Description=corporate')
    PREFIX_LIST_ID=$(json_get "$out" '.PrefixList.PrefixListId')
}

@test "EC2: create managed prefix list" {
    run aws_cmd ec2 create-managed-prefix-list \
        --prefix-list-name "$PREFIX_LIST_NAME" \
        --address-family IPv4 \
        --max-entries 5 \
        --entries 'Cidr=10.0.0.0/8,Description=corporate'
    assert_success
    PREFIX_LIST_ID=$(json_get "$output" '.PrefixList.PrefixListId')
    [ -n "$PREFIX_LIST_ID" ]

    state=$(json_get "$output" '.PrefixList.State')
    [ "$state" = "create-complete" ]
    version=$(json_get "$output" '.PrefixList.Version')
    [ "$version" = "1" ]
}

@test "EC2: describe managed prefix list by id" {
    create_prefix_list

    run aws_cmd ec2 describe-managed-prefix-lists --prefix-list-ids "$PREFIX_LIST_ID"
    assert_success
    name=$(json_get "$output" '.PrefixLists[0].PrefixListName')
    [ "$name" = "$PREFIX_LIST_NAME" ]
}

@test "EC2: describe managed prefix lists exposes the AWS-managed S3 list" {
    run aws_cmd ec2 describe-managed-prefix-lists \
        --filters "Name=prefix-list-name,Values=com.amazonaws.${AWS_DEFAULT_REGION}.s3"
    assert_success
    id=$(json_get "$output" '.PrefixLists[0].PrefixListId')
    [ "$id" = "pl-63a5400a" ]
    owner=$(json_get "$output" '.PrefixLists[0].OwnerId')
    [ "$owner" = "AWS" ]
}

@test "EC2: get managed prefix list entries" {
    create_prefix_list

    run aws_cmd ec2 get-managed-prefix-list-entries --prefix-list-id "$PREFIX_LIST_ID"
    assert_success
    cidr=$(json_get "$output" '.Entries[0].Cidr')
    [ "$cidr" = "10.0.0.0/8" ]
    desc=$(json_get "$output" '.Entries[0].Description')
    [ "$desc" = "corporate" ]
}

@test "EC2: modify managed prefix list bumps version and keeps history" {
    create_prefix_list

    run aws_cmd ec2 modify-managed-prefix-list \
        --prefix-list-id "$PREFIX_LIST_ID" \
        --add-entries 'Cidr=192.168.0.0/16,Description=lab'
    assert_success
    version=$(json_get "$output" '.PrefixList.Version')
    [ "$version" = "2" ]

    run aws_cmd ec2 get-managed-prefix-list-entries --prefix-list-id "$PREFIX_LIST_ID"
    assert_success
    count=$(json_get "$output" '.Entries | length')
    [ "$count" = "2" ]

    run aws_cmd ec2 get-managed-prefix-list-entries --prefix-list-id "$PREFIX_LIST_ID" --target-version 1
    assert_success
    count=$(json_get "$output" '.Entries | length')
    [ "$count" = "1" ]
}

@test "EC2: modify with a stale current version is rejected" {
    create_prefix_list
    aws_cmd ec2 modify-managed-prefix-list --prefix-list-id "$PREFIX_LIST_ID" \
        --add-entries 'Cidr=192.168.0.0/16' >/dev/null

    run aws_cmd ec2 modify-managed-prefix-list \
        --prefix-list-id "$PREFIX_LIST_ID" \
        --current-version 1 \
        --add-entries 'Cidr=172.16.0.0/12'
    assert_failure
    assert_output --partial "PrefixListVersionMismatch"
}

@test "EC2: AWS-managed prefix list cannot be modified" {
    run aws_cmd ec2 modify-managed-prefix-list \
        --prefix-list-id pl-63a5400a \
        --add-entries 'Cidr=10.1.0.0/16'
    assert_failure
    assert_output --partial "UnsupportedOperation"
}

@test "EC2: delete managed prefix list" {
    create_prefix_list
    local created_id="$PREFIX_LIST_ID"

    run aws_cmd ec2 delete-managed-prefix-list --prefix-list-id "$created_id"
    assert_success
    state=$(json_get "$output" '.PrefixList.State')
    [ "$state" = "delete-complete" ]
    PREFIX_LIST_ID=""

    run aws_cmd ec2 describe-managed-prefix-lists --prefix-list-ids "$created_id"
    assert_failure
    assert_output --partial "InvalidPrefixListID.NotFound"
}

@test "EC2: legacy describe-prefix-lists still serves the gateway lists" {
    run aws_cmd ec2 describe-prefix-lists \
        --filters "Name=prefix-list-name,Values=com.amazonaws.${AWS_DEFAULT_REGION}.s3"
    assert_success
    id=$(json_get "$output" '.PrefixLists[0].PrefixListId')
    [ "$id" = "pl-63a5400a" ]
}

# ─── security group rules sourced from a prefix list ────────────────────────

@test "EC2: authorize a security group rule from a prefix list" {
    create_prefix_list
    local sg
    sg=$(aws_cmd ec2 create-security-group --group-name "$(unique_name bats-sg)" \
            --description "prefix list source" --query 'GroupId' --output text)

    run aws_cmd ec2 authorize-security-group-ingress --group-id "$sg" \
        --ip-permissions "IpProtocol=tcp,FromPort=5432,ToPort=5432,PrefixListIds=[{PrefixListId=$PREFIX_LIST_ID,Description=from-corp}]"
    assert_success
    pl=$(json_get "$output" '.SecurityGroupRules[0].PrefixListId')
    [ "$pl" = "$PREFIX_LIST_ID" ]

    run aws_cmd ec2 describe-security-groups --group-ids "$sg"
    assert_success
    nested=$(json_get "$output" '.SecurityGroups[0].IpPermissions[0].PrefixListIds[0].PrefixListId')
    [ "$nested" = "$PREFIX_LIST_ID" ]

    run aws_cmd ec2 describe-security-group-rules --filters "Name=group-id,Values=$sg"
    assert_success
    found=$(echo "$output" | jq --arg pl "$PREFIX_LIST_ID" '.SecurityGroupRules | any(.PrefixListId == $pl)')
    [ "$found" = "true" ]

    aws_cmd ec2 delete-security-group --group-id "$sg" >/dev/null 2>&1 || true
}

@test "EC2: authorizing from an unknown prefix list is rejected" {
    local sg
    sg=$(aws_cmd ec2 create-security-group --group-name "$(unique_name bats-sg)" \
            --description "unknown prefix list" --query 'GroupId' --output text)

    run aws_cmd ec2 authorize-security-group-ingress --group-id "$sg" \
        --ip-permissions 'IpProtocol=tcp,FromPort=1,ToPort=1,PrefixListIds=[{PrefixListId=pl-doesnotexist}]'
    assert_failure
    assert_output --partial "InvalidPrefixListID.NotFound"

    aws_cmd ec2 delete-security-group --group-id "$sg" >/dev/null 2>&1 || true
}

# Creates a VPC holding a source and a target security group, and sets SG_VPC_ID,
# SG_SOURCE_ID and SG_TARGET_ID.
create_sg_pair() {
    local out
    out=$(aws_cmd ec2 create-vpc --cidr-block 10.0.0.0/16)
    SG_VPC_ID=$(json_get "$out" '.Vpc.VpcId')
    out=$(aws_cmd ec2 create-security-group \
        --group-name "$(unique_name bats-sg-source)" \
        --description "traffic source" --vpc-id "$SG_VPC_ID")
    SG_SOURCE_ID=$(json_get "$out" '.GroupId')
    out=$(aws_cmd ec2 create-security-group \
        --group-name "$(unique_name bats-sg-target)" \
        --description "traffic target" --vpc-id "$SG_VPC_ID")
    SG_TARGET_ID=$(json_get "$out" '.GroupId')
}

@test "EC2: ingress rule keeps its source security group" {
    create_sg_pair

    # The CLI serializes UserIdGroupPairs under the wire name "Groups", so this exercises the
    # form the reporter of #2190 actually sent.
    run aws_cmd ec2 authorize-security-group-ingress --group-id "$SG_TARGET_ID" \
        --ip-permissions "IpProtocol=tcp,FromPort=443,ToPort=443,UserIdGroupPairs=[{GroupId=$SG_SOURCE_ID,Description=from-source-sg}]"
    assert_success
    ref=$(json_get "$output" '.SecurityGroupRules[0].ReferencedGroupInfo.GroupId')
    [ "$ref" = "$SG_SOURCE_ID" ]

    # Control: the CIDR form of the same rule.
    aws_cmd ec2 authorize-security-group-ingress --group-id "$SG_TARGET_ID" \
        --ip-permissions 'IpProtocol=tcp,FromPort=8443,ToPort=8443,IpRanges=[{CidrIp=10.0.0.0/8,Description=control}]' >/dev/null

    run aws_cmd ec2 describe-security-groups --group-ids "$SG_TARGET_ID"
    assert_success
    gid=$(json_get "$output" '.SecurityGroups[0].IpPermissions[] | select(.FromPort==443) | .UserIdGroupPairs[0].GroupId')
    [ "$gid" = "$SG_SOURCE_ID" ]
    desc=$(json_get "$output" '.SecurityGroups[0].IpPermissions[] | select(.FromPort==443) | .UserIdGroupPairs[0].Description')
    [ "$desc" = "from-source-sg" ]
    cidr=$(json_get "$output" '.SecurityGroups[0].IpPermissions[] | select(.FromPort==8443) | .IpRanges[0].CidrIp')
    [ "$cidr" = "10.0.0.0/8" ]

    run aws_cmd ec2 describe-security-group-rules --filters "Name=group-id,Values=$SG_TARGET_ID"
    assert_success
    ref=$(json_get "$output" '.SecurityGroupRules[] | select(.FromPort==443) | .ReferencedGroupInfo.GroupId')
    [ "$ref" = "$SG_SOURCE_ID" ]
    desc=$(json_get "$output" '.SecurityGroupRules[] | select(.FromPort==443) | .Description')
    [ "$desc" = "from-source-sg" ]
    cidr=$(json_get "$output" '.SecurityGroupRules[] | select(.FromPort==8443) | .CidrIpv4')
    [ "$cidr" = "10.0.0.0/8" ]
}

# ─── transit gateways ───────────────────────────────────────────────────────

@test "EC2: create a transit gateway and read it back" {
    run aws_cmd ec2 create-transit-gateway \
        --description "bats hub" \
        --tag-specifications 'ResourceType=transit-gateway,Tags=[{Key=Name,Value=bats-tgw}]'
    assert_success
    TRANSIT_GATEWAY_ID=$(json_get "$output" '.TransitGateway.TransitGatewayId')
    [ "$(json_get "$output" '.TransitGateway.State')" = "available" ]
    [ "$(json_get "$output" '.TransitGateway.Options.AmazonSideAsn')" = "64512" ]
    [ "$(json_get "$output" '.TransitGateway.Options.SecurityGroupReferencingSupport')" = "disable" ]

    # The default route table is minted with the gateway, so both ids are already set and equal.
    assoc=$(json_get "$output" '.TransitGateway.Options.AssociationDefaultRouteTableId')
    prop=$(json_get "$output" '.TransitGateway.Options.PropagationDefaultRouteTableId')
    [ "$assoc" = "$prop" ]
    case "$assoc" in tgw-rtb-*) ;; *) return 1 ;; esac

    run aws_cmd ec2 describe-transit-gateways --transit-gateway-ids "$TRANSIT_GATEWAY_ID"
    assert_success
    [ "$(json_get "$output" '.TransitGateways[0].TransitGatewayId')" = "$TRANSIT_GATEWAY_ID" ]
    [ "$(json_get "$output" '.TransitGateways[0].Tags[0].Value')" = "bats-tgw" ]
}

@test "EC2: create a transit gateway with CIDR blocks" {
    run aws_cmd ec2 create-transit-gateway \
        --options 'TransitGatewayCidrBlocks=[10.99.0.0/16]'
    assert_success
    TRANSIT_GATEWAY_ID=$(json_get "$output" '.TransitGateway.TransitGatewayId')
    [ "$(json_get "$output" '.TransitGateway.Options.TransitGatewayCidrBlocks[0]')" = "10.99.0.0/16" ]

    run aws_cmd ec2 describe-transit-gateways --transit-gateway-ids "$TRANSIT_GATEWAY_ID"
    assert_success
    [ "$(json_get "$output" '.TransitGateways[0].Options.TransitGatewayCidrBlocks[0]')" = "10.99.0.0/16" ]
}

# Tags are changed after creation with create-tags rather than a tag specification, and read back
# from describe-transit-gateways, which is how a Terraform tag update converges.
@test "EC2: transit gateway tags changed after creation are visible on describe" {
    local out
    out=$(aws_cmd ec2 create-transit-gateway --tag-specifications \
        'ResourceType=transit-gateway,Tags=[{Key=Name,Value=bats-tgw-tags}]')
    TRANSIT_GATEWAY_ID=$(json_get "$out" '.TransitGateway.TransitGatewayId')

    aws_cmd ec2 create-tags --resources "$TRANSIT_GATEWAY_ID" --tags 'Key=env,Value=prod' >/dev/null

    run aws_cmd ec2 describe-transit-gateways --transit-gateway-ids "$TRANSIT_GATEWAY_ID"
    assert_success
    count=$(json_get "$output" '.TransitGateways[0].Tags | length')
    [ "$count" = "2" ]
    env_value=$(json_get "$output" '.TransitGateways[0].Tags[] | select(.Key=="env") | .Value')
    [ "$env_value" = "prod" ]

    aws_cmd ec2 delete-tags --resources "$TRANSIT_GATEWAY_ID" --tags 'Key=env' >/dev/null

    run aws_cmd ec2 describe-transit-gateways --transit-gateway-ids "$TRANSIT_GATEWAY_ID"
    assert_success
    count=$(json_get "$output" '.TransitGateways[0].Tags | length')
    [ "$count" = "1" ]
}

@test "EC2: modify a transit gateway" {
    local out
    out=$(aws_cmd ec2 create-transit-gateway --description "before")
    TRANSIT_GATEWAY_ID=$(json_get "$out" '.TransitGateway.TransitGatewayId')

    run aws_cmd ec2 modify-transit-gateway \
        --transit-gateway-id "$TRANSIT_GATEWAY_ID" \
        --description "after" \
        --options 'DnsSupport=disable,AddTransitGatewayCidrBlocks=[10.100.0.0/16]'
    assert_success
    [ "$(json_get "$output" '.TransitGateway.Description')" = "after" ]
    [ "$(json_get "$output" '.TransitGateway.Options.DnsSupport')" = "disable" ]
    [ "$(json_get "$output" '.TransitGateway.Options.TransitGatewayCidrBlocks[0]')" = "10.100.0.0/16" ]

    # Describe serializes the same list, so read it back through the CLI's own parser too.
    run aws_cmd ec2 describe-transit-gateways --transit-gateway-ids "$TRANSIT_GATEWAY_ID"
    assert_success
    [ "$(json_get "$output" '.TransitGateways[0].Options.TransitGatewayCidrBlocks[0]')" = "10.100.0.0/16" ]
}

@test "EC2: delete a transit gateway" {
    local out tgw
    out=$(aws_cmd ec2 create-transit-gateway --description "short lived")
    tgw=$(json_get "$out" '.TransitGateway.TransitGatewayId')

    run aws_cmd ec2 delete-transit-gateway --transit-gateway-id "$tgw"
    assert_success

    run aws_cmd ec2 describe-transit-gateways --transit-gateway-ids "$tgw"
    assert_failure
    assert_output --partial "InvalidTransitGatewayID.NotFound"
}

@test "EC2: describing an unknown transit gateway is rejected" {
    run aws_cmd ec2 describe-transit-gateways --transit-gateway-ids tgw-0123456789abcdef0
    assert_failure
    assert_output --partial "InvalidTransitGatewayID.NotFound"
}
