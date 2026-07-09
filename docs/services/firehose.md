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
3. **Format**: Records are flushed as raw NDJSON (newline-delimited JSON) to the `floci-firehose-results` bucket.

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
