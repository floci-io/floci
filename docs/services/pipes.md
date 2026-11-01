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
- Amazon Redshift clusters and Redshift Serverless workgroups, through the Data API (see below)

## Redshift Target

A pipe whose target is a Redshift cluster (`arn:aws:redshift:...:cluster:<id>`) or a Redshift Serverless
workgroup (`arn:aws:redshift-serverless:...:workgroup/<id>`) submits its SQL to the Redshift Data API as
a `BatchExecuteStatement`, so the statements of one delivery run as a single transaction. The target
parameters follow `PipeTargetRedshiftDataParameters`:

```bash
aws pipes create-pipe   --name sqs-to-redshift   --source "arn:aws:sqs:us-east-1:000000000000:events"   --target "arn:aws:redshift:us-east-1:000000000000:cluster:warehouse"   --role-arn "arn:aws:iam::000000000000:role/pipe-role"   --target-parameters '{"RedshiftDataParameters":{"Database":"dev","DbUser":"admin","Sqls":["$.body.statement"]}}'   --endpoint-url $AWS_ENDPOINT_URL
```

- `Sqls` is required, with 1 to 40 statements of at most 100,000 characters each; `CreatePipe` and
  `UpdatePipe` reject anything else with `ValidationException`. `Database`, `DbUser`,
  `SecretManagerArn`, `StatementName` and `WithEvent` are passed to the Data API.
- A parameter that is wholly a dynamic path, such as `$.body.statement`, is read from the source event as
  it arrived (for SQS, the path starts at the message, so the body is `$.body`). Input transformation does
  not apply to a Redshift target, and a path that resolves to nothing fails the delivery with
  `ValidationException`. A path embedded in a longer string is sent as written.
- The pipe's role must trust `pipes.amazonaws.com` (an `aws:SourceArn` condition naming the pipe is
  honored) and, with `FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED=true`, be allowed
  `redshift-data:BatchExecuteStatement` and the action that authenticates the statement
  (`secretsmanager:GetSecretValue`, `redshift:GetClusterCredentials` or
  `redshift-serverless:GetCredentials`). A refusal fails the delivery and the record follows the pipe's
  dead-letter queue. Floci does not assume the role: the statements run with the role ARN recorded on the
  pipe, and `GetClusterCredentialsWithIAM` is not supported.
- The Data API accepts the statements and the pipe does not wait for them. A statement that fails afterwards
  is visible through `DescribeStatement`, but does not fail the delivery or get retried. A delivery that is
  retried, because the Data API refused it or the source batch was redelivered, submits its statements
  again, so write statements that tolerate being run twice.
- Each source record is a delivery of its own, as for the other non-Lambda targets, so a batch of N SQS
  messages makes N `BatchExecuteStatement` calls. How AWS maps a batch onto the Data API call has not been
  verified, and this is a Floci choice.

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
