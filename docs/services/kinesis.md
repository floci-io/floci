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
| `UpdateMaxRecordSize` | - |
| `UpdateShardCount` | - |
<!-- floci:actions:end -->

## Stream Listing

`ListStreams` returns sorted `StreamNames`, with at most 100 names per page by default.
`Limit` accepts integers from 1 to 10000, but values above 100 still return at most 100 names.
When `HasMoreStreams` is true, pass the last returned name as `ExclusiveStartStreamName`
to continue. Truncated pages also return `NextToken`, which CLI and SDK paginators can send
back without an `ExclusiveStartStreamName`. Tokens are scoped to this operation and region,
use the same configurable lifetime as `ListShards` tokens (see the TTL setting below),
and remain usable if the cursor stream is deleted.
`StreamSummaries` are not currently modeled.

## Local Inspection Endpoints

These endpoints are Floci-local read-only helpers for the UI and tests. AWS SDK
traffic should continue to use the JSON 1.1 Kinesis API on `/`.

| Endpoint | Description |
|---|---|
| `GET /_aws/kinesis/streams` | List streams in the resolved region with shard, mode, tag, and record counts |
| `GET /_aws/kinesis/records?StreamName=<name>` | Peek up to 100 stream records with shard attribution without consuming them |
| `GET /_aws/kinesis/records?StreamName=<name>&Limit=<n>` | Peek up to `n` records, capped at 1000 |
| `GET /_aws/kinesis/records?StreamName=<name>&ShardId=<id>` | Peek records for one shard |

## Stream Addressing

Most actions accept either `StreamName` or `StreamARN` to identify a stream. When both are provided, `StreamName` takes precedence. `CreateStream` only accepts `StreamName`.

```bash
# By name
aws kinesis describe-stream --stream-name events --endpoint-url $AWS_ENDPOINT_URL

# By ARN
aws kinesis describe-stream --stream-arn arn:aws:kinesis:us-east-1:000000000000:stream/events --endpoint-url $AWS_ENDPOINT_URL
```

## Shard Iterators

`GetShardIterator` supports all five iterator types: `TRIM_HORIZON`, `LATEST`, `AT_SEQUENCE_NUMBER`, `AFTER_SEQUENCE_NUMBER`, `AT_TIMESTAMP`.

A `LATEST` iterator is positioned at the shard tip at the moment the iterator is created, matching AWS: records written after the iterator was obtained are returned, records written before are not. This supports the standard tailing pattern: obtain a `LATEST` iterator, trigger the action that produces the record, then poll `GetRecords` following `NextShardIterator`.

A closed shard returns a JSON null `NextShardIterator` on its final page, including
when that page contains records. Earlier pages keep their continuation iterator.
Only the terminal page includes `ChildShards`: each direct child's ID, all of its
parent IDs and its hash-key range. A caught-up open shard keeps a non-null iterator.
Consumers should process the final records before stopping at the null iterator.

The internal Pipes and Firehose readers retain a sequence cursor after the final
record to avoid replaying a terminal batch. Firehose commits that cursor only after
delivery succeeds; its unflushed buffer remains in memory. Pipes keeps its existing
advance-before-delivery policy and first-shard polling limitation; child scheduling
and full lineage orchestration are not implemented.

## Record Retention

A stream's retention period (default 24 hours, `IncreaseStreamRetentionPeriod`/`DecreaseStreamRetentionPeriod` up to 8760 hours) is enforced: a record older than the current retention period is not returned by `GetRecords` or the `GET /_aws/kinesis/records` inspection endpoint, and is actually removed from memory rather than merely hidden. Expiry is checked lazily on each stream's next `PutRecord`/`PutRecords` or read after a record ages out; there is no scheduled sweep. After `DecreaseStreamRetentionPeriod`, records outside the new window are inaccessible from the next put or read, in line with AWS making them inaccessible almost immediately. Shard iterators (including `NextShardIterator` continuation tokens and `LATEST`) resolve by sequence number, so they keep working correctly across pruning even if the exact record they were positioned at has since expired.

## Record Routing

`PutRecord` and `PutRecords` honor `ExplicitHashKey` when it is provided. The
value must be a decimal integer in the Kinesis hash-key space, and records are
written to the open shard whose `HashKeyRange` contains that value.

Without `ExplicitHashKey`, Floci uses the partition key's Java hash modulo the
number of stored shards, including closed shards. If this selects a closed
shard, it follows split and merge lineage through any closed intermediate shards
to the first open descendant in breadth-first, stored-shard order. Writes never
append to a closed shard or fall back to an unrelated open shard. A lineage with
no reachable open shard fails the write. This simplified ordinary-key placement
differs from AWS's MD5 hash-range routing.

## Shard Scaling (UpdateShardCount)

`UpdateShardCount` enforces the documented default limits: at most double the current
open shard count per call, at most ten calls per rolling 24-hour period per stream, a
10000-shard ceiling, and the asymmetric rule that a stream already above 10000 shards can
only move to a target strictly below it.

The minimum bound (`TargetShardCount` cannot go below half the current open shard count)
rounds up for an odd current count, so `current=5` accepts `target=3` but rejects
`target=2`. AWS's documentation says only "below half" with no stated rounding, so which
direction an odd count rounds is Floci's own call rather than observed AWS behavior; this
is the more conservative reading (fewer shards allowed, not more).

AWS also documents a separate 10 TPS call-rate limit on this action ("Make over 10 TPS.
TPS over 10 will trigger the LimitExceededException"), independent of the ten-calls-per-24h
limit above. Floci does not simulate real-time request throttling for this or any other
action, so that limit is intentionally unenforced here.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_KINESIS_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_KINESIS_LIST_SHARDS_NEXT_TOKEN_TTL_MILLIS` | `300000` | Lifetime of `ListShards` and `ListStreams` `NextToken` values in milliseconds. Lower it to exercise `ExpiredNextTokenException` without waiting |

## Enhanced Fan-Out (EFO)

`SubscribeToShard` uses a snapshot-and-close model: the server returns one batch
of records as a binary EventStream response and closes the connection. For a
nonterminal batch, use `ContinuationSequenceNumber` from the last delivered record
when resubscribing. At a closed shard's end, the event includes the same direct
`ChildShards` metadata and omits `ContinuationSequenceNumber`, even when it includes
final records. This remains a snapshot response rather than a long-lived
subscription. All five `StartingPosition` types are supported: `TRIM_HORIZON`,
`LATEST`, `AT_SEQUENCE_NUMBER`, `AFTER_SEQUENCE_NUMBER`, `AT_TIMESTAMP`.

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
