# Storage Modes

Floci supports four storage backends. You can set a global default and override it per service.

## Modes

| Mode | Data survives restart | Write performance | Use case |
|---|---|---|---|
| `memory` | No | Fastest | Unit tests, CI pipelines |
| `persistent` | Yes | Synchronous disk write on every change | Development with durable state |
| `hybrid` | Yes | In-memory reads, async flush to disk | General local development |
| `wal` | Yes | Append-only write-ahead log with compaction | High-write workloads |

## Global Configuration

```yaml title="application.yml"
floci:
  storage:
    mode: hybrid              # default for all services
    persistent-path: ./data   # base directory for all persistent data
    wal:
      compaction-interval-ms: 30000
```

## Per-Service Override

When `mode` is omitted for a service, it inherits the global `storage.mode`. Only set a per-service mode when you need a different behaviour for that service.

```yaml title="application.yml"
floci:
  storage:
    mode: memory              # default for all services
    services:
      dynamodb:
        mode: persistent      # DynamoDB uses persistent; everything else uses memory
        flush-interval-ms: 5000
      s3:
        mode: hybrid          # S3 uses hybrid; everything else uses memory
```

## Per-Service Storage Overrides

Override the global mode for individual services via environment variables. When not set, the service inherits `FLOCI_STORAGE_MODE`.

| Variable                                                        | Default        | Description                            |
|-----------------------------------------------------------------|----------------|----------------------------------------|
| `FLOCI_STORAGE_SERVICES_SSM_MODE`                               | global default | SSM storage mode                       |
| `FLOCI_STORAGE_SERVICES_SSM_FLUSH_INTERVAL_MS`                  | `5000`         | SSM flush interval (ms)                |
| `FLOCI_STORAGE_SERVICES_SQS_MODE`                               | global default | SQS storage mode                       |
| `FLOCI_STORAGE_SERVICES_S3_MODE`                                | global default | S3 storage mode                        |
| `FLOCI_STORAGE_SERVICES_DYNAMODB_MODE`                          | global default | DynamoDB storage mode                  |
| `FLOCI_STORAGE_SERVICES_DYNAMODB_FLUSH_INTERVAL_MS`             | `5000`         | DynamoDB flush interval (ms)           |
| `FLOCI_STORAGE_SERVICES_SNS_MODE`                               | global default | SNS storage mode                       |
| `FLOCI_STORAGE_SERVICES_SNS_FLUSH_INTERVAL_MS`                  | `5000`         | SNS flush interval (ms)                |
| `FLOCI_STORAGE_SERVICES_LAMBDA_MODE`                            | global default | Lambda storage mode                    |
| `FLOCI_STORAGE_SERVICES_LAMBDA_FLUSH_INTERVAL_MS`               | `5000`         | Lambda flush interval (ms)             |
| `FLOCI_STORAGE_SERVICES_CLOUDWATCHLOGS_MODE`                    | global default | CloudWatch Logs storage mode           |
| `FLOCI_STORAGE_SERVICES_CLOUDWATCHLOGS_FLUSH_INTERVAL_MS`       | `5000`         | CloudWatch Logs flush interval (ms)    |
| `FLOCI_STORAGE_SERVICES_CLOUDWATCHMETRICS_MODE`                 | global default | CloudWatch Metrics storage mode        |
| `FLOCI_STORAGE_SERVICES_CLOUDWATCHMETRICS_FLUSH_INTERVAL_MS`    | `5000`         | CloudWatch Metrics flush interval (ms) |
| `FLOCI_STORAGE_SERVICES_SECRETSMANAGER_MODE`                    | global default | Secrets Manager storage mode           |
| `FLOCI_STORAGE_SERVICES_SECRETSMANAGER_FLUSH_INTERVAL_MS`       | `5000`         | Secrets Manager flush interval (ms)    |
| `FLOCI_STORAGE_SERVICES_ACM_MODE`                               | global default | ACM storage mode                       |
| `FLOCI_STORAGE_SERVICES_ACM_FLUSH_INTERVAL_MS`                  | `5000`         | ACM flush interval (ms)                |

## Environment Variable Override

```bash
FLOCI_STORAGE_MODE=persistent
FLOCI_STORAGE_PERSISTENT_PATH=/app/data
```

## Recommended Profiles

=== "Fast CI"

    All in memory, fastest possible startup and test execution:

    ```yaml
    floci:
      storage:
        mode: memory
    ```

=== "Local development"

    Hybrid — survive restarts without slowing down writes:

    ```yaml
    floci:
      storage:
        mode: hybrid
        persistent-path: ./data
    ```

=== "Durable development"

    Persistent — every write is immediately on disk:

    ```yaml
    floci:
      storage:
        mode: persistent
        persistent-path: ./data
    ```