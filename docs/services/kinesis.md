# Kinesis

**Protocol:** JSON 1.1 (`X-Amz-Target: Kinesis_20131202.*`)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

| Action | Description |
|---|---|
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
| `PutRecord` | Write a single record |
| `PutRecords` | Write up to 500 records |
| `GetShardIterator` | Get an iterator for reading |
| `GetRecords` | Read records from a shard |
| `SplitShard` | Split a shard into two |
| `MergeShards` | Merge two adjacent shards |
| `AddTagsToStream` | Tag a stream |
| `RemoveTagsFromStream` | Remove tags |
| `ListTagsForStream` | List tags |
| `IncreaseStreamRetentionPeriod` | Increase retention up to 8760 hours (365 days) |
| `DecreaseStreamRetentionPeriod` | Decrease retention down to 24 hours |
| `StartStreamEncryption` | Enable KMS encryption |
| `StopStreamEncryption` | Disable encryption |

## Examples

```bash
export AWS_ENDPOINT=http://localhost:4566

# Create a stream
aws kinesis create-stream \
  --stream-name events \
  --shard-count 2 \
  --endpoint-url $AWS_ENDPOINT

# Put a record
aws kinesis put-record \
  --stream-name events \
  --partition-key "user-123" \
  --data '{"event":"page_view","page":"/home"}' \
  --endpoint-url $AWS_ENDPOINT

# Get a shard iterator
SHARD_ID=$(aws kinesis describe-stream \
  --stream-name events \
  --query 'StreamDescription.Shards[0].ShardId' --output text \
  --endpoint-url $AWS_ENDPOINT)

ITERATOR=$(aws kinesis get-shard-iterator \
  --stream-name events \
  --shard-id $SHARD_ID \
  --shard-iterator-type TRIM_HORIZON \
  --query ShardIterator --output text \
  --endpoint-url $AWS_ENDPOINT)

# Read records
aws kinesis get-records \
  --shard-iterator $ITERATOR \
  --endpoint-url $AWS_ENDPOINT
```