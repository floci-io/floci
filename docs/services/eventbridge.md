# EventBridge

**Protocol:** JSON 1.1 (`X-Amz-Target: AmazonEventBridge.*`)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateEventBus` | Create a custom event bus |
| `DeleteEventBus` | Delete an event bus |
| `DescribeEventBus` | Get event bus details |
| `UpdateEventBus` | Update event bus description, KMS key, dead-letter config, or log config |
| `ListEventBuses` | List all event buses |
| `PutRule` | Create or update a rule with a schedule or event pattern |
| `DeleteRule` | Delete a rule |
| `DescribeRule` | Get rule details |
| `ListRules` | List rules |
| `EnableRule` | Enable a disabled rule |
| `DisableRule` | Disable a rule |
| `PutTargets` | Add targets to a rule |
| `RemoveTargets` | Remove targets from a rule |
| `ListTargetsByRule` | List targets for a rule |
| `PutEvents` | Publish custom events to an event bus |
| `TestEventPattern` | Test whether a sample event matches a given pattern (no targets fired) |
| `ListTagsForResource` | - |
| `TagResource` | - |
| `UntagResource` | - |
| `PutPermission` | - |
| `RemovePermission` | - |
| `CreateArchive` | - |
| `DescribeArchive` | - |
| `UpdateArchive` | - |
| `DeleteArchive` | - |
| `ListArchives` | - |
| `CreateConnection` | Create a connection for API destinations (credential values are stored but never returned) |
| `DescribeConnection` | Get connection details with credential values stripped |
| `UpdateConnection` | Update connection description, auth type, or auth parameters |
| `DeleteConnection` | Delete a connection |
| `ListConnections` | List connections, optionally filtered by name prefix or state |
| `StartReplay` | - |
| `DescribeReplay` | - |
| `CancelReplay` | - |
| `ListReplays` | - |
<!-- floci:actions:end -->

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_EVENTBRIDGE_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a custom event bus
aws events create-event-bus \
  --name my-bus \
  --endpoint-url $AWS_ENDPOINT_URL

# Create a rule matching a pattern
aws events put-rule \
  --name order-placed-rule \
  --event-bus-name my-bus \
  --event-pattern '{"source":["com.myapp"],"detail-type":["OrderPlaced"]}' \
  --state ENABLED \
  --endpoint-url $AWS_ENDPOINT_URL

# Add a Lambda target
aws events put-targets \
  --rule order-placed-rule \
  --event-bus-name my-bus \
  --targets '[{
    "Id": "process-order",
    "Arn": "arn:aws:lambda:us-east-1:000000000000:function:process-order"
  }]' \
  --endpoint-url $AWS_ENDPOINT_URL

# Publish an event
aws events put-events \
  --entries '[{
    "Source": "com.myapp",
    "DetailType": "OrderPlaced",
    "Detail": "{\"orderId\":\"123\",\"amount\":99.99}",
    "EventBusName": "my-bus"
  }]' \
  --endpoint-url $AWS_ENDPOINT_URL
```

## Default Event Bus

EventBridge includes a default event bus (`default`) that accepts events from AWS services. Custom buses are for your own application events.

```bash
# List rules on the default bus
aws events list-rules --endpoint-url $AWS_ENDPOINT_URL

# Send to default bus
aws events put-events \
  --entries '[{"Source":"myapp","DetailType":"test","Detail":"{}"}]' \
  --endpoint-url $AWS_ENDPOINT_URL
```

## Event Bus Targets

A rule can target another event bus by ARN: the event is republished there, and that bus's own rules evaluate it and fan out normally. `Source`, `DetailType`, `Resources` and the originating `account`/`region` carry over, and each hop gets a new event id.

```bash
aws events put-targets \
  --rule order-placed-rule \
  --event-bus-name my-bus \
  --targets '[{
    "Id": "forward-to-domain-bus",
    "Arn": "arn:aws:events:us-east-1:000000000000:event-bus/domain-bus"
  }]' \
  --endpoint-url $AWS_ENDPOINT_URL
```

## Current Behavior

- `PutEvents` reports success once the source bus accepts an event, so target delivery failures surface only as a `WARN` in the Floci logs.
- A `Detail` forwarded to an event bus must be a JSON object, as in AWS; anything else is dropped, including an `InputPath` selecting a scalar such as `$.detail.orderId` or an envelope carrying `"detail": null`.
- A bus ARN naming another account is forwarded under that account, so the target bus and its rules resolve there.
- Onward delivery from that bus follows each target type: SQS resolves cross-account, while Lambda, SNS, Batch and Firehose resolve in the caller's account.
- An event is forwarded between buses only once, matching AWS: a bus that received an event from another bus does not forward it on to a third. The second hop is dropped with only a `WARN` rather than reported to the caller.
