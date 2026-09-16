# Testcontainers for .NET

The `Testcontainers.Floci` package integrates Floci with [Testcontainers for .NET](https://dotnet.testcontainers.org/). It starts a real Floci container before your tests and disposes it after, and gives you a typed, per-service configuration API over Floci's environment-variable surface.

Targets `net8.0`, `net9.0`, `net10.0`, `netstandard2.0` and `netstandard2.1`.

## Installation

```bash
dotnet add package Testcontainers.Floci
```

!!! note "Published to GitHub Packages, not nuget.org"

    The package is served from the [floci-io GitHub Packages feed](https://github.com/floci-io/testcontainers-floci-dotnet/pkgs/nuget/Testcontainers.Floci). A package with the same id, `Testcontainers.Floci`, also exists on nuget.org, published by the Testcontainers project. Without package source mapping, NuGet may resolve that one instead of this module, so the mapping block below is required, not optional. Add a `nuget.config` next to your solution:

    ```xml
    <?xml version="1.0" encoding="utf-8"?>
    <configuration>
      <packageSources>
        <add key="nuget.org" value="https://api.nuget.org/v3/index.json" />
        <add key="github-floci-io" value="https://nuget.pkg.github.com/floci-io/index.json" />
      </packageSources>
      <packageSourceCredentials>
        <github-floci-io>
          <add key="Username" value="%GITHUB_ACTOR%" />
          <add key="ClearTextPassword" value="%GITHUB_PACKAGES_PAT%" />
        </github-floci-io>
      </packageSourceCredentials>
      <packageSourceMapping>
        <!-- Route only this package to the floci-io feed; everything else stays on nuget.org. -->
        <packageSource key="github-floci-io">
          <package pattern="Testcontainers.Floci" />
        </packageSource>
        <packageSource key="nuget.org">
          <package pattern="*" />
        </packageSource>
      </packageSourceMapping>
    </configuration>
    ```

    GitHub Packages requires authentication even for public packages. `GITHUB_PACKAGES_PAT` is a personal access token with the `read:packages` scope, and `GITHUB_ACTOR` is your GitHub username. The `dotnet nuget add source` command can register the feed and credentials for you, but it does not create the `packageSourceMapping` entry, so the config file above is the canonical setup.

## Basic usage with xUnit

The container implements the Testcontainers builder pattern. Use `IAsyncLifetime` so it starts once per test class:

```csharp
using Amazon.S3;
using Amazon.S3.Model;
using Testcontainers.Floci;
using Xunit;

public sealed class S3Tests : IAsyncLifetime
{
    private readonly FlociContainer _floci = new FlociBuilder("floci/floci:latest").Build();

    public Task InitializeAsync() => _floci.StartAsync();

    public Task DisposeAsync() => _floci.DisposeAsync().AsTask();

    [Fact]
    public async Task CreatesAndListsBucket()
    {
        using var s3 = new AmazonS3Client(
            _floci.AccessKey,
            _floci.SecretKey,
            new AmazonS3Config
            {
                ServiceURL = _floci.GetEndpoint(),
                AuthenticationRegion = _floci.Region,
                ForcePathStyle = true,
            });

        await s3.PutBucketAsync(new PutBucketRequest { BucketName = "my-bucket" });

        var buckets = await s3.ListBucketsAsync();
        Assert.Contains(buckets.Buckets, b => b.BucketName == "my-bucket");
    }
}
```

`GetEndpoint()` returns `http://localhost:<mapped-port>/`. `Region`, `AccountId`, `AvailabilityZone`, `AccessKey` and `SecretKey` expose the container's identity defaults.

## SQS example

```csharp
using Amazon.SQS;
using Amazon.SQS.Model;
using Testcontainers.Floci;
using Xunit;

public sealed class SqsTests : IAsyncLifetime
{
    private readonly FlociContainer _floci = new FlociBuilder("floci/floci:latest").Build();

    public Task InitializeAsync() => _floci.StartAsync();

    public Task DisposeAsync() => _floci.DisposeAsync().AsTask();

    private AmazonSQSClient CreateClient() => new(
        _floci.AccessKey,
        _floci.SecretKey,
        new AmazonSQSConfig
        {
            ServiceURL = _floci.GetEndpoint(),
            AuthenticationRegion = _floci.Region,
        });

    [Fact]
    public async Task SendsAndReceivesMessage()
    {
        using var sqs = CreateClient();

        var queueUrl = (await sqs.CreateQueueAsync(
            new CreateQueueRequest { QueueName = "orders" })).QueueUrl;

        await sqs.SendMessageAsync(new SendMessageRequest
        {
            QueueUrl = queueUrl,
            MessageBody = """{"event":"order.placed"}""",
        });

        var received = await sqs.ReceiveMessageAsync(new ReceiveMessageRequest
        {
            QueueUrl = queueUrl,
            MaxNumberOfMessages = 1,
        });

        Assert.Single(received.Messages);
    }
}
```

## DynamoDB example

```csharp
using Amazon.DynamoDBv2;
using Amazon.DynamoDBv2.Model;
using Testcontainers.Floci;
using Xunit;

public sealed class DynamoDbTests : IAsyncLifetime
{
    private readonly FlociContainer _floci = new FlociBuilder("floci/floci:latest").Build();

    public Task InitializeAsync() => _floci.StartAsync();

    public Task DisposeAsync() => _floci.DisposeAsync().AsTask();

    [Fact]
    public async Task PutsAndGetsItem()
    {
        using var dynamo = new AmazonDynamoDBClient(
            _floci.AccessKey,
            _floci.SecretKey,
            new AmazonDynamoDBConfig
            {
                ServiceURL = _floci.GetEndpoint(),
                AuthenticationRegion = _floci.Region,
            });

        await dynamo.CreateTableAsync(new CreateTableRequest
        {
            TableName = "Orders",
            AttributeDefinitions = [new AttributeDefinition("id", ScalarAttributeType.S)],
            KeySchema = [new KeySchemaElement("id", KeyType.HASH)],
            BillingMode = BillingMode.PAY_PER_REQUEST,
        });

        await dynamo.PutItemAsync(new PutItemRequest
        {
            TableName = "Orders",
            Item = new()
            {
                ["id"] = new AttributeValue { S = "order-1" },
                ["status"] = new AttributeValue { S = "placed" },
            },
        });

        var response = await dynamo.GetItemAsync(new GetItemRequest
        {
            TableName = "Orders",
            Key = new() { ["id"] = new AttributeValue { S = "order-1" } },
        });

        Assert.Equal("placed", response.Item["status"].S);
    }
}
```

## Configuring services

Each service has a `record` config with `init` properties. Pass it to the matching `With…` method on the builder; only the settings you set differ from Floci's defaults.

```csharp
var floci = new FlociBuilder("floci/floci:latest")
    .WithRegion("eu-west-2")
    .WithAccountId("123456789012")
    .WithSqs(new SqsConfig { VisibilityTimeout = 60, MaxMessageSize = 131072 })
    .WithS3(new S3Config { Enabled = true })
    .Build();
```

Configs map onto Floci's `FLOCI_SERVICES_<SERVICE>_<SETTING>` environment variables. `ENABLED` is always sent; the rest only when the service is enabled.

## Container-based services

RDS, Lambda, ElastiCache, ECS, EC2 and ECR make Floci spawn real sibling containers through the Docker daemon. Their configs opt into mounting the Docker socket and into publishing ports 1:1, because Floci returns `endpoint=localhost:<port>` literally.

```csharp
var floci = new FlociBuilder("floci/floci:latest")
    .WithRds(new RdsConfig { Enabled = true, ProxyBasePort = 7010 })
    .Build();
```

A few things to know:

- Sibling containers are managed by Floci, not by Ryuk. They are named after the resource, so delete the resource in teardown (`DeleteDBInstanceAsync`, `DeleteFunctionAsync`, `DeleteReplicationGroupAsync`) or the container is left behind.
- **On macOS, avoid the default RDS `ProxyBasePort` of 7000**: AirPlay Receiver listens on `*:7000`. Use 7010, as above.
- Connect to `127.0.0.1` rather than `localhost` so the client does not resolve to IPv6, and disable SSL (`SSL Mode=Disable`): Floci's RDS proxy does not negotiate TLS.
- The first Lambda invocation pulls the AWS runtime image and cold-starts (roughly 8–10s). Give the client a generous timeout.

## Running the tests

Integration tests need a running Docker daemon.

```bash
dotnet build          # must stay warning-free
dotnet test           # requires Docker
```

Under [Colima](https://github.com/abiosoft/colima) on macOS, the host socket path cannot be bind-mounted into a container, which breaks Ryuk. Point Testcontainers at the in-VM path instead, with no code change required:

```bash
export DOCKER_HOST="unix:///Users/<you>/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="/var/run/docker.sock"
```

Container-backed tests can flake when many Floci containers start at once, so the suite disables xUnit's test parallelization.

## Source and changelog

[github.com/floci-io/testcontainers-floci-dotnet](https://github.com/floci-io/testcontainers-floci-dotnet)
