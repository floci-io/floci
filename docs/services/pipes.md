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
- EventBridge event buses
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
    (`shardId-000000000000`) per Kinesis pipe, and reads the shards of a DynamoDB stream one batch
    at a time, regardless of the configured value. Multi-shard Kinesis polling and real per-shard
    concurrency are tracked as follow-ups.

## DynamoDB Streams Source

- `StartingPosition` `TRIM_HORIZON` and `LATEST` are honored. `LATEST` is pinned on the pipe's
  first poll, so only records written after that poll are delivered.
- Progress survives `StopPipe` and `StartPipe`: a restarted pipe resumes after the last record it
  delivered or sent to the DLQ. The progress is kept in memory, so it is lost when Floci restarts.
- Records are delivered in the AWS DynamoDB Streams record shape (`eventName`, `dynamodb.Keys`,
  `dynamodb.NewImage` and so on) with `eventSourceARN` set to the stream ARN, so filter patterns on
  `dynamodb.*` fields match as on AWS.
- A failed batch is retried until it is delivered or sent to the `DeadLetterConfig` queue. When
  some records of a batch reach a non-Lambda target and others fail, the whole batch is retried.
- `MaximumRetryAttempts`, `MaximumRecordAgeInSeconds` and `OnPartialBatchItemFailure` are accepted
  and returned but not enforced, so they do not bound or split those retries.

## Enrichment

A pipe's optional enrichment step (`source → filter → enrichment → target`) is emulated for
**Lambda** enrichments on **SQS** and **DynamoDB Streams** sources: the filtered batch is invoked
synchronously (`RequestResponse`), once per batch, as a bare JSON array, and the response becomes the
target input.

- **Empty responses skip the target**, matching AWS: an empty body, `null`, `{}`, or `[]` consumes
  the source records without invoking the target. A non-empty array such as `[{}]` still invokes the
  target (with an empty-payload element).
- **An array response is delivered by target.** An event bus, SQS queue or SNS topic takes one event
  per element, with the target's `InputTemplate` rendered on each element apart. A Lambda function or
  a Step Functions state machine takes the whole response in one request. A failure on any element
  fails the batch, and a retried batch runs through the enrichment again, so elements already
  delivered are delivered again.
- **A Lambda enrichment `FunctionError` fails the batch** — the source records are routed to the
  pipe's dead-letter queue rather than silently consumed. Where the pipe has no dead-letter queue or
  the queue refuses them, an SQS message becomes visible again and a DynamoDB stream batch is read
  again on the next poll.
- **A batch in flight when Floci stops is not finished.** A stop interrupts a running enrichment,
  which counts as a function error, so the batch goes to the dead-letter queue where the queue still
  takes it; elements already delivered stay delivered. Nothing else of the batch is kept: a DynamoDB
  stream's progress is held in memory, so after a restart under persistent storage a `TRIM_HORIZON`
  pipe reads the stream's retained records again, enrichment included, and a `LATEST` pipe pins past
  them, so records it had not delivered are not delivered. An SQS message not yet deleted comes back
  once its visibility timeout ends. Under `memory` storage the sources go with the process.
- **Non-Lambda enrichment types** (API destinations, API Gateway, Step Functions Express) are valid
  on AWS but not emulated; a pipe configured with one fails the batch to the DLQ rather than
  delivering the unenriched payload.
- **Kinesis and Kafka sources do not apply an enrichment.** They deliver filtered records straight to
  the target; an enrichment configured on those sources is not yet applied.

## Target Parameters

- An event bus target's `EventBridgeEventBusParameters` `Source` and `DetailType`, written as a JSON
  path (`$.Source`), are read off the event the target receives, the enrichment's element where the
  pipe has an enrichment, before the `InputTemplate` renders. A value that does not start `$.` stays
  as written. A path that names nothing fails the delivery, which is retried or sent to the
  dead-letter queue like any other failure.
- An `InputTemplate` that is exactly one placeholder, such as `<$.Detail>`, renders a string as its
  own text with no quotes added, so a serialized object reaches an event bus as that object. A
  placeholder inside a larger template renders a string with quotes where the template gives none:
  `{"id": <$.messageId>}` renders as `{"id": "m1"}`.
