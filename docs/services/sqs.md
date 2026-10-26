# SQS

**Protocol:** Query (XML) and JSON 1.0 (both supported)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateQueue` | Create a standard or FIFO queue |
| `DeleteQueue` | Delete a queue |
| `ListQueues` | List queues, with `QueueNamePrefix` filtering and `MaxResults`/`NextToken` paging |
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

## Long Polling

`ReceiveMessage` waits up to `WaitTimeSeconds` (0 to 20) for a message to arrive and returns as soon as one is available. Values outside that range are rejected with `InvalidParameterValue`. When the request omits `WaitTimeSeconds`, the queue's `ReceiveMessageWaitTimeSeconds` attribute applies (default `0`, short polling), matching AWS.

```bash
# Enable long polling for every consumer of the queue
aws sqs set-queue-attributes \
  --queue-url $QUEUE_URL \
  --attributes ReceiveMessageWaitTimeSeconds=20 \
  --endpoint-url $AWS_ENDPOINT_URL
```

## Queue Attributes

`GetQueueAttributes` with `--attribute-names All` returns the set AWS returns for a standard queue: `QueueArn`, `CreatedTimestamp`, `LastModifiedTimestamp`, `ApproximateNumberOfMessages`, `ApproximateNumberOfMessagesNotVisible`, `ApproximateNumberOfMessagesDelayed`, `VisibilityTimeout`, `MaximumMessageSize`, `MessageRetentionPeriod`, `DelaySeconds`, `ReceiveMessageWaitTimeSeconds` and `SqsManagedSseEnabled`. `Policy`, `RedrivePolicy` and `KmsMasterKeyId` appear only once set, and FIFO queues also report `FifoQueue` and `ContentBasedDeduplication`.

`MaximumMessageSize` defaults to `1048576` bytes, the AWS default and maximum since August 2025, when AWS raised both from `262144`. `CreateQueue` and `SetQueueAttributes` reject values outside 1024 to 1048576 with `InvalidAttributeValue`; raising `FLOCI_SERVICES_SQS_MAX_MESSAGE_SIZE` above the AWS maximum raises that accepted ceiling with it. Lowering the variable below 1048576 only changes the default a new queue receives: the accepted ceiling stays at the AWS maximum, so a queue can still be set to `1048576` explicitly.

`SqsManagedSseEnabled` is `true` for a queue with no `KmsMasterKeyId`, matching AWS, and `false` once a KMS key is set. It is derived on read rather than stored, so clearing `KmsMasterKeyId` returns the queue to `true`, unless you set `SqsManagedSseEnabled` yourself, in which case your value stands. AWS does not document the cleared-key case crisply, so that behaviour is Floci's choice rather than a copied one. `CreateQueue` on an existing name compares requested attributes against these reported values, so asking for `SqsManagedSseEnabled=false` on a queue that reports `true` fails with `QueueAlreadyExists` (`QueueNameExists` over JSON).

## Message Retention

A message older than the queue's `MessageRetentionPeriod` is no longer returned or counted, including a message in flight. A shorter period applies to messages already in the queue. A message moved to a FIFO dead-letter queue, or moved by `StartMessageMoveTask`, starts its retention period over. A message moved to a standard dead-letter queue keeps its original enqueue time. Expired messages are freed from memory and storage every 60 seconds.

`CreateQueue` and `SetQueueAttributes` reject a `MessageRetentionPeriod` outside 60 to 1209600 seconds with `InvalidAttributeValue`.

## CloudWatch Metrics

Floci publishes the `AWS/SQS` metrics of every active queue to CloudWatch once a minute, on the single `QueueName` dimension, as SQS does. Each publication runs just after a minute boundary and is stamped with the minute that ended, so a call counts in the minute it was made. Alarms, `GetMetricData`, `GetMetricStatistics` and `ListMetrics` see them with no `PutMetricData`. A queue is active while it holds a message, and for six hours after `CreateQueue`, `SendMessage`, `ReceiveMessage`, `DeleteMessage`, `ChangeMessageVisibility`, `PurgeQueue`, `GetQueueAttributes` or `SetQueueAttributes` on it, batch forms included. Publication stops when `FLOCI_SERVICES_SQS_CLOUDWATCH_METRICS_ENABLED` is `false` or the CloudWatch Metrics service is disabled.

| Metric | Unit | Samples each minute |
|---|---|---|
| `ApproximateNumberOfMessagesVisible` | `Count` | One |
| `ApproximateNumberOfMessagesNotVisible` | `Count` | One |
| `ApproximateNumberOfMessagesDelayed` | `Count` | One |
| `ApproximateAgeOfOldestMessage` | `Seconds` | One, `0` for an empty queue |
| `NumberOfMessagesSent` | `Count` | One per sent message, plus a zero |
| `NumberOfMessagesReceived` | `Count` | One per `ReceiveMessage` call that returned messages, valued at their number, plus a zero |
| `NumberOfEmptyReceives` | `Count` | One per `ReceiveMessage` call that returned none, plus a zero |
| `NumberOfMessagesDeleted` | `Count` | One per delete with a valid receipt handle, repeats included, plus a zero |
| `SentMessageSize` | `Bytes` | One per sent message, none in a minute without sends |
| `ApproximateNumberOfGroupsWithInflightMessages` | `Count` | FIFO only: one |
| `NumberOfDeduplicatedSentMessages` | `Count` | FIFO only: one per send, `1` when it was deduplicated, plus a zero |

These match what AWS publishes. A deduplicated FIFO send still counts in `NumberOfMessagesSent`, and a message a redrive policy moves to a dead-letter queue does not. `ApproximateAgeOfOldestMessage` counts a message moved to a FIFO dead-letter queue or by `StartMessageMoveTask` from the move.

Known differences: a `SendMessageBatch` or `DeleteMessageBatch` call adds one sample per entry where AWS adds one per call, so `Sum` matches while `SampleCount`, `Average`, `Minimum` and `Maximum` can differ. A message in a standard dead-letter queue keeps its original send time for its age, where AWS counts from the move, and a standard-queue message received three times or more still counts toward the age, where AWS leaves it out until it is deleted. Floci keeps these datapoints for the 15 days AWS keeps one-minute data and then drops them, where AWS goes on serving them at 5 minute and 1 hour resolution. The fair-queue metrics (`ApproximateNumberOfNoisyGroups` and the `InQuietGroups` variants) are not published, and the delay of up to 15 minutes AWS has before a newly active queue reports is not reproduced.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_SQS_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_SQS_DEFAULT_VISIBILITY_TIMEOUT` | `30` | Default message visibility timeout (seconds) |
| `FLOCI_SERVICES_SQS_MAX_MESSAGE_SIZE` | `1048576` | Maximum message size in bytes (1 MiB) |
| `FLOCI_SERVICES_SQS_CLEAR_FIFO_DEDUPLICATION_CACHE_ON_PURGE` | `false` | When `true`, `PurgeQueue` also clears the FIFO deduplication cache for the queue and any SNS FIFO topics subscribed to it |
| `FLOCI_SERVICES_SQS_RECEIPT_HANDLE_SECRET` | `local-emulator-secret` | HMAC secret that signs receipt handles, so a handle that was edited or built by hand is rejected |
| `FLOCI_SERVICES_SQS_ENDPOINT_STRATEGY` | `floci` | Shape of the returned `QueueUrl`, see [Queue URL Format](#queue-url-format) |
| `FLOCI_SERVICES_SQS_CLOUDWATCH_METRICS_ENABLED` | `true` | Publish the `AWS/SQS` metrics of active queues to CloudWatch once a minute, see [CloudWatch Metrics](#cloudwatch-metrics) |

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

`CreateQueue`, `GetQueueUrl`, `ListQueues` and `ListDeadLetterSourceQueues` return the queue URL under Floci's own endpoint by default, `<FLOCI_BASE_URL>/<account>/<queue-name>`.

Set `FLOCI_SERVICES_SQS_ENDPOINT_STRATEGY=aws` to get the form AWS returns instead.

```
https://sqs.<region>.amazonaws.com/000000000000/<queue-name>
```

The region is the one the request was signed for, and the host carries that region's partition DNS suffix, so a queue in `cn-north-1` is reported as `https://sqs.cn-north-1.amazonaws.com.cn/...`. Use it for tooling that parses or compares queue URLs against the AWS form. Most SDKs send requests to the endpoint you configured and take only the path from the URL. Clients that send requests to the host in the returned URL, such as the AWS SDK for Java 1.x, need the default `floci` setting or DNS that points the `sqs.<region>` host at Floci.

Under either setting every form is accepted as input. That covers the AWS URL, the Floci URL, any other host whose path ends in `/<account>/<queue-name>`, and the bare queue name. A queue is stored by account and name, so a base URL with a path of its own, such as `http://localhost:4566/sqs`, keeps the same queues under either setting. Existing queues, persisted ones included, are reported in the form the current setting and base URL give.

## Persisted delivery state

With file-backed storage, delayed messages keep their absolute delivery deadline across
reloads. An in-flight message keeps its visibility deadline and receipt handle, so a FIFO
group remains blocked until that delivery is deleted or becomes visible again. A restart
does not begin a new delay or visibility period.

Older stored messages without these fields still load as visible messages. Delivery state
that an older version did not save cannot be reconstructed. Hybrid mode retains its
configured flush window; this does not add synchronous durability to hybrid storage.
