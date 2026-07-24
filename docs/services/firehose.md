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

1. **Buffering**: Incoming records are buffered in memory per stream.
2. **Delivery**: The buffer is flushed to the destination bucket following the stream's
   `BufferingHints`, matching AWS semantics — as soon as `SizeInMBs` worth of data is
   buffered, or once `IntervalInSeconds` has elapsed since the oldest buffered record,
   whichever happens first. Defaults match AWS: 5 MiB / 300 seconds. Set
   `"BufferingHints": {"SizeInMBs": 1, "IntervalInSeconds": 1}` on the destination
   configuration for near-immediate local feedback. Hints are validated against the AWS
   ranges (`SizeInMBs` 1–128, `IntervalInSeconds` 0–900).
3. **Format**: Records are flushed as NDJSON (newline-delimited) to the bucket from the
   destination's `BucketARN`, or to the `floci-firehose-results` fallback bucket when the
   stream was created without an S3 destination.
4. **Durability**: Buffers live in memory. A failed delivery is retried on the next
   interval (up to 10 attempts, then the batch is dropped with an error log), and records
   not yet delivered are lost when the emulator stops or the stream is deleted.

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
