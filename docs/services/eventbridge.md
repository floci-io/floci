# EventBridge

**Protocol:** JSON 1.1 (`X-Amz-Target: AmazonEventBridge.*`)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

| Action | Description |
|---|---|
| `CreateEventBus` | Create a custom event bus |
| `DeleteEventBus` | Delete an event bus |
| `DescribeEventBus` | Get event bus details |
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
