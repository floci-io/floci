# SQS

**Protocol:** Query (XML) and JSON 1.0 (both supported)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

| Action | Description |
|---|---|
| `CreateQueue` | Create a standard or FIFO queue |
| `DeleteQueue` | Delete a queue |
| `ListQueues` | List all queues |
| `GetQueueUrl` | Look up a queue URL by name |
| `GetQueueAttributes` | Get queue configuration attributes |
| `SetQueueAttributes` | Update queue configuration |
| `SendMessage` | Send a message to a queue |
| `SendMessageBatch` | Send up to 10 messages in one call |
| `ReceiveMessage` | Poll for messages |
| `DeleteMessage` | Acknowledge and delete a message |
| `DeleteMessageBatch` | Delete multiple messages at once |
| `ChangeMessageVisibility` | Extend or reset a message's visibility timeout |
| `ChangeMessageVisibilityBatch` | Change visibility for multiple messages |
| `PurgeQueue` | Delete all messages in a queue |
| `TagQueue` | Add tags to a queue |
| `UntagQueue` | Remove tags from a queue |
| `ListQueueTags` | List tags on a queue |
| `ListDeadLetterSourceQueues` | Find queues that use this queue as DLQ |
| `StartMessageMoveTask` | Start a DLQ redrive task |
| `ListMessageMoveTasks` | List DLQ redrive tasks |

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