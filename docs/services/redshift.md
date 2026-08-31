# Redshift

**Protocol:** Query (XML) for the management API
**Management Endpoint:** `POST http://localhost:4566/` with `Action=` param
**Data Endpoint:** Floci's auth proxy on the `Endpoint` and `Port` returned by `DescribeClusters` (PostgreSQL wire protocol)

Floci emulates Amazon Redshift by managing a real [PostgreSQL](https://www.postgresql.org/) Docker container per cluster behind a Redshift-shaped control plane. Each cluster sits behind a lightweight auth proxy on the Floci host, so the endpoint is reachable from outside Docker and the master password is validated at the proxy — a `ModifyCluster` password change takes effect for new connections immediately. Redshift speaks the PostgreSQL wire protocol, so the cluster endpoint returned by `DescribeClusters` works with any standard PostgreSQL driver (`psql`, JDBC, `psycopg`, …).

> **Always read the host and port from `DescribeClusters`** rather than assuming a fixed port. PostgreSQL listens on `5432` *inside* the container; the port you connect to is dynamically assigned on the host and returned as `Clusters[0].Endpoint.Port`. Redshift's conventional port is `5439`, but the emulator does not bind it — use whatever `DescribeClusters` reports.

The container has **no persistent volume**: if the physical container survives a Floci restart it is adopted and its data is kept, but if the container itself is gone (host reboot, `docker rm`, a pruned dev box) the cluster comes back empty. Use `CreateClusterSnapshot` / `RestoreFromClusterSnapshot` to preserve data explicitly.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateCluster` | Create a cluster and start a PostgreSQL container for it |
| `DescribeClusters` | List clusters and their connection details |
| `DeleteCluster` | Stop and remove a cluster and its container |
| `CreateClusterSnapshot` | Back up a cluster to a SQL dump via `pg_dump` |
| `DescribeClusterSnapshots` | List snapshots, optionally filtered by snapshot or cluster identifier |
| `DeleteClusterSnapshot` | Remove a snapshot and its stored dump |
| `RestoreFromClusterSnapshot` | Create a new cluster and load a snapshot's dump into it via `psql` |
| `CreateClusterParameterGroup` | Register a parameter group (metadata only) |
| `DescribeClusterParameterGroups` | List parameter groups, optionally filtered by name |
| `DescribeClusterParameters` | Return the parameters of a group, with any values set by `ModifyClusterParameterGroup` |
| `ModifyClusterParameterGroup` | Update parameter values on a group |
| `DeleteClusterParameterGroup` | Remove a parameter group |
| `CreateTags` | Add or overwrite tags on a cluster, snapshot, subnet group or parameter group |
| `DeleteTags` | Remove tags by key from a resource |
| `DescribeTags` | List tagged resources and their tags |
| `CreateClusterSubnetGroup` | Register a cluster subnet group (metadata only) |
| `DescribeClusterSubnetGroups` | List subnet groups, optionally filtered by name |
| `ModifyClusterSubnetGroup` | Update a subnet group's description or subnet list |
| `DeleteClusterSubnetGroup` | Remove a subnet group |
| `ModifyCluster` | Update node type, parameter group, security groups, or the master password |
| `RebootCluster` | Restart a cluster's container |
<!-- floci:actions:end -->

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `FLOCI_SERVICES_REDSHIFT_ENABLED` | `true` | Enable or disable Redshift |
| `FLOCI_SERVICES_REDSHIFT_IMAGE_VERSION` | `postgres:15-alpine` | PostgreSQL Docker image backing each cluster |
| `FLOCI_SERVICES_REDSHIFT_DEFAULT_PORT` | `5439` | Reported Redshift port hint (the real host port is dynamic and comes from `DescribeClusters`) |
| `FLOCI_SERVICES_REDSHIFT_PROXY_BASE_PORT` | `7100` | Lowest host port the per-cluster auth proxies bind |
| `FLOCI_SERVICES_REDSHIFT_PROXY_MAX_PORT` | `7199` | Highest host port the per-cluster auth proxies bind |
| `FLOCI_SERVICES_REDSHIFT_ENDPOINT_HOST` | _(unset)_ | Hostname advertised in `DescribeClusters`; unset resolves from the Docker host |

Redshift needs the Docker socket so it can launch PostgreSQL containers. Each cluster's container is published on a dynamically assigned host port, returned by `DescribeClusters`.

### Docker Compose

```yaml
services:
  floci:
    image: floci/floci:latest
    ports:
      - "4566:4566"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
```

For private registry authentication and other Docker settings see [Docker Configuration](../configuration/docker.md).

## Examples

### Management API (AWS CLI)

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

# Create a cluster (starts a PostgreSQL container)
aws redshift create-cluster \
  --cluster-identifier my-warehouse \
  --node-type dc2.large \
  --master-username admin \
  --master-user-password Secret123

# Read the cluster endpoint and port
aws redshift describe-clusters \
  --cluster-identifier my-warehouse \
  --query 'Clusters[0].Endpoint'

# Snapshot and restore
aws redshift create-cluster-snapshot \
  --snapshot-identifier snap-1 \
  --cluster-identifier my-warehouse
aws redshift restore-from-cluster-snapshot \
  --cluster-identifier my-warehouse-restored \
  --snapshot-identifier snap-1

# Delete
aws redshift delete-cluster \
  --cluster-identifier my-warehouse \
  --skip-final-cluster-snapshot
```

### Data plane (Python + psycopg)

```python
import psycopg

# Read host and port from DescribeClusters — the host port is dynamic.
host, port = "localhost", 32768  # e.g. Clusters[0].Endpoint.Address / .Port
with psycopg.connect(f"host={host} port={port} dbname=dev user=admin password=Secret123") as conn:
    conn.execute("CREATE TABLE people (name text)")
    conn.execute("INSERT INTO people VALUES ('Alice')")
    for row in conn.execute("SELECT * FROM people"):
        print(row)
```

### Management API (Python / boto3)

```python
import boto3

redshift = boto3.client(
    "redshift",
    endpoint_url="http://localhost:4566",
    region_name="us-east-1",
)

cluster = redshift.create_cluster(
    ClusterIdentifier="my-warehouse",
    NodeType="dc2.large",
    MasterUsername="admin",
    MasterUserPassword="Secret123",
)
print(cluster["Cluster"]["Endpoint"])
```

## SQL Interceptor & S3 COPY/UNLOAD

Floci's Redshift auth proxy intercepts frontend queries on the PostgreSQL wire protocol (Simple Query `'Q'` protocol) to emulate common Redshift-specific SQL syntax:

### Supported Features

- **DDL Compatibility:**
  - Redshift-specific DDL keywords are automatically stripped before forwarding to PostgreSQL: `DISTSTYLE ALL|EVEN|KEY|AUTO`, `DISTKEY (<col>)`, `COMPOUND|INTERLEAVED SORTKEY (<cols>)`, and column encodings `ENCODE <codec>` (only the real Redshift encodings — `raw`, `az64`, `bytedict`, `delta`, `delta32k`, `lzo`, `mostly8/16/32`, `runlength`, `text255`, `text32k`, `zstd`, `auto`).
  - The rewrite only runs when the query's first keyword is `CREATE TABLE` / `ALTER TABLE`; a `SELECT`, `INSERT`, etc. is forwarded byte-for-byte even if it mentions these keywords. String literals are masked before rewriting, so a keyword inside a quoted value (including in a later statement of a multi-statement query) is preserved.
- **S3 COPY Emulation:**
  - `COPY <table> FROM 's3://<bucket>/<prefix>'` is intercepted and streamed from Floci's S3 service directly into PostgreSQL via `COPY ... FROM STDIN WITH (FORMAT csv...)`.
  - Supports exact object keys or prefix directories (streams all matching objects).
  - Supports `CSV`, `DELIMITER`, `HEADER` / `IGNOREHEADER <n>`, `GZIP` decompression, and `NULL AS '<string>'`.
- **S3 UNLOAD Emulation:**
  - `UNLOAD ('<select query>') TO 's3://<bucket>/<prefix>'` executes the query against PostgreSQL using `COPY (...) TO STDOUT WITH (FORMAT csv...)` and writes the output directly to Floci's S3 service as `<prefix>000`.
  - Supports `CSV`, `DELIMITER`, `HEADER`, `GZIP` compression, `ADDQUOTES`, `NULL AS '<string>'`, and `MANIFEST` generation (writes `<prefix>manifest` JSON metadata).

### Limitations

- Emulation is supported on the **Simple Query protocol** (`'Q'`) only. Extended Query protocol statements (`Parse`/`Bind`/`Execute`) pass through untouched — including anything a JDBC `PreparedStatement` sends, and, with the pgjdbc default `preferQueryMode=extended`, plain `Statement` calls too. To exercise the interceptor from JDBC, connect with `preferQueryMode=simple`.
- Only `CSV` and `GZIP` formats are currently supported for S3 COPY/UNLOAD (Parquet, ORC, JSON, and columnar formats are not yet supported).
- The DDL rewrite is textual (regex). It masks single-quoted string literals first, so `DEFAULT`/`CHECK` string values are safe. It is **not** comment-aware and does not recognise dollar-quoting (`$$ … $$`) or escape strings (`E'…'`): an apostrophe inside a `--` or `/* */` comment can make the rewrite skip a Redshift clause. That fails safe — the statement then reaches PostgreSQL, which returns its own syntax error — but avoid apostrophes-in-comments and dollar-quoted bodies in `CREATE TABLE`.
- `UNLOAD` buffers the entire result set in memory before writing to S3 (single `<prefix>000` object, no multi-file split): a warning is logged past 256 MiB and the operation is **aborted with an error** past 512 MiB to protect the shared emulator process. Streaming/multipart UNLOAD is not implemented.
- `IGNOREHEADER <n>` with `n > 1` still skips only the first line (maps to PostgreSQL `HEADER`).
- **S3 authorization**: a Postgres session carries no AWS principal, so COPY/UNLOAD authorize against S3 as an *unsigned* request. With `s3.enforceAuth` off (the default) this is unrestricted, like every other operation in that mode; with it on, only buckets whose policy / public-access configuration permits anonymous access are reachable. `IAM_ROLE` / `CREDENTIALS` clauses are parsed and ignored.

## Out of Scope

- Real Redshift distributed execution — every cluster is a single PostgreSQL container; `NodeType` and `NumberOfNodes` are metadata only.
- Redshift-specific data types and advanced features like `SUPER`, `SPECTRUM`, or columnar storage internals.
- Parameter groups apply no real engine settings; values are stored and echoed back only.
- Subnet groups, VPC routing, and security groups are metadata only.
- Resize, pause/resume, IAM authentication, snapshot schedules, and cross-region snapshot copy.
- The auth proxy validates only the master user's password. Non-master users pass straight through to PostgreSQL, which remains the authority for their credentials.
- IAM database authentication (`GetClusterCredentials`), and `sslmode=verify-full` against the self-signed proxy certificate.
