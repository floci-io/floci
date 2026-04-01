# Quick Start

This guide gets Floci running and verifies that AWS CLI commands work against it in under five minutes.

## Step 1 — Start Floci

=== "Native (recommended)"

    `latest` is the native image — sub-second startup, minimal memory:

    ```yaml
    services:
      floci:
        image: hectorvent/floci:latest
        ports:
          - "4566:4566"
        volumes:
          # Local directory bind mount (default)
          - ./data:/app/data
          
          # OR named volume (optional):
          # - floci-data:/app/data

#    volumes:
#      floci-data:
    ```

    ```bash
    docker compose up -d
    ```

=== "JVM"

    Use `latest-jvm` if you need broader platform compatibility:

    ```yaml
    services:
      floci:
        image: hectorvent/floci:latest-jvm
        ports:
          - "4566:4566"
        volumes:
          # Local directory bind mount (default)
          - ./data:/app/data
          
          # OR named volume (optional):
          # - floci-data:/app/data

#    volumes:
#      floci-data:
    ```

    ```bash
    docker compose up -d
    ```

=== "Build from source"

    ```bash
    git clone https://github.com/hectorvent/floci.git
    cd floci
    mvn quarkus:dev   # hot reload, port 4566
    ```

## Step 2 — Configure AWS CLI

Floci accepts any dummy credentials — no real AWS account needed.

```bash
export AWS_ENDPOINT=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
```

Add these to your shell profile (`.bashrc` / `.zshrc`) so they persist across sessions.

## Step 3 — Verify the Setup

Run a few quick smoke tests:

```bash
# S3 — create a bucket and upload a file
aws s3 mb s3://my-bucket --endpoint-url $AWS_ENDPOINT
echo "hello floci" | aws s3 cp - s3://my-bucket/hello.txt --endpoint-url $AWS_ENDPOINT
aws s3 ls s3://my-bucket --endpoint-url $AWS_ENDPOINT

# SQS — create a queue and send a message
aws sqs create-queue --queue-name orders --endpoint-url $AWS_ENDPOINT
aws sqs send-message \
  --queue-url $AWS_ENDPOINT/000000000000/orders \
  --message-body '{"event":"order.placed"}' \
  --endpoint-url $AWS_ENDPOINT

# DynamoDB — create a table
aws dynamodb create-table \
  --table-name Users \
  --attribute-definitions AttributeName=id,AttributeType=S \
  --key-schema AttributeName=id,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST \
  --endpoint-url $AWS_ENDPOINT
```

You should see successful responses for all three commands.

## Step 4 — Use in Your Application

Point your AWS SDK to Floci the same way:

=== "Java"

    ```java
    S3Client s3 = S3Client.builder()
        .endpointOverride(URI.create("http://localhost:4566"))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create("test", "test")))
        .build();
    ```

=== "Python (boto3)"

    ```python
    import boto3

    s3 = boto3.client(
        "s3",
        endpoint_url="http://localhost:4566",
        region_name="us-east-1",
        aws_access_key_id="test",
        aws_secret_access_key="test",
    )
    ```

=== "Node.js"

    ```javascript
    import { S3Client } from "@aws-sdk/client-s3";

    const s3 = new S3Client({
      endpoint: "http://localhost:4566",
      region: "us-east-1",
      credentials: { accessKeyId: "test", secretAccessKey: "test" },
      forcePathStyle: true,
    });
    ```

=== "Go"

    ```go
    cfg, _ := config.LoadDefaultConfig(context.TODO(),
        config.WithRegion("us-east-1"),
        config.WithEndpointResolverWithOptions(
            aws.EndpointResolverWithOptionsFunc(func(service, region string, opts ...interface{}) (aws.Endpoint, error) {
                return aws.Endpoint{URL: "http://localhost:4566"}, nil
            }),
        ),
    )
    client := s3.NewFromConfig(cfg)
    ```

## Next Steps

- [Configure Docker Compose with ElastiCache and RDS ports](../configuration/docker-compose.md)
- [Review all configuration options](../configuration/application-yml.md)
- [Browse per-service documentation](../services/index.md)