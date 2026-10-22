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
- SQS queues. `TargetParameters.SqsQueueParameters.MessageGroupId` and `MessageDeduplicationId` are
  sent with each message, so FIFO queue targets work; either can be a JSON path into the event, such
  as `$.body.customerId`. No per-message delay is set, so the queue's `DelaySeconds` applies, also to
  records sent to the pipe's dead-letter queue.
- SNS topics
- EventBridge event buses
- Kinesis streams. `TargetParameters.KinesisStreamParameters.PartitionKey` is required, as on AWS, and
  can be a JSON path into the event, such as `$.body.orderId` for an SQS source. A partition key that
  is missing or resolves to nothing fails the delivery.
- Step Functions state machines

Other target types AWS supports (API destinations, API Gateway, Batch job queues, CloudWatch log
groups, ECS tasks, Firehose streams, Redshift, SageMaker pipelines, Timestream) are accepted by
`CreatePipe`, as on AWS, but are not emulated. Delivering to one fails, so the batch goes to the
pipe's dead-letter queue, or stays in the source to be retried, instead of being consumed.

## Delivery to Targets

- **Lambda functions and Step Functions state machines** receive the whole batch in one request (one
  invocation, or one execution) as a bare JSON array of source records, even when the batch holds a
  single record, as AWS does. A Lambda handler on an SQS source reads `event[0].body`, not
  `event.Records[0].body`.
- **Every other target** (SQS queues, SNS topics, EventBridge event buses, Kinesis streams) receives
  one request per record.
- **`InputTemplate`**, on `TargetParameters` and on `EnrichmentParameters`, is applied to each record
  of a batch, not to the array as a whole. A template output that is not JSON becomes a string
  element of the array.
- **Partial batch failures**: a Lambda target can return
  `{"batchItemFailures":[{"itemIdentifier":"<id>"}]}`, where the id is the record's `messageId` (SQS)
  or `eventID` (Kinesis, DynamoDB Streams). For an SQS source only the reported messages stay in the
  queue for redelivery. For a stream source the pipe resumes at the first reported record, so it and
  the records after it are delivered again. A `batchItemFailures` that is not an array, or an entry
  whose `itemIdentifier` is empty, null, missing or not in the batch, fails the whole batch. A Step
  Functions target is started asynchronously, so it cannot report partial failures, and partial
  failures are not read after an enrichment or for Kafka sources.

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

## SQS Source

- `SqsQueueParameters.BatchSize` (default 10) bounds the messages delivered per poll. The poller
  reads them in `ReceiveMessage` calls of at most 10 messages each, until the batch is full or the
  queue has no more visible messages.

## Kinesis Streams Source

- Records are delivered in the AWS Pipes Kinesis record shape: a flat record with
  `kinesisSchemaVersion`, `partitionKey`, `sequenceNumber`, `data` (base64 encoded),
  `approximateArrivalTimestamp`, `eventSource` (`aws:kinesis`), `eventVersion`, `eventID`
  (`<shardId>:<sequenceNumber>`), `eventName` (`aws:kinesis:record`), `invokeIdentityArn` (the pipe's
  role), `awsRegion` and `eventSourceARN`.
- Filter patterns match `data` decoded, and parsed as JSON when it is JSON, so a pattern such as
  `{"partitionKey":["1"],"data":{"City":["Seattle"]}}` works as on AWS. The delivered record keeps
  `data` base64 encoded.
- `StartingPosition` `TRIM_HORIZON`, `LATEST` and `AT_TIMESTAMP` (with `StartingPositionTimestamp`,
  in Unix seconds) are honored. The position is resolved once, on the pipe's first poll.
  `CreatePipe` and `UpdatePipe` reject `AT_TIMESTAMP` without a `StartingPositionTimestamp`, or with
  one that is not a timestamp, with `ValidationException`.
- The pipe moves past a batch only once it is filtered out, delivered, or sent to the
  `DeadLetterConfig` queue, as AWS checkpoints only a fully successful batch. A failed batch is read
  again on the next poll, and later records wait behind it. With a per-record target, the batch stops
  at the first record that is neither delivered nor sent to the DLQ, and the next poll resumes at that
  record, so the records before it are not delivered twice.
- Progress survives `StopPipe` and `StartPipe`: a restarted pipe resumes after the last batch it
  disposed of. The progress is kept in memory, so it is lost when Floci restarts.
- `MaximumRetryAttempts`, `MaximumRecordAgeInSeconds` and `OnPartialBatchItemFailure` are accepted
  and returned but not enforced, so they do not bound or split those retries.

## DynamoDB Streams Source

- `StartingPosition` `TRIM_HORIZON` and `LATEST` are honored. `LATEST` is pinned on the pipe's
  first poll, so only records written after that poll are delivered.
- Progress survives `StopPipe` and `StartPipe`: a restarted pipe resumes after the last record it
  delivered or sent to the DLQ. The progress is kept in memory, so it is lost when Floci restarts.
- Records are delivered in the AWS DynamoDB Streams record shape (`eventName`, `dynamodb.Keys`,
  `dynamodb.NewImage` and so on) with `eventSourceARN` set to the stream ARN, so filter patterns on
  `dynamodb.*` fields match as on AWS.
- A failed batch is retried until it is delivered or sent to the `DeadLetterConfig` queue. With a
  per-record target, the batch stops at the first record that is neither delivered nor sent to the
  DLQ, and the next poll resumes at that record, so the records before it are not delivered twice.
- `MaximumRetryAttempts`, `MaximumRecordAgeInSeconds` and `OnPartialBatchItemFailure` are accepted
  and returned but not enforced, so they do not bound or split those retries.

## Enrichment

A pipe's optional enrichment step (`source → filter → enrichment → target`) is emulated for
**Lambda** enrichments: the filtered batch is invoked synchronously (`RequestResponse`) and the
response becomes the target input.

- **Empty responses skip the target**, matching AWS: an empty body, `null`, `{}`, or `[]` consumes
  the source records without invoking the target. A non-empty array such as `[{}]` still invokes the
  target (with an empty-payload element).
- **A Lambda or Step Functions target receives the enrichment response as a batch array**: a single
  JSON object response is wrapped in a one-element array, an array response is forwarded as is.
  Other targets receive the response unchanged.
- **A Lambda enrichment `FunctionError` fails the batch**: the source records are routed to the
  pipe's dead-letter queue rather than silently consumed.
- **Non-Lambda enrichment types** (API destinations, API Gateway, Step Functions Express) are valid
  on AWS but not emulated; a pipe configured with one fails the batch to the DLQ rather than
  delivering the unenriched payload.
- **Enrichment is currently applied only on the SQS source path.** Kinesis, DynamoDB Streams and
  Kafka sources deliver filtered records straight to the target; an enrichment configured on those
  sources is not yet applied.
