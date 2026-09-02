# NOTE: Keep resource definitions in sync with ../compat-opentofu/main.tf

# -- S3 Bucket ------------------------------------------------------------------
resource "aws_s3_bucket" "app" {
  bucket        = "floci-compat-app"
  force_destroy = true
}

resource "aws_s3_bucket_versioning" "app" {
  bucket = aws_s3_bucket.app.id
  versioning_configuration {
    status = "Enabled"
  }
}

# -- SQS Queue -----------------------------------------------------------------
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

# -- SNS Topic -----------------------------------------------------------------
resource "aws_sns_topic" "events" {
  name = "floci-compat-events"
}

resource "aws_sns_topic_subscription" "events_to_sqs" {
  topic_arn = aws_sns_topic.events.arn
  protocol  = "sqs"
  endpoint  = aws_sqs_queue.jobs.arn
}

# -- DynamoDB Table -------------------------------------------------------------
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

# -- IAM Role (for Lambda) -----------------------------------------------------
data "aws_iam_policy_document" "lambda_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]
    effect  = "Allow"

    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "lambda_exec" {
  name               = "floci-compat-lambda-exec"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume_role.json
}

# -- ECR Repository ------------------------------------------------------------
resource "aws_ecr_repository" "app" {
  name                 = "floci-compat-app"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = false
  }
}

# -- ECS Cluster ---------------------------------------------------------------
resource "aws_ecs_cluster" "app" {
  name = "floci-compat-cluster"
}

# -- KMS Key -------------------------------------------------------------------
resource "aws_kms_key" "compat" {
  description             = "Floci Terraform compatibility key"
  deletion_window_in_days = 7
  enable_key_rotation     = false
}

# -- Kinesis Stream ------------------------------------------------------------
resource "aws_kinesis_stream" "events" {
  name             = "floci-compat-events"
  shard_count      = 1
  retention_period = 24
}

# -- CloudWatch Log Group ------------------------------------------------------
resource "aws_cloudwatch_log_group" "app" {
  name              = "/floci/compat/app"
  retention_in_days = 1

  tags = {
    Environment = "compat-test"
  }
}

# -- EventBridge Event Bus -----------------------------------------------------
resource "aws_cloudwatch_event_bus" "compat" {
  name = "floci-compat-bus"
}

# -- Step Functions State Machine ----------------------------------------------
resource "aws_sfn_state_machine" "compat" {
  name     = "floci-compat-state-machine"
  role_arn = aws_iam_role.lambda_exec.arn
  definition = jsonencode({
    Comment = "Floci Terraform compatibility state machine"
    StartAt = "Pass"
    States = {
      Pass = {
        Type = "Pass"
        End  = true
      }
    }
  })
}

# -- CloudFormation Stack ------------------------------------------------------
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

# -- EKS Cluster ---------------------------------------------------------------
resource "aws_eks_cluster" "compat" {
  name     = "floci-compat-eks"
  role_arn = aws_iam_role.lambda_exec.arn

  vpc_config {
    subnet_ids = [aws_subnet.compat.id]
  }
}

# -- API Gateway v2 ------------------------------------------------------------
resource "aws_apigatewayv2_api" "compat" {
  name          = "floci-compat-http-api"
  protocol_type = "HTTP"
}

# -- AppConfig Application -----------------------------------------------------
resource "aws_appconfig_application" "compat" {
  name        = "floci-compat-appconfig"
  description = "Floci Terraform compatibility application"
}

# -- AWS Backup Vault ----------------------------------------------------------
resource "aws_backup_vault" "compat" {
  name = "floci-compat-vault"
}

# -- Cloud Map Namespace -------------------------------------------------------
resource "aws_service_discovery_private_dns_namespace" "compat" {
  name        = "floci-compat.internal"
  description = "Floci Terraform compatibility namespace"
  vpc         = aws_vpc.compat.id
}

# -- CodeDeploy Application ----------------------------------------------------
resource "aws_codedeploy_app" "compat" {
  name             = "floci-compat-codedeploy"
  compute_platform = "Server"
}

# -- ACM Certificate -----------------------------------------------------------
resource "aws_acm_certificate" "compat" {
  domain_name       = "floci-compat.internal"
  validation_method = "DNS"
}

# -- SES Email Identity --------------------------------------------------------
resource "aws_ses_email_identity" "compat" {
  email = "terraform@floci-compat.internal"
}

# -- EventBridge Scheduler -----------------------------------------------------
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

# -- WAFv2 Web ACL -------------------------------------------------------------
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

# -- Cost and Usage Report -----------------------------------------------------
resource "aws_cur_report_definition" "compat" {
  report_name                = "floci-compat-report"
  time_unit                  = "HOURLY"
  format                     = "Parquet"
  compression                = "Parquet"
  additional_schema_elements = ["RESOURCES"]
  s3_bucket                  = aws_s3_bucket.app.bucket
  s3_prefix                  = "cur"
  s3_region                  = "us-east-1"
}

# -- AppSync GraphQL API -------------------------------------------------------
resource "aws_appsync_graphql_api" "compat" {
  name                = "floci-compat-graphql"
  authentication_type = "API_KEY"
  schema              = "type Query { health: String }"
}

# -- ElastiCache Replication Group ----------------------------------------------
resource "aws_elasticache_replication_group" "compat" {
  replication_group_id = "floci-compat-cache"
  description          = "Floci Terraform compatibility cache"
  engine               = "redis"
  node_type            = "cache.t3.micro"
  num_cache_clusters   = 1
  port                 = 6379
}

# -- Firehose Delivery Stream --------------------------------------------------
resource "aws_kinesis_firehose_delivery_stream" "compat" {
  name        = "floci-compat-firehose-basic"
  destination = "extended_s3"

  extended_s3_configuration {
    role_arn           = aws_iam_role.lambda_exec.arn
    bucket_arn         = aws_s3_bucket.app.arn
    buffering_size     = 5
    buffering_interval = 60
    compression_format = "UNCOMPRESSED"
  }
}

# -- EventBridge Pipe ----------------------------------------------------------
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

# -- SSM Parameters ------------------------------------------------------------
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

# -- Secrets Manager -----------------------------------------------------------
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

# -- RDS DB Instance -----------------------------------------------------------
resource "aws_db_instance" "app" {
  identifier          = "floci-compat-db"
  engine              = "postgres"
  engine_version      = "15"
  instance_class      = "db.t3.micro"
  allocated_storage   = 20
  username            = "admin"
  password            = "Password1!"
  skip_final_snapshot = true
}

# -- Outputs -------------------------------------------------------------------
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

# -- Cognito User Pool ---------------------------------------------------------
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

# -- Cognito User Pool Client --------------------------------------------------
resource "aws_cognito_user_pool_client" "client" {
  name         = "floci-compat-pool-client"
  user_pool_id = aws_cognito_user_pool.pool.id
}

# -- CloudWatch Alarms ---------------------------------------------------------
resource "aws_cloudwatch_metric_alarm" "cpu" {
  alarm_name          = "floci-compat-cpu-alarm"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "CPUUtilization"
  namespace           = "AWS/EC2"
  period              = 60
  statistic           = "Average"
  threshold           = 80
  alarm_description   = "CPU alarm for compat test"

  tags = {
    env = "compat-test"
  }
}

output "alarm_arn" {
  value = aws_cloudwatch_metric_alarm.cpu.arn
}

# -- VPC networking (issues #468, #401: VpcAttribute, RouteTableAssociation, DescribeTags) ------
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

# -- Route53 -------------------------------------------------------------------
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

# -- Kinesis Firehose Delivery Stream (extended_s3, issue #1043) ---------------
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

# -- Application Auto Scaling (scalable target + target-tracking policies) -----
resource "aws_appautoscaling_target" "ecs_service" {
  max_capacity       = 20
  min_capacity       = 2
  resource_id        = "service/floci-compat-cluster/floci-compat-service"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"

  tags = {
    Environment = "compat-test"
  }
}

resource "aws_appautoscaling_policy" "ecs_cpu" {
  name               = "floci-compat-cpu-tracking"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.ecs_service.resource_id
  scalable_dimension = aws_appautoscaling_target.ecs_service.scalable_dimension
  service_namespace  = aws_appautoscaling_target.ecs_service.service_namespace

  target_tracking_scaling_policy_configuration {
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
    target_value = 65
  }
}

# resource_label round-trips only if every nested field is echoed back, so this
# resource is the canary for target-tracking drift.
resource "aws_appautoscaling_policy" "ecs_alb_requests" {
  name               = "floci-compat-alb-request-count"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.ecs_service.resource_id
  scalable_dimension = aws_appautoscaling_target.ecs_service.scalable_dimension
  service_namespace  = aws_appautoscaling_target.ecs_service.service_namespace

  target_tracking_scaling_policy_configuration {
    target_value       = 1000
    scale_in_cooldown  = 240
    scale_out_cooldown = 60

    predefined_metric_specification {
      predefined_metric_type = "ALBRequestCountPerTarget"
      resource_label         = "app/floci-compat-alb/abc123/targetgroup/floci-compat-tg/def456"
    }
  }
}

output "appautoscaling_target_arn" {
  value = aws_appautoscaling_target.ecs_service.arn
}

output "appautoscaling_alb_policy_arn" {
  value = aws_appautoscaling_policy.ecs_alb_requests.arn
}

# ── SES Receipt Rule Set ───────────────────────────────────────────────────
# floci stores it inertly (no inbound-mail routing); the management API just round-trips.
resource "aws_ses_receipt_rule_set" "compat" {
  rule_set_name = "floci-compat-rule-set"
}

resource "aws_ses_active_receipt_rule_set" "compat" {
  rule_set_name = aws_ses_receipt_rule_set.compat.rule_set_name
}

output "ses_rule_set_name" {
  value = aws_ses_receipt_rule_set.compat.rule_set_name
}

# -- IAM managed-policy attachment ---------------------------------------------
# These policies sit outside the small curated set Floci used to ship, so the
# attachments below only succeed once the full AWS managed-policy catalog is seeded.
resource "aws_iam_role" "managed_policy_attach" {
  name               = "floci-compat-managed-policy-role"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume_role.json
}

resource "aws_iam_role_policy_attachment" "ec2_read_only" {
  role       = aws_iam_role.managed_policy_attach.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ReadOnlyAccess"
}

resource "aws_iam_role_policy_attachment" "emr_service_role" {
  role       = aws_iam_role.managed_policy_attach.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonElasticMapReduceRole"
}

output "managed_policy_role_arn" {
  value = aws_iam_role.managed_policy_attach.arn
}

# The provider reads tags off the GetRole/GetPolicy/GetFunction response rather than by calling
# List*Tags, so a tagged resource that does not echo them back applies cleanly and then diffs on
# every subsequent plan. The re-plan assertion below is what catches that.
resource "aws_iam_role" "tagged" {
  name               = "floci-compat-tagged-role"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume_role.json

  tags = {
    Environment = "compat"
    Owner       = "floci"
  }
}

resource "aws_iam_policy" "tagged" {
  name        = "floci-compat-tagged-policy"
  description = "Tagged policy used to assert tags survive a round trip"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = "s3:GetObject"
      Resource = "*"
    }]
  })

  tags = {
    Environment = "compat"
  }
}

output "tagged_role_arn" {
  value = aws_iam_role.tagged.arn
}

output "tagged_policy_arn" {
  value = aws_iam_policy.tagged.arn
}

# -- GuardDuty -----------------------------------------------------------------
# Detector, per-feature configuration, and organization configuration mirror the
# resource set an org security-baseline stack manages. additional_configuration
# is an ordered list block: Floci must echo it back in submitted order or every
# re-plan proposes a replacement.
resource "aws_guardduty_detector" "compat" {
  enable                       = true
  finding_publishing_frequency = "SIX_HOURS"

  tags = {
    Environment = "compat-test"
  }
}

resource "aws_guardduty_detector_feature" "runtime_monitoring" {
  detector_id = aws_guardduty_detector.compat.id
  name        = "RUNTIME_MONITORING"
  status      = "ENABLED"

  additional_configuration {
    name   = "ECS_FARGATE_AGENT_MANAGEMENT"
    status = "ENABLED"
  }

  additional_configuration {
    name   = "EC2_AGENT_MANAGEMENT"
    status = "ENABLED"
  }

  additional_configuration {
    name   = "EKS_ADDON_MANAGEMENT"
    status = "DISABLED"
  }
}

resource "aws_guardduty_organization_configuration" "compat" {
  detector_id                      = aws_guardduty_detector.compat.id
  auto_enable_organization_members = "ALL"
}

resource "aws_guardduty_organization_configuration_feature" "runtime_monitoring" {
  detector_id = aws_guardduty_detector.compat.id
  name        = "RUNTIME_MONITORING"
  auto_enable = "ALL"

  additional_configuration {
    name        = "ECS_FARGATE_AGENT_MANAGEMENT"
    auto_enable = "ALL"
  }

  additional_configuration {
    name        = "EC2_AGENT_MANAGEMENT"
    auto_enable = "ALL"
  }

  additional_configuration {
    name        = "EKS_ADDON_MANAGEMENT"
    auto_enable = "NONE"
  }

  depends_on = [aws_guardduty_organization_configuration.compat]
}

output "guardduty_detector_id" {
  value = aws_guardduty_detector.compat.id
}

output "guardduty_detector_arn" {
  value = aws_guardduty_detector.compat.arn
}
