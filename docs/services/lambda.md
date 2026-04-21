# Lambda

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/2015-03-31/functions/...`

Lambda runs your function code inside real Docker containers — the same way real AWS Lambda does.

## Supported Operations

| Operation | Description |
|---|---|
| `CreateFunction` | Deploy a Lambda function |
| `GetFunction` | Get function details and download URL |
| `GetFunctionConfiguration` | Get runtime configuration |
| `ListFunctions` | List all functions |
| `UpdateFunctionCode` | Upload new code |
| `UpdateFunctionConfiguration` | Update runtime, handler, memory, timeout, environment, architectures, tracing, layers, and more |
| `DeleteFunction` | Remove a function |
| `Invoke` | Invoke a function synchronously or asynchronously |
| `CreateEventSourceMapping` | Connect SQS / Kinesis / DynamoDB Streams to a function |
| `GetEventSourceMapping` | Get event source mapping details |
| `ListEventSourceMappings` | List all event source mappings |
| `UpdateEventSourceMapping` | Update a mapping |
| `DeleteEventSourceMapping` | Remove a mapping |
| `PublishVersion` | Publish an immutable version |
| `ListVersionsByFunction` | List all published versions of a function |
| `CreateAlias` | Create a named alias pointing to a version |
| `GetAlias` | Get alias details |
| `ListAliases` | List all aliases for a function |
| `UpdateAlias` | Update an alias |
| `DeleteAlias` | Delete an alias |
| `AddPermission` | Add a resource-policy statement |
| `GetPolicy` | Get the function resource policy |
| `RemovePermission` | Remove a resource-policy statement |
| `GetFunctionCodeSigningConfig` | Return code-signing config (always empty) |
| `CreateFunctionUrlConfig` | Provision a function URL |
| `GetFunctionUrlConfig` | Read function URL config |
| `UpdateFunctionUrlConfig` | Update function URL config |
| `DeleteFunctionUrlConfig` | Delete function URL config |
| `ListTags` | List tags on a function |
| `TagResource` | Tag a function |
| `UntagResource` | Untag a function |
| `PutFunctionConcurrency` | Set reserved concurrent executions |
| `GetFunctionConcurrency` | Get reserved concurrent executions |
| `DeleteFunctionConcurrency` | Clear reserved concurrent executions |

## Hot-Reloading via Reactive S3 Sync

Floci supports an automatic hot-reloading mechanism when functions are deployed via S3. This follows the standard AWS behavior where S3 and Lambda interact, but is optimized for a seamless local development experience.

When a Lambda function is created using an S3 bucket and key, Floci maintains a link between the function and its source object. Any subsequent update to that S3 object (e.g., via `s3:PutObject`) automatically triggers a reactive synchronization:

1.  **Detection**: Floci detects the S3 update via an internal event system.
2.  **Synchronization**: The new code is automatically re-extracted to the local code storage.
3.  **Invalidation**: Any active "warm" containers for that function are proactively drained.
4.  **Reload**: The very next invocation starts a fresh container with the updated code.

This allows you to update your Lambda code by simply re-uploading your ZIP to S3, without having to manually call `UpdateFunctionCode` or restart any containers.

### Example

```bash
# 1. Create a function linked to S3
aws lambda create-function \
  --function-name my-function \
  --code S3Bucket=my-bucket,S3Key=function.zip \
  ...

# 2. Invoke (starts a warm container)
aws lambda invoke --function-name my-function out.json

# 3. Update the code in S3 (Triggers Reactive Sync)
aws s3 cp updated-function.zip s3://my-bucket/function.zip

# 4. Invoke again (automatically picks up the new code)
aws lambda invoke --function-name my-function out.json
```

!!! note "Standard Behavior"
    This mechanism requires no custom configuration or non-standard magic strings. It works with standard AWS SDKs and CLI tools, providing a "live" development feel while staying within the AWS API contract.

!!! note "Concurrency enforcement"
    Reserved concurrency is enforced: invocations beyond the reserved value
    return `TooManyRequestsException` (HTTP 429). Functions without a reserved
    value share a **per-region** pool — AWS Lambda's "account-level" limit is
    in fact a per-account-per-region quota, and Floci mirrors that by
    partitioning counters on the ARN's region segment. The pool size (default
    1000) is configurable via `floci.services.lambda.region-concurrency-limit`
    and applies independently to each region. `PutFunctionConcurrency`
    validates that the requested value leaves at least
    `floci.services.lambda.unreserved-concurrency-min` (default 100) available
    for unreserved functions in that region. `PutProvisionedConcurrencyConfig`
    and related provisioned-concurrency operations remain unimplemented.

    Reducing or clearing a function's reserved value does not kill
    invocations that are already in flight — this matches AWS, which
    applies changes only to new invocations. As a consequence, during the
    drain window `Σreserved-inflight + unreserved-inflight` can briefly
    exceed `region-concurrency-limit`.

Function URLs are also reachable directly on `/{proxy:.*}` under the Lambda URL controller, which routes the request into the normal `Invoke` path.

**Stubbed:** `ListLayers` and `ListLayerVersions` return empty arrays. No layer storage exists.

## Not Implemented

These AWS Lambda operations have no handler in Floci. Calls will return `404` or an error:

- Layers (`PublishLayerVersion`, `DeleteLayerVersion`, `GetLayerVersion`, `GetLayerVersionByArn`, `AddLayerVersionPermission`, `RemoveLayerVersionPermission`, `GetLayerVersionPolicy`)
- Provisioned concurrency (`PutProvisionedConcurrencyConfig`, `GetProvisionedConcurrencyConfig`, `ListProvisionedConcurrencyConfigs`, `DeleteProvisionedConcurrencyConfig`)
- Dead-letter, async invoke config, and event invoke config operations
- `InvokeWithResponseStream`
- Code signing management (only `GetFunctionCodeSigningConfig` is wired; there is no `PutFunctionCodeSigningConfig` or `CreateCodeSigningConfig`)
- Account and regional settings (`GetAccountSettings`)

## Configuration

```yaml
floci:
  services:
    lambda:
      enabled: true
      ephemeral: false                     # Remove container after each invocation
      default-memory-mb: 128
      default-timeout-seconds: 3
      runtime-api-base-port: 9200
      runtime-api-max-port: 9299
      code-path: ./data/lambda-code        # ZIP storage location
      poll-interval-ms: 1000
      container-idle-timeout-seconds: 300  # Idle container cleanup
      region-concurrency-limit: 1000       # Concurrent executions ceiling per region
      unreserved-concurrency-min: 100      # Min unreserved capacity PutFunctionConcurrency must leave
```

### Docker socket requirement

Lambda requires the Docker socket. Mount it in your compose file:

```yaml
services:
  floci:
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
```

### Private registry authentication

Container image functions (`"PackageType": "Image"`) that pull from private registries need Docker credentials. See [Docker Configuration → Private Registry Authentication](../configuration/docker.md#private-registry-authentication) for the full guide.

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Package a simple Node.js function
cat > index.mjs << 'EOF'
export const handler = async (event) => {
  console.log("Event:", JSON.stringify(event));
  return { statusCode: 200, body: JSON.stringify({ hello: "world" }) };
};
EOF
zip function.zip index.mjs

# Deploy the function
aws lambda create-function \
  --function-name my-function \
  --runtime nodejs22.x \
  --role arn:aws:iam::000000000000:role/lambda-role \
  --handler index.handler \
  --zip-file fileb://function.zip \
  --endpoint-url $AWS_ENDPOINT_URL

# Invoke synchronously
aws lambda invoke \
  --function-name my-function \
  --payload '{"key":"value"}' \
  --cli-binary-format raw-in-base64-out \
  response.json \
  --endpoint-url $AWS_ENDPOINT_URL

cat response.json

# Invoke asynchronously
aws lambda invoke \
  --function-name my-function \
  --invocation-type Event \
  --payload '{"key":"value"}' \
  --cli-binary-format raw-in-base64-out \
  /dev/null \
  --endpoint-url $AWS_ENDPOINT_URL

# Update code
zip function.zip index.mjs
aws lambda update-function-code \
  --function-name my-function \
  --zip-file fileb://function.zip \
  --endpoint-url $AWS_ENDPOINT_URL
```

## Event Source Mappings

Connect Lambda to SQS, Kinesis, or DynamoDB Streams:

```bash
# SQS trigger
QUEUE_ARN=$(aws sqs get-queue-attributes \
  --queue-url $AWS_ENDPOINT_URL/000000000000/orders \
  --attribute-names QueueArn \
  --query Attributes.QueueArn --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

aws lambda create-event-source-mapping \
  --function-name my-function \
  --event-source-arn $QUEUE_ARN \
  --batch-size 10 \
  --endpoint-url $AWS_ENDPOINT_URL
```

### ScalingConfig (SQS only)

`CreateEventSourceMapping` and `UpdateEventSourceMapping` accept a
`ScalingConfig.MaximumConcurrency` integer between 2 and 1000 on SQS
event sources, matching the AWS wire format. `GetEventSourceMapping` and
`ListEventSourceMappings` echo the value back when set; responses omit
the `ScalingConfig` field entirely when no cap is configured.

```bash
aws lambda create-event-source-mapping \
  --function-name my-function \
  --event-source-arn $QUEUE_ARN \
  --scaling-config MaximumConcurrency=5 \
  --endpoint-url $AWS_ENDPOINT_URL
```

Validation mirrors AWS: values outside 2–1000 are rejected with
`InvalidParameterValueException`, and `ScalingConfig` on a non-SQS event
source (Kinesis / DynamoDB Streams) is also rejected — those services
use `ParallelizationFactor` instead, which is a separate field.

!!! note "Enforcement status"
    The configured `MaximumConcurrency` is persisted and returned on the
    wire, but the SQS poller does not yet cap concurrent invocations at
    this value (the poller today serializes invocations per ESM to one
    at a time regardless). Real parallel dispatch capped by
    `MaximumConcurrency` is tracked as a follow-up.

## Supported Runtimes

Any runtime that has an official AWS Lambda container image works with Floci (e.g. `nodejs22.x`, `python3.13`, `java21`, `go1.x`, `provided.al2023`).