# Kinesis

**Protocol:** JSON 1.1 (`X-Amz-Target: Kinesis_20131202.*`)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateStream` | Create a stream |
| `DeleteStream` | Delete a stream |
| `ListStreams` | List all streams |
| `DescribeStream` | Get stream details and shard info |
| `DescribeStreamSummary` | Lightweight stream description |
| `RegisterStreamConsumer` | Register an enhanced fan-out consumer |
| `DeregisterStreamConsumer` | Remove a consumer |
| `DescribeStreamConsumer` | Get consumer details |
| `ListStreamConsumers` | List consumers for a stream |
| `SubscribeToShard` | Subscribe to a shard for enhanced fan-out |
| `AddTagsToStream` | Tag a stream |
| `RemoveTagsFromStream` | Remove tags |
| `ListTagsForStream` | List tags |
| `StartStreamEncryption` | Enable KMS encryption |
| `StopStreamEncryption` | Disable encryption |
| `SplitShard` | Split a shard into two |
| `MergeShards` | Merge two adjacent shards |
| `PutRecord` | Write a single record |
| `PutRecords` | Write up to 500 records |
| `GetShardIterator` | Get an iterator for reading |
| `GetRecords` | Read records from a shard |
| `ListShards` | - |
| `IncreaseStreamRetentionPeriod` | Increase retention up to 8760 hours (365 days) |
| `DecreaseStreamRetentionPeriod` | Decrease retention down to 24 hours |
| `EnableEnhancedMonitoring` | - |
| `DisableEnhancedMonitoring` | - |
| `UpdateStreamMode` | - |
<!-- floci:actions:end -->

## Stream Addressing

Most actions accept either `StreamName` or `StreamARN` to identify a stream. When both are provided, `StreamName` takes precedence. `CreateStream` only accepts `StreamName`.

```bash
# By name
aws kinesis describe-stream --stream-name events --endpoint-url $AWS_ENDPOINT_URL

# By ARN
aws kinesis describe-stream --stream-arn arn:aws:kinesis:us-east-1:000000000000:stream/events --endpoint-url $AWS_ENDPOINT_URL
```

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_KINESIS_ENABLED` | `true` | Enable or disable the service |

## Examples

## Enhanced Fan-Out (EFO)

`SubscribeToShard` uses a snapshot-and-close model: the server returns one batch of records as a binary EventStream response and closes the connection. The SDK resubscribes automatically using the `ContinuationSequenceNumber` from the last delivered record. All five `StartingPosition` types are supported: `TRIM_HORIZON`, `LATEST`, `AT_SEQUENCE_NUMBER`, `AFTER_SEQUENCE_NUMBER`, `AT_TIMESTAMP`.

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
STREAM=my-stream

# Register a consumer
aws kinesis register-stream-consumer \
  --stream-arn $(aws kinesis describe-stream --stream-name $STREAM \
      --query StreamDescription.StreamARN --output text) \
  --consumer-name my-consumer

# Subscribe (AWS CLI streams events to stdout)
aws kinesis subscribe-to-shard \
  --consumer-arn <consumer-arn> \
  --shard-id shardId-000000000000 \
  --starting-position Type=TRIM_HORIZON
```

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a stream
aws kinesis create-stream \
  --stream-name events \
  --shard-count 2 \
  --endpoint-url $AWS_ENDPOINT_URL

# Put a record
aws kinesis put-record \
  --stream-name events \
  --partition-key "user-123" \
  --data '{"event":"page_view","page":"/home"}' \
  --endpoint-url $AWS_ENDPOINT_URL

# Get a shard iterator
SHARD_ID=$(aws kinesis describe-stream \
  --stream-name events \
  --query 'StreamDescription.Shards[0].ShardId' --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

ITERATOR=$(aws kinesis get-shard-iterator \
  --stream-name events \
  --shard-id $SHARD_ID \
  --shard-iterator-type TRIM_HORIZON \
  --query ShardIterator --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

# Read records
aws kinesis get-records \
  --shard-iterator $ITERATOR \
  --endpoint-url $AWS_ENDPOINT_URL
```