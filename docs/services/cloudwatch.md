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

### Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_CLOUDWATCHLOGS_ENABLED` | `true` | Enable or disable the CloudWatch Logs service |
| `FLOCI_SERVICES_CLOUDWATCHLOGS_MAX_EVENTS_PER_QUERY` | `10000` | Maximum events returned per `FilterLogEvents` / `GetLogEvents` call |

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
| `PutMetricStream` | Create or update a metric stream |
| `GetMetricStream` | Read a metric stream definition |
| `DeleteMetricStream` | Delete a metric stream |
| `ListMetricStreams` | List metric streams |
| `StartMetricStreams` / `StopMetricStreams` | Move streams between `running` and `stopped` |

Metric streams are stored as definitions only — Floci never delivers metrics to the
configured Firehose delivery stream.

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
