terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.25"
    }
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.0"
    }
  }
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

  endpoints {
    iam    = var.endpoint
    lambda = var.endpoint
    sts    = var.endpoint
  }
}
