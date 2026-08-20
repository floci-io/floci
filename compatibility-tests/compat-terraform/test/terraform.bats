#!/usr/bin/env bats
# Terraform Compatibility Tests for floci

setup_file() {
    load 'test_helper/common-setup'

    cd "$TF_DIR"

    echo "# === Terraform Compatibility Test ===" >&3
    echo "# Endpoint: $FLOCI_ENDPOINT" >&3

    # Clean any previous state
    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* 2>/dev/null || true

    echo "# --- Setup: state bucket & lock table ---" >&3
    create_state_backend
    generate_backend_config

    echo "# --- terraform init ---" >&3
    run terraform init -backend-config=/tmp/floci-backend.hcl \
        -var="endpoint=${FLOCI_ENDPOINT}" -input=false -no-color
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

    cd "$TF_DIR"

    echo "# --- terraform destroy ---" >&3
    terraform destroy -var="endpoint=${FLOCI_ENDPOINT}" -input=false -auto-approve -no-color || true
}

setup() {
    load 'test_helper/common-setup'
}

# --- Spot Checks ---

@test "Terraform: S3 bucket created" {
    run aws_cmd s3api head-bucket --bucket floci-compat-app
    assert_success
}

@test "Terraform: SQS queue created" {
    run aws_cmd sqs get-queue-url --queue-name floci-compat-jobs
    assert_success
    assert_output --partial "QueueUrl"
}

@test "Terraform: SNS topic created" {
    run aws_cmd sns list-topics
    assert_success
    assert_output --partial "floci-compat-events"
}

@test "Terraform: SES receipt rule set created and active" {
    run aws_cmd ses describe-receipt-rule-set --rule-set-name floci-compat-rule-set
    assert_success
    assert_output --partial "floci-compat-rule-set"

    run aws_cmd ses describe-active-receipt-rule-set
    assert_success
    assert_output --partial "floci-compat-rule-set"
}

@test "Terraform: DynamoDB table created" {
    run aws_cmd dynamodb describe-table --table-name floci-compat-items
    assert_success
    assert_output --partial "ACTIVE"
}

@test "Terraform: SSM parameter created" {
    run aws_cmd ssm get-parameter --name /floci-compat/db-url
    assert_success
    assert_output --partial "jdbc:"
}

@test "Terraform: Secrets Manager secret created" {
    run aws_cmd secretsmanager describe-secret --secret-id "floci-compat/db-creds"
    assert_success
    assert_output --partial "floci-compat"
}

@test "Terraform: RDS DB instance created and available" {
    run aws_cmd rds describe-db-instances --db-instance-identifier floci-compat-db
    assert_success
    assert_output --partial "floci-compat-db"
    assert_output --partial "available"
}

@test "Terraform: CloudWatch alarm created with tags" {
    run aws_cmd cloudwatch describe-alarms --alarm-names floci-compat-cpu-alarm
    assert_success
    assert_output --partial "floci-compat-cpu-alarm"

    ALARM_ARN=$(aws_cmd cloudwatch describe-alarms --alarm-names floci-compat-cpu-alarm \
        --query 'MetricAlarms[0].AlarmArn' --output text)
    run aws_cmd cloudwatch list-tags-for-resource --resource-arn "$ALARM_ARN"
    assert_success
    assert_output --partial "compat-test"
}

@test "Terraform: VPC created with custom DNS settings" {
    run aws_cmd ec2 describe-vpcs \
        --filters "Name=tag:Name,Values=floci-compat-vpc"
    assert_success
    assert_output --partial "floci-compat-vpc"
    assert_output --partial "10.0.0.0/16"
}

@test "Terraform: VPC enableDnsSupport persisted as false" {
    VPC_ID=$(aws_cmd ec2 describe-vpcs \
        --filters "Name=tag:Name,Values=floci-compat-vpc" \
        --query 'Vpcs[0].VpcId' --output text)
    run aws_cmd ec2 describe-vpc-attribute \
        --vpc-id "$VPC_ID" --attribute enableDnsSupport
    assert_success
    assert_output --partial '"Value": false'
}

@test "Terraform: VPC enableDnsHostnames persisted as false" {
    VPC_ID=$(aws_cmd ec2 describe-vpcs \
        --filters "Name=tag:Name,Values=floci-compat-vpc" \
        --query 'Vpcs[0].VpcId' --output text)
    run aws_cmd ec2 describe-vpc-attribute \
        --vpc-id "$VPC_ID" --attribute enableDnsHostnames
    assert_success
    assert_output --partial '"Value": false'
}

@test "Terraform: Route53 hosted zone created" {
    ZONE_ID=$(aws_cmd route53 list-hosted-zones \
        --query "HostedZones[?Name=='floci-compat.internal.'].Id | [0]" \
        --output text | sed 's|/hostedzone/||')
    [ -n "$ZONE_ID" ]
    run aws_cmd route53 get-hosted-zone --id "$ZONE_ID"
    assert_success
    assert_output --partial "floci-compat.internal"
}

@test "Terraform: Route53 A record created" {
    ZONE_ID=$(aws_cmd route53 list-hosted-zones \
        --query "HostedZones[?Name=='floci-compat.internal.'].Id | [0]" \
        --output text | sed 's|/hostedzone/||')
    run aws_cmd route53 list-resource-record-sets --hosted-zone-id "$ZONE_ID"
    assert_success
    assert_output --partial "app.floci-compat.internal"
    assert_output --partial "10.0.1.10"
}

@test "Terraform: Route53 zone has auto-created SOA and NS records" {
    ZONE_ID=$(aws_cmd route53 list-hosted-zones \
        --query "HostedZones[?Name=='floci-compat.internal.'].Id | [0]" \
        --output text | sed 's|/hostedzone/||')
    run aws_cmd route53 list-resource-record-sets --hosted-zone-id "$ZONE_ID"
    assert_success
    assert_output --partial '"SOA"'
    assert_output --partial '"NS"'
}

@test "Terraform: Route53 health check created" {
    HEALTH_CHECK_ID=$(aws_cmd route53 list-health-checks \
        --query "HealthChecks[?HealthCheckConfig.FullyQualifiedDomainName=='app.floci-compat.internal'].Id | [0]" \
        --output text)
    [ -n "$HEALTH_CHECK_ID" ]
    run aws_cmd route53 get-health-check --health-check-id "$HEALTH_CHECK_ID"
    assert_success
    assert_output --partial "app.floci-compat.internal"
    assert_output --partial "HTTP"
}

@test "Terraform: Route53 zone tags persisted" {
    ZONE_ID=$(aws_cmd route53 list-hosted-zones \
        --query "HostedZones[?Name=='floci-compat.internal.'].Id | [0]" \
        --output text | sed 's|/hostedzone/||')
    run aws_cmd route53 list-tags-for-resource \
        --resource-type hostedzone --resource-id "$ZONE_ID"
    assert_success
    assert_output --partial "compat-test"
}

@test "Terraform: Cognito user pool client created without optional blocks" {
    POOL_ID=$(aws_cmd cognito-idp list-user-pools --max-results 10 \
        --query "UserPools[?Name=='floci-compat-pool'].Id | [0]" --output text)
    [ -n "$POOL_ID" ]
    run aws_cmd cognito-idp list-user-pool-clients --user-pool-id "$POOL_ID" \
        --query "UserPoolClients[?ClientName=='floci-compat-pool-client'].ClientId | [0]" --output text
    assert_success
    [ -n "$output" ]
    [ "$output" != "None" ]
}

@test "Terraform: Firehose extended_s3 delivery stream created with correct config" {
    run aws_cmd firehose describe-delivery-stream --delivery-stream-name floci-compat-firehose \
        --query "DeliveryStreamDescription.Destinations[0].ExtendedS3DestinationDescription.CompressionFormat" --output text
    assert_success
    assert_output "GZIP"
}

@test "Terraform: Application Auto Scaling scalable target created with tags" {
    run aws_cmd application-autoscaling describe-scalable-targets --service-namespace ecs \
        --resource-ids service/floci-compat-cluster/floci-compat-service \
        --query "ScalableTargets[0].MaxCapacity" --output text
    assert_success
    assert_output "20"

    run aws_cmd application-autoscaling describe-scalable-targets --service-namespace ecs \
        --resource-ids service/floci-compat-cluster/floci-compat-service \
        --query "ScalableTargets[0].ScalableTargetARN" --output text
    assert_success
    [[ "$output" == arn:aws:application-autoscaling:* ]]

    run aws_cmd application-autoscaling list-tags-for-resource --resource-arn "$output" \
        --query "Tags.Environment" --output text
    assert_success
    assert_output "compat-test"
}

@test "Terraform: Application Auto Scaling target-tracking policy round-trips resource_label" {
    run aws_cmd application-autoscaling describe-scaling-policies --service-namespace ecs \
        --resource-id service/floci-compat-cluster/floci-compat-service \
        --query "ScalingPolicies[?PolicyName=='floci-compat-alb-request-count'].TargetTrackingScalingPolicyConfiguration.PredefinedMetricSpecification.ResourceLabel | [0]" \
        --output text
    assert_success
    assert_output "app/floci-compat-alb/abc123/targetgroup/floci-compat-tg/def456"

    run aws_cmd application-autoscaling describe-scaling-policies --service-namespace ecs \
        --resource-id service/floci-compat-cluster/floci-compat-service \
        --query "ScalingPolicies[?PolicyName=='floci-compat-alb-request-count'].TargetTrackingScalingPolicyConfiguration.ScaleInCooldown | [0]" \
        --output text
    assert_success
    assert_output "240"
}

# A successful apply proves very little on its own: a response that silently drops a
# field still applies cleanly, and only shows up later as perpetual drift. Asserting an
# empty second plan is what actually catches that class of bug.
#
# Whole-config: this used to be scoped to just the Application Auto Scaling resources
# because a full re-plan reported drift on aws_cognito_user_pool (device_configuration,
# email_configuration, user_pool_add_ons), aws_db_instance (auto_minor_version_upgrade)
# and aws_kinesis_firehose_delivery_stream (s3_backup_mode) - see #2200. Now that those
# are fixed, this guards every resource in the fixture instead of a hand-picked subset.
@test "Terraform: re-planning the full configuration reports no changes" {
    cd "$TF_DIR"
    run terraform plan -var="endpoint=${FLOCI_ENDPOINT}" -input=false -no-color -detailed-exitcode
    if [ "$status" -eq 2 ]; then
        echo "# drift detected on re-plan:" >&3
        echo "$output" >&3
    fi
    [ "$status" -eq 0 ]
}

@test "Terraform: IAM role attaches managed policies outside the curated set" {
    run aws_cmd iam list-attached-role-policies --role-name floci-compat-managed-policy-role \
        --query "sort_by(AttachedPolicies, &PolicyArn)[].PolicyArn" --output text
    assert_success
    assert_output --partial "arn:aws:iam::aws:policy/AmazonEC2ReadOnlyAccess"
    assert_output --partial "arn:aws:iam::aws:policy/service-role/AmazonElasticMapReduceRole"
}

# A policy AWS does not publish must still be rejected, otherwise a typo silently
# "succeeds" and Terraform/CloudFormation never surface the mistake.
@test "Terraform: attaching a nonexistent managed policy still fails" {
    run aws_cmd iam attach-role-policy --role-name floci-compat-managed-policy-role \
        --policy-arn "arn:aws:iam::aws:policy/AmazonS3FullAcess"
    assert_failure
    assert_output --partial "NoSuchEntity"
}

# Tags are stored and readable via ListRoleTags, but the provider reads them off the
# GetRole response. Omitting them there made every tagged role read back untagged.
@test "Terraform: GetRole returns the role's tags" {
    run aws_cmd iam get-role --role-name floci-compat-tagged-role \
        --query "Role.Tags[?Key=='Environment'].Value" --output text
    assert_success
    assert_output "compat"
}

@test "Terraform: GetPolicy returns the policy's tags" {
    policy_arn=$(aws_cmd iam list-policies --scope Local \
        --query "Policies[?PolicyName=='floci-compat-tagged-policy'].Arn" --output text)
    run aws_cmd iam get-policy --policy-arn "$policy_arn" \
        --query "Policy.Tags[?Key=='Environment'].Value" --output text
    assert_success
    assert_output "compat"
}

# The other half of the contract: IAM's listing operations deliberately return a subset
# of attributes. ListRoles "does not return the following attributes, even though they
# are an attribute of the returned object: PermissionsBoundary, RoleLastUsed, Tags".
# Emitting them here would deviate from AWS in the opposite direction.
@test "Terraform: ListRoles omits tags even for a tagged role" {
    run aws_cmd iam list-roles \
        --query "Roles[?RoleName=='floci-compat-tagged-role'].Tags" --output text
    assert_success
    refute_output --partial "compat"
}

@test "Terraform: ListPolicies omits tags and description even for a tagged policy" {
    run aws_cmd iam list-policies --scope Local \
        --query "Policies[?PolicyName=='floci-compat-tagged-policy'].[Tags,Description]" \
        --output text
    assert_success
    refute_output --partial "compat"
    refute_output --partial "round trip"
}

# The assertion that actually matters for the drift class: tags that do not survive the
# round trip apply cleanly and then re-plan dirty forever.
@test "Terraform: re-planning the tagged IAM resources reports no changes" {
    cd "$TF_DIR"
    run terraform plan -var="endpoint=${FLOCI_ENDPOINT}" -input=false -no-color -detailed-exitcode \
        -target=aws_iam_role.tagged \
        -target=aws_iam_policy.tagged
    if [ "$status" -eq 2 ]; then
        echo "# drift detected on re-plan:" >&3
        echo "$output" >&3
    fi
    [ "$status" -eq 0 ]
}

@test "Terraform: GuardDuty detector is created with features" {
    run aws_cmd guardduty list-detectors --query "DetectorIds[0]" --output text
    assert_success
    DETECTOR_ID="$output"
    run aws_cmd guardduty get-detector --detector-id "$DETECTOR_ID" \
        --query "[Status, FindingPublishingFrequency]" --output text
    assert_success
    assert_output --partial "ENABLED"
    assert_output --partial "SIX_HOURS"
}

@test "Terraform: GuardDuty organization configuration round-trips" {
    run aws_cmd guardduty list-detectors --query "DetectorIds[0]" --output text
    assert_success
    DETECTOR_ID="$output"
    run aws_cmd guardduty describe-organization-configuration --detector-id "$DETECTOR_ID" \
        --query "AutoEnableOrganizationMembers" --output text
    assert_success
    assert_output "ALL"
}

# additional_configuration is an ordered list block; drift here means the
# emulator reordered the list on read-back.
@test "Terraform: re-planning GuardDuty reports no changes" {
    cd "$TF_DIR"
    run terraform plan -var="endpoint=${FLOCI_ENDPOINT}" -input=false -no-color -detailed-exitcode \
        -target=aws_guardduty_detector.compat \
        -target=aws_guardduty_detector_feature.runtime_monitoring \
        -target=aws_guardduty_organization_configuration.compat \
        -target=aws_guardduty_organization_configuration_feature.runtime_monitoring
    if [ "$status" -eq 2 ]; then
        echo "# drift detected on re-plan:" >&3
        echo "$output" >&3
    fi
    [ "$status" -eq 0 ]
}
