# CloudWatch RUM

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566`

Floci implements the CloudWatch RUM app-monitor management lifecycle for local SDK, CLI, and custom-resource workflows. App monitors are isolated by account and region and use the configured Floci storage mode.

## Supported Operations

| Operation | Method and path | Description |
|---|---|---|
| `CreateAppMonitor` | `POST /appmonitor` | Create an app monitor |
| `GetAppMonitor` | `GET /appmonitor/{name}` | Return an app monitor and its configuration |
| `UpdateAppMonitor` | `PATCH /appmonitor/{name}` | Update the supplied configuration fields |
| `DeleteAppMonitor` | `DELETE /appmonitor/{name}` | Delete an app monitor |
| `ListAppMonitors` | `POST /appmonitors` | List app-monitor summaries with pagination |

`ListAppMonitors` returns monitors in name order. Its default page size is 50 and its maximum page size is 100.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_RUM_ENABLED` | `true` | Enable or disable CloudWatch RUM |
| `FLOCI_STORAGE_SERVICES_RUM_MODE` | *(inherits global)* | Optional RUM storage-mode override |
| `FLOCI_STORAGE_SERVICES_RUM_FLUSH_INTERVAL_MS` | `5000` | Hybrid storage flush interval in milliseconds |

Unless the RUM-specific override is set, app-monitor state follows the global `FLOCI_STORAGE_MODE` setting. Persistent, hybrid, and write-ahead-log modes restore monitors after restart.

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws rum create-app-monitor \
  --name local-app \
  --domain example.com \
  --tags env1=test

aws rum get-app-monitor --name local-app
aws rum list-app-monitors

aws rum update-app-monitor \
  --name local-app \
  --domain-list updated.example.com localhost \
  --cw-log-enabled

aws rum delete-app-monitor --name local-app
```

## Current Scope

- App-monitor configuration, domains, platforms, create-time tags, state, timestamps, and pagination are modeled.
- `CwLogEnabled` is retained as control-plane configuration; Floci does not create a CloudWatch Logs group or copy RUM telemetry.
- Event and data-plane APIs are not implemented: `PutRumEvents` and `GetAppMonitorData`.
- Tag-management APIs are not implemented: `TagResource`, `UntagResource`, and `ListTagsForResource`.
- Resource-policy APIs are not implemented: `PutResourcePolicy`, `GetResourcePolicy`, and `DeleteResourcePolicy`.
- Metric-definition APIs are not implemented: `BatchCreateRumMetricDefinitions`, `BatchDeleteRumMetricDefinitions`, `BatchGetRumMetricDefinitions`, and `UpdateRumMetricDefinition`.
- Metric-destination APIs are not implemented: `PutRumMetricsDestination`, `DeleteRumMetricsDestination`, and `ListRumMetricsDestinations`.
