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
| `TagLogGroup` | Tag a log group |
| `UntagLogGroup` | Remove tags |
| `ListTagsLogGroup` | List tags |
| `TagResource` | Tag a log group by ARN |
| `UntagResource` | Remove tags from a log group by ARN |
| `ListTagsForResource` | List tags for a log group ARN |
| `PutSubscriptionFilter` | Create or update a subscription filter (stored only, see note below) |
| `DescribeSubscriptionFilters` | List subscription filters on a log group |
| `DeleteSubscriptionFilter` | Delete a subscription filter |
| `GetDataProtectionPolicy` | Return the resolved log group identifier (see note below) |
| `StartQuery` | Start a Logs Insights query (see [Logs Insights](#logs-insights)) |
| `GetQueryResults` | Get the status and results of a Logs Insights query |
| `StopQuery` | Stop a query that has not completed yet |

Log group deletion protection defaults to disabled and is persisted with the log group. When it is
enabled, `DeleteLogGroup` returns `ValidationException` until protection is explicitly disabled
with `PutLogGroupDeletionProtection`.

Two actions are currently simplified:

- **`PutSubscriptionFilter`** stores the filter so that `DescribeSubscriptionFilters`
  returns it, but log events are **not** forwarded to the destination ARN. Lambda,
  Kinesis, and Firehose subscription destinations are not wired up.
- **`GetDataProtectionPolicy`** does not model data-protection policies. It returns
  HTTP 200 with the resolved `logGroupIdentifier` and no `policyDocument` — including for
  a log group that does not exist, where real AWS returns `ResourceNotFoundException`.

### Logs Insights {#logs-insights}

`StartQuery` / `GetQueryResults` / `StopQuery` run a **subset** of the Logs Insights
query language. Supported commands:

| Command | Notes |
|---|---|
| `fields a, b, ...` | Projection. Defaults to `@timestamp, @message`. `display` is an alias |
| `filter <field> = 'v'` | Equality. `!=` and `==` are also accepted; `where` is an alias |
| `sort <field> [asc\|desc]` | `order` is an alias |
| `dedup <field, ...>` | Keeps the first row per unique tuple, applied after sorting |
| `limit N` | The effective cap is the smallest of this value, the `StartQuery` `limit` parameter, and `FLOCI_SERVICES_CLOUDWATCHLOGS_MAX_EVENTS_PER_QUERY` |

Fields may be `@timestamp`, `@message`, `@ingestionTime`, `@ptr`, or a dotted path into
a JSON log message (for example `level` or `params.job_id`). A `@ptr` column is always
included in each result row, appended unless `fields` already names it.

Unsupported syntax never fails the query, so it is worth knowing how each case degrades:

| Input | Result |
|---|---|
| An unsupported command (`stats`, `parse`, ...) | Skipped with a warning in the server log. No aggregation happens |
| A `filter` whose operator is not `=`, `!=` or `==` — for example `<`, `<=`, `>`, `>=`, `like /ERROR/` or `=~ /ERROR/` | The whole stage is dropped with a warning, so **every** row is returned |
| A `filter` combining conditions with `and` / `or` — for example `filter level = 'ERROR' and status = 200` | Only the leftmost operator is parsed; the rest of the line becomes the compared value, so nothing matches and you get **no** rows. No warning is logged |
| A projected field that does not exist | Rendered as an empty string. No warning |
| A `sort` direction other than `asc` / `desc` | Treated as ascending. No warning |

In short, a query can come back either wider or narrower than intended without any error. When a
result set looks wrong, check the server log for `Ignoring unsupported Logs Insights ...` — and note
that the compound-filter case above produces no log line at all.

For simple substring matching, `FilterLogEvents` is the more predictable option today. Note that
Floci matches `--filter-pattern` as a plain substring of the message; the real filter-pattern
syntax (`?ERROR ?WARN`, `{ $.level = "ERROR" }`, and so on) is not parsed.

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

**Protocol:** Query (XML) and JSON 1.1 (both supported)
**Endpoint:** `POST http://localhost:4566/`

### Supported Actions

| Action | Description |
|---|---|
| `PutMetricData` | Publish custom metrics |
| `ListMetrics` | List available metrics |
| `GetMetricStatistics` | Get metric statistics (Average, Sum, etc.) |
| `GetMetricData` | Query metrics with math expressions |
| `PutMetricAlarm` | Create a metric alarm |
| `DescribeAlarms` | List alarms |
| `DeleteAlarms` | Delete alarms |
| `SetAlarmState` | Manually set alarm state |

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
