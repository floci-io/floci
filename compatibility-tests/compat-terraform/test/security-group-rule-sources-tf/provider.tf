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

provider "aws" {
  region     = "us-east-1"
  access_key = "test"
  secret_key = "test"

  skip_credentials_validation = true
  skip_metadata_api_check     = true
  s3_use_path_style           = true

  # Deliberately NOT skip_requesting_account_id. The provider renders a same-account
  # referenced_security_group_id as a bare group id and a cross-account one as
  # "<account>/<group>", and it tells them apart by comparing ReferencedGroupInfo.UserId
  # against its own resolved account. Skipping account resolution leaves that unknown,
  # so every same-account reference reads back as cross-account and the plan never
  # converges. Floci answers STS GetCallerIdentity, so there is nothing to skip.

  endpoints {
    ec2 = var.endpoint
    sts = var.endpoint
  }
}
