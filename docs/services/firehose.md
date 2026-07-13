# Data Firehose

**Protocol:** JSON 1.1
**Endpoint:** `http://localhost:4566/`

Floci emulates Amazon Data Firehose for streaming data ingestion and delivery to S3.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateDeliveryStream` | Creates a new delivery stream |
| `UpdateDestination` | - |
| `DescribeDeliveryStream` | Returns metadata about a stream |
| `ListDeliveryStreams` | Lists all delivery streams |
| `DeleteDeliveryStream` | Deletes a delivery stream |
| `PutRecord` | Writes a single data record to the stream |
| `PutRecordBatch` | Writes multiple data records to the stream |
| `TagDeliveryStream` | - |
| `UntagDeliveryStream` | - |
| `ListTagsForDeliveryStream` | - |
<!-- floci:actions:end -->

## How it works

1. **Buffering**: Incoming records are buffered in memory.
2. **Automatic Flush**: Floci automatically flushes the buffer to S3 after every 5 records for immediate local feedback.
3. **Format**: Records are flushed as raw NDJSON (newline-delimited JSON) to the bucket configured in the S3 destination (`floci-firehose-results` if the stream has no destination configuration).

## S3 object keys

Delivered objects are named `<evaluated prefix><streamName>-<versionId>-<yyyy-MM-dd-HH-mm-ss>-<uuid>` (no file extension), matching [AWS's object name format](https://docs.aws.amazon.com/firehose/latest/dev/s3-object-name.html):

- Without a `Prefix`, the default prefix `yyyy/MM/dd/HH/` is used.
- A `Prefix` without expressions gets `yyyy/MM/dd/HH/` appended by literal concatenation — no `/` is inserted, exactly like AWS (`legacy` → `legacy2026/07/13/14/...`).
- [Custom prefix expressions](https://docs.aws.amazon.com/firehose/latest/dev/s3-prefixes.html) are evaluated: `!{timestamp:<pattern>}` (Java `DateTimeFormatter` pattern; all instances share the same instant) and `!{firehose:random-string}` (a fresh 11-character alphanumeric string per instance). When the prefix contains any expression, nothing is appended to it.
- `CustomTimeZone` is honored for all timestamps; absent or invalid time zones fall back to UTC.

Known deviations from AWS: timestamps are evaluated at flush time instead of the oldest buffered record's arrival time; expressions AWS would reject at create time (unknown namespaces, `!{partitionKeyFromQuery:...}`/`!{partitionKeyFromLambda:...}` dynamic partitioning keys, invalid patterns) are kept literally in the key; delivered content is never compressed, so no compression extension is ever added.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_FIREHOSE_ENABLED` | `true` | Enable or disable the service |

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a stream
aws firehose create-delivery-stream --delivery-stream-name my-stream --endpoint-url $AWS_ENDPOINT_URL

# Put a record
aws firehose put-record \
  --delivery-stream-name my-stream \
  --record '{"Data": "{\"id\": 1, \"amount\": 10.5}"}' \
  --endpoint-url $AWS_ENDPOINT_URL
```
