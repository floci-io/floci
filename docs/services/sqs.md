# SQS

**Protocol:** Query (XML) and JSON 1.0 (both supported)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

<!-- floci:actions:start -->
| Action |
| --- |
| `CreateQueue` |
| `DeleteQueue` |
| `ListQueues` |
| `GetQueueUrl` |
| `GetQueueAttributes` |
| `SendMessage` |
| `ReceiveMessage` |
| `DeleteMessage` |
| `DeleteMessageBatch` |
| `SendMessageBatch` |
| `ChangeMessageVisibility` |
| `ChangeMessageVisibilityBatch` |
| `SetQueueAttributes` |
| `TagQueue` |
| `UntagQueue` |
| `ListQueueTags` |
| `PurgeQueue` |
| `ListDeadLetterSourceQueues` |
| `StartMessageMoveTask` |
| `ListMessageMoveTasks` |
<!-- floci:actions:end -->

## Local Inspection Endpoint

For test assertions and debugging, Floci exposes a LocalStack-compatible endpoint that lets you peek at queue contents without consuming messages:

| Method | Path | Description |
|---|---|---|
| `GET` | `/_aws/sqs/messages?QueueUrl=<url>` | List all messages in the queue (non-destructive) |
| `DELETE` | `/_aws/sqs/messages?QueueUrl=<url>` | Purge all messages from the queue |

`GET` returns every message currently in the queue — including in-flight messages — without changing visibility timeouts or advancing receive counts. It does not remove messages.

`DELETE` is equivalent to `PurgeQueue` and removes all messages.

### Response shape

```json
{
  "messages": [
    {
      "MessageId": "abc123",
      "MD5OfBody": "...",
      "Body": "{\"event\":\"order.placed\"}",
      "ReceiptHandle": null,
      "Attributes": {
        "SentTimestamp": "1714000000000",
        "ApproximateReceiveCount": "0"
      },
      "MessageAttributes": {}
    }
  ]
}
```

`ReceiptHandle` is `null` for messages that have not yet been received. FIFO messages include `MessageGroupId`, `MessageDeduplicationId`, and `SequenceNumber` in `Attributes` when set.

### Example

```bash
QUEUE_URL="http://localhost:4566/000000000000/orders"

# Peek at messages without consuming them
curl "http://localhost:4566/_aws/sqs/messages?QueueUrl=$QUEUE_URL"

# Purge the queue
curl -X DELETE "http://localhost:4566/_aws/sqs/messages?QueueUrl=$QUEUE_URL"
```

## Configuration

```yaml
floci:
  services:
    sqs:
      enabled: true
      default-visibility-timeout: 30  # Seconds
      max-message-size: 262144        # 256 KB
      clear-fifo-deduplication-cache-on-purge: false  # When true, PurgeQueue clears the FIFO deduplication cache for the queue and for any SNS FIFO topics that subscribe to that queue (SNS in-memory dedup)
```

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a standard queue
aws sqs create-queue --queue-name orders --endpoint-url $AWS_ENDPOINT_URL

# Create a FIFO queue
aws sqs create-queue \
  --queue-name orders.fifo \
  --attributes FifoQueue=true \
  --endpoint-url $AWS_ENDPOINT_URL

# Send a message
QUEUE_URL="$AWS_ENDPOINT_URL/000000000000/orders"
aws sqs send-message \
  --queue-url $QUEUE_URL \
  --message-body '{"event":"order.placed","id":"abc123"}' \
  --endpoint-url $AWS_ENDPOINT_URL

# Receive messages
aws sqs receive-message \
  --queue-url $QUEUE_URL \
  --max-number-of-messages 10 \
  --endpoint-url $AWS_ENDPOINT_URL

# Delete a message (replace RECEIPT_HANDLE with the value from ReceiveMessage)
aws sqs delete-message \
  --queue-url $QUEUE_URL \
  --receipt-handle "RECEIPT_HANDLE" \
  --endpoint-url $AWS_ENDPOINT_URL

# Set up a dead-letter queue
DLQ_ARN=$(aws sqs get-queue-attributes \
  --queue-url $AWS_ENDPOINT_URL/000000000000/orders-dlq \
  --attribute-names QueueArn \
  --query Attributes.QueueArn \
  --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

aws sqs set-queue-attributes \
  --queue-url $QUEUE_URL \
  --attributes "{\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$DLQ_ARN\\\",\\\"maxReceiveCount\\\":3}\"}" \
  --endpoint-url $AWS_ENDPOINT_URL
```

## Queue URL Format

```
http://localhost:4566/000000000000/<queue-name>
```