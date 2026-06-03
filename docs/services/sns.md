# SNS

**Protocol:** Query (XML) and JSON 1.0 (both supported)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

| Action | Description |
|---|---|
| `CreateTopic` | Create a topic |
| `DeleteTopic` | Delete a topic |
| `ListTopics` | List all topics |
| `GetTopicAttributes` | Get topic configuration |
| `SetTopicAttributes` | Update topic configuration |
| `Subscribe` | Subscribe an endpoint (SQS, HTTP, Lambda, email) |
| `Unsubscribe` | Remove a subscription |
| `ListSubscriptions` | List all subscriptions |
| `ListSubscriptionsByTopic` | List subscriptions for a specific topic |
| `GetSubscriptionAttributes` | Get subscription settings |
| `SetSubscriptionAttributes` | Update subscription settings |
| `ConfirmSubscription` | Confirm a pending subscription |
| `Publish` | Publish a message to a topic |
| `PublishBatch` | Publish up to 10 messages in one call |
| `TagResource` | Tag a topic |
| `UntagResource` | Remove tags from a topic |
| `ListTagsForResource` | List tags on a topic |
| `CreatePlatformApplication` | Create a mobile push platform app (iOS or Android) |
| `DeletePlatformApplication` | Delete a platform app and its endpoints |
| `GetPlatformApplicationAttributes` | Read platform app attributes |
| `SetPlatformApplicationAttributes` | Update platform app attributes (e.g. `Enabled`) |
| `ListPlatformApplications` | List platform applications in the region |
| `CreatePlatformEndpoint` | Register a device token under a platform app |
| `DeleteEndpoint` | Delete a platform endpoint |
| `GetEndpointAttributes` | Read endpoint attributes |
| `SetEndpointAttributes` | Update endpoint attributes (e.g. `Enabled=false` to simulate token expiry) |
| `ListEndpointsByPlatformApplication` | List endpoints under a platform app |

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_SNS_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_STORAGE_SERVICES_SNS_MODE` | *(global default)* | Storage mode override for SNS (`memory`, `persistent`, `hybrid`, `wal`) |
| `FLOCI_STORAGE_SERVICES_SNS_FLUSH_INTERVAL_MS` | `5000` | Flush interval for `hybrid`/`wal` storage modes (milliseconds) |

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

## Mobile push (mock)

Floci mocks SNS mobile push for iOS and Android. No real APNS or FCM connection is
made — every push is captured in memory so tests can assert what would have been sent.

**Supported platforms:** `APNS`, `APNS_SANDBOX`, `GCM`, `FCM`. Any other platform
value returns `InvalidParameter`.

### End-to-end flow

```bash
APP_ARN=$(aws sns create-platform-application \
  --name ios-app --platform APNS \
  --attributes PlatformCredential=fake-cert \
  --endpoint-url http://localhost:4566 --query PlatformApplicationArn --output text)

ENDPOINT_ARN=$(aws sns create-platform-endpoint \
  --platform-application-arn $APP_ARN \
  --token ios-device-token-abc \
  --endpoint-url http://localhost:4566 --query EndpointArn --output text)

# Plain string payload
aws sns publish --target-arn $ENDPOINT_ARN --message '{"aps":{"alert":"hi"}}' \
  --endpoint-url http://localhost:4566

# Platform-specific payloads with MessageStructure=json
aws sns publish --target-arn $ENDPOINT_ARN --message-structure json \
  --message '{"default":"fallback","APNS":"{\"aps\":{\"alert\":\"ios\"}}","GCM":"{\"notification\":{\"body\":\"android\"}}"}' \
  --endpoint-url http://localhost:4566
```

When `MessageStructure="json"`, Floci picks the key matching the endpoint's platform
(`APNS`, `APNS_SANDBOX`, `GCM`, or `FCM`), falling back to `default`. The envelope
must be a JSON object and must include `default` — otherwise `InvalidParameter`.

### Inspecting captured pushes

```bash
# All captured pushes (newest first), or filtered by endpoint
curl http://localhost:4566/_aws/sns/push-notifications
curl "http://localhost:4566/_aws/sns/push-notifications?EndpointArn=$ENDPOINT_ARN"

# Reset between tests
curl -X DELETE http://localhost:4566/_aws/sns/push-notifications
```

### Simulating expired tokens

Two ways to make `Publish` fail with `EndpointDisabledException`:

1. **Explicit** — call `SetEndpointAttributes` with `Enabled=false`. Matches the
   real AWS flow after an async APNS/FCM failure.
2. **Sentinel** — create an endpoint whose token contains `EXPIRED`
   (case-insensitive). Floci marks it `Enabled=false` on creation, so the first
   publish fails. Lets you exercise the unhappy path with a single API call.

### Error codes

| Action | Condition | Error code | HTTP |
|---|---|---|---|
| `CreatePlatformApplication` | Missing `Name` | `InvalidParameter` | 400 |
| `CreatePlatformApplication` | Unsupported `Platform` (e.g. `WNS`, `ADM`) | `InvalidParameter` | 400 |
| `CreatePlatformEndpoint` | Missing `Token` | `InvalidParameter` | 400 |
| `CreatePlatformEndpoint` | Unknown `PlatformApplicationArn` | `NotFound` | 404 |
| `CreatePlatformEndpoint` | Same `Token`, different `CustomUserData` or attrs | `InvalidParameter` | 400 |
| `CreatePlatformEndpoint` | Platform app disabled | `PlatformApplicationDisabledException` | 400 |
| `Publish` | Unknown endpoint ARN | `NotFound` | 404 |
| `Publish` | `TargetArn` is a platform application ARN | `InvalidParameter` | 400 |
| `Publish` | Endpoint `Enabled=false` | `EndpointDisabledException` | 400 |
| `Publish` | Platform application `Enabled=false` | `PlatformApplicationDisabledException` | 400 |
| `Publish` | `MessageStructure=json` missing `default` key | `InvalidParameter` | 400 |
| `Publish` | `MessageStructure=json` message is not valid JSON | `InvalidParameter` | 400 |
| `GetPlatformApplicationAttributes` | Unknown ARN | `NotFound` | 404 |
| `GetEndpointAttributes` | Unknown ARN | `NotFound` | 404 |
| `SetEndpointAttributes` | Unknown ARN | `NotFound` | 404 |

`DeletePlatformApplication` and `DeleteEndpoint` are idempotent — they succeed
silently if the resource does not exist, matching real SNS behavior.