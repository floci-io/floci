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
| `DeleteEventBus` | Delete an event bus that has no subscribers or event sources that are not revoked |
| `ListEventBuses` | List the Region's event buses in name order, filtered by name prefix, a page at a time |
| `CreateSubscriber` | Create a subscriber that delivers the events its filters match to an Amazon SQS queue |
| `DescribeSubscriber` | Return a subscriber's target, filters, transformer, delivery settings, state and times |
| `ListSubscribers` | List the Region's subscribers, or one event bus's, filtered by name prefix, a page at a time |
| `UpdateSubscriber` | Change a subscriber's settings, keeping those the request leaves out |
| `DeleteSubscriber` | Delete a subscriber |
| `CreateEventSource` | Create a webhook event source on an event bus, with its URL and a managed secret |
| `DescribeEventSource` | Return a webhook event source's configuration, URL, secret ARN, state and times |
| `ListEventSources` | List the Region's event sources, or one event bus's, filtered by name prefix, a page at a time |
| `UpdateEventSource` | Replace a webhook event source's configuration and description |
| `RotateWebhookCredentials` | Replace the credential in a webhook event source's secret, with a given or a generated one |
| `RevokeResource` | Revoke a webhook event source or a subscriber for good, keeping it but ending its use |
| `DeleteEventSource` | Delete a webhook event source and schedule its secret for deletion |
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
- `DeleteEventBus` fails with `ResourceInUseException` while the bus has subscribers or event sources.
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
not retained. `DescribeSubscriber` and `ListSubscribers` return `Revoked` once the subscriber is revoked, and leave it out before.

## Revoking a resource

`RevokeResource` takes the ARN of a webhook event source or of a subscriber and answers it back. A revoke cannot be
undone. A second revoke answers `200` and changes nothing.

- A revoked source answers `403` on its webhook URL for every POST that reaches authentication. Its secret stays
  and its name stays taken. `UpdateEventSource` and `RotateWebhookCredentials` answer `InvalidStateException`
  (409) after their own `400` checks. A rotation also checks its `ClientToken` first, so a repeat of an earlier
  rotation still answers `200`.
- A revoked subscriber receives no more events. `UpdateSubscriber` answers `InvalidStateException` (409) after its
  own `400` checks.
- Both stay `ACTIVE` or `RUNNING`, report `Revoked`, and can still be described, listed and deleted.
- `DeleteEventBus` ignores revoked sources and subscribers. A revoked resource outlives its bus. Its description
  then has no `Configuration` for a source. A subscriber has no `InvokeConfiguration`, `BatchConfiguration`,
  `Transformer`, `RetryPolicy`, `FilterConfiguration`, `OnFailureConfiguration` or `LogConfiguration`, and its
  `Description` reads `*** Not accessible: event bus deleted ***`. A new bus of the same name does not take it
  over. It can have a subscriber of the orphan's name, but a source name stays taken.
- The ARN of a webhook source or subscriber that does not exist is answered `403` as access denied. A source ARN of
  type `aws.service` or `aws.partner` is answered `404`.

## Webhook event sources

Floci serves event sources of the `WebhookEventsConfiguration` kind. A request with an
`AwsServiceEventsConfiguration` or `PartnerEventsConfiguration` is refused with `InvalidInputException`.

- A source is `ACTIVE` at once. Its ARN is
  `arn:aws:events:<region>:<account>:event-sourcev2/webhook/<name>/<id>`.
- A source name is unique per account and Region, across event buses.
- The webhook URL is `<scheme>://<host id>.c01.events-webhook.<region>.<authority>/<token>/`. The scheme and
  authority are those of Floci's base URL. A POST to it publishes an event, as described below.
- Each source has a Secrets Manager secret named `events-webhook!<uuid>`, owned by `events-webhook`. It holds the
  static token, the HMAC secret, or the Basic credential as `{"username":"...","password":"..."}`. A credential
  the request leaves out is generated. The secret ARN is in `EventSourceDetails.WebhookDetails.SecretArn`.
- `DescribeEventSource` returns `Configuration` as given, without `SecretValue`, `Username` and `Password`.
- `UpdateEventSource` replaces the whole webhook configuration and keeps the URL, the secret and the
  authentication type. It does not accept credentials.
- `RotateWebhookCredentials` replaces the credential in the secret and keeps the URL, the secret ARN and
  `LastModifiedTime`. The new value becomes `AWSCURRENT` and the one it replaces `AWSPREVIOUS`. The webhook URL
  keeps accepting the credential the last rotation replaced for 15 minutes. An earlier one is refused at once.
  `Credential` must set the member of the source's authentication type, or be left out to generate a value.
  Basic keeps its username. A repeat of a `ClientToken` of the source with the same request changes nothing.
  Floci forgets a token 8 hours after its rotation. A rotation answers `InvalidStateException` (409) when the
  secret is scheduled for deletion or deleted.
- `KmsKeyIdentifier` is stored and returned. It is not used for the secret.
- `DeleteEventSource` schedules the secret for deletion at once.
- `DescribeEventSource` and `ListEventSources` return `Revoked` once the source is revoked, and leave it out before.

### Receiving events

A POST to the webhook URL authenticates the caller and publishes the body as an event on the source's bus.
Subscribers receive it as they receive a `PutEvents` event, with the next sequence number of the bus.
The request is answered in this order.

1. A method other than POST is `400`, even on an unknown URL.
2. A path that is not exactly `/<token>/` or a token that does not belong to the host is `404`. A query string is accepted.
3. A body over 1048576 bytes is `400`.
4. A caller that fails authentication is `403`. So is a URL whose source was deleted.
5. A body that is not one JSON value is `400`.

The answers are `OK`, `Forbidden`, `Bad Request` and `Not Found` as `text/plain`, with `x-amzn-RequestId`.
A `400` also sends `Connection: close` and closes the connection.

- The body may be any JSON value. Floci refuses an empty body, a duplicate key at any depth, a second value,
  trailing text, a byte order mark, text that is not UTF-8, `NaN`, single quotes, comments, trailing commas and a
  leading zero.
  Floats are written as doubles, so `1e2` becomes `100.0`. Integers keep their size. `Content-Type` is not checked.
- `StaticTokenAuthConfiguration` compares the whole value of `TokenHttpHeader` with the secret.
  `BasicHttpAuthConfiguration` reads `Authorization` with the scheme `Basic` in any case and one space.
- `HmacAuthConfiguration` signs the body, the timestamp and the body, or the message id, the timestamp and the body,
  with `Algorithm` `SHA1` or `SHA256`. `Encoding` is `HEX` or `BASE64`. A hex signature may be in any case.
  A Base64 signature may lack padding and may not be URL-safe. `SecretEncoding` `BASE64` decodes the secret first.
  `SignaturePrefix` is required when set. `Delimiter` splits a list of signatures and any one may match.
  When `SignatureHttpHeader` and `TimestampHttpHeader` are the same header, the elements may come in any order.
  A timestamp is epoch seconds or an ISO-8601 date and time with an offset. `TimestampToleranceInSeconds` applies
  to past and future, and is 300 when the source does not set it. A message id with a `.` is refused.
- The event is `Data`, `Metadata` and `SystemMetadata`. `Data` is the parsed body. `Metadata` holds the
  `MetadataHttpHeaders` of the source under lower-case names. Repeated headers are joined with `, `.
  An empty value and a value over 1024 UTF-8 bytes are dropped. The names come in the order AWS gives them,
  which is that of a hash map and not that of the list.
- `SystemMetadata` has `EventGroupId`, `DeduplicationId`, `ContentType`, `aws:IngestionTime`, `aws:EventId`,
  `aws:SequenceNumber` and `aws:DeliveryType`. It has no `aws:Source` or `aws:DetailType`.
- A `WITH_METADATA` subscriber receives the three parts. A `RAW` subscriber receives the request body as sent.
- `DeduplicationType` `CONTENT_BASED` drops a request whose body bytes and copied metadata the source saw in the
  last 5 minutes. The request is answered `200` and publishes nothing. `EventGroupId` and `DeduplicationId` are then
  a 64 character hash, and otherwise a random UUID. The hash is SHA-256 over an encoding of Floci's own,
  so it does not equal the hash AWS gives. The hashes are kept in memory and are lost on restart.
- The host of the request decides the source. The Region in the host is not checked.
- Not emulated: `429` throttling and `LogConfiguration` delivery.

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

## CloudFormation

`AWS::EventsV2::EventBus` and `AWS::EventsV2::Subscriber` are provisioned from their properties.
`Ref` returns the ARN of each.

| Type | `Fn::GetAtt` |
| --- | --- |
| `AWS::EventsV2::EventBus` | `EventBusArn`, `State`, `CreationTime`, `LastModifiedTime` |
| `AWS::EventsV2::Subscriber` | `SubscriberArn`, `BusName`, `CreationTime`, `LastModifiedTime` |

An update that keeps a bus's `Name` changes it in place. An update that keeps a subscriber's `Name`,
`EventBusArn`, `InvokeConfiguration.TargetArn`, `Type`, `StartingPosition` and `PointInTimeConfiguration`
changes the rest in place.
Changing any of them replaces the subscriber. With a new `Name`, the new subscriber is created and the
old one deleted once the update completes. Under the same `Name`, the old subscriber is deleted first and
then created again, the `delete_then_create` replacement its schema declares. If that update rolls back,
or the new subscriber cannot be created, the old subscriber is recreated with its original ARN and
settings; a rollback that cannot recreate it fails rather than reporting success. An update rolled back after
changing a bus or subscriber in place puts back the settings it had.

With `aws-cdk-lib` 2.272 to 2.273, `CfnSubscriber` lacks its `invokeConfiguration` prop
([aws/aws-cdk#38996](https://github.com/aws/aws-cdk/issues/38996)); set it with
`addPropertyOverride("InvokeConfiguration", {...})`.

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
