# SNS

**Protocol:** Query (XML) and JSON 1.0 (both supported)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateTopic` | Create a topic |
| `DeleteTopic` | Delete a topic |
| `ListTopics` | List all topics |
| `GetTopicAttributes` | Get topic configuration |
| `SetTopicAttributes` | Update topic configuration |
| `Subscribe` | Subscribe an endpoint (SQS, HTTP, Lambda, email) |
| `Unsubscribe` | Remove a subscription |
| `ListSubscriptions` | List all subscriptions |
| `ListSubscriptionsByTopic` | List subscriptions for a specific topic |
| `Publish` | Publish a message to a topic |
| `PublishBatch` | Publish up to 10 messages in one call |
| `GetSubscriptionAttributes` | Get subscription settings |
| `SetSubscriptionAttributes` | Update subscription settings |
| `ConfirmSubscription` | Confirm a pending subscription |
| `TagResource` | Tag a topic |
| `UntagResource` | Remove tags from a topic |
| `ListTagsForResource` | List tags on a topic |
<!-- floci:actions:end -->

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a topic
TOPIC_ARN=$(aws sns create-topic --name notifications \
  --query TopicArn --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

# Subscribe an SQS queue
QUEUE_ARN=$(aws sqs get-queue-attributes \
  --queue-url $AWS_ENDPOINT_URL/000000000000/orders \
  --attribute-names QueueArn \
  --query Attributes.QueueArn --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

aws sns subscribe \
  --topic-arn $TOPIC_ARN \
  --protocol sqs \
  --notification-endpoint $QUEUE_ARN \
  --endpoint-url $AWS_ENDPOINT_URL

# Publish a message
aws sns publish \
  --topic-arn $TOPIC_ARN \
  --message '{"event":"user.registered"}' \
  --endpoint-url $AWS_ENDPOINT_URL

# Fan-out: publish and verify the SQS queue received the message
aws sqs receive-message \
  --queue-url $AWS_ENDPOINT_URL/000000000000/orders \
  --endpoint-url $AWS_ENDPOINT_URL
```

## SNS → SQS Fan-Out

Floci supports real SNS → SQS fan-out. When you publish to a topic, all SQS-subscribed queues receive the message immediately.

Supported subscription protocols:
- `sqs` — delivers to a Floci SQS queue
- `lambda` — invokes a Floci Lambda function
- `http` / `https` — posts to an HTTP endpoint