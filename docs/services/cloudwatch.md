# CloudWatch

Floci supports both CloudWatch Logs and CloudWatch Metrics.

---

## CloudWatch Logs

**Protocol:** JSON 1.1 (`X-Amz-Target: Logs.*`)
**Endpoint:** `POST http://localhost:4566/`

### Supported Actions

| Action | Description |
|---|---|
| `CreateLogGroup` | Create a log group |
| `DeleteLogGroup` | Delete a log group |
| `PutLogGroupDeletionProtection` | Enable or disable deletion protection for a log group by name or ARN |
| `DescribeLogGroups` | List log groups |
| `CreateLogStream` | Create a log stream inside a log group |
| `DeleteLogStream` | Delete a log stream |
| `DescribeLogStreams` | List log streams in a group |
| `PutLogEvents` | Write log events to a stream |
| `GetLogEvents` | Read log events from a stream |
| `FilterLogEvents` | Search log events with a filter pattern |
| `PutRetentionPolicy` | Set log retention (days) |
| `DeleteRetentionPolicy` | Remove log retention policy |
| `AssociateKmsKey` | Associate a KMS key with a log group (`logGroupName` or `resourceIdentifier`) |
| `DisassociateKmsKey` | Remove a log group's KMS key association |
| `TagLogGroup` | Tag a log group |
| `UntagLogGroup` | Remove tags |
| `ListTagsLogGroup` | List tags |
| `TagResource` | Tag a log group, cross-account destination, delivery source, delivery destination or delivery by ARN |
| `UntagResource` | Remove tags from a log group, cross-account destination, delivery source, delivery destination or delivery by ARN |
| `ListTagsForResource` | List tags for a log group, cross-account destination, delivery source, delivery destination or delivery ARN |
| `PutSubscriptionFilter` | Create or update a subscription filter (see [Subscription filters](#subscription-filters)) |
| `DescribeSubscriptionFilters` | List subscription filters on a log group |
| `DeleteSubscriptionFilter` | Delete a subscription filter |
| `PutMetricFilter` | Create or replace a metric filter on a log group (see [Metric filters](#metric-filters)) |
| `DescribeMetricFilters` | List metric filters by log group and name prefix, or by metric name and namespace |
| `DeleteMetricFilter` | Delete a metric filter |
| `TestMetricFilter` | Run a filter pattern over sample messages and return the matches with their extracted values |
| `PutResourcePolicy` | Create or update an account-level resource policy |
| `DescribeResourcePolicies` | List account-level resource policies |
| `PutDestination` | Create or update a cross-account subscription destination, with optional tags on creation |
| `PutDestinationPolicy` | Create or update the access policy for a destination |
| `PutAccountPolicy` | Create or update an account-level Logs policy |
| `DescribeAccountPolicies` | List account-level Logs policies by type and optional name |
| `GetDataProtectionPolicy` | Return the resolved log group identifier (see note below) |
| `StartQuery` | Start a Logs Insights query (see [Logs Insights](#logs-insights)) |
| `GetQueryResults` | Get the status and results of a Logs Insights query |
| `StopQuery` | Stop a query that has not completed yet |
| `PutQueryDefinition` | Create or replace a saved Logs Insights query definition |
| `DescribeQueryDefinitions` | List saved queries, with name prefix and query language filters and pagination |
| `DeleteQueryDefinition` | Delete a saved query definition by ID |
| `PutDeliverySource` | Create or replace a vended-log delivery source (see [Vended log delivery](#vended-log-delivery)) |
| `GetDeliverySource` | Return a delivery source |
| `DeleteDeliverySource` | Delete a delivery source no delivery uses |
| `DescribeDeliverySources` | List delivery sources, optionally only those for given resource ARNs |
| `PutDeliveryDestination` | Create or replace a delivery destination: a log group, S3 bucket, Firehose stream or X-Ray |
| `GetDeliveryDestination` | Return a delivery destination |
| `DeleteDeliveryDestination` | Delete a delivery destination no delivery uses |
| `DescribeDeliveryDestinations` | List delivery destinations, optionally only those with given names |
| `CreateDelivery` | Pair a delivery source with a delivery destination |
| `GetDelivery` | Return a delivery, with its record fields and S3 configuration |
| `UpdateDeliveryConfiguration` | Change a delivery's record fields, delimiter or S3 configuration |
| `DeleteDelivery` | Delete a delivery |
| `DescribeDeliveries` | List deliveries, optionally only those from given delivery sources |

`DescribeLogGroups` returns names in ASCII order, with up to 50 groups per page by default.
Set `limit` to an integer from 1 to 50 and pass the returned `nextToken` with the same region
and name prefix to continue. The final page omits `nextToken`.

`CreateLogGroup` stores `logGroupClass` as `STANDARD` (the default), `INFREQUENT_ACCESS`, or
`DELIVERY`. `DescribeLogGroups` returns the stored class and applies an optional `logGroupClass`
filter before pagination. Continue with the same class filter when using `nextToken`.
Stored groups without a class default to `STANDARD`. Class metadata does not change retention,
delivery behavior, or the features available on a group.

Log group deletion protection defaults to disabled and is persisted with the log group. When it is
enabled, `DeleteLogGroup` returns `ValidationException` until protection is explicitly disabled
with `PutLogGroupDeletionProtection`.

One action is currently simplified:

- **`GetDataProtectionPolicy`** does not model data-protection policies. It returns
  HTTP 200 with the resolved `logGroupIdentifier` and no `policyDocument`, including for
  a log group that does not exist, where real AWS returns `ResourceNotFoundException`.

`FilterLogEvents` reads `filterPattern` with the filter pattern syntax described below, the one
metric filters and subscription filters use: terms, quoted phrases, `?` alternatives, `-`
exclusions, JSON and space-delimited patterns. An invalid pattern is refused with
`InvalidParameterException`, and so is a request that names no log group by `logGroupName` or
`logGroupIdentifier`.

### Saved query definitions

`PutQueryDefinition`, `DescribeQueryDefinitions`, and `DeleteQueryDefinition` store and manage
saved Logs Insights queries, including the metadata used by Terraform's
`aws_cloudwatch_query_definition`. Definitions are isolated by account and region and use the
configured CloudWatch Logs storage mode, including persistence across restarts.

Create a definition with `name` and `queryString`; pass its returned `queryDefinitionId` to
update it. Updates replace the definition, so omitting `logGroupNames` or `parameters` clears
those lists. `queryLanguage` defaults to `CWLI`; `SQL` and `PPL` are also stored as metadata.
Query parameters are supported for `CWLI` definitions. Saving a definition does not execute or
validate its query syntax, and does not extend the query execution subset described below.
Log group names can be saved before their groups exist.

`clientToken` retries are scoped to the account and region and retain the original request and
returned ID independently of later updates. Repeating that request returns the same ID without
overwriting newer content; reusing its token with different input returns `InvalidParameterException`.
Floci retains these records until emulator reset, including after the definition is deleted, so
a delayed retry cannot recreate a deleted definition. They use the same storage mode as definitions.
During reset, saves and deletes return `ServiceUnavailableException` until the reset hooks resume writes.

`DescribeQueryDefinitions` accepts `queryDefinitionNamePrefix`, `queryLanguage`, and
`maxResults` from 1 to 1000. Pass the returned `nextToken` with the same account, region, and
filters for the next page; the final page omits it. Tokens expire after 24 hours or an emulator
restart. Updating or deleting an unknown definition returns `ResourceNotFoundException`.

### Vended log delivery {#vended-log-delivery}

Delivery sources, delivery destinations and the deliveries that pair them, which Terraform's
`aws_cloudwatch_log_delivery_source`, `aws_cloudwatch_log_delivery_destination` and
`aws_cloudwatch_log_delivery` manage, for example for an EventBridge bus's logs. Floci stores the
configuration and does not deliver the source's logs.

- `PutDeliverySource` and `PutDeliveryDestination` create the resource or replace every parameter
  of an existing one. A source reports the service of its resource's ARN (`events` for an event
  bus) and the `logType` it was given.
- A destination's `deliveryDestinationType` follows from its resource's ARN: `CWL` for a log group,
  `S3` for a bucket, `FH` for a Firehose stream. Only an X-Ray destination, which has no resource,
  names its type.
- `CreateDelivery` needs an existing source and destination, and one delivery per pair; a second is
  `ConflictException`. Its id is 16 letters and digits. For an EventBridge event bus source,
  `recordFields` defaults to the bus's ten template fields and `s3DeliveryConfiguration` to the
  `{region}/{yyyy}/{MM}/{dd}/{HH}/` suffix path, as AWS reports them. `GetDelivery` returns those;
  `DescribeDeliveries` leaves them out, as AWS does.
- A source or destination a delivery uses cannot be deleted (`ConflictException`).
- Tags are set on create and through `TagResource`, `UntagResource` and `ListTagsForResource` on the
  resource's ARN; the Get and Describe responses carry none, as on AWS.
- `DescribeDeliverySources` narrows to `resourceArns`, `DescribeDeliveryDestinations` to `names` and
  `DescribeDeliveries` to `deliverySourceNames`; a name that matches nothing is left out, not an error.
- Requests are checked against the Logs API model's lengths, patterns, enums and required members,
  with AWS's `ValidationException` wording, for example "1 validation error detected: Value at
  'limit' failed to satisfy constraint: Member must have value less than or equal to 50".
- An unknown name or id is `ResourceNotFoundException`, "Requested Delivery Source does not exist in
  this account." (likewise for a Delivery Destination and a Delivery).
- With IAM enforcement on, each action is authorized on the resources the AWS Service Authorization
  Reference lists for it: the source, destination or delivery it names, and for `CreateDelivery` the
  source, the destination and the new delivery; `DeleteDeliverySource` on the source it deletes. The
  Describe actions are authorized on `*`, as the reference lists no resource for them.

Not modelled: delivery destination policies (`PutDeliveryDestinationPolicy` and its siblings), and
record field defaults for sources other than an EventBridge event bus.

### Subscription filters {#subscription-filters}

Subscription filters deliver newly ingested log events to a downstream destination. Floci delivers
matching events to Lambda functions, Kinesis data streams, and Firehose delivery streams. Delivery
happens synchronously on the calling thread when `PutLogEvents` persists events. A destination error,
missing destination stream/function, or unresolvable destination ARN is logged and does not fail the
log ingestion request.

The delivered payload is a gzip-compressed JSON object containing `messageType` (`DATA_MESSAGE`),
`owner` (the account ID), `logGroup`, `logStream`, `subscriptionFilters` (the matched filter names),
and `logEvents` (an array of `{id, timestamp, message}`). Lambda receives the payload base64-encoded
under `awslogs.data`. Kinesis and Firehose receive the gzip-compressed JSON bytes directly as record data.

For Kinesis destinations, `distribution` controls the partition key: `ByLogStream` uses the log stream
name, while `Random` assigns a randomized UUID partition key. Cross-account Logs destinations
(`PutDestination`), IAM role permission checks, and the `CONTROL_MESSAGE` ping on filter creation
are deferred.

### Metric filters {#metric-filters}

A metric filter turns newly ingested log events into CloudWatch metric samples. Each matching
event contributes the literal `metricValue`, or its referenced field (`$.latency` in JSON,
`$size` in space-delimited logs), at the **event timestamp**, not ingestion or filter-creation time.
Events ingested before the filter existed are not replayed. Backdated and future event timestamps
remain backdated and future metric timestamps.

A configured `defaultValue` contributes **once per nonmatching event**, including alongside matches
in the same batch or minute. A matching event with a missing or JSON-null metric-value field also
uses the default; a present nonnumeric value is skipped, not replaced by the default. Without a
default, those nonmatches and missing/null values produce no sample. No events means no samples.
Healthy publication is immediate and needs no subsequent ingestion, reads, or timer. For example,
pattern `ERROR`, value 3 and default 7 produce Sum 13 / SampleCount 3 for `ERROR, INFO, ERROR`,
and three quiet nonmatches produce Sum 21 / SampleCount 3. A late match appends its value without
retracting earlier defaults. CloudWatch aggregates these contributions by period; Floci has no
minute-default accumulator or deduplication history.

Ordinary transformation dimensions are all-or-none: a complete configured set is emitted, while
a missing member produces a dimensionless sample, not a partial set. Requested system dimensions
(`@aws.account`, `@aws.region`) are populated on matches using the resolved receiving account and
region, including regular noncentralized Logs ingestion. **Pattern-nonmatch defaults are
dimensionless**, even when system dimensions are requested. These scenarios are supported by the
recorded [live AWS observations](cloudwatch-metric-filters-verification.md).

**Inferred emulator policy, not a live AWS claim:** matching missing/null metric-value fallbacks
retain requested system dimensions. Likewise, an incomplete ordinary dimension set is discarded
as a unit while requested system dimensions are retained. Null ordinary dimension values are
treated as missing; empty strings retain the existing scalar-extraction behavior. These combinations
were not measured by the recorded probes. Floci does not infer centralized source provenance.

**Publication runs off the request thread:** `PutLogEvents` evaluates the group's filters as they
are at that moment, builds one immutable sample per contributing event (canonical account, region,
group, filter, transformation snapshot, event timestamp and a stable internal publication ID) and
hands them to a process-wide queue. One managed worker writes them to the Metrics store, so
ingestion latency does not depend on the Metrics sink and a `PutMetricFilter` or a reset waits for
at most one sample write. The queue holds **100,000 samples**; a batch that does not fit is written
on the request thread under the filter-store monitor instead of being dropped, so accepted samples
are never lost to the bound and only an overfull queue makes the request wait on the sink.
A read issued immediately after `PutLogEvents` may run before the worker has written the sample,
as on AWS; SDK tests should poll. Shutdown drains the queue before storage shuts down.

**Runtime publication failures:** accepted Logs ingestion remains successful if the Metrics sink
reports a failure. Each failed sample keeps its snapshot and publication ID, and retries replace
that same metric-store key, making partial or ambiguous writes safe without deduplicating distinct
identical log events. The existing AWS `PutMetricData` APIs continue appending samples as before.

The same worker retries failed publications at one-second intervals while both Logs and Metrics
services are enabled; idle ticks never create defaults. The retry map holds at most **10,000
samples**, not 10,000 batches. This fixed conservative limit bounds outage memory without
introducing a configuration surface. When full, new failed samples are dropped with an **ERROR**
containing account, region, group, filter and dropped count; already queued samples and their IDs are
preserved. Successful retries and cancellations release capacity. Each filter-owned outage logs
one detailed stack, followed by a stack-free aggregate warning every 60 failed retry ticks.
Recovery, cancellation and reset release that suppression. This is bounded best effort, not
lossless delivery through an indefinite outage. Pending work is in memory only; crash-durable
pending publication is not implemented. Storage failures that a backend only logs rather than
throws are not observable to this retry path; configured storage durability semantics are unchanged.

A filter update or deletion affects new ingestion only: samples already accepted for a batch still
publish under the definition that was current at ingestion, as on AWS, and deleting a filter or
group cancels only its own account/region-scoped failed retries without retracting stored metrics.
Reset pauses publication, waits for the active sample write, discards queued and failed samples,
wipes storage and resumes the same live service. The canonical filter-store monitor serializes
each sample write with update/delete; reset hooks release it before the storage-factory wipe to
avoid lock inversion. Shutdown writes the queued samples, abandons failed retries and stops the
worker (up to five seconds to await executor termination). Storage backends expose synchronous
operations without a cancellation deadline; a stuck backend can delay that drain. Runtime
cancellation is not a crash-recovery guarantee.

Samples are readable with `GetMetricStatistics` and `GetMetricData`, and alarms evaluate normally.

The filter pattern syntax follows the AWS reference:

- Terms: `ERROR ARGUMENTS` (all present), `?ERROR ?ARGUMENTS` (any present), `ERROR -ARGUMENTS`
  (exclusion), `"exact phrase"`. Terms are case-sensitive substrings of the message.
- Regular expressions between percent signs, `%^[hc]at%`, with the operators and escapes AWS
  allows. Parentheses and characters outside ASCII are rejected, as on AWS.
- JSON patterns: `{ $.eventType = "UpdateTrail" && $.code >= 400 }`, with `=`, `!=`, `<`, `<=`,
  `>`, `>=`, `IS NULL`, `IS TRUE`, `IS FALSE`, `NOT EXISTS`, `&&`, `||`, parentheses, array indexes,
  `[*]` and `.*` wildcards, and `$.['a.b']` for a property with a dot in its name. AWS's wildcard
  quotas apply: one per property selector and three per pattern.
- Space-delimited patterns: `[ip, ..., status_code = 4*, bytes]`, with named fields, `...` for any
  number of fields, conditions on any field, `w1`/`w2` indicators, and `%regex%` values. Text
  between double quotes or square brackets is one field.

`PutMetricFilter` applies the rules AWS applies: the log group must exist, the pattern must parse,
at most two regular expressions per pattern, exactly one transformation whose `metricValue` is a
number or a single-valued field reference, at most three dimensions in total, and 100 metric
filters per log group. Scalar JSON metric-value and dimension references need not be mentioned
in the filter pattern. Wildcard metric-value references such as `$.values[*]` are rejected;
wildcards remain available in the filter pattern itself. Ordinary dimensions are
available only for JSON or space-delimited patterns and cannot be combined with a `defaultValue`;
system-only dimensions can. The shared group regex quota counts up to five **regex-bearing filters**
across metric and subscription filters, independently of the two expressions allowed per pattern.
This shared pool is documentation-backed, not a new live mixed-writer claim. `TestMetricFilter`
reports one-based event numbers. JSON matches have empty public `extractedValues`; space-delimited
matches expose named fields as `$name` and positional fields as `$1`, `$2` and so on.

`fieldSelectionCriteria` selects which batches a filter processes from the system fields
`@aws.account` and `@aws.region`, with `=`, `!=`, `IN`, `NOT IN` and the `AND` and `OR` the API
documents, as in `@aws.account IN ["111111111111"]`. Both fields describe the ingested batch rather
than the individual event, so one evaluation decides the batch. A criterion that does not parse is
rejected when the filter is stored.

`emitSystemFieldDimensions` adds `@aws.account` and `@aws.region` on matching contributions as
described above. They count toward the same combined limit of three dimensions.

`applyOnTransformedLogs` is stored and returned unchanged. Request acceptance was verified on AWS,
including `true` on the tested group without a group-level transformer. Floci does not execute
group-level or account-level log transformations, including stored `TRANSFORMER_POLICY`
configuration. Accepting the flag therefore does not establish transformed-log processing or
full compatibility with an AWS group that has an active transformer.

See [Metric-filter contract verification](cloudwatch-metric-filters-verification.md) for the
scenarios tagged **Verified on Live AWS**, the recorded fixtures and their evidence boundaries.

### Logs Insights {#logs-insights}

`StartQuery` accepts log group names and canonical log group ARNs in `logGroupIdentifiers`.
Floci also accepts the `:*` suffix and the bare trailing `:` produced when Grafana removes
the `*` from `DescribeLogGroups.arn`. Equivalent identifiers scan the group only once.
The bare-colon tolerance applies only to `logGroupIdentifiers`, requires a complete Logs ARN,
and does not rewrite values supplied through `logGroupName` or `logGroupNames`.
The bare-colon spelling is a client-compatibility tolerance: the
[AWS reference](https://docs.aws.amazon.com/AmazonCloudWatchLogs/latest/APIReference/API_StartQuery.html#CWL-StartQuery-request-logGroupIdentifiers)
documents the canonical ARN, not that spelling. This tolerance does not change other APIs' ARN handling.

`StartQuery` / `GetQueryResults` / `StopQuery` run a **subset** of the Logs Insights
query language. As on AWS, `StartQuery` rejects a `queryString` longer than 10,000 characters
with `InvalidParameterException`. Supported commands:

| Command | Notes |
|---|---|
| `fields a, b, ...` | Projection. Defaults to `@timestamp, @message`. `display` is an alias |
| `filter <field> = 'v'` | Equality. `!=` and `==` are also accepted; `where` is an alias. An unquoted value must be a number and compares by value, so `n = 2` matches `2` and `2.0`. Comparisons can be joined with `or` (any case) and grouped with parentheses, for example `filter (type = 'A' or type = 'B')` |
| `sort <field> [asc\|desc]` | `order` is an alias |
| `dedup <field, ...>` | Keeps the first row per unique tuple, applied after sorting |
| `limit N` | The effective cap is the smallest of this value, the `StartQuery` `limit` parameter, and `FLOCI_SERVICES_CLOUDWATCHLOGS_MAX_EVENTS_PER_QUERY` |

Fields may be `@timestamp`, `@message`, `@ingestionTime`, `@ptr`, or a dotted path into
a JSON log message (for example `level` or `params.job_id`). A `@ptr` column is always
included in each result row, appended unless `fields` already names it.

A command or filter operator outside this subset fails the query instead of being dropped:
`StartQuery` still returns a query id, and `GetQueryResults` reports `status: Failed` with no
rows. The reason is not part of the response (the AWS `GetQueryResults` shape has no such field);
it is written to the server log as `Logs Insights query <id> will fail: ...`. A few inputs still
degrade silently, so it is worth knowing how each case behaves:

| Input | Result |
|---|---|
| An unsupported command (`stats`, `parse`, ...) | The query fails. Logged as `Unsupported command: stats` |
| A `filter` whose operator is not `=`, `!=` or `==`, for example `<`, `<=`, `>`, `>=`, `like /ERROR/` or `=~ /ERROR/` | The query fails. Logged as `Unsupported filter expression: ...` |
| A `filter` using `and`, `not` or `\|\|`, or with text after the value, as in `filter level = 'ERROR' x` | The query fails. AWS evaluates `and` and `not`, ignores the trailing text, and rejects an unparenthesized `\|\|` at `StartQuery` with `MalformedQueryException` (use `or`) |
| A blank query, or an empty stage as in a trailing `\|` | The query fails. AWS rejects it at `StartQuery` |
| A `filter` with a backslash escape, a backtick-quoted field name, an expression such as `1+1` in place of the field, or an unquoted value that is not a number, such as `level = ERROR` or `n = 1+1` | The query fails. AWS interprets them |
| A projected field that does not exist | Rendered as an empty string. No warning |
| A `sort` direction other than `asc` / `desc` | Treated as ascending. No warning |

For matching events by a pattern, `FilterLogEvents` is the more predictable option today: it
applies the filter pattern syntax (`?ERROR ?WARN`, `{ $.level = "ERROR" }`, and so on) as AWS does.

### Reading events past the limit

`FilterLogEvents` and `GetLogEvents` both page, and they signal the end of the results differently
because the AWS APIs do.

`FilterLogEvents` pages forward only. Its `nextToken` is an `f/<index>` offset into the matched set,
so the offset counts matches, not stored events: a request narrowed by `--filter-pattern`,
`--start-time` or `--log-stream-names` pages through only what it matched. A missing token starts
from the oldest match, and **a response with no `nextToken` means pagination is finished**, so the
final page omits it. An unrecognized, non-numeric or negative token returns
`InvalidParameterException` (400). `startFromHead` is not supported, so results always run oldest
first.

`GetLogEvents` pages in both directions with `f/<index>` and `b/<index>`, and always returns
`nextForwardToken` and `nextBackwardToken`. It signals the end by returning the same token it was
given rather than by omitting it, which is what its SDK paginators expect.

### Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_CLOUDWATCHLOGS_ENABLED` | `true` | Enable or disable the CloudWatch Logs service |
| `FLOCI_SERVICES_CLOUDWATCHLOGS_MAX_EVENTS_PER_QUERY` | `10000` | Maximum events returned per `FilterLogEvents` / `GetLogEvents` call, and the upper bound for a Logs Insights `limit` |
| `FLOCI_SERVICES_CLOUDWATCHLOGS_QUERY_COMPLETION_DELAY_MS` | `0` | Artificial Logs Insights query delay. With `0` a query completes immediately. A positive value emulates the real asynchronous lifecycle (`Running` → `Complete` after the delay), which also makes `StopQuery` on a still-running query return `success=true` |

### Storage

Log groups, streams, filters and resource policies follow the configured storage mode as every
other service does. The event store is the one CloudWatch Logs store under steady append load, so
under `persistent` mode it is journaled instead of rewritten on every `PutLogEvents` call: a
batch is appended to `cwlogs-events.wal`, and `cwlogs-events.json` is rewritten from memory on
the `FLOCI_STORAGE_WAL_COMPACTION_INTERVAL_MS` cadence and at shutdown. The store keeps at most
20,000 events per account (`FLOCI_SERVICES_CLOUDWATCHLOGS_MAX_STORED_EVENTS`), which bounds both
the memory footprint and the size of each snapshot. `hybrid` remains the alternative when a
bounded delay for every CloudWatch Logs store is acceptable. See
[Storage Modes](../configuration/storage.md#journaled-stores-under-persistent-mode).

### Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a log group and stream
aws logs create-log-group --log-group-name /app/backend --endpoint-url $AWS_ENDPOINT_URL
aws logs create-log-stream \
  --log-group-name /app/backend \
  --log-stream-name 2025/01/app-1 \
  --endpoint-url $AWS_ENDPOINT_URL

# Write log events
TIMESTAMP=$(date +%s%3N)   # milliseconds
aws logs put-log-events \
  --log-group-name /app/backend \
  --log-stream-name 2025/01/app-1 \
  --log-events "[{\"timestamp\":$TIMESTAMP,\"message\":\"Service started\"}]" \
  --endpoint-url $AWS_ENDPOINT_URL

# Read log events
aws logs get-log-events \
  --log-group-name /app/backend \
  --log-stream-name 2025/01/app-1 \
  --endpoint-url $AWS_ENDPOINT_URL

# Search logs
aws logs filter-log-events \
  --log-group-name /app/backend \
  --filter-pattern "ERROR" \
  --endpoint-url $AWS_ENDPOINT_URL

# Run a Logs Insights query
QUERY_ID=$(aws logs start-query \
  --log-group-name /app/backend \
  --start-time $(($(date +%s) - 3600)) \
  --end-time $(date +%s) \
  --query-string 'fields @timestamp, @message | sort @timestamp desc | limit 20' \
  --query queryId --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

aws logs get-query-results \
  --query-id "$QUERY_ID" \
  --endpoint-url $AWS_ENDPOINT_URL

# Set retention
aws logs put-retention-policy \
  --log-group-name /app/backend \
  --retention-in-days 30 \
  --endpoint-url $AWS_ENDPOINT_URL
```

---

## CloudWatch Metrics {#metrics}

**Protocol:** Query (XML), JSON 1.0, and OTLP/HTTP.

The OTLP HTTP metrics endpoint accepts `POST /v1/metrics` with `application/json` or
`application/x-protobuf`, optionally gzip-compressed. Requests are stored separately from classic
CloudWatch metrics, retaining their OTLP resource, scope and metric data. The uncompressed request
limit is 5 MiB and a request may contain at most 1,000 data points. Floci checks each datapoint's
timestamp, the limit of 150 resource, scope and datapoint attributes combined, and the 40,000-byte
metadata limit. Attribute strings are limited to 1,024 UTF-16 code units. Storage follows the
`cloudwatchmetrics` storage configuration. Metric querying through PromQL is not yet supported, and
the endpoint does not yet implement every CloudWatch OTLP validation rule. Invalid requests return OTLP `google.rpc.Status`
bodies. When only part of a request is invalid, Floci returns an OTLP partial-success response and
stores only the accepted datapoints. Empty or untyped metrics produce a partial-success message
without increasing the rejected datapoint count.
Stored batches expire 14 days after ingestion and are removed on a later ingestion in the same account
and region. Cleanup runs at most once per minute for each account and region.
With IAM enforcement enabled, requests require AWS credentials and the `cloudwatch:PutMetricData`
permission. Bearer-token authentication for this endpoint is not implemented.

CloudWatch Query operations accept form-encoded POST requests and root GET requests with
URL query parameters. GET uses the existing handlers, signature validation and IAM policy checks. A `monitoring`
credential in the Authorization header or presigned URL selects CloudWatch, including
requests with browser Accept headers or unrelated target headers. Unsigned GET requests
select it only for unambiguous supported actions; ordinary S3, SNS and browser requests
retain their existing routing. Only a Query protocol claim can dispatch a CloudWatch GET;
an unclaimed request cannot perform a CloudWatch operation. Header-signed and presigned
requests use the account and region in their credential scope.
`Action` takes precedence over `Operation`.

Use `Version=2010-08-01` for current GET operations. Omitting the version selects the
legacy `2009-05-15` API, which supports only `ListMetrics` and `GetMetricStatistics`.
An invalid version or action returns HTTP 404 `InvalidAction`; an absent action returns
HTTP 404 `UnknownOperationException`. IAM policies use the `cloudwatch` action prefix.

JSON `PutMetricData` accepts a gzip-compressed body (`Content-Encoding: gzip`), which the AWS
CLI, boto3 and the other SDKs that call CloudWatch over JSON send by default for any body of
10 KiB or more. With signature validation and IAM enforcement on, the signature is checked over
the compressed bytes as sent and IAM decides the request before the body is decompressed. As on
the CBOR protocol, the decompressed body is limited to 10 MiB: a larger one returns HTTP 413
`PayloadTooLargeException`, a body that cannot be decompressed returns HTTP 400
`InvalidRequestException`, and an empty body reads as no input. Other JSON operations do not
decompress their body.

**Endpoint:** `http://localhost:4566/`. Query: GET or POST; JSON: POST.

JSON 1.0 requests name their operation in `X-Amz-Target` with the `GraniteServiceVersion20100801.`
prefix the AWS SDKs send, or with `CloudWatchVersion20130116.`, the prefix of the CloudWatch
console's API. Both get the same signature validation and IAM checks. The console prefix serves
four operations on the same metrics and alarms, with these request members: `ListMetrics`
(`Namespace`, `MetricName`, `Dimensions`, `NextToken`), `DescribeAlarms` (`AlarmNames`,
`AlarmNamePrefix`, `MaxRecords`, `NextToken`), `GetMetricStatistics` (`Namespace`, `MetricName`,
`Dimensions`, `StartTime`, `EndTime`, `Period`, `Statistics`, `Unit`) and `DeleteAlarms`
(`AlarmNames`). Every result comes back at once, so `MaxRecords` and `NextToken` change nothing.
It returns HTTP 400 `UnsupportedOperation` for every other operation, such as the console's
`GetMetricData` with `Defaults` and `Metrics`; for a request that sets any other member, such as
`StateValue`, `ExtendedStatistics` or the console's `CountOnlyRequest`, to a value other than
`false` or `null`; and for a `DescribeAlarms` answer that would list an alarm defined with
`Metrics`, whose items the console's API shapes differently. Such an alarm makes every listing
that includes it fail, so filter by `AlarmNames` or `AlarmNamePrefix` to list the others.
The console prefix is JSON only: a CBOR request naming it returns HTTP 404
`UnknownOperationException`, and a Smithy RPC v2 request HTTP 404. Only the bare prefix over
`application/x-amz-json-1.0` is accepted; the fully qualified
`com.amazonaws.cloudwatch.v2013_01_16.CloudWatchVersion20130116.` form over `application/json`
returns HTTP 404.

### Supported Actions

| Action | Description |
|---|---|
| `PutMetricData` | Publish custom metrics |
| `ListMetrics` | List available metrics |
| `GetMetricStatistics` | Get metric statistics (Average, Sum, etc.) |
| `GetMetricData` | Query metrics with math expressions |
| `PutMetricAlarm` | Create a metric alarm |
| `PutCompositeAlarm` | Create or update a composite alarm rule |
| `DescribeAlarms` | List alarms |
| `DeleteAlarms` | Delete alarms |
| `SetAlarmState` | Manually set alarm state |
| `DescribeAlarmHistory` | List alarm state transitions and configuration changes (see [Alarm history](#alarm-history)) |
| `PutMetricStream` | Create or update a metric stream definition |
| `GetMetricStream` | Read a metric stream definition |
| `ListMetricStreams` | List metric streams |
| `DeleteMetricStream` | Delete a metric stream |
| `StartMetricStreams` | Move metric streams to `running` |
| `StopMetricStreams` | Move metric streams to `stopped` |
| `PutDashboard` | Create a dashboard, or replace its body when the name is taken (tags apply on create only) |
| `GetDashboard` | Read a dashboard body and ARN |
| `ListDashboards` | List dashboards, optionally by name prefix |
| `DeleteDashboards` | Delete dashboards by name |
| `TagResource` | Tag an alarm or dashboard by ARN |
| `UntagResource` | Remove tags from an alarm or dashboard |
| `ListTagsForResource` | List the tags of an alarm or dashboard |

Metric streams are stored as definitions with their `running` or `stopped` state. Floci never
delivers metrics to the Firehose delivery stream a metric stream names.

SQS publishes its queue metrics under `AWS/SQS` once a minute; see
[SQS CloudWatch Metrics](sqs.md#cloudwatch-metrics).

Composite alarms support boolean rules over alarm states. Request `AlarmTypes` containing
`CompositeAlarm` when describing them; the default describes metric alarms. Composite
action lists are stored but not executed. Action suppression is not implemented and is
rejected with `ValidationError`.

### Metric alarm updates

Updating a metric alarm preserves its current state, state reason, state timestamps and tags.
Tags supplied to `PutMetricAlarm` apply only when creating an alarm; use `TagResource` and
`UntagResource` to change existing tags. `SetAlarmState` requires `StateReason` and rejects
missing or unknown state values before changing the alarm.

`StateTransitionedTimestamp` records when an alarm's `StateValue` last changed, while
`StateUpdatedTimestamp` moves on every state update, including one that keeps the value.
Alarms stored before Floci recorded it report their last state update.

`SetAlarmState` enforces the modeled maximum lengths of 1023 characters for
`StateReason` and 4000 characters for `StateReasonData` before changing alarm state.
Length checks count Unicode code points and preserve empty and whitespace values.

### Composite alarm evaluation

A composite alarm is evaluated when it is created, when `PutCompositeAlarm` updates it, and when
one of its children changes state, up through nested composite alarms. `PutMetricAlarm` on an
existing child, or a `SetAlarmState` to the state a child already has, is not a change of state,
so a composite alarm set with `SetAlarmState` keeps that state until a child really changes. An
update that leaves the state unchanged also keeps the state reason, reason data and timestamps.
A composite alarm on a cycle of rules is not evaluated, not even by the update that closes the
cycle, where CloudWatch evaluates that alarm and the ones after it. One above a cycle is still
evaluated when one of its children changes state. A change of state records the `StateReason`
CloudWatch writes:

- `<alarm ARN> was created and its alarm rule evaluates to OK` when the alarm is created,
- `<alarm ARN> was updated and its new alarm rule evaluates to ALARM` when an update changes it,
- `<child ARN> transitioned to ALARM at Monday 14 September, 2026 06:29:58 UTC` when a child does.

`StateReasonData` lists under `triggeringAlarms` the children whose state agrees with the new one,
the most recently changed first, each with its state and when it took it. After a child's change
it lists only the children that changed state since the composite alarm last did. The list stops
before the 4000 characters `StateReasonData` can hold, counted in Unicode code points like the
length checks. Floci keeps state times to the second, so the timestamps end in `.000`.

Every alarm a rule names must exist, or `PutCompositeAlarm` fails with `ValidationError` and
`Could not save the composite alarm as alarms [<ARN>] in the alarm rule do not exist`.
`DeleteAlarms` deletes nothing when it names more than one composite alarm
(`Cannot delete [2] Composite Alarms as only one composite alarm delete is allowed in single request`)
or an alarm that a composite alarm's rule names, even when it deletes that composite alarm too
(`Cannot delete <name> as there are composite alarm(s) depending on it.`), so delete composite
alarms before their children. Names that match no alarm are ignored.

### SNS alarm actions

State transitions publish to same-account standard SNS topics in `AlarmActions`, `OKActions`, or
`InsufficientDataActions`, including transitions from `SetAlarmState`. Unchanged states and
`ActionsEnabled=false` suppress notifications. Scaling action retries remain independent.
Failures are logged per target without blocking other targets. Notifications include a
single-metric `Trigger`; durable retries, FIFO topics, and metric-math payloads are not covered.

### Metric-math alarm definitions

`PutMetricAlarm` stores the `Metrics` query definitions and `DescribeAlarms` returns them over
both JSON and Query protocols. Expressions, labels, `ReturnData`, account IDs, query periods,
and nested metric statistics (including dimensions and units) survive storage reloads.
Metric-math alarm responses omit the single-metric fields, including top-level `Period`.
Updating an alarm replaces its metric definition, so changing between `Metrics` and a single
metric does not retain the previous definition.

This is management-plane support: the alarm evaluator currently evaluates only single-metric
alarms, not metric-math expressions. `SetAlarmState` remains available for manual transitions.

### DescribeAlarms filters {#describealarms-filters}

`DescribeAlarms` applies every filter a request gives: `AlarmNames` or `AlarmNamePrefix`,
`AlarmTypes`, `StateValue` and `ActionPrefix`. `StateValue` and `ActionPrefix` narrow metric and
composite alarms alike. The API reference calls `ActionPrefix` a prefix for any action, so an alarm
matches when one of its `AlarmActions`, `OKActions` or `InsufficientDataActions` starts with it.

`ChildrenOfAlarmName` lists the metric and composite alarms a composite alarm's rule names, by name
or ARN, with only their name, ARN, `StateValue` and `StateUpdatedTimestamp`. `ParentsOfAlarmName`
lists the composite alarms whose rules name an alarm, with only their name and ARN. Neither lists
the alarm it names. Either one with any other member except `MaxRecords` and `NextToken` is refused
with `ValidationError`, as is a `StateValue` outside `OK`, `ALARM` and `INSUFFICIENT_DATA`, an
empty or over-long `ActionPrefix`, and an empty or over-long alarm name in either member.
`MaxRecords` and `NextToken` are accepted, but every match comes back in one response.

### Alarm history {#alarm-history}

`DescribeAlarmHistory` lists two kinds of items, newest first unless `ScanBy` is
`TimestampAscending`:

- `StateUpdate` for every real state transition, whether it comes from `SetAlarmState`, the alarm
  evaluator or a composite alarm's rule. Setting an alarm to the state it already has records
  nothing. `HistoryData` carries the old and new state with their reasons and reason data.
- `ConfigurationUpdate` when `PutMetricAlarm` or `PutCompositeAlarm` creates or updates an alarm,
  and when `DeleteAlarms` deletes one. An update lists the previous value of each changed field
  under `originalUpdatedFields`.

History survives the deletion of its alarm and is kept for 30 days. Without `AlarmTypes` only
metric alarm history is listed; request `CompositeAlarm` to include composite alarms. `Action`,
`AlarmContributorStateUpdate` and `AlarmContributorAction` are accepted filters, but Floci never
records those items, so they match nothing. `MaxRecords` defaults to 50, and a `NextToken` issued
for the other `ScanBy` direction returns an empty page, as on AWS.

The alarm snapshots in `ConfigurationUpdate` items use Floci's field names, such as `okActions`
and `statistic`, where AWS uses its internal ones, such as `okactions` and `extendedStatistic`.

### Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Publish a custom metric
aws cloudwatch put-metric-data \
  --namespace MyApp \
  --metric-data '[{
    "MetricName": "RequestCount",
    "Value": 42,
    "Unit": "Count",
    "Dimensions": [{"Name":"Service","Value":"api"}]
  }]' \
  --endpoint-url $AWS_ENDPOINT_URL

# List metrics
aws cloudwatch list-metrics \
  --namespace MyApp \
  --endpoint-url $AWS_ENDPOINT_URL

# Get statistics
aws cloudwatch get-metric-statistics \
  --namespace MyApp \
  --metric-name RequestCount \
  --dimensions Name=Service,Value=api \
  --start-time $(date -u -v-1H +%Y-%m-%dT%H:%M:%SZ) \
  --end-time $(date -u +%Y-%m-%dT%H:%M:%SZ) \
  --period 300 \
  --statistics Sum \
  --endpoint-url $AWS_ENDPOINT_URL

# Create an alarm
aws cloudwatch put-metric-alarm \
  --alarm-name high-error-rate \
  --metric-name ErrorCount \
  --namespace MyApp \
  --statistic Sum \
  --period 60 \
  --threshold 10 \
  --comparison-operator GreaterThanThreshold \
  --evaluation-periods 1 \
  --endpoint-url $AWS_ENDPOINT_URL
```
