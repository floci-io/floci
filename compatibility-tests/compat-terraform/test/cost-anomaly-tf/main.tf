terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

variable "endpoint" {
  type    = string
  default = "http://localhost:4566"
}

variable "monitor_name" {
  type    = string
  default = "floci-compat-cost-monitor"
}

variable "absolute_threshold" {
  type    = number
  default = 100
}

variable "resource_tags" {
  type = map(string)
  default = {
    Fixture        = "cost-anomaly"
    Stage          = "initial"
    RemoveOnUpdate = "yes"
  }
}

provider "aws" {
  region     = "us-east-1"
  access_key = "941000000010"
  secret_key = "floci-compat-cost-anomaly"

  skip_credentials_validation = true
  skip_metadata_api_check     = true
  skip_requesting_account_id  = true
  skip_region_validation      = true

  endpoints {
    ce = var.endpoint
  }
}

resource "aws_ce_anomaly_monitor" "service" {
  name              = var.monitor_name
  monitor_type      = "DIMENSIONAL"
  monitor_dimension = "SERVICE"
  tags              = var.resource_tags
}

resource "aws_ce_anomaly_subscription" "daily" {
  name             = "floci-compat-cost-alerts"
  frequency        = "DAILY"
  monitor_arn_list = [aws_ce_anomaly_monitor.service.arn]
  tags             = var.resource_tags

  subscriber {
    type    = "EMAIL"
    address = "floci-compat@example.invalid"
  }

  threshold_expression {
    dimension {
      key           = "ANOMALY_TOTAL_IMPACT_ABSOLUTE"
      match_options = ["GREATER_THAN_OR_EQUAL"]
      values        = [tostring(var.absolute_threshold)]
    }
  }
}

output "monitor_arn" {
  value = aws_ce_anomaly_monitor.service.arn
}

output "subscription_arn" {
  value = aws_ce_anomaly_subscription.daily.arn
}
