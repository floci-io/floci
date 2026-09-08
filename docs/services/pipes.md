# EventBridge Pipes

**Protocol:** REST-JSON
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

| Action | Description |
|---|---|
| `CreatePipe` | Create a new pipe with source, target, and optional enrichment |
| `DescribePipe` | Get pipe details including state and configuration |
| `UpdatePipe` | Update pipe configuration (source, target, role, enrichment, desired state) |
| `DeletePipe` | Delete a pipe |
| `ListPipes` | List all pipes with optional filtering by state and prefix |
| `StartPipe` | Start a stopped pipe |
| `StopPipe` | Stop a running pipe |

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_PIPES_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_PIPES_KAFKA_REST_BRIDGE_DEFAULT_IMAGE` | `ghcr.io/aiven-open/karapace:latest` | Docker image for the Karapace REST Proxy sidecar a Kafka-sourced pipe starts on demand |
| `FLOCI_SERVICES_PIPES_KAFKA_REST_BRIDGE_HOST_PORT_BASE` | `9500` | Start of the host port range allocated to Karapace sidecars |
| `FLOCI_SERVICES_PIPES_KAFKA_REST_BRIDGE_HOST_PORT_MAX` | `9599` | End of the host port range allocated to Karapace sidecars |

A pipe with a Kafka source (MSK or self-managed via `smk://`) starts a Karapace REST Proxy sidecar container on first use, one per distinct `bootstrap.servers` target shared across every pipe reading it, so Docker is required for those pipes even though it is not for the others.

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a pipe (SQS to Lambda)
aws pipes create-pipe \
  --name my-pipe \
  --source "arn:aws:sqs:us-east-1:000000000000:source-queue" \
  --target "arn:aws:lambda:us-east-1:000000000000:function:my-function" \
  --role-arn "arn:aws:iam::000000000000:role/pipe-role" \
  --endpoint-url $AWS_ENDPOINT_URL

# Describe a pipe
aws pipes describe-pipe \
  --name my-pipe \
  --endpoint-url $AWS_ENDPOINT_URL

# List all pipes
aws pipes list-pipes \
  --endpoint-url $AWS_ENDPOINT_URL

# Start a pipe
aws pipes start-pipe \
  --name my-pipe \
  --endpoint-url $AWS_ENDPOINT_URL

# Stop a pipe
aws pipes stop-pipe \
  --name my-pipe \
  --endpoint-url $AWS_ENDPOINT_URL

# Update a pipe
aws pipes update-pipe \
  --name my-pipe \
  --target "arn:aws:lambda:us-east-1:000000000000:function:new-function" \
  --endpoint-url $AWS_ENDPOINT_URL

# Delete a pipe
aws pipes delete-pipe \
  --name my-pipe \
  --endpoint-url $AWS_ENDPOINT_URL
```

## Pipe States

- `STARTING` - Pipe is being started
- `RUNNING` - Pipe is actively processing events
- `STOPPING` - Pipe is being stopped
- `STOPPED` - Pipe is stopped and not processing events
- `DELETED` - Pipe has been deleted

## Supported Sources and Targets

Floci emulates EventBridge Pipes with the following supported source and target types:

**Sources:**
- Amazon SQS queues
- Amazon Kinesis streams
- Amazon DynamoDB streams
- Kafka topics (MSK and self-managed via `smk://`)

**Targets:**
- Lambda functions
- SQS queues
- SNS topics
- Kinesis streams
- Step Functions state machines

## ParallelizationFactor

`CreatePipe` and `UpdatePipe` accept a `ParallelizationFactor` integer between 1 and 10 on the
`KinesisStreamParameters` and `DynamoDBStreamParameters` source blocks, matching the AWS wire
format. `DescribePipe` echoes it back as part of `SourceParameters`. `ListPipes` returns pipe
summaries only and omits `SourceParameters` entirely, matching AWS.

```bash
aws pipes create-pipe \
  --name kinesis-pipe \
  --source "arn:aws:kinesis:us-east-1:000000000000:stream/events" \
  --target "arn:aws:lambda:us-east-1:000000000000:function:my-function" \
  --role-arn "arn:aws:iam::000000000000:role/pipe-role" \
  --source-parameters '{"KinesisStreamParameters":{"StartingPosition":"TRIM_HORIZON","ParallelizationFactor":4}}' \
  --endpoint-url $AWS_ENDPOINT_URL
```

Validation mirrors AWS: values outside 1 to 10 are rejected with `ValidationException`, and so is
the field on a source its parameter block does not describe, for example
`KinesisStreamParameters.ParallelizationFactor` on an SQS source.

!!! note "Enforcement status"
    The configured `ParallelizationFactor` is persisted and returned on the wire, but the poller
    does not yet process concurrent batches per shard. Floci opens an iterator on a single shard
    (`shardId-000000000000`) per Kinesis or DynamoDB Streams pipe and delivers one batch at a time
    regardless of the configured value. Multi-shard polling and real per-shard concurrency are
    tracked as follow-ups.

## Enrichment

A pipe's optional enrichment step (`source → filter → enrichment → target`) is emulated for
**Lambda** enrichments: the filtered batch is invoked synchronously (`RequestResponse`) and the
response becomes the target input.

- **Empty responses skip the target**, matching AWS: an empty body, `null`, `{}`, or `[]` consumes
  the source records without invoking the target. A non-empty array such as `[{}]` still invokes the
  target (with an empty-payload element).
- **A Lambda enrichment `FunctionError` fails the batch** — the source records are routed to the
  pipe's dead-letter queue rather than silently consumed.
- **Non-Lambda enrichment types** (API destinations, API Gateway, Step Functions Express) are valid
  on AWS but not emulated; a pipe configured with one fails the batch to the DLQ rather than
  delivering the unenriched payload.
- **Enrichment is currently applied only on the SQS source path.** Kinesis, DynamoDB Streams and
  Kafka sources deliver filtered records straight to the target; an enrichment configured on those
  sources is not yet applied.
