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
    
    # volumes:
    #   floci-data:
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
    
    # volumes:
    #   floci-data:
    ```

    ```bash
    docker compose up -d
    ```

=== "Build from source"

    ```bash
    git clone https://github.com/floci-io/floci.git
    cd floci
    mvn quarkus:dev   # hot reload, port 4566
    ```

## Step 2 — Configure AWS CLI

Floci accepts any dummy credentials — no real AWS account needed.

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
```

Add these to your shell profile (`.bashrc` / `.zshrc`) so they persist across sessions.

## Step 3 — Verify the Setup

Run a few quick smoke tests:

```bash
# S3 — create a bucket and upload a file
aws s3 mb s3://my-bucket --endpoint-url $AWS_ENDPOINT_URL
echo "hello floci" | aws s3 cp - s3://my-bucket/hello.txt --endpoint-url $AWS_ENDPOINT_URL
aws s3 ls s3://my-bucket --endpoint-url $AWS_ENDPOINT_URL

# SQS — create a queue and send a message
aws sqs create-queue --queue-name orders --endpoint-url $AWS_ENDPOINT_URL
aws sqs send-message \
  --queue-url $AWS_ENDPOINT_URL/000000000000/orders \
  --message-body '{"event":"order.placed"}' \
  --endpoint-url $AWS_ENDPOINT_URL

# DynamoDB — create a table
aws dynamodb create-table \
  --table-name Users \
  --attribute-definitions AttributeName=id,AttributeType=S \
  --key-schema AttributeName=id,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST \
  --endpoint-url $AWS_ENDPOINT_URL
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

## Step 5 — (Optional) Push and pull a container image to emulated ECR

Floci emulates ECR with a real OCI registry behind it, so the stock `docker` client works against repositories you create through the AWS CLI. No daemon configuration needed — Floci returns repository URIs that resolve to loopback, which `docker` auto-trusts as insecure.

```bash
# Create the repository (lazy-starts the backing registry container)
aws ecr create-repository --repository-name floci-it/app --endpoint-url $AWS_ENDPOINT

# Authenticate
aws ecr get-login-password --endpoint-url $AWS_ENDPOINT \
  | docker login --username AWS --password-stdin \
        000000000000.dkr.ecr.us-east-1.localhost:5000

# Push
docker pull alpine:3.19
docker tag  alpine:3.19 000000000000.dkr.ecr.us-east-1.localhost:5000/floci-it/app:v1
docker push             000000000000.dkr.ecr.us-east-1.localhost:5000/floci-it/app:v1

# Pull from a clean local image store
docker rmi  000000000000.dkr.ecr.us-east-1.localhost:5000/floci-it/app:v1
docker pull 000000000000.dkr.ecr.us-east-1.localhost:5000/floci-it/app:v1
```

See the [ECR service docs](../services/ecr.md) for the full action surface, image-backed Lambda integration, and CDK `DockerImageFunction` support.

## Lambda on native Linux Docker (UFW)

When Floci runs **natively on a Linux host** (not Docker Desktop), Lambda function containers reach Floci's Runtime API server via the docker bridge gateway. On Ubuntu / Pop!_OS / Debian boxes with **UFW enabled**, the default `INPUT DROP` policy silently drops these packets and Lambda invocations time out with `Function.TimedOut`. This affects every Lambda packaging type — Zip *and* image-backed functions deployed via emulated ECR.

**One-time fix**, scoped to the docker bridge only (does not expose anything to the network — `docker0` is internal):

```bash
sudo ufw allow in on docker0 comment 'floci: containers reach host'
```

If you want to scope it tighter to just the Lambda Runtime API and the ECR registry port ranges:

```bash
sudo ufw allow in on docker0 to any port 9200:9299 proto tcp comment 'floci lambda runtime api'
sudo ufw allow in on docker0 to any port 5000:5099 proto tcp comment 'floci ecr registry'
```

**Docker Desktop** (macOS / Windows / Linux) does not need this — it routes container → host through the Docker VM, which Floci's `DockerHostResolver` detects automatically.

**Floci-in-Docker** (running the published Floci image inside a container) does not need this either — Lambda containers and Floci share the same docker network and reach each other via container IPs.

## Next Steps

- [Configure Docker Compose with ElastiCache and RDS ports](../configuration/docker-compose.md)
- [Review all configuration options](../configuration/application-yml.md)
- [Browse per-service documentation](../services/index.md)
