# Terraform with Floci

Terraform can use Floci as a local AWS-compatible endpoint. Terraform sends requests through the standard `hashicorp/aws` provider, while Floci stores and emulates the resources locally. No real AWS account or cloud resources are used.

## Start Floci

```yaml title="docker-compose.yml"
services:
  floci:
    image: floci/floci:latest
    ports:
      - "4566:4566"
    volumes:
      - ./data:/app/data
```

```bash
docker compose up -d
```

## Configure the AWS provider

Create `provider.tf`:

```hcl
terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
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
    dynamodb       = "http://localhost:4566"
    secretsmanager = "http://localhost:4566"
    s3             = "http://localhost:4566"
    sns            = "http://localhost:4566"
    sqs            = "http://localhost:4566"
    ssm            = "http://localhost:4566"
  }
}
```

The endpoint must be configured for every AWS service used by the Terraform configuration. See the [services overview](../services/index.md) for the services implemented by Floci.

## Create local AWS resources

Create `main.tf`:

```hcl
resource "aws_s3_bucket" "app" {
  bucket = "floci-terraform-example"
}

resource "aws_sqs_queue" "jobs" {
  name = "floci-terraform-jobs"
}

resource "aws_dynamodb_table" "items" {
  name         = "floci-terraform-items"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "id"

  attribute {
    name = "id"
    type = "S"
  }
}

resource "aws_ssm_parameter" "environment" {
  name  = "/floci/environment"
  type  = "String"
  value = "local"
}
```

Initialize and apply the configuration:

```bash
terraform init
terraform validate
terraform plan
terraform apply
```

Verify a resource through the AWS CLI:

```bash
aws --endpoint-url http://localhost:4566 \
  s3api head-bucket --bucket floci-terraform-example
```

Remove the local resources when finished:

```bash
terraform destroy
```

## Terraform state in Floci

Terraform can keep state locally by using its default local backend. For a fully emulated AWS workflow, Floci also supports the S3 backend and DynamoDB locking used by the compatibility suite:

```hcl
terraform {
  backend "s3" {
    bucket                      = "tfstate"
    key                         = "terraform.tfstate"
    region                      = "us-east-1"
    endpoint                    = "http://localhost:4566"
    dynamodb_endpoint           = "http://localhost:4566"
    dynamodb_table              = "tflock"
    access_key                  = "test"
    secret_key                  = "test"
    skip_credentials_validation = true
    skip_region_validation      = true
    force_path_style            = true
  }
}
```

Create the backend resources before running `terraform init`:

```bash
aws --endpoint-url http://localhost:4566 s3api create-bucket --bucket tfstate
aws --endpoint-url http://localhost:4566 dynamodb create-table \
  --table-name tflock \
  --attribute-definitions AttributeName=LockID,AttributeType=S \
  --key-schema AttributeName=LockID,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST
```

For a complete automated example covering more services and the full lifecycle, see [`compatibility-tests/compat-terraform`](https://github.com/floci-io/floci/tree/main/compatibility-tests/compat-terraform).
