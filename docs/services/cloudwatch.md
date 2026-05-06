# CloudWatch

Floci supports both CloudWatch Logs and CloudWatch Metrics.

---

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
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
| `ListTagsForResource` |  |
| `TagResource` |  |
| `UntagResource` |  |
| `PutMetricData` | Publish custom metrics |
| `ListMetrics` | List available metrics |
| `GetMetricStatistics` | Get metric statistics (Average, Sum, etc.) |
| `PutMetricAlarm` | Create a metric alarm |
| `DescribeAlarms` | List alarms |
| `DeleteAlarms` | Delete alarms |
| `SetAlarmState` | Manually set alarm state |
| `GetMetricData` | Query metrics with math expressions |
<!-- floci:actions:end -->

## CloudWatch Logs

**Protocol:** JSON 1.1 (`X-Amz-Target: Logs.*`)
**Endpoint:** `POST http://localhost:4566/`

### Configuration

```yaml
floci:
  services:
    cloudwatchlogs:
      enabled: true
      max-events-per-query: 10000
```

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
