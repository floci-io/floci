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
| `CreateApiDestination` | Create an HTTP or HTTPS API destination for EventBridge targets |
| `DescribeApiDestination` | Get API destination details including endpoint, HTTP method, and rate limit |
| `UpdateApiDestination` | Update API destination endpoint, HTTP method, connection, or rate limit |
| `DeleteApiDestination` | Delete an API destination |
| `ListApiDestinations` | List API destinations, optionally filtered by name prefix or connection ARN |
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

## Step Functions Targets

A matching rule can start an unqualified Step Functions state machine ARN. The target supports the
full event as the default input, plus `Input`, `InputPath`, and `InputTransformer`. Floci resolves
the state machine from the account and region in its ARN.

State machine aliases and versions are not supported as EventBridge targets.

## Target Roles

`PutTargets` stores each target's optional `RoleArn`, and `ListTargetsByRule`
returns it when set. It is independent of the rule's `RoleArn`. An explicit
role update replaces the stored value. Omitting the role on an existing
cross-account event-bus target with the same ID and ARN retains its previous
role, as documented by AWS.

The value must be between 1 and 1600 characters when supplied. Floci stores
the role as configuration; target delivery does not assume it or enforce its
IAM policies.

## API Destination Targets

A matching rule can send events directly to HTTP or HTTPS endpoints via API destinations. An API destination pairs an HTTP endpoint URL and HTTP method with an EventBridge Connection for authentication (API key, basic auth, or OAuth client credentials).

```bash
# 1. Create a connection with API key auth
aws events create-connection \
  --name webhook-auth \
  --authorization-type API_KEY \
  --auth-parameters '{"ApiKeyAuthParameters":{"ApiKeyName":"x-api-key","ApiKeyValue":"secret123"}}' \
  --endpoint-url $AWS_ENDPOINT_URL

# 2. Create an API destination
aws events create-api-destination \
  --name webhook-dest \
  --connection-arn "arn:aws:events:us-east-1:000000000000:connection/webhook-auth" \
  --invocation-endpoint "https://api.example.com/events/*" \
  --http-method POST \
  --invocation-rate-limit-per-second 10 \
  --endpoint-url $AWS_ENDPOINT_URL

# 3. Add the API destination as a target with path and query parameters
aws events put-targets \
  --rule order-placed-rule \
  --event-bus-name my-bus \
  --targets '[{
    "Id": "post-to-webhook",
    "Arn": "arn:aws:events:us-east-1:000000000000:api-destination/webhook-dest",
    "HttpParameters": {
      "PathParameterValues": ["orders"],
      "QueryStringParameters": {"version": "v1"},
      "HeaderParameters": {"X-Custom-Header": "eventbridge"}
    }
  }]' \
  --endpoint-url $AWS_ENDPOINT_URL
```

- `InvocationEndpoint` must be an `http` or `https` URL.
- Path parameter wildcards (`*`) in the path of `InvocationEndpoint` are substituted in order by `PathParameterValues`. Each value is percent-encoded as a single path segment, and a `*` in the host or query is never substituted.
- Query string and header parameters from both the Connection and the Target are merged and forwarded on the outgoing HTTP request. Connection `BodyParameters` are merged into the event body when it is a JSON object.
- `OAUTH_CLIENT_CREDENTIALS` connections run the RFC 6749 client-credentials exchange against `AuthorizationEndpoint` for each delivery: the client ID and secret go in a `Basic` `Authorization` header, the body is form-encoded `grant_type=client_credentials` plus any `BodyParameters`, and the returned `access_token` is sent as `Authorization: <token_type> <access_token>`. Tokens are not cached.
- If the connection is missing, or its authorization cannot be obtained, the delivery is dropped instead of being sent unauthenticated.
- Requests to link-local and AWS metadata endpoints (such as `169.254.0.0/16`) are blocked for SSRF protection, including for the OAuth endpoint. The host is resolved once for the check and again when the request is sent, so DNS rebinding is not fully prevented.
- A delivery that fails with a connection error, HTTP 429 or HTTP 5xx is retried and dead-lettered like any other target (see Target Retry and Dead-Letter Queues). Any other failure is permanent and goes straight to the dead-letter queue: another non-2xx response, an invalid or blocked URL, a missing connection, or an OAuth token request that fails.
- `InvocationRateLimitPerSecond` is enforced per destination with a one-second window. An event over the limit is treated as throttled and retried with backoff.
- A destination or connection ARN taken before the resource was deleted and recreated under the same name no longer resolves.
- `ListApiDestinations` supports `Limit` (1 to 100) and `NextToken`.

## Target Retry and Dead-Letter Queues

A target's `RetryPolicy` and `DeadLetterConfig` are validated by `PutTargets`, stored with the target, returned by `ListTargetsByRule`, and applied to delivery from `PutEvents` and scheduled rules.

```bash
aws events put-targets \
  --rule order-placed-rule \
  --event-bus-name my-bus \
  --targets '[{
    "Id": "orders-queue",
    "Arn": "arn:aws:sqs:us-east-1:000000000000:orders",
    "RetryPolicy": {"MaximumRetryAttempts": 4, "MaximumEventAgeInSeconds": 3600},
    "DeadLetterConfig": {"Arn": "arn:aws:sqs:us-east-1:000000000000:orders-dlq"}
  }]' \
  --endpoint-url $AWS_ENDPOINT_URL
```

- The first attempt is immediate. Throttling, server-side errors from the target, and internal errors are retried with exponential backoff (1 second doubling, capped at 5 minutes) until `MaximumRetryAttempts` or `MaximumEventAgeInSeconds` runs out. The AWS defaults, 185 attempts and 86400 seconds, apply when a field is omitted.
- A missing target resource, a permission error, or any other client error is not retried.
- An exhausted or non-retryable event is sent to the `DeadLetterConfig` queue with the original event as the body and the `RULE_ARN`, `TARGET_ARN`, `ERROR_CODE`, `ERROR_MESSAGE`, `EXHAUSTED_RETRY_CONDITION` and `RETRY_ATTEMPTS` message attributes. `EXHAUSTED_RETRY_CONDITION` is omitted for a non-retryable error, since no retry limit was reached. The `ERROR_CODE` value is Floci's best mapping of the underlying failure.
- The dead-letter queue must be a standard SQS queue in the rule's region, as in AWS. It may be in another account. Any other queue, and any failure to send to it, is logged as a `WARN`.
- A retry uses the target's current definition, so `PutTargets` changes apply to pending retries, and a target removed with `RemoveTargets` stops being retried.
- An accepted asynchronous Lambda invocation counts as delivered. A later function error is handled by Lambda's own retry and destination settings, not by the rule.
- Pending retries are kept in memory and are dropped by an emulator reset or restart.

## Current Behavior

- `PutEvents` reports success once the source bus accepts an event. Target delivery failures are retried and dead-lettered as described above, and otherwise surface only as a `WARN` in the Floci logs.
- A `Detail` forwarded to an event bus must be a JSON object, as in AWS; anything else is dropped, including an `InputPath` selecting a scalar such as `$.detail.orderId` or an envelope carrying `"detail": null`.
- A bus ARN naming another account is forwarded under that account, so the target bus and its rules resolve there.
- Onward delivery from that bus follows each target type: SQS and Step Functions resolve cross-account, while Lambda, SNS, Batch and Firehose resolve in the caller's account.
- An event is forwarded between buses only once, matching AWS: a bus that received an event from another bus does not forward it on to a third. The second hop is dropped with only a `WARN` rather than reported to the caller.
