# NOTE: Keep resource definitions in sync with ../compat-terraform/main.tf

# ── S3 Bucket ──────────────────────────────────────────────────────────────
resource "aws_s3_bucket" "app" {
  bucket = "floci-compat-app"
}

resource "aws_s3_bucket_versioning" "app" {
  bucket = aws_s3_bucket.app.id
  versioning_configuration {
    status = "Enabled"
  }
}

# ── SQS Queue ──────────────────────────────────────────────────────────────
resource "aws_sqs_queue" "jobs" {
  name                       = "floci-compat-jobs"
  visibility_timeout_seconds = 30
  message_retention_seconds  = 86400
}

resource "aws_sqs_queue" "jobs_dlq" {
  name = "floci-compat-jobs-dlq"
}

resource "aws_sqs_queue_redrive_policy" "jobs" {
  queue_url = aws_sqs_queue.jobs.id
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.jobs_dlq.arn
    maxReceiveCount     = 3
  })
}

# ── SNS Topic ──────────────────────────────────────────────────────────────
resource "aws_sns_topic" "events" {
  name = "floci-compat-events"
}

resource "aws_sns_topic_subscription" "events_to_sqs" {
  topic_arn = aws_sns_topic.events.arn
  protocol  = "sqs"
  endpoint  = aws_sqs_queue.jobs.arn
}

# ── DynamoDB Table ─────────────────────────────────────────────────────────
resource "aws_dynamodb_table" "items" {
  name         = "floci-compat-items"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "pk"
  range_key    = "sk"

  attribute {
    name = "pk"
    type = "S"
  }

  attribute {
    name = "sk"
    type = "S"
  }

  ttl {
    attribute_name = "expires_at"
    enabled        = true
  }

  tags = {
    Environment = "compat-test"
  }
}

# ── IAM Role (for Lambda) ──────────────────────────────────────────────────
resource "aws_iam_role" "lambda_exec" {
  name = "floci-compat-lambda-exec"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
    }]
  })
}

# ── ECR Repository ─────────────────────────────────────────────────────────
resource "aws_ecr_repository" "app" {
  name                 = "floci-compat-app"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = false
  }
}

# ── ECS Cluster ─────────────────────────────────────────────────────────────
resource "aws_ecs_cluster" "app" {
  name = "floci-compat-cluster"

  setting {
    name  = "containerInsights"
    value = "disabled"
  }
}

# ── KMS Key ─────────────────────────────────────────────────────────────────
resource "aws_kms_key" "compat" {
  description             = "Floci OpenTofu compatibility key"
  deletion_window_in_days = 7
  enable_key_rotation     = false
}

# ── Kinesis Stream ──────────────────────────────────────────────────────────
resource "aws_kinesis_stream" "events" {
  name             = "floci-compat-events"
  shard_count      = 1
  retention_period = 24
}

# ── CloudWatch Log Group ────────────────────────────────────────────────────
resource "aws_cloudwatch_log_group" "app" {
  name              = "/floci/compat/app"
  retention_in_days = 1

  tags = {
    Environment = "compat-test"
  }
}

# ── EventBridge Event Bus ───────────────────────────────────────────────────
resource "aws_cloudwatch_event_bus" "compat" {
  name = "floci-compat-bus"
}

# ── Step Functions State Machine ────────────────────────────────────────────
resource "aws_sfn_state_machine" "compat" {
  name     = "floci-compat-state-machine"
  role_arn = aws_iam_role.lambda_exec.arn
  definition = jsonencode({
    Comment = "Floci OpenTofu compatibility state machine"
    StartAt = "Pass"
    States = {
      Pass = {
        Type = "Pass"
        End  = true
      }
    }
  })
}

# ── CloudFormation Stack ────────────────────────────────────────────────────
resource "aws_cloudformation_stack" "compat" {
  name = "floci-compat-stack"
  template_body = jsonencode({
    Resources = {
      CompatBucket = {
        Type = "AWS::S3::Bucket"
        Properties = {
          BucketName = "floci-compat-cfn-bucket"
        }
      }
    }
  })
}

# ── EKS Cluster ─────────────────────────────────────────────────────────────
resource "aws_eks_cluster" "compat" {
  name     = "floci-compat-eks"
  role_arn = aws_iam_role.lambda_exec.arn

  vpc_config {
    subnet_ids = [aws_subnet.compat.id]
  }
}

# ── CodeBuild Project ───────────────────────────────────────────────────────
resource "aws_codebuild_project" "compat" {
  name         = "floci-compat-codebuild"
  service_role = aws_iam_role.lambda_exec.arn

  artifacts {
    type = "NO_ARTIFACTS"
  }

  environment {
    compute_type                = "BUILD_GENERAL1_SMALL"
    image                       = "aws/codebuild/standard:7.0"
    type                        = "LINUX_CONTAINER"
    image_pull_credentials_type = "CODEBUILD"
  }

  source {
    type = "NO_SOURCE"
  }
}

# ── API Gateway v1 ──────────────────────────────────────────────────────────
resource "aws_api_gateway_rest_api" "compat" {
  name = "floci-compat-api"
}

# ── API Gateway v2 ──────────────────────────────────────────────────────────
resource "aws_apigatewayv2_api" "compat" {
  name          = "floci-compat-http-api"
  protocol_type = "HTTP"
}

# ── AppConfig Application ───────────────────────────────────────────────────
resource "aws_appconfig_application" "compat" {
  name        = "floci-compat-appconfig"
  description = "Floci OpenTofu compatibility application"
}

# ── Athena Workgroup ────────────────────────────────────────────────────────
resource "aws_athena_workgroup" "compat" {
  name = "floci-compat-workgroup"
}

# ── AWS Backup Vault ─────────────────────────────────────────────────────────
resource "aws_backup_vault" "compat" {
  name = "floci-compat-vault"
}

# ── Cloud Map Namespace ─────────────────────────────────────────────────────
resource "aws_service_discovery_private_dns_namespace" "compat" {
  name        = "floci-compat.internal"
  description = "Floci OpenTofu compatibility namespace"
  vpc         = aws_vpc.compat.id
}

# ── CodeDeploy Application ──────────────────────────────────────────────────
resource "aws_codedeploy_app" "compat" {
  name             = "floci-compat-codedeploy"
  compute_platform = "Server"
}

# ── ACM Certificate ─────────────────────────────────────────────────────────
resource "aws_acm_certificate" "compat" {
  domain_name       = "floci-compat.internal"
  validation_method = "DNS"
}

# ── SES Email Identity ──────────────────────────────────────────────────────
resource "aws_ses_email_identity" "compat" {
  email = "terraform@floci-compat.internal"
}

# ── EventBridge Scheduler ───────────────────────────────────────────────────
resource "aws_scheduler_schedule" "compat" {
  name                         = "floci-compat-schedule"
  schedule_expression          = "rate(1 hour)"
  schedule_expression_timezone = "UTC"

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = aws_sqs_queue.jobs.arn
    role_arn = aws_iam_role.lambda_exec.arn
  }
}

# ── Transfer Family Server ──────────────────────────────────────────────────
resource "aws_transfer_server" "compat" {
  identity_provider_type = "SERVICE_MANAGED"
  protocols              = ["SFTP"]
}

# ── WAFv2 Web ACL ───────────────────────────────────────────────────────────
resource "aws_wafv2_web_acl" "compat" {
  name  = "floci-compat-waf"
  scope = "REGIONAL"

  default_action {
    allow {}
  }

  visibility_config {
    cloudwatch_metrics_enabled = false
    metric_name                = "flociCompatWaf"
    sampled_requests_enabled   = false
  }
}

# ── CloudTrail Trail ─────────────────────────────────────────────────────────
resource "aws_cloudtrail" "compat" {
  name           = "floci-compat-trail"
  s3_bucket_name = aws_s3_bucket.app.bucket
}

# ── Cost and Usage Report ───────────────────────────────────────────────────
resource "aws_cur_report_definition" "compat" {
  report_name                = "floci-compat-report"
  time_unit                  = "HOURLY"
  format                     = "textORcsv"
  compression                = "GZIP"
  additional_schema_elements = ["RESOURCES"]
  s3_bucket                  = aws_s3_bucket.app.bucket
  s3_prefix                  = "cur"
  s3_region                  = "us-east-1"
}

# ── AppSync GraphQL API ─────────────────────────────────────────────────────
resource "aws_appsync_graphql_api" "compat" {
  name                = "floci-compat-graphql"
  authentication_type = "API_KEY"
  schema              = "type Query { health: String }"
}

# ── Glue Catalog Database ───────────────────────────────────────────────────
resource "aws_glue_catalog_database" "compat" {
  name        = "floci_compat_db"
  description = "Floci OpenTofu compatibility database"
}

# ── ElastiCache Cluster ─────────────────────────────────────────────────────
resource "aws_elasticache_cluster" "compat" {
  cluster_id      = "floci-compat-cache"
  engine          = "redis"
  node_type       = "cache.t3.micro"
  num_cache_nodes = 1
  port            = 6379
}

# ── Firehose Delivery Stream ────────────────────────────────────────────────
resource "aws_kinesis_firehose_delivery_stream" "compat" {
  name        = "floci-compat-firehose"
  destination = "extended_s3"

  extended_s3_configuration {
    role_arn           = aws_iam_role.lambda_exec.arn
    bucket_arn         = aws_s3_bucket.app.arn
    buffering_size     = 5
    buffering_interval = 60
    compression_format = "UNCOMPRESSED"
  }
}

# ── EventBridge Pipe ────────────────────────────────────────────────────────
resource "aws_pipes_pipe" "compat" {
  name     = "floci-compat-pipe"
  role_arn = aws_iam_role.lambda_exec.arn
  source   = aws_sqs_queue.jobs.arn
  target   = aws_sns_topic.events.arn

  source_parameters {
    sqs_queue_parameters {
      batch_size                         = 1
      maximum_batching_window_in_seconds = 0
    }
  }
}

# ── AWS Batch Compute Environment ───────────────────────────────────────────
resource "aws_batch_compute_environment" "compat" {
  compute_environment_name = "floci-compat-batch"
  type                     = "MANAGED"
  state                    = "ENABLED"
  service_role             = aws_iam_role.lambda_exec.arn

  compute_resources {
    type               = "FARGATE"
    max_vcpus          = 1
    subnets            = [aws_subnet.compat.id]
    security_group_ids = [aws_security_group.compat.id]
  }
}

# ── Neptune Cluster ─────────────────────────────────────────────────────────
resource "aws_neptune_cluster" "compat" {
  cluster_identifier      = "floci-compat-neptune"
  engine                  = "neptune"
  skip_final_snapshot     = true
  apply_immediately       = true
  backup_retention_period = 1
}

# ── OpenSearch Domain ───────────────────────────────────────────────────────
resource "aws_opensearch_domain" "compat" {
  domain_name = "floci-compat-search"

  cluster_config {
    instance_type = "t3.small.search"
  }
}

# ── SSM Parameters ─────────────────────────────────────────────────────────
resource "aws_ssm_parameter" "db_url" {
  name  = "/floci-compat/db-url"
  type  = "String"
  value = "jdbc:postgresql://localhost:5432/app"
}

resource "aws_ssm_parameter" "api_key" {
  name  = "/floci-compat/api-key"
  type  = "SecureString"
  value = "super-secret-key"
}

# ── Secrets Manager ────────────────────────────────────────────────────────
resource "aws_secretsmanager_secret" "db_creds" {
  name = "floci-compat/db-creds"
}

resource "aws_secretsmanager_secret_version" "db_creds" {
  secret_id = aws_secretsmanager_secret.db_creds.id
  secret_string = jsonencode({
    username = "admin"
    password = "s3cret"
  })
}

# ── Outputs ────────────────────────────────────────────────────────────────
output "bucket_id" {
  value = aws_s3_bucket.app.id
}

output "queue_url" {
  value = aws_sqs_queue.jobs.url
}

output "topic_arn" {
  value = aws_sns_topic.events.arn
}

output "table_name" {
  value = aws_dynamodb_table.items.name
}

output "secret_arn" {
  value = aws_secretsmanager_secret.db_creds.arn
}

# ── VPC networking (issues #468, #401: VpcAttribute, RouteTableAssociation, DescribeTags) ──
resource "aws_vpc" "compat" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_support   = false
  enable_dns_hostnames = false

  tags = {
    Name        = "floci-compat-vpc"
    Environment = "compat-test"
  }
}

resource "aws_internet_gateway" "compat" {
  vpc_id = aws_vpc.compat.id

  tags = {
    Name = "floci-compat-igw"
  }
}

resource "aws_subnet" "compat" {
  vpc_id            = aws_vpc.compat.id
  cidr_block        = "10.0.1.0/24"
  availability_zone = "us-east-1a"

  tags = {
    Name = "floci-compat-subnet"
  }
}

resource "aws_route_table" "compat" {
  vpc_id = aws_vpc.compat.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.compat.id
  }

  tags = {
    Name = "floci-compat-rt"
  }
}

# Exercises AssociateRouteTable + DescribeRouteTables(association.route-table-association-id)
resource "aws_route_table_association" "compat" {
  subnet_id      = aws_subnet.compat.id
  route_table_id = aws_route_table.compat.id
}

resource "aws_security_group" "compat" {
  name        = "floci-compat-sg"
  description = "Compat test security group"
  vpc_id      = aws_vpc.compat.id

  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "floci-compat-sg"
  }
}

output "vpc_id" {
  value = aws_vpc.compat.id
}

output "subnet_id" {
  value = aws_subnet.compat.id
}

output "route_table_id" {
  value = aws_route_table.compat.id
}

output "security_group_id" {
  value = aws_security_group.compat.id
}

# ── Route53 ────────────────────────────────────────────────────────────────
resource "aws_route53_zone" "compat" {
  name          = "floci-compat.internal"
  force_destroy = true

  tags = {
    Environment = "compat-test"
  }
}

resource "aws_route53_record" "app" {
  zone_id = aws_route53_zone.compat.zone_id
  name    = "app.floci-compat.internal"
  type    = "A"
  ttl     = 300
  records = ["10.0.1.10"]
}

resource "aws_route53_health_check" "app" {
  fqdn              = "app.floci-compat.internal"
  port              = 80
  type              = "HTTP"
  resource_path     = "/health"
  failure_threshold = 3
  request_interval  = 30

  tags = {
    Environment = "compat-test"
  }
}

output "zone_id" {
  value = aws_route53_zone.compat.zone_id
}

output "health_check_id" {
  value = aws_route53_health_check.app.id
}

# ── Cognito User Pool ─────────────────────────────────────────────────────
resource "aws_cognito_user_pool" "pool" {
  name = "floci-compat-pool"

  password_policy {
    minimum_length    = 12
    require_lowercase = true
    require_numbers   = true
    require_symbols   = true
    require_uppercase = true
  }

  auto_verified_attributes = ["email"]
  username_attributes      = ["email"]

  admin_create_user_config {
    allow_admin_create_user_only = false
  }

  verification_message_template {
    default_email_option = "CONFIRM_WITH_CODE"
    email_message        = "Your code is {####}"
    email_subject        = "Verify your account"
  }

  account_recovery_setting {
    recovery_mechanism {
      name     = "verified_email"
      priority = 1
    }
  }
}

output "user_pool_id" {
  value = aws_cognito_user_pool.pool.id
}

output "user_pool_arn" {
  value = aws_cognito_user_pool.pool.arn
}

# ── Cognito User Pool Client ──────────────────────────────────────────────
resource "aws_cognito_user_pool_client" "client" {
  name         = "floci-compat-pool-client"
  user_pool_id = aws_cognito_user_pool.pool.id
}

# ── Kinesis Firehose Delivery Stream (extended_s3, issue #1043) ───────────
resource "aws_kinesis_firehose_delivery_stream" "events" {
  name        = "floci-compat-firehose"
  destination = "extended_s3"

  extended_s3_configuration {
    role_arn            = aws_iam_role.lambda_exec.arn
    bucket_arn          = aws_s3_bucket.app.arn
    prefix              = "events/data/"
    error_output_prefix = "events/errors/"
    compression_format  = "GZIP"
    buffering_size      = 64
    buffering_interval  = 120
  }

  tags = {
    Environment = "compat-test"
  }
}

output "firehose_stream_arn" {
  value = aws_kinesis_firehose_delivery_stream.events.arn
}
