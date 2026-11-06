# Testcontainers — Go

The `testcontainers-floci-go` module integrates Floci with [Testcontainers for Go](https://golang.testcontainers.org/). It starts a real Floci container before your tests and shuts it down after, with no extra setup.

## Installation

```bash
go get github.com/floci-io/testcontainers-floci-go
```

Requires Go 1.25+ and Testcontainers for Go v0.42+.

## Basic usage

```go
package myservice_test

import (
    "context"
    "testing"

    "github.com/aws/aws-sdk-go-v2/aws"
    "github.com/aws/aws-sdk-go-v2/config"
    "github.com/aws/aws-sdk-go-v2/credentials"
    "github.com/aws/aws-sdk-go-v2/service/s3"
    floci "github.com/floci-io/testcontainers-floci-go"
)

func TestS3CreateBucket(t *testing.T) {
    ctx := context.Background()

    container, err := floci.NewFlociContainer().Start(ctx)
    if err != nil {
        t.Fatal(err)
    }
    defer container.Stop(ctx)

    cfg, err := config.LoadDefaultConfig(ctx,
        config.WithRegion(container.GetRegion()),
        config.WithCredentialsProvider(credentials.NewStaticCredentialsProvider(
            container.GetAccessKey(), container.GetSecretKey(), "",
        )),
        config.WithBaseEndpoint(container.GetEndpoint()),
    )
    if err != nil {
        t.Fatal(err)
    }

    client := s3.NewFromConfig(cfg, func(o *s3.Options) {
        o.UsePathStyle = true
    })

    _, err = client.CreateBucket(ctx, &s3.CreateBucketInput{
        Bucket: aws.String("my-bucket"),
    })
    if err != nil {
        t.Fatal(err)
    }

    out, err := client.ListBuckets(ctx, &s3.ListBucketsInput{})
    if err != nil {
        t.Fatal(err)
    }

    var found bool
    for _, b := range out.Buckets {
        if aws.ToString(b.Name) == "my-bucket" {
            found = true
            break
        }
    }
    if !found {
        t.Error("bucket not found after create")
    }
}
```

## SQS example

```go
func TestSqsSendReceive(t *testing.T) {
    ctx := context.Background()

    container, err := floci.NewFlociContainer().Start(ctx)
    if err != nil {
        t.Fatal(err)
    }
    defer container.Stop(ctx)

    cfg, _ := config.LoadDefaultConfig(ctx,
        config.WithRegion(container.GetRegion()),
        config.WithCredentialsProvider(credentials.NewStaticCredentialsProvider(
            container.GetAccessKey(), container.GetSecretKey(), "",
        )),
        config.WithBaseEndpoint(container.GetEndpoint()),
    )

    client := sqs.NewFromConfig(cfg)

    queue, err := client.CreateQueue(ctx, &sqs.CreateQueueInput{
        QueueName: aws.String("orders"),
    })
    if err != nil {
        t.Fatal(err)
    }

    _, err = client.SendMessage(ctx, &sqs.SendMessageInput{
        QueueUrl:    queue.QueueUrl,
        MessageBody: aws.String(`{"event":"order.placed"}`),
    })
    if err != nil {
        t.Fatal(err)
    }

    out, err := client.ReceiveMessage(ctx, &sqs.ReceiveMessageInput{
        QueueUrl:            queue.QueueUrl,
        MaxNumberOfMessages: 1,
    })
    if err != nil {
        t.Fatal(err)
    }

    if len(out.Messages) != 1 {
        t.Fatalf("expected 1 message, got %d", len(out.Messages))
    }
}
```

## DynamoDB example

```go
func TestDynamoDBPutGet(t *testing.T) {
    ctx := context.Background()

    container, err := floci.NewFlociContainer().Start(ctx)
    if err != nil {
        t.Fatal(err)
    }
    defer container.Stop(ctx)

    cfg, _ := config.LoadDefaultConfig(ctx,
        config.WithRegion(container.GetRegion()),
        config.WithCredentialsProvider(credentials.NewStaticCredentialsProvider(
            container.GetAccessKey(), container.GetSecretKey(), "",
        )),
        config.WithBaseEndpoint(container.GetEndpoint()),
    )

    client := dynamodb.NewFromConfig(cfg)

    _, err = client.CreateTable(ctx, &dynamodb.CreateTableInput{
        TableName:   aws.String("Orders"),
        BillingMode: types.BillingModePayPerRequest,
        AttributeDefinitions: []types.AttributeDefinition{
            {AttributeName: aws.String("id"), AttributeType: types.ScalarAttributeTypeS},
        },
        KeySchema: []types.KeySchemaElement{
            {AttributeName: aws.String("id"), KeyType: types.KeyTypeHash},
        },
    })
    if err != nil {
        t.Fatal(err)
    }

    _, err = client.PutItem(ctx, &dynamodb.PutItemInput{
        TableName: aws.String("Orders"),
        Item: map[string]types.AttributeValue{
            "id":     &types.AttributeValueMemberS{Value: "order-1"},
            "status": &types.AttributeValueMemberS{Value: "placed"},
        },
    })
    if err != nil {
        t.Fatal(err)
    }

    result, err := client.GetItem(ctx, &dynamodb.GetItemInput{
        TableName: aws.String("Orders"),
        Key: map[string]types.AttributeValue{
            "id": &types.AttributeValueMemberS{Value: "order-1"},
        },
    })
    if err != nil {
        t.Fatal(err)
    }

    status := result.Item["status"].(*types.AttributeValueMemberS).Value
    if status != "placed" {
        t.Errorf("expected status 'placed', got '%s'", status)
    }
}
```

## Reusing the container across tests

Start the container once with `TestMain` and share it across all tests in the package:

```go
var sharedContainer *floci.StartedFlociContainer

func TestMain(m *testing.M) {
    ctx := context.Background()

    var err error
    sharedContainer, err = floci.NewFlociContainer().Start(ctx)
    if err != nil {
        log.Fatalf("failed to start floci: %v", err)
    }

    code := m.Run()

    sharedContainer.Stop(ctx)
    os.Exit(code)
}

func awsCfg(ctx context.Context) aws.Config {
    cfg, _ := config.LoadDefaultConfig(ctx,
        config.WithRegion(sharedContainer.GetRegion()),
        config.WithCredentialsProvider(credentials.NewStaticCredentialsProvider(
            sharedContainer.GetAccessKey(), sharedContainer.GetSecretKey(), "",
        )),
        config.WithBaseEndpoint(sharedContainer.GetEndpoint()),
    )
    return cfg
}
```

## Options

Options are chained on the builder before calling `Start`:

```go
container, err := floci.NewFlociContainer().
    WithRegion("eu-west-1").
    WithAccountID("123456789012").
    WithImage("floci/floci:1.6.0").
    WithDedicatedNetwork().
    Start(ctx)
```

| Method | Default | Description |
|---|---|---|
| `WithImage(image string)` | `floci/floci:latest` | Docker image to use |
| `WithRegion(region string)` | `us-east-1` | AWS region set in Floci and returned by `GetRegion()` |
| `WithAccountID(id string)` | `000000000000` | Default AWS account ID used in ARNs |
| `WithAvailabilityZone(az string)` | `us-east-1a` | Availability zone reported by Floci |
| `WithDedicatedNetwork()` | _(none)_ | Create a dedicated Docker network for container-backed services (ElastiCache, RDS, OpenSearch, MSK) |

## Service configuration

Each service exposes a typed config struct. Pass it with the matching `With<Service>Config` method:

```go
container, err := floci.NewFlociContainer().
    WithS3Config(floci.S3Config{
        Enabled:                    true,
        DefaultPresignExpirySeconds: 7200,
    }).
    WithSqsConfig(floci.SqsConfig{
        Enabled:                  true,
        DefaultVisibilityTimeout: 60,
        MaxMessageSize:           131072,
    }).
    WithDynamoDbConfig(floci.DynamoDbConfig{
        Enabled: true,
    }).
    Start(ctx)
```

Available config methods: `WithAcmConfig`, `WithApiGatewayConfig`, `WithAppConfigConfig`, `WithAthenaConfig`, `WithBackupConfig`, `WithBedrockRuntimeConfig`, `WithCloudFormationConfig`, `WithCloudWatchLogsConfig`, `WithCloudWatchMetricsConfig`, `WithCodeBuildConfig`, `WithCodeDeployConfig`, `WithCognitoConfig`, `WithDynamoDbConfig`, `WithEc2Config`, `WithEcrConfig`, `WithEcsConfig`, `WithEksConfig`, `WithElastiCacheConfig`, `WithElbConfig`, `WithEventBridgeConfig`, `WithFirehoseConfig`, `WithGlueConfig`, `WithIamConfig`, `WithKinesisConfig`, `WithKmsConfig`, `WithLambdaConfig`, `WithMskConfig`, `WithOpenSearchConfig`, `WithRdsConfig`, `WithRoute53Config`, `WithS3Config`, `WithSchedulerConfig`, `WithSecretsManagerConfig`, `WithSesConfig`, `WithSnsConfig`, `WithSqsConfig`, `WithSsmConfig`, `WithStepFunctionsConfig`, `WithStsConfig`, `WithTextractConfig`, `WithTransferConfig`.

## `StartedFlociContainer` API

| Method | Returns | Description |
|---|---|---|
| `GetEndpoint()` | `string` | Full HTTP endpoint, e.g. `http://localhost:32768` |
| `GetRegion()` | `string` | AWS region configured at start |
| `GetAccountID()` | `string` | AWS account ID configured at start |
| `GetAccessKey()` | `string` | Always `"test"` |
| `GetSecretKey()` | `string` | Always `"test"` |
| `GetAvailabilityZone()` | `string` | Availability zone configured at start |
| `GetDedicatedNetworkName()` | `string` | Docker network name, or empty string if not using a dedicated network |
| `GetMappedPort(ctx, port int)` | `(int, error)` | Host port mapped to the given container port |
| `Stop(ctx)` | `error` | Terminate the container and clean up |

## Source

[github.com/floci-io/testcontainers-floci-go](https://github.com/floci-io/testcontainers-floci-go)
