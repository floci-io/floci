# SQS

**Protocol:** Query (XML) and JSON 1.0 (both supported)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateQueue` | Create a standard or FIFO queue |
| `DeleteQueue` | Delete a queue |
| `ListQueues` | List all queues |
| `GetQueueUrl` | Look up a queue URL by name |
| `GetQueueAttributes` | Get queue configuration attributes |
| `SendMessage` | Send a message to a queue |
| `ReceiveMessage` | Poll for messages |
| `DeleteMessage` | Acknowledge and delete a message |
| `DeleteMessageBatch` | Delete multiple messages at once |
| `SendMessageBatch` | Send up to 10 messages in one call |
| `ChangeMessageVisibility` | Extend or reset a message's visibility timeout |
| `ChangeMessageVisibilityBatch` | Change visibility for multiple messages |
| `SetQueueAttributes` | Update queue configuration |
| `TagQueue` | Add tags to a queue |
| `UntagQueue` | Remove tags from a queue |
| `ListQueueTags` | List tags on a queue |
| `PurgeQueue` | Delete all messages in a queue |
| `ListDeadLetterSourceQueues` | Find queues that use this queue as DLQ |
| `StartMessageMoveTask` | Start a DLQ redrive task |
| `ListMessageMoveTasks` | List DLQ redrive tasks |
| `CancelMessageMoveTask` | - |
| `AddPermission` | - |
| `RemovePermission` | - |
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

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_SQS_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_SQS_DEFAULT_VISIBILITY_TIMEOUT` | `30` | Default message visibility timeout (seconds) |
| `FLOCI_SERVICES_SQS_MAX_MESSAGE_SIZE` | `1048576` | Maximum message size in bytes (1 MB) |
| `FLOCI_SERVICES_SQS_CLEAR_FIFO_DEDUPLICATION_CACHE_ON_PURGE` | `false` | When `true`, `PurgeQueue` also clears the FIFO deduplication cache for the queue and any SNS FIFO topics subscribed to it |

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