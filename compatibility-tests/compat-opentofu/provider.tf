terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  backend "s3" {}
}

variable "endpoint" {
  type    = string
  default = "http://localhost:4566"
}

provider "aws" {
  region     = "us-east-1"
  access_key = "test"
  secret_key = "test"

  skip_credentials_validation = true
  skip_metadata_api_check     = true
  skip_requesting_account_id  = true
  s3_use_path_style           = true

  endpoints {
    s3               = var.endpoint
    sqs              = var.endpoint
    sns              = var.endpoint
    dynamodb         = var.endpoint
    lambda           = var.endpoint
    iam              = var.endpoint
    sts              = var.endpoint
    ssm              = var.endpoint
    secretsmanager   = var.endpoint
    ec2              = var.endpoint
    route53          = var.endpoint
    elasticache      = var.endpoint
    ecr              = var.endpoint
    ecs              = var.endpoint
    kms              = var.endpoint
    kinesis          = var.endpoint
    logs             = var.endpoint
    events           = var.endpoint
    sfn              = var.endpoint
    cloudformation   = var.endpoint
    eks              = var.endpoint
    codebuild        = var.endpoint
    apigateway       = var.endpoint
    apigatewayv2     = var.endpoint
    appconfig        = var.endpoint
    appsync          = var.endpoint
    athena           = var.endpoint
    backup           = var.endpoint
    servicediscovery = var.endpoint
    codedeploy       = var.endpoint
    acm              = var.endpoint
    ses              = var.endpoint
    scheduler        = var.endpoint
    transfer         = var.endpoint
    wafv2            = var.endpoint
    cloudtrail       = var.endpoint
    cur              = var.endpoint
    glue             = var.endpoint
    firehose         = var.endpoint
    pipes            = var.endpoint
    batch            = var.endpoint
    neptune          = var.endpoint
    opensearch       = var.endpoint
  }
}
