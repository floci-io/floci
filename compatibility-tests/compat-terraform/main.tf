# NOTE: Keep resource definitions in sync with ../compat-opentofu/main.tf

# -- S3 Bucket ------------------------------------------------------------------
resource "aws_s3_bucket" "app" {
  bucket = "floci-compat-app"
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
  identifier        = "floci-compat-db"
  engine            = "postgres"
  engine_version    = "15"
  instance_class    = "db.t3.micro"
  allocated_storage = 20
  username          = "admin"
  password          = "Password1!"
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
