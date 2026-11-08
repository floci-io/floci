# Storage Modes

Floci supports four storage backends. You can set a global default and override it per service.

## Modes

| Mode | Data survives restart | Write performance | Use case |
|---|---|---|---|
| `memory` | No | Fastest | Unit tests, CI pipelines |
| `persistent` | Yes | Synchronous disk write on every change; append-heavy stores are journaled (see below) | Development with durable state |
| `hybrid` | Yes | In-memory reads, async flush to disk | General local development |
| `wal` | Yes | Append-only write-ahead log with compaction | High-write workloads |

### Atomic replacement batches

Services that need to replace related records together can use the opt-in
`StorageBackend.putAllAtomically` operation. Values must be copied before modification;
the operation does not make existing mutable objects immutable. Memory publication and
individual scans see the whole batch. Separate reads are not a transaction. Atomic batches
copy the store's key/value map before publishing it, so their memory and CPU cost grows
with the number of stored entries. Reads remain lock-free during snapshot writes and WAL
compaction; ordinary writes retain their existing storage policy.

Persistent storage writes the complete candidate snapshot before publishing it in memory.
WAL storage writes a single framed batch, so replay applies either the whole batch or none
of a truncated frame. Both report write errors to the caller. Hybrid storage swaps in the prepared
batch map and retains its configured asynchronous flush interval; an unflushed batch can still be lost on restart. Ordinary `putAll` keeps its
existing batching behavior and does not provide this atomicity guarantee. Partitioned
stores do not implement the opt-in operation.

WAL replay still accepts existing PUT and DELETE records. Older Floci versions cannot
read the new batch record format; do not downgrade a store containing batch records
without first making a backup and completing a clean shutdown that compacts its WAL.
This is a batch/replay guarantee, not a claim of hardware power-loss durability.

## Global Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_STORAGE_MODE` | `memory` | Storage backend (`memory`, `persistent`, `hybrid`, `wal`) |
| `FLOCI_STORAGE_PERSISTENT_PATH` | `./data` | Base directory for all persistent data |
| `FLOCI_STORAGE_WAL_COMPACTION_INTERVAL_MS` | `30000` | WAL compaction interval (milliseconds) |

!!! note "Code default vs shipped default"
    The Java `@WithDefault` for `storage.mode` is `hybrid`, but the published Docker image ships with `memory` set in `application.yml`. Running the stock image gives you `memory` unless you set `FLOCI_STORAGE_MODE`.

### Journaled stores under `persistent` mode

Most stores under `persistent` mode are rewritten in full on every change, which keeps the file
current after every call but makes the cost of one write grow with the size of the store.
Append-heavy stores are journaled instead: a change is appended to a `.wal` file next to the
store, and the store's JSON file is rewritten from memory on the `FLOCI_STORAGE_WAL_COMPACTION_INTERVAL_MS`
cadence and at shutdown, and only when something changed. Today this applies to CloudWatch Logs
events (`cwlogs-events.json` with `cwlogs-events.wal`), CloudWatch metric datapoints
(`cwmetrics.json` with `cwmetrics.wal`), which services such as SQS publish every minute, and the
S3 object index (`s3-objects.json` with `s3-objects.wal`), which holds the metadata of every
object in every bucket. After a clean shutdown the JSON file holds every entry; while Floci runs it can be up to
one compaction interval behind, and the journal is replayed on the next start. An existing
`cwlogs-events.json`, `cwmetrics.json` or `s3-objects.json` from an older version is picked up as the first snapshot
without any migration.

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
| `FLOCI_STORAGE_SERVICES_DYNAMODB_ITEM_LAYOUT` | `single-file` | `single-file` or `per-table` item files, see [DynamoDB](../services/dynamodb.md#item-layout-on-disk) |
| `FLOCI_STORAGE_SERVICES_DYNAMODB_ITEM_SHARDS` | `1` | Files per table with the `per-table` item layout |
| `FLOCI_STORAGE_SERVICES_SNS_MODE` | global default | SNS storage mode |
| `FLOCI_STORAGE_SERVICES_SNS_FLUSH_INTERVAL_MS` | `5000` | SNS flush interval (ms) |
| `FLOCI_STORAGE_SERVICES_LAMBDA_MODE` | global default | Lambda storage mode |
| `FLOCI_STORAGE_SERVICES_LAMBDA_FLUSH_INTERVAL_MS` | `5000` | Lambda flush interval (ms) |
| `FLOCI_STORAGE_SERVICES_CLOUDWATCHLOGS_MODE` | global default | CloudWatch Logs storage mode |
| `FLOCI_STORAGE_SERVICES_CLOUDWATCHLOGS_FLUSH_INTERVAL_MS` | `15000` | CloudWatch Logs flush interval (ms) |
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
    (`floci-aws-rds-{volumeId}`). In `memory` mode the volume is automatically removed when the
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
floci-aws-rds-a1b2c3         # RDS instance container and volume
floci-aws-opensearch-b4c5d6  # OpenSearch domain container and volume
floci-aws-msk-e7f8a9         # MSK cluster container and volume
floci-aws-ecr-registry-data  # ECR shared registry volume (singleton)
```

### Upgrading from a version that used the `floci-` prefix

Floci once named containers and volumes `floci-<service>-<id>`. Every Floci emulator shared that
prefix, so two of them on one Docker daemon could collide, and `docker volume prune --filter
label=floci=true` reached all of them at once. Each emulator now owns a prefix of its own, and this
one uses `floci-aws-`.

**Existing data is not moved and not lost.** A resource created before the change keeps its
`floci-` volume, and Floci records that name so it keeps resolving to it across future upgrades.
Its container is recreated under the new prefix, which is safe because containers hold no data.
Volumes with no record to consult, such as the ECR registry and EFS file systems, are found by
checking for the old name first.

Two consequences worth knowing:

- **Scripts that name containers must be updated**, for example `docker exec floci-rds-<id>`, and
  so must anything resolving a container by DNS on the Docker network.
- **Downgrading does not migrate back.** A resource created after the upgrade records a
  `floci-aws-` name that an older build will not look for.

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

### OpenSearch data volume override

`FLOCI_SERVICES_OPENSEARCH_DATA_VOLUME` replaces the derived per-domain volume for
OpenSearch domains. An absolute path bind-mounts `<path>/<account-id>/<region>/<domain-name>`;
any other value is a Docker volume name and each domain gets its own
`<name>-<account-id>-<region>-<domain-name>` volume. Account and Region come from the
domain's stored ARN, so identical domain names in different accounts or Regions use
separate storage. When `FLOCI_DOCKER_RESOURCE_NAMESPACE` is set, the layouts become
`<path>/<namespace>/<account-id>/<region>/<domain-name>` and
`<name>-<namespace>-<account-id>-<region>-<domain-name>`, separating namespaced deployments
sharing one Docker daemon as well. Storage named through this variable is
never removed on domain delete, regardless of storage mode and prune policy: the operator
named it, so its lifecycle is theirs. A volume Floci creates when absent carries no Floci
label, so neither a floci-scoped `docker volume prune --filter label=floci=true` nor a
sweep of Floci-labelled child resources, a testcontainers module reaping a run's labelled
children, removes it.

Bind paths refer to the Docker daemon's host. Linux paths starting with `/` retain Linux
path syntax when Floci runs on Windows; native Windows absolute paths retain their native
handling. Before mounting the directory, a temporary root container using the selected
OpenSearch image copies ownership from the image's data directory and grants the owner
write access. Only the directory itself is adjusted; existing files retain their ownership
and permissions. Preparation failure aborts domain startup.

```bash
# For domain my-search in account 000000000000 and Region us-east-1:
FLOCI_SERVICES_OPENSEARCH_DATA_VOLUME=opensearch-data
# Volume: opensearch-data-000000000000-us-east-1-my-search
FLOCI_SERVICES_OPENSEARCH_DATA_VOLUME=/srv/floci/opensearch
# Directory: /srv/floci/opensearch/000000000000/us-east-1/my-search
# With FLOCI_DOCKER_RESOURCE_NAMESPACE=team-a:
# Volume: opensearch-data-team-a-000000000000-us-east-1-my-search
# Directory: /srv/floci/opensearch/team-a/000000000000/us-east-1/my-search
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

### WAL write failures

Ordinary WAL puts, batches of puts, and deletions encode their records, then append and
flush through the buffered WAL writer before publishing the changed mappings in memory.
They do not force the log to disk on each change. A missing writer, encoding error, or
log write/flush failure is reported to the caller. A failed append is rolled back to its
previous log length. If rollback or the buffered writer cannot be trusted, further writes
are rejected until recovery rather than acknowledged only in memory.
Writes after a WAL backend has shut down are rejected as well; they cannot change the
acknowledged in-memory state or the closed log. Create a fresh `StorageFactory` to load
persisted state and resume writes.

The PUT and DELETE record formats are unchanged. `putAll` still writes separate PUT
records and is not a crash-atomic replacement operation; use `putAllAtomically` when the
whole replacement must replay together. Atomic batches retain their forced-log commit
boundary. Values already stored must not be mutated in place before a write, as with the
existing atomic-batch contract.

Flushing the writer does not establish a host-power-loss guarantee: snapshot replacement,
directory metadata and retiring the old log require their own durability protocol. Hybrid
storage retains its asynchronous flush policy, and DynamoDB uses a separate per-table
journal.
