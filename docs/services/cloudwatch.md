# CloudWatch

Floci supports both CloudWatch Logs and CloudWatch Metrics.

---

## Supported Actions

<!-- floci:actions:start -->
| Action |
| --- |
| `CreateLogGroup` |
| `DeleteLogGroup` |
| `DescribeLogGroups` |
| `CreateLogStream` |
| `DeleteLogStream` |
| `DescribeLogStreams` |
| `PutLogEvents` |
| `GetLogEvents` |
| `FilterLogEvents` |
| `PutRetentionPolicy` |
| `DeleteRetentionPolicy` |
| `TagLogGroup` |
| `UntagLogGroup` |
| `ListTagsLogGroup` |
| `ListTagsForResource` |
| `TagResource` |
| `UntagResource` |
| `PutMetricData` |
| `ListMetrics` |
| `GetMetricStatistics` |
| `PutMetricAlarm` |
| `DescribeAlarms` |
| `DeleteAlarms` |
| `SetAlarmState` |
| `GetMetricData` |
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
