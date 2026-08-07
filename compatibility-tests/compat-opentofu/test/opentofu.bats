#!/usr/bin/env bats
# OpenTofu Compatibility Tests for floci

setup_file() {
    load 'test_helper/common-setup'

    cd "$TOFU_DIR"

    echo "# === OpenTofu Compatibility Test ===" >&3
    echo "# Endpoint: $FLOCI_ENDPOINT" >&3

    # Clean any previous state
    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* 2>/dev/null || true

    echo "# --- Setup: state bucket & lock table ---" >&3
    create_state_backend
    generate_backend_config

    echo "# --- tofu init ---" >&3
    run tofu init -backend-config=/tmp/floci-backend.hcl \
        -var="endpoint=${FLOCI_ENDPOINT}" -input=false -no-color
    if [ "$status" -ne 0 ]; then
        echo "# tofu init failed: $output" >&3
        return 1
    fi

    echo "# --- tofu validate ---" >&3
    run tofu validate -no-color
    if [ "$status" -ne 0 ]; then
        echo "# tofu validate failed: $output" >&3
        return 1
    fi

    echo "# --- tofu plan ---" >&3
    run tofu plan -var="endpoint=${FLOCI_ENDPOINT}" -input=false -no-color
    if [ "$status" -ne 0 ]; then
        echo "# tofu plan failed: $output" >&3
        return 1
    fi

    echo "# --- tofu apply ---" >&3
    run tofu apply -var="endpoint=${FLOCI_ENDPOINT}" -input=false -auto-approve -no-color
    if [ "$status" -ne 0 ]; then
        echo "# tofu apply failed: $output" >&3
        return 1
    fi
}

teardown_file() {
    load 'test_helper/common-setup'

    cd "$TOFU_DIR"

    echo "# --- tofu destroy ---" >&3
    tofu destroy -var="endpoint=${FLOCI_ENDPOINT}" -input=false -auto-approve -no-color || true
}

setup() {
    load 'test_helper/common-setup'
}

# --- Spot Checks ---

@test "OpenTofu: S3 bucket created" {
    run aws_cmd s3api head-bucket --bucket floci-compat-app
    assert_success
}

@test "OpenTofu: SQS queue created" {
    run aws_cmd sqs get-queue-url --queue-name floci-compat-jobs
    assert_success
    assert_output --partial "QueueUrl"
}

@test "OpenTofu: SNS topic created" {
    run aws_cmd sns list-topics
    assert_success
    assert_output --partial "floci-compat-events"
}

@test "OpenTofu: DynamoDB table created" {
    run aws_cmd dynamodb describe-table --table-name floci-compat-items
    assert_success
    assert_output --partial "ACTIVE"
}

@test "OpenTofu: SSM parameter created" {
    run aws_cmd ssm get-parameter --name /floci-compat/db-url
    assert_success
    assert_output --partial "jdbc:"
}

@test "OpenTofu: Secrets Manager secret created" {
    run aws_cmd secretsmanager describe-secret --secret-id "floci-compat/db-creds"
    assert_success
    assert_output --partial "floci-compat"
}

@test "OpenTofu: ECR repository created" {
    run aws_cmd ecr describe-repositories --repository-names floci-compat-app
    assert_success
    assert_output --partial "floci-compat-app"
}

@test "OpenTofu: ECS cluster created" {
    run aws_cmd ecs describe-clusters --clusters floci-compat-cluster
    assert_success
    assert_output --partial "floci-compat-cluster"
    assert_output --partial "ACTIVE"
}

@test "OpenTofu: KMS key created" {
    run aws_cmd kms list-keys
    assert_success
    [ "$(aws_cmd kms list-keys --query 'length(Keys)' --output text)" -gt 0 ]
}

@test "OpenTofu: Kinesis stream created" {
    run aws_cmd kinesis describe-stream-summary --stream-name floci-compat-events
    assert_success
    assert_output --partial "ACTIVE"
}

@test "OpenTofu: CloudWatch log group created" {
    run aws_cmd logs describe-log-groups --log-group-name-prefix /floci/compat/app
    assert_success
    assert_output --partial "/floci/compat/app"
}

@test "OpenTofu: EventBridge event bus created" {
    run aws_cmd events describe-event-bus --name floci-compat-bus
    assert_success
    assert_output --partial "floci-compat-bus"
}

@test "OpenTofu: Step Functions state machine created" {
    run aws_cmd stepfunctions list-state-machines
    assert_success
    assert_output --partial "floci-compat-state-machine"
}

@test "OpenTofu: CloudFormation stack created" {
    run aws_cmd cloudformation describe-stacks --stack-name floci-compat-stack
    assert_success
    assert_output --partial "floci-compat-stack"
}

@test "OpenTofu: EKS cluster created" {
    run aws_cmd eks describe-cluster --name floci-compat-eks
    assert_success
    assert_output --partial "floci-compat-eks"
    assert_output --partial "ACTIVE"
}

@test "OpenTofu: CodeBuild project created" {
    run aws_cmd codebuild batch-get-projects --names floci-compat-codebuild
    assert_success
    assert_output --partial "floci-compat-codebuild"
}

@test "OpenTofu: API Gateway v1 REST API created" {
    run aws_cmd apigateway get-rest-apis
    assert_success
    assert_output --partial "floci-compat-api"
}

@test "OpenTofu: API Gateway v2 HTTP API created" {
    run aws_cmd apigatewayv2 get-apis
    assert_success
    assert_output --partial "floci-compat-http-api"
}

@test "OpenTofu: AppConfig application created" {
    run aws_cmd appconfig list-applications
    assert_success
    assert_output --partial "floci-compat-appconfig"
}

@test "OpenTofu: Athena workgroup created" {
    run aws_cmd athena get-work-group --work-group floci-compat-workgroup
    assert_success
    assert_output --partial "floci-compat-workgroup"
}

@test "OpenTofu: Backup vault created" {
    run aws_cmd backup describe-backup-vault --backup-vault-name floci-compat-vault
    assert_success
    assert_output --partial "floci-compat-vault"
}

@test "OpenTofu: Cloud Map namespace created" {
    run aws_cmd servicediscovery list-namespaces
    assert_success
    assert_output --partial "floci-compat.internal"
}

@test "OpenTofu: CodeDeploy application created" {
    run aws_cmd deploy get-application --application-name floci-compat-codedeploy
    assert_success
    assert_output --partial "floci-compat-codedeploy"
}

@test "OpenTofu: ACM certificate created" {
    run aws_cmd acm list-certificates
    assert_success
    assert_output --partial "floci-compat.internal"
}

@test "OpenTofu: SES identity created" {
    run aws_cmd ses get-identity-verification-attributes \
        --identities terraform@floci-compat.internal
    assert_success
    assert_output --partial "terraform@floci-compat.internal"
}

@test "OpenTofu: EventBridge schedule created" {
    run aws_cmd scheduler get-schedule --name floci-compat-schedule
    assert_success
    assert_output --partial "floci-compat-schedule"
}

@test "OpenTofu: Transfer server created" {
    run aws_cmd transfer list-servers
    assert_success
    assert_output --partial "ServerId"
}

@test "OpenTofu: WAFv2 web ACL created" {
    run aws_cmd wafv2 list-web-acls --scope REGIONAL
    assert_success
    assert_output --partial "floci-compat-waf"
}

@test "OpenTofu: CloudTrail trail created" {
    run aws_cmd cloudtrail describe-trails
    assert_success
    assert_output --partial "floci-compat-trail"
}

@test "OpenTofu: Cost and Usage Report created" {
    run aws_cmd cur describe-report-definitions
    assert_success
    assert_output --partial "floci-compat-report"
}

@test "OpenTofu: AppSync GraphQL API created" {
    run aws_cmd appsync list-graphql-apis
    assert_success
    assert_output --partial "floci-compat-graphql"
}

@test "OpenTofu: Glue catalog database created" {
    run aws_cmd glue get-databases
    assert_success
    assert_output --partial "floci_compat_db"
}

@test "OpenTofu: ElastiCache cluster created" {
    run aws_cmd elasticache describe-cache-clusters --cache-cluster-id floci-compat-cache
    assert_success
    assert_output --partial "floci-compat-cache"
}

@test "OpenTofu: Firehose delivery stream created" {
    run aws_cmd firehose list-delivery-streams
    assert_success
    assert_output --partial "floci-compat-firehose"
}

@test "OpenTofu: EventBridge pipe created" {
    run aws_cmd pipes list-pipes
    assert_success
    assert_output --partial "floci-compat-pipe"
}

@test "OpenTofu: Batch compute environment created" {
    run aws_cmd batch describe-compute-environments
    assert_success
    assert_output --partial "floci-compat-batch"
}

@test "OpenTofu: Neptune cluster created" {
    run aws_cmd neptune describe-db-clusters --db-cluster-identifier floci-compat-neptune
    assert_success
    assert_output --partial "floci-compat-neptune"
}

@test "OpenTofu: OpenSearch domain created" {
    run aws_cmd opensearch describe-domain --domain-name floci-compat-search
    assert_success
    assert_output --partial "floci-compat-search"
}

@test "OpenTofu: VPC created with custom DNS settings" {
    run aws_cmd ec2 describe-vpcs \
        --filters "Name=tag:Name,Values=floci-compat-vpc"
    assert_success
    assert_output --partial "floci-compat-vpc"
    assert_output --partial "10.0.0.0/16"
}

@test "OpenTofu: VPC enableDnsSupport persisted as false" {
    VPC_ID=$(aws_cmd ec2 describe-vpcs \
        --filters "Name=tag:Name,Values=floci-compat-vpc" \
        --query 'Vpcs[0].VpcId' --output text)
    run aws_cmd ec2 describe-vpc-attribute \
        --vpc-id "$VPC_ID" --attribute enableDnsSupport
    assert_success
    assert_output --partial '"Value": false'
}

@test "OpenTofu: VPC enableDnsHostnames persisted as false" {
    VPC_ID=$(aws_cmd ec2 describe-vpcs \
        --filters "Name=tag:Name,Values=floci-compat-vpc" \
        --query 'Vpcs[0].VpcId' --output text)
    run aws_cmd ec2 describe-vpc-attribute \
        --vpc-id "$VPC_ID" --attribute enableDnsHostnames
    assert_success
    assert_output --partial '"Value": false'
}

@test "OpenTofu: Route53 hosted zone created" {
    ZONE_ID=$(aws_cmd route53 list-hosted-zones \
        --query "HostedZones[?Name=='floci-compat.internal.'].Id | [0]" \
        --output text | sed 's|/hostedzone/||')
    [ -n "$ZONE_ID" ]
    run aws_cmd route53 get-hosted-zone --id "$ZONE_ID"
    assert_success
    assert_output --partial "floci-compat.internal"
}

@test "OpenTofu: Route53 A record created" {
    ZONE_ID=$(aws_cmd route53 list-hosted-zones \
        --query "HostedZones[?Name=='floci-compat.internal.'].Id | [0]" \
        --output text | sed 's|/hostedzone/||')
    run aws_cmd route53 list-resource-record-sets --hosted-zone-id "$ZONE_ID"
    assert_success
    assert_output --partial "app.floci-compat.internal"
    assert_output --partial "10.0.1.10"
}

@test "OpenTofu: Route53 zone has auto-created SOA and NS records" {
    ZONE_ID=$(aws_cmd route53 list-hosted-zones \
        --query "HostedZones[?Name=='floci-compat.internal.'].Id | [0]" \
        --output text | sed 's|/hostedzone/||')
    run aws_cmd route53 list-resource-record-sets --hosted-zone-id "$ZONE_ID"
    assert_success
    assert_output --partial '"SOA"'
    assert_output --partial '"NS"'
}

@test "OpenTofu: Route53 health check created" {
    HEALTH_CHECK_ID=$(aws_cmd route53 list-health-checks \
        --query "HealthChecks[?HealthCheckConfig.FullyQualifiedDomainName=='app.floci-compat.internal'].Id | [0]" \
        --output text)
    [ -n "$HEALTH_CHECK_ID" ]
    run aws_cmd route53 get-health-check --health-check-id "$HEALTH_CHECK_ID"
    assert_success
    assert_output --partial "app.floci-compat.internal"
    assert_output --partial "HTTP"
}

@test "OpenTofu: Route53 zone tags persisted" {
    ZONE_ID=$(aws_cmd route53 list-hosted-zones \
        --query "HostedZones[?Name=='floci-compat.internal.'].Id | [0]" \
        --output text | sed 's|/hostedzone/||')
    run aws_cmd route53 list-tags-for-resource \
        --resource-type hostedzone --resource-id "$ZONE_ID"
    assert_success
    assert_output --partial "compat-test"
}

@test "OpenTofu: Cognito user pool client created without optional blocks" {
    POOL_ID=$(aws_cmd cognito-idp list-user-pools --max-results 10 \
        --query "UserPools[?Name=='floci-compat-pool'].Id | [0]" --output text)
    [ -n "$POOL_ID" ]
    run aws_cmd cognito-idp list-user-pool-clients --user-pool-id "$POOL_ID" \
        --query "UserPoolClients[?ClientName=='floci-compat-pool-client'].ClientId | [0]" --output text
    assert_success
    [ -n "$output" ]
    [ "$output" != "None" ]
}

@test "OpenTofu: Firehose extended_s3 delivery stream created with correct config" {
    run aws_cmd firehose describe-delivery-stream --delivery-stream-name floci-compat-firehose \
        --query "DeliveryStreamDescription.Destinations[0].ExtendedS3DestinationDescription.CompressionFormat" --output text
    assert_success
    assert_output "GZIP"
}
