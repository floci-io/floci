# AWS CLI & SDK Setup

Floci accepts any non-empty credentials — no real AWS account is needed.

## Environment Variables

The simplest approach for local development:

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
```

## AWS CLI Profile

Add a dedicated profile to `~/.aws/config` and `~/.aws/credentials`:

```ini title="~/.aws/config"
[profile floci]
region = us-east-1
output = json
```

```ini title="~/.aws/credentials"
[floci]
aws_access_key_id = test
aws_secret_access_key = test
```

Then use it with every command:

```bash
aws s3 ls --profile floci --endpoint-url http://localhost:4566
```

Or set it as the default for your shell session:

```bash
export AWS_PROFILE=floci
export AWS_ENDPOINT_URL=http://localhost:4566
```

## SDK Configuration

### Java (AWS SDK v2)

```java
// Reusable endpoint override
URI endpoint = URI.create("http://localhost:4566");
AwsCredentialsProvider creds = StaticCredentialsProvider.create(
    AwsBasicCredentials.create("test", "test"));
Region region = Region.US_EAST_1;

// Build any client the same way
DynamoDbClient dynamo = DynamoDbClient.builder()
    .endpointOverride(endpoint)
    .region(region)
    .credentialsProvider(creds)
    .build();

SqsClient sqs = SqsClient.builder()
    .endpointOverride(endpoint)
    .region(region)
    .credentialsProvider(creds)
    .build();
```

### Python (boto3)

```python
import boto3

def floci_client(service):
    return boto3.client(
        service,
        endpoint_url="http://localhost:4566",
        region_name="us-east-1",
        aws_access_key_id="test",
        aws_secret_access_key="test",
    )

s3   = floci_client("s3")
sqs  = floci_client("sqs")
dynamo = floci_client("dynamodb")
```

### Node.js / TypeScript

```typescript
import { DynamoDBClient } from "@aws-sdk/client-dynamodb";
import { SQSClient } from "@aws-sdk/client-sqs";

const config = {
  endpoint: "http://localhost:4566",
  region: "us-east-1",
  credentials: { accessKeyId: "test", secretAccessKey: "test" },
};

const dynamo = new DynamoDBClient(config);
const sqs = new SQSClient(config);
```

!!! tip "S3 path-style URLs"
    When using S3 with the AWS SDK v3 (Node.js), add `forcePathStyle: true` to the config object. Floci serves S3 in path-style mode (`http://localhost:4566/bucket-name`).

### Go

```go
import (
    "github.com/aws/aws-sdk-go-v2/config"
    "github.com/aws/aws-sdk-go-v2/credentials"
)

cfg, err := config.LoadDefaultConfig(context.TODO(),
    config.WithRegion("us-east-1"),
    config.WithCredentialsProvider(credentials.NewStaticCredentialsProvider("test", "test", "")),
    config.WithEndpointResolverWithOptions(
        aws.EndpointResolverWithOptionsFunc(
            func(service, region string, opts ...interface{}) (aws.Endpoint, error) {
                return aws.Endpoint{URL: "http://localhost:4566"}, nil
            },
        ),
    ),
)
```

## Account ID

Floci uses account ID `000000000000` in all ARNs and queue URLs by default:

```
arn:aws:sqs:us-east-1:000000000000:my-queue
http://localhost:4566/000000000000/my-queue
```

Change the default with `FLOCI_DEFAULT_ACCOUNT_ID`:

```bash
FLOCI_DEFAULT_ACCOUNT_ID=123456789012
```

**Multi-account isolation** is also supported: if your access key ID is exactly 12 digits, Floci uses it directly as the account ID and fully isolates that account's resources from all others. See [Multi-Account Isolation](../configuration/multi-account.md) for details.