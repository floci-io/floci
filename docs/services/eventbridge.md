# EventBridge

**Protocol:** JSON 1.1 (`X-Amz-Target: AmazonEventBridge.*`)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateEventBus` | Create a custom event bus, with its description, KMS key, dead-letter config and log config |
| `DeleteEventBus` | Delete an event bus |
| `DescribeEventBus` | Get event bus details |
| `UpdateEventBus` | Update event bus description, KMS key, dead-letter config, or log config |
| `ListEventBuses` | List all event buses |
| `PutRule` | Create or update a rule with a schedule or event pattern |
| `DeleteRule` | Delete a rule |
| `DescribeRule` | Get rule details |
| `ListRules` | List rules, paged by `Limit` (1 to 100, default 100) and `NextToken` |
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

`ListRules` and `ListConnections` accept `NextToken` values of 1 to 2048
characters. Empty or overlong tokens return `ValidationException`; unrecognized
cursors within those bounds return `InvalidToken`.

## Numeric event patterns

`TestEventPattern` and `PutEvents` rule routing support `numeric` comparisons on
JSON number fields using `=`, `>`, `>=`, `<`, and `<=`. Both bounds of a range
must match. For example,
`{"detail":{"severity":[{"numeric":[">=",7,"<",9]}]}}` matches severity `7` or `8`,
but excludes `6`, `9`, the string `"7"`, and missing or null values.

For array-valued fields, a numeric filter matches when at least one number in the
array satisfies all comparisons. The range above matches `[3,8]` but excludes
`[3,9]`; separate elements cannot satisfy separate bounds of the same range.

`PutRule` and `TestEventPattern` reject malformed numeric filters with
`InvalidEventPatternException` (HTTP 400), independently of the supplied event.
The comparison array must contain either one operator/number pair or a lower bound
(`>` or `>=`) followed by an upper bound (`<` or `<=`). Equality (`=`) must stand
alone. Range endpoints must be strictly increasing, even when both bounds are
inclusive. Repeated bounds, reversed bounds, and extra comparisons are rejected.
Nested patterns and every `$or` branch are validated before a rule is saved;
rejected updates preserve the existing rule.

See [AWS numeric matching](https://docs.aws.amazon.com/eventbridge/latest/userguide/eb-create-pattern-operators.html#filtering-numeric-matching).

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

## Target Settings

`PutTargets` defaults omitted ECS `TaskCount` to `1`, and `EnableECSManagedTags` and
`EnableExecuteCommand` to `false`. Explicit values are preserved. A task count below
one or a malformed target `RoleArn` or `DeadLetterConfig.Arn` returns a
`ValidationException` before any targets in the request are stored.
Role ARNs must identify an IAM role with a non-empty name; dead-letter ARNs must
identify an SQS queue with a region. Both require a published AWS partition and a
12-digit account ID. Queue names are limited to 80 characters, including a FIFO
suffix when present. These checks do not verify resource existence or permissions.

## Default Event Bus

EventBridge includes a default event bus (`default`) that accepts events from AWS services. Custom buses are for your own application events.

Secrets Manager `PutSecretValue` and `UpdateSecret` calls publish `AWS API Call via CloudTrail`
events here when a logging CloudTrail trail selects management writes. See
[secret write events](secrets-manager.md#secret-write-events) for the required trail configuration
and a pattern matching `detail.responseElements.arn`.

```bash
# List rules on the default bus
aws events list-rules --endpoint-url $AWS_ENDPOINT_URL

# Send to default bus
aws events put-events \
  --entries '[{"Source":"myapp","DetailType":"test","Detail":"{}"}]' \
  --endpoint-url $AWS_ENDPOINT_URL
```

## Partner Event Buses

`CreateEventBus` with `EventSourceName` creates a partner event bus. As in AWS, the bus takes the
source's name, so `Name` must equal `EventSourceName`, and the name must match
`aws.partner/<partner>/<source>`. Floci does not model partner event sources, so any well-formed
source name is accepted without one being created or activated first. Once created, the bus works
like a custom bus: rules, targets, tags and `PutEvents` address it by name or ARN. A rule name
can't contain a slash on any bus: `PutRule` rejects a name outside `[\.\-_A-Za-z0-9]+` or longer
than 64 characters with `ValidationException`, as AWS does.

Two calls differ from a custom bus. `PutPermission` and `RemovePermission` reject a partner bus
with `ValidationException`, since the API types their `EventBusName` as a non-partner bus name.
`PutEvents` to a partner bus is accepted and fans out to its rules, where AWS reserves that bus for
the partner's own events; this lets a test publish an event a partner would send.

When upgrading, a rule persisted by an earlier Floci version under a name `PutRule` now rejects,
such as one with a slash or a space or longer than 64 characters, is still reachable by its exact
name on its own bus, and it keeps `DeleteEventBus` from deleting that bus. One with a slash in its
name is also no longer listed by `ListRules` or matched by `PutEvents`. On a custom bus it can still
be tagged by its ARN, but on the default bus it cannot: an ARN such as `rule/team/rule-1` reads as
rule `rule-1` on bus `team`, so that rule is reachable by its exact name for the rule and target
operations only. On a custom bus named `aws.partner`, a rule such as `example.com/orders/r1` shares
its storage key and ARN with rule `r1` on partner bus `aws.partner/example.com/orders`. That partner
bus does not list, match or manage the legacy rule, and `PutRule` of `r1` on it fails with
`ValidationException` until the legacy rule is deleted. A CloudFormation stack holding any of these legacy rules can still be deleted, but
an `UpdateStack` that touches the rule fails, since `PutRule` rejects its name. Remove its targets,
delete it and recreate it under a valid name. `DescribeRule`, `DeleteRule`, `EnableRule`,
`DisableRule`, `PutTargets`, `RemoveTargets` and `ListTargetsByRule` otherwise reject an invalid
rule name as `PutRule` does.

```bash
aws events create-event-bus \
  --name aws.partner/example.com/123/orders \
  --event-source-name aws.partner/example.com/123/orders \
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

## AppSync Targets

A rule can invoke a mutation on a public AppSync GraphQL API configured for `AWS_IAM`,
either as its default or an additional authorization mode. Schema authorization directives
still apply. Set the target `Arn` to the GraphQL **endpoint ARN**,
`arn:aws:appsync:us-east-1:000000000000:endpoints/graphql-api/<api-id>`.
CloudFormation exposes this through `Fn::GetAtt: [Api, GraphQLEndpointArn]`.

```bash
aws events put-targets \
  --rule order-placed-rule \
  --targets '[{
    "Id": "publish-order",
    "Arn": "arn:aws:appsync:us-east-1:000000000000:endpoints/graphql-api/example",
    "RoleArn": "arn:aws:iam::000000000000:role/EventBridgeAppSyncRole",
    "AppSyncParameters": {
      "GraphQLOperation": "mutation Publish($id: ID!) { publish(id: $id) { id } }"
    },
    "InputTransformer": {
      "InputPathsMap": {"id": "$.detail.orderId"},
      "InputTemplate": "{\"id\": <id>}"
    }
  }]' \
  --endpoint-url $AWS_ENDPOINT_URL
```

The delivered input must be a JSON object containing the mutation's variables. `Input`,
`InputPath`, and `InputTransformer` work as for other targets; with none, the full event is
the variables object. `PutTargets`, `ListTargetsByRule`, persisted target settings, and
`AWS::Events::Rule` preserve `AppSyncParameters.GraphQLOperation`. Endpoint targets require
these settings and a parsed document containing exactly one mutation operation, with 1 to
1,048,576 characters. `PutTargets` validates the whole batch before storage, so a rejected
replacement preserves the previous targets. Validation errors omit the operation's sensitive
contents. Parsing uses the GraphQL sidecar and requires Docker; it does not require the target
API to exist or validate its schema fields during `PutTargets`.
CloudFormation rollback restores previously accepted targets without requiring the GraphQL
sidecar to be available.

With IAM enforcement enabled, the target role must exist and trust `events.amazonaws.com`,
including any trust conditions on the rule's `aws:SourceArn` and `aws:SourceAccount`. Its
policies, permissions boundary, and SCPs must allow `appsync:GraphQL` on the selected API
fields. The permission resources use `apis/<api-id>/...`, rather than the endpoint ARN;
`arn:aws:appsync:us-east-1:000000000000:apis/<api-id>/*` covers all fields of one API.
Resolvers receive the target role's identity, and their data sources retain their own
service-role authorization.
With IAM enforcement off, target role existence, trust and permission checks are skipped,
matching the other service-role integrations.

Only mutations are delivered. Private APIs and cross-account or cross-partition targets
are refused. The target ARN selects the API's region; an API with the same ID in another
region is not used. AppSync must be enabled. This target does not add realtime subscription
support to Floci's AppSync endpoint.

Pre-execution validation and authorization failures use the target's dead-letter handling;
retryable transport failures use its retry policy. Floci treats a returned GraphQL execution
result, including field errors and partial data, as delivered so it does not retry a mutation
that may have already written data. A GraphQL request error with no `data` member is a failed
delivery. This error mapping has integration coverage but has not been compared with live AWS.

## Redshift Data API Targets

A matching rule can submit one SQL statement to the Redshift Data API for a provisioned cluster, or
multiple statements as one batch. A Redshift Serverless workgroup ARN is also supported. Configure
the database and SQL in `RedshiftDataParameters`; `WithEvent` publishes the statement's terminal
status event through Redshift Data API's existing EventBridge integration.

```bash
aws events put-targets \
  --rule refresh-warehouse \
  --targets '[{
    "Id": "refresh-redshift",
    "Arn": "arn:aws:redshift:us-east-1:000000000000:cluster:warehouse",
    "RoleArn": "arn:aws:iam::000000000000:role/EventBridgeRedshiftRole",
    "RedshiftDataParameters": {
      "Database": "dev",
      "DbUser": "admin",
      "Sql": "refresh materialized view daily_totals",
      "StatementName": "refresh-warehouse"
    }
  }]' \
  --endpoint-url $AWS_ENDPOINT_URL
```

`Sqls` selects `BatchExecuteStatement`; otherwise the target submits `Sql` with
`ExecuteStatement`. `SecretManagerArn` maps to the Data API's `SecretArn`. The target's ARN provides
the region used for execution; statements run in the rule's account. The stored `RoleArn` is passed
as the Data API principal.

With IAM enforcement on (`FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED`), a delivery runs only when the role
of the target can do what EventBridge does with it:

- the role belongs to the rule's account and trusts `events.amazonaws.com`. A trust condition on
  `aws:SourceArn` is matched against the rule's ARN, and `aws:SourceAccount` against the rule's account;
- it is allowed `redshift-data:ExecuteStatement`, or `redshift-data:BatchExecuteStatement` with `Sqls`,
  on the cluster or workgroup ARN of the target;
- it is allowed the action that authenticates the statement: `secretsmanager:GetSecretValue` on the
  secret for `SecretManagerArn`, `redshift-serverless:GetCredentials` on the workgroup for a Serverless
  target, or `redshift:GetClusterCredentials` on the database user and the database for `DbUser`.

The role's identity policies, permissions boundary and the account's service control policies all apply.
A refused delivery fails with `AccessDeniedException`, which the dispatcher records as `NO_PERMISSIONS`
and sends to the dead-letter queue without retrying. A target with no role is refused as well. The role is
checked before a bad JSONPath in `Sql`, `Sqls` or `StatementName` is reported, but after the paths in
`Database`, `DbUser` and `SecretManagerArn` are resolved, since the permissions depend on them. A
`SecretManagerArn` that is not an ARN adds no permission to check: the Data API rejects it as an invalid
`SecretArn`. The check
does not cover `redshift:GetClusterCredentialsWithIAM`, and it does not assume the role: the statement
runs as the stored `RoleArn`. With enforcement off the role is not checked.

String parameters, including entries in `Sqls`, can be complete JSONPath references such as
`$.detail.database`, `$.detail.query` or `$.detail.queries[0]`. Paths resolve against the original event, before
`Input`, `InputPath` or `InputTransformer`; embedded paths inside SQL text are left unchanged.
Dispatch is rejected when the `redshift-data` service is disabled.

## Target Roles

`PutTargets` stores each target's optional `RoleArn`, and `ListTargetsByRule`
returns it when set. It is independent of the rule's `RoleArn`. An explicit
role update replaces the stored value. Omitting the role on an existing
cross-account event-bus target with the same ID and ARN retains its previous
role, as documented by AWS.

The value must be between 1 and 1600 characters when supplied.
With IAM enforcement on, [AppSync targets](#appsync-targets) and
[Redshift Data API targets](#redshift-data-api-targets) check the role's existence,
trust and permissions, including source-rule conditions. With enforcement off,
these checks are skipped. Other target types store the role as
configuration without assuming it or enforcing its IAM policies.

## API Destination Targets

A matching rule can send events directly to HTTP or HTTPS endpoints via API destinations. An API destination pairs an HTTP endpoint URL and HTTP method with an EventBridge Connection for authentication (API key, basic auth, or OAuth client credentials).

`ListConnections` applies its name-prefix and state filters before returning
name-ordered pages. `Limit` accepts 1 to 100; Floci uses 100 when it is omitted.
Pass the returned `NextToken` with the same filters to continue. Tokens are scoped
to the action, account, region, prefix and state; final pages omit the token.
A cursor remains resumable after its connection is deleted. Token lengths must
be 1 to 2048 characters, and unrecognized cursors return `InvalidToken`.

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
- An `AWS::Events::Rule` target in a CloudFormation template keeps its `RetryPolicy`, `DeadLetterConfig`, `RoleArn`, `EcsParameters` and `AppSyncParameters`, which reach the rule as `PutTargets` would set them. A value of another type than the resource schema declares for them fails the rule before it is created, with the message CloudFormation gives.

## Event Bus Policies under IAM Enforcement

With `floci.services.iam.enforcement-enabled` on, a bus's policy takes part in the decision as IAM's
policy evaluation logic describes. `PutPermission` with a `Principal` account id adds a statement
naming that account's root, `{"AWS": "arn:aws:iam::<account>:root"}`, and `*` stays `*`. Within one
account a statement naming the caller's role or user, or `*`, can allow a call on its own, a statement
naming only the account leaves it to the caller's identity policies, and an explicit `Deny` in either
refuses it. Across accounts the bus policy and the caller's identity policies both have to allow it.

## Current Behavior

- `PutRule` validates schedule expressions before creating or updating a rule, including disabled rules and rules with an event pattern. Rate values must be positive integers, with singular `minute`, `hour` or `day` for 1 and plural units for larger values. Invalid expressions return `ValidationException` without changing an existing rule or its timer.
- `PutEvents` reports success once the source bus accepts an event. Target delivery failures are retried and dead-lettered as described above, and otherwise surface only as a `WARN` in the Floci logs.
- A `PutEvents` entry for an event bus that does not exist is answered as AWS answers it: with an event id, not counted in `FailedEntryCount`, and dropped, as no rule matches it. Floci logs the drop at `INFO`. Floci's own deliveries to a bus, for a rule, a pipe or a schedule, are unchanged: an entry for a bus that does not exist fails there.
- A `Detail` forwarded to an event bus must be a JSON object, as in AWS; anything else is dropped, including an `InputPath` selecting a scalar such as `$.detail.orderId` or an envelope carrying `"detail": null`.
- A bus ARN naming another account is forwarded under that account, so the target bus and its rules resolve there.
- Onward delivery from that bus follows each target type: SQS and Step Functions resolve cross-account, while Lambda, SNS, Batch and Firehose resolve in the caller's account.
- An event is forwarded between buses only once, matching AWS: a bus that received an event from another bus does not forward it on to a third. The second hop is dropped with only a `WARN` rather than reported to the caller.
