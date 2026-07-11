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

@test "Terraform: ECR repository created" {
    run aws_cmd ecr describe-repositories --repository-names floci-compat-app
    assert_success
    assert_output --partial "floci-compat-app"
}

@test "Terraform: ECS cluster created" {
    run aws_cmd ecs describe-clusters --clusters floci-compat-cluster
    assert_success
    assert_output --partial "floci-compat-cluster"
    assert_output --partial "ACTIVE"
}

@test "Terraform: KMS key created" {
    run aws_cmd kms list-keys
    assert_success
    [ "$(aws_cmd kms list-keys --query 'length(Keys)' --output text)" -gt 0 ]
}

@test "Terraform: Kinesis stream created" {
    run aws_cmd kinesis describe-stream-summary --stream-name floci-compat-events
    assert_success
    assert_output --partial "ACTIVE"
}

@test "Terraform: CloudWatch log group created" {
    run aws_cmd logs describe-log-groups --log-group-name-prefix /floci/compat/app
    assert_success
    assert_output --partial "/floci/compat/app"
}

@test "Terraform: EventBridge event bus created" {
    run aws_cmd events describe-event-bus --name floci-compat-bus
    assert_success
    assert_output --partial "floci-compat-bus"
}

@test "Terraform: Step Functions state machine created" {
    run aws_cmd stepfunctions list-state-machines
    assert_success
    assert_output --partial "floci-compat-state-machine"
}

@test "Terraform: CloudFormation stack created" {
    run aws_cmd cloudformation describe-stacks --stack-name floci-compat-stack
    assert_success
    assert_output --partial "floci-compat-stack"
}

@test "Terraform: EKS cluster created" {
    run aws_cmd eks describe-cluster --name floci-compat-eks
    assert_success
    assert_output --partial "floci-compat-eks"
    assert_output --partial "ACTIVE"
}

@test "Terraform: CodeBuild project created" {
    run aws_cmd codebuild batch-get-projects --names floci-compat-codebuild
    assert_success
    assert_output --partial "floci-compat-codebuild"
}

@test "Terraform: API Gateway v1 REST API created" {
    run aws_cmd apigateway get-rest-apis
    assert_success
    assert_output --partial "floci-compat-api"
}

@test "Terraform: API Gateway v2 HTTP API created" {
    run aws_cmd apigatewayv2 get-apis
    assert_success
    assert_output --partial "floci-compat-http-api"
}

@test "Terraform: AppConfig application created" {
    run aws_cmd appconfig list-applications
    assert_success
    assert_output --partial "floci-compat-appconfig"
}

@test "Terraform: Athena workgroup created" {
    run aws_cmd athena get-work-group --work-group floci-compat-workgroup
    assert_success
    assert_output --partial "floci-compat-workgroup"
}

@test "Terraform: Backup vault created" {
    run aws_cmd backup describe-backup-vault --backup-vault-name floci-compat-vault
    assert_success
    assert_output --partial "floci-compat-vault"
}

@test "Terraform: Cloud Map namespace created" {
    run aws_cmd servicediscovery list-namespaces
    assert_success
    assert_output --partial "floci-compat.internal"
}

@test "Terraform: CodeDeploy application created" {
    run aws_cmd deploy get-application --application-name floci-compat-codedeploy
    assert_success
    assert_output --partial "floci-compat-codedeploy"
}

@test "Terraform: ACM certificate created" {
    run aws_cmd acm list-certificates
    assert_success
    assert_output --partial "floci-compat.internal"
}

@test "Terraform: SES identity created" {
    run aws_cmd ses get-identity-verification-attributes \
        --identities terraform@floci-compat.internal
    assert_success
    assert_output --partial "terraform@floci-compat.internal"
}

@test "Terraform: EventBridge schedule created" {
    run aws_cmd scheduler get-schedule --name floci-compat-schedule
    assert_success
    assert_output --partial "floci-compat-schedule"
}

@test "Terraform: Transfer server created" {
    run aws_cmd transfer list-servers
    assert_success
    assert_output --partial "ServerId"
}

@test "Terraform: WAFv2 web ACL created" {
    run aws_cmd wafv2 list-web-acls --scope REGIONAL
    assert_success
    assert_output --partial "floci-compat-waf"
}

@test "Terraform: CloudTrail trail created" {
    run aws_cmd cloudtrail describe-trails
    assert_success
    assert_output --partial "floci-compat-trail"
}

@test "Terraform: Cost and Usage Report created" {
    run aws_cmd cur describe-report-definitions
    assert_success
    assert_output --partial "floci-compat-report"
}

@test "Terraform: AppSync GraphQL API created" {
    run aws_cmd appsync list-graphql-apis
    assert_success
    assert_output --partial "floci-compat-graphql"
}

@test "Terraform: Glue catalog database created" {
    run aws_cmd glue get-databases
    assert_success
    assert_output --partial "floci_compat_db"
}

@test "Terraform: ElastiCache cluster created" {
    run aws_cmd elasticache describe-cache-clusters --cache-cluster-id floci-compat-cache
    assert_success
    assert_output --partial "floci-compat-cache"
}

@test "Terraform: Firehose delivery stream created" {
    run aws_cmd firehose list-delivery-streams
    assert_success
    assert_output --partial "floci-compat-firehose"
}

@test "Terraform: EventBridge pipe created" {
    run aws_cmd pipes list-pipes
    assert_success
    assert_output --partial "floci-compat-pipe"
}

@test "Terraform: Batch compute environment created" {
    run aws_cmd batch describe-compute-environments
    assert_success
    assert_output --partial "floci-compat-batch"
}

@test "Terraform: Neptune cluster created" {
    run aws_cmd neptune describe-db-clusters --db-cluster-identifier floci-compat-neptune
    assert_success
    assert_output --partial "floci-compat-neptune"
}

@test "Terraform: OpenSearch domain created" {
    run aws_cmd opensearch describe-domain --domain-name floci-compat-search
    assert_success
    assert_output --partial "floci-compat-search"
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
