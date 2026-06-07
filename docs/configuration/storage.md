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

| Variable | Default | Description |
|---|---|---|
| `FLOCI_STORAGE_MODE` | `memory` | Storage backend (`memory`, `persistent`, `hybrid`, `wal`) |
| `FLOCI_STORAGE_PERSISTENT_PATH` | `./data` | Base directory for all persistent data |
| `FLOCI_STORAGE_WAL_COMPACTION_INTERVAL_MS` | `30000` | WAL compaction interval (milliseconds) |

!!! note "Code default vs shipped default"
    The Java `@WithDefault` for `storage.mode` is `hybrid`, but the published Docker image ships with `memory` set in `application.yml`. Running the stock image gives you `memory` unless you set `FLOCI_STORAGE_MODE`.

## Per-Service Override

When not set for a service, it inherits `FLOCI_STORAGE_MODE`. Only override when you need different behavior for a specific service.

| Variable | Default | Description |
|---|---|---|
| `FLOCI_STORAGE_SERVICES_SSM_MODE` | global default | SSM storage mode |
| `FLOCI_STORAGE_SERVICES_SSM_FLUSH_INTERVAL_MS` | `5000` | SSM flush interval (ms) |
| `FLOCI_STORAGE_SERVICES_SQS_MODE` | global default | SQS storage mode |
| `FLOCI_STORAGE_SERVICES_S3_MODE` | global default | S3 storage mode |
| `FLOCI_STORAGE_SERVICES_DYNAMODB_MODE` | global default | DynamoDB storage mode |
| `FLOCI_STORAGE_SERVICES_DYNAMODB_FLUSH_INTERVAL_MS` | `5000` | DynamoDB flush interval (ms) |
| `FLOCI_STORAGE_SERVICES_SNS_MODE` | global default | SNS storage mode |
| `FLOCI_STORAGE_SERVICES_SNS_FLUSH_INTERVAL_MS` | `5000` | SNS flush interval (ms) |
| `FLOCI_STORAGE_SERVICES_LAMBDA_MODE` | global default | Lambda storage mode |
| `FLOCI_STORAGE_SERVICES_LAMBDA_FLUSH_INTERVAL_MS` | `5000` | Lambda flush interval (ms) |
| `FLOCI_STORAGE_SERVICES_CLOUDWATCHLOGS_MODE` | global default | CloudWatch Logs storage mode |
| `FLOCI_STORAGE_SERVICES_CLOUDWATCHLOGS_FLUSH_INTERVAL_MS` | `5000` | CloudWatch Logs flush interval (ms) |
| `FLOCI_STORAGE_SERVICES_CLOUDWATCHMETRICS_MODE` | global default | CloudWatch Metrics storage mode |
| `FLOCI_STORAGE_SERVICES_CLOUDWATCHMETRICS_FLUSH_INTERVAL_MS` | `5000` | CloudWatch Metrics flush interval (ms) |
| `FLOCI_STORAGE_SERVICES_SECRETSMANAGER_MODE` | global default | Secrets Manager storage mode |
| `FLOCI_STORAGE_SERVICES_SECRETSMANAGER_FLUSH_INTERVAL_MS` | `5000` | Secrets Manager flush interval (ms) |
| `FLOCI_STORAGE_SERVICES_ACM_MODE` | global default | ACM storage mode |
| `FLOCI_STORAGE_SERVICES_ACM_FLUSH_INTERVAL_MS` | `5000` | ACM flush interval (ms) |
| `FLOCI_STORAGE_SERVICES_APPSYNC_MODE` | global default | AppSync storage mode |
| `FLOCI_STORAGE_SERVICES_APPSYNC_FLUSH_INTERVAL_MS` | `5000` | AppSync flush interval (ms) |
| `FLOCI_STORAGE_SERVICES_OPENSEARCH_MODE` | global default | OpenSearch storage mode |
| `FLOCI_STORAGE_SERVICES_OPENSEARCH_FLUSH_INTERVAL_MS` | `5000` | OpenSearch flush interval (ms) |
| `FLOCI_STORAGE_SERVICES_RDS_MODE` | global default | RDS metadata storage mode (see note below) |

!!! note "RDS storage mode"
    `FLOCI_STORAGE_SERVICES_RDS_MODE` controls Floci's own metadata persistence for RDS, not the
    DB container volumes. In all modes, each DB instance or cluster gets a named Docker volume
    (`floci-rds-{volumeId}`). In `memory` mode the volume is automatically removed when the
    instance is deleted. In other modes the volume is retained unless
    `FLOCI_STORAGE_PRUNE_VOLUMES_ON_DELETE=true`.

## Recommended Profiles

=== "Fast CI"

    All in memory — fastest possible startup and test execution:

    ```bash
    FLOCI_STORAGE_MODE=memory
    ```

=== "Local development"

    Hybrid — survive restarts without slowing down writes:

    ```bash
    FLOCI_STORAGE_MODE=hybrid
    FLOCI_STORAGE_PERSISTENT_PATH=/app/data
    ```

    Docker Compose:

    ```yaml
    volumes:
      - floci-data:/app/data
    environment:
      FLOCI_STORAGE_MODE: hybrid
      FLOCI_STORAGE_PERSISTENT_PATH: /app/data
    ```

=== "Durable development"

    Persistent — every write is immediately on disk:

    ```bash
    FLOCI_STORAGE_MODE=persistent
    FLOCI_STORAGE_PERSISTENT_PATH=/app/data
    ```

    Docker Compose:

    ```yaml
    volumes:
      - floci-data:/app/data
    environment:
      FLOCI_STORAGE_MODE: persistent
      FLOCI_STORAGE_PERSISTENT_PATH: /app/data
    ```

=== "Mixed"

    Keep most services in memory, persist only DynamoDB and S3:

    ```bash
    FLOCI_STORAGE_MODE=memory
    FLOCI_STORAGE_SERVICES_DYNAMODB_MODE=persistent
    FLOCI_STORAGE_SERVICES_S3_MODE=hybrid
    FLOCI_STORAGE_PERSISTENT_PATH=/app/data
    ```

## Container Storage (RDS, OpenSearch, MSK, ECR)

Services that spawn Docker containers (RDS, OpenSearch, MSK, ECR registry) need a volume for their
data. Floci manages this automatically using **named Docker volumes** — no extra configuration
required.

### How it works

Each resource gets a `volumeId` (a 6-character hex string, e.g. `a1b2c3`) generated at creation
time and stored in the resource model. The container name and volume name both use this suffix:

```
floci-rds-a1b2c3         # RDS instance container and volume
floci-opensearch-b4c5d6  # OpenSearch domain container and volume
floci-msk-e7f8a9         # MSK cluster container and volume
floci-ecr-registry-data  # ECR shared registry volume (singleton)
```

Volumes are labelled `floci=true` so you can manage them with standard Docker commands:

```bash
# List all Floci-managed volumes
docker volume ls --filter label=floci=true

# Remove all Floci-managed volumes (destructive)
docker volume prune --filter label=floci=true
```

### Volume lifecycle

By default volumes survive resource deletion, matching real AWS behavior.

| `FLOCI_STORAGE_MODE` | Volume on resource delete |
|---|---|
| `memory` | **Always removed** — memory mode implies no persistence across restarts |
| `persistent` / `hybrid` / `wal` | Retained (default) — remove with `FLOCI_STORAGE_PRUNE_VOLUMES_ON_DELETE=true` |

```bash
# Remove named volumes immediately when a resource is deleted (useful in CI with persistent mode)
FLOCI_STORAGE_PRUNE_VOLUMES_ON_DELETE=true
```

### Host-path mode (advanced)

Set `FLOCI_STORAGE_HOST_PERSISTENT_PATH` to an **absolute host path** to use bind mounts instead
of named volumes. This is only needed when you must access the container data directly from the
host filesystem.

```bash
FLOCI_STORAGE_HOST_PERSISTENT_PATH=/absolute/host/path/data
```

!!! warning
    `FLOCI_STORAGE_HOST_PERSISTENT_PATH` must be an absolute path (starting with `/`). Volume
    names and relative paths are not supported and will be silently ignored, falling back to
    named-volume mode.
