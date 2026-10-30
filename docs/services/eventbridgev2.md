# EventBridge V2

**Protocol:** Smithy RPC v2 CBOR (`smithy-protocol: rpc-v2-cbor`)
**Endpoint:** `POST http://localhost:4566/service/AWSEventsV2/operation/<Operation>`

EventBridge V2 is a separate service from [EventBridge](eventbridge.md). Its event buses are
`event-busv2` resources, `arn:aws:events:<region>:<account>:event-busv2/<name>/<id>`, which accept
events and deliver each one to every subscriber whose filters it matches. It signs requests with the
same `events` name as EventBridge, but nothing is shared between the two: a V2 bus is not visible to
EventBridge's `ListEventBuses`, and the reverse holds as well.

The AWS CLI calls it `aws eventsv2` and the SDKs `EventBridgeV2`.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateEventBus` | Create an event bus, with an optional description, KMS key, retention period, and tags |
| `DescribeEventBus` | Return an event bus's name, description, configuration, state and times |
| `DeleteEventBus` | Delete an event bus that has no subscribers |
| `ListEventBuses` | List the Region's event buses in name order, filtered by name prefix, a page at a time |
| `CreateSubscriber` | Create a subscriber that delivers the events its filters match to an Amazon SQS queue |
| `DescribeSubscriber` | Return a subscriber's target, filters, transformer, delivery settings, state and times |
| `ListSubscribers` | List the Region's subscribers, or one event bus's, filtered by name prefix, a page at a time |
| `UpdateSubscriber` | Change a subscriber's settings, keeping those the request leaves out |
| `DeleteSubscriber` | Delete a subscriber |
| `PutEvents` | Publish events in the EventBridge envelope to an event bus |
<!-- floci:actions:end -->

Every other EventBridge V2 operation answers `UnsupportedOperation`.

## Event buses

- A new bus is `ACTIVE` at once. AWS creates it in the `CREATING` state first; the `EventBusActive`
  waiter accepts either.
- A bus name is unique per account and Region. Creating one that exists fails with
  `ResourceAlreadyExistsException`.
- A retried `CreateEventBus` or `CreateSubscriber` with the same `ClientToken` returns what the first
  call created. The same token with a different request fails with `IdempotentParameterMismatchException`.
- `DeleteEventBus` fails with `ResourceInUseException` while the bus has subscribers.
- `ListEventBuses` lists only your own buses. Floci does not share buses across accounts, so an
  `EventBusAccountId` naming another account lists none.
- `EncryptionConfiguration.KmsKeyIdentifier` is stored and returned as given. AWS returns the key's ARN.
- `StorageConfiguration.RetentionPeriodInDays` is stored and returned. No default retention period
  is applied when it is omitted, and `RetentionWindowStartTime` is not returned. Events are not
  retained, so they cannot be replayed.

## Subscribers and filters

A subscriber delivers each event to one target. Floci delivers to an **Amazon SQS queue** in your
account. Any other target type is refused with `InvalidInputException`, as is a parameter block other
than `SqsParameters`.

`FilterConfiguration.Filters` holds at most one EventBridge pattern for each scope, and an event must
match every filter. A subscriber without a `FilterConfiguration` receives every event on the bus.

| Scope | Matches against |
| --- | --- |
| `DATA` | The event data. For `PutEvents`, the EventBridge envelope, with your `Detail` under `detail` |
| `METADATA` | The producer's metadata. Exact values only. A `PutEvents` event has none |
| `SYSTEM_METADATA` | The fields EventBridge sets: `ContentType` (`application/eventbridge+json`), `EventGroupId`, `DeduplicationId`, `aws:EventId`, `aws:IngestionTime`, `aws:SequenceNumber`, `aws:DeliveryType`, `aws:Source`, `aws:DetailType` |

Patterns support exact values, `prefix`, `suffix`, `equals-ignore-case`, `anything-but`, `numeric`,
`exists` and `$or`. As on AWS, a wildcard is rejected, as are two filters of one scope and patterns
over 4,096 bytes in all, a field given a single value rather than a list, and a `numeric` matcher
whose operators or operands are not comparisons and numbers. A pattern naming fields the event does not
have is not an error; it matches nothing.
A matcher Floci does not evaluate, such as `cidr` or `anything-but` with `numeric`, is refused with
`InvalidInputException` when the subscriber is created or updated, where AWS would accept it.

### Delivery

- `Transformer.Type` `RAW`, the default, sends the event data as the message body: for `PutEvents`,
  the envelope. `WITH_METADATA` sends `{"Data": ..., "Metadata": ..., "SystemMetadata": ...}`.
  A `JSONATA` transformer is refused.
- `SqsParameters` (`MessageGroupId`, `MessageDeduplicationId`, `DelaySeconds`, `MessageAttributes`)
  take a literal, or an expression that is a path into the event, such as
  `{% $events.SystemMetadata.EventGroupId %}` or `{% $events.SystemMetadata."aws:EventId" %}`. Other
  JSONata is refused when the subscriber is created.
- Delivery is immediate, one message per event, in the bus's sequence order, also across concurrent
  `PutEvents` requests. A delivery that fails, for example to a queue that
  does not exist, is logged and the event is dropped: `RetryPolicy` and `OnFailureConfiguration` are
  stored but not applied, and neither is `BatchConfiguration`.
- A `STOPPED` subscriber receives nothing. `StartingPosition` `POINT_IN_TIME` is stored but delivers
  from the latest event, since events are not retained.
- The `RoleArn` is checked for shape only; delivery does not assume it.

### Updating a subscriber

`UpdateSubscriber` changes only the settings the request gives. Its `InvokeConfiguration` replaces the role and
the target parameters but has no `TargetArn`: a subscriber keeps its target. An update is checked as a whole, so a
request with one invalid setting changes nothing. `ResumePosition` is accepted and has no effect, since events are
not retained. `DescribeSubscriber` and `ListSubscribers` always report `Revoked` as `false`.

## IAM

Under IAM enforcement, EventBridge V2 operations are authorized as the `events` actions of the same
name, as on AWS, and checked on the resource their request names: `PutEvents`, `DescribeEventBus`
and `DeleteEventBus` on the `event-busv2` ARN in `EventBusArn`, the subscriber operations on the
`subscriber` ARN in `SubscriberArn`, and `CreateSubscriber` on its bus and on
`subscriber/<name>/*`, and `CreateEventBus` on `event-busv2/<name>/*`. A V2 request is never
checked on the default [EventBridge](eventbridge.md) bus.

Where the two APIs share an operation name, the API serving the request decides which resource is
checked, not the members the request gives. An EventBridge request that adds a V2 bus's
`EventBusArn` is still checked on the EventBridge bus it acts on.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_EVENTBRIDGEV2_ENABLED` | `true` | Enable or disable the service |

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

QUEUE_URL=$(aws sqs create-queue --queue-name large-orders --query QueueUrl --output text)
QUEUE_ARN=$(aws sqs get-queue-attributes --queue-url "$QUEUE_URL" \
  --attribute-names QueueArn --query Attributes.QueueArn --output text)
BUS_ARN=$(aws eventsv2 create-event-bus --name orders --query EventBusArn --output text)

aws eventsv2 create-subscriber \
  --name large-orders \
  --event-bus-arn "$BUS_ARN" \
  --invoke-configuration "{\"TargetArn\":\"$QUEUE_ARN\",\"RoleArn\":\"arn:aws:iam::000000000000:role/EventBusDelivery\"}" \
  --filter-configuration '{"Filters":[{"Scope":"DATA","Pattern":"{\"detail\":{\"total\":[{\"numeric\":[\">\",500]}]}}"}]}'

aws eventsv2 put-events --event-bus-arn "$BUS_ARN" \
  --entries '[{"Source":"com.shop","DetailType":"Order Placed","Detail":"{\"total\":750}"}]'

aws sqs receive-message --queue-url "$QUEUE_URL"
```
