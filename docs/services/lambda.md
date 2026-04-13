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
| `PutFunctionConcurrency` | Set reserved concurrent executions (enforced at invocation) |
| `GetFunctionConcurrency` | Get reserved concurrent executions |
| `DeleteFunctionConcurrency` | Clear reserved concurrent executions |

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
- `UpdateFunctionConfiguration` (use `UpdateFunctionCode` for code-only updates; configuration-only updates are not separately supported)
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
      docker-host: unix:///var/run/docker.sock
      runtime-api-base-port: 9200
      runtime-api-max-port: 9299
      code-path: ./data/lambda-code        # ZIP storage location
      poll-interval-ms: 1000
      container-idle-timeout-seconds: 300  # Idle container cleanup
```

### Docker socket requirement

Lambda requires the Docker socket. Mount it in your compose file:

```yaml
services:
  floci:
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
```

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

## Supported Runtimes

Any runtime that has an official AWS Lambda container image works with Floci (e.g. `nodejs22.x`, `python3.13`, `java21`, `go1.x`, `provided.al2023`).