# Redshift

**Protocol:** Query (XML) for the management API
**Management Endpoint:** `POST http://localhost:4566/` with `Action=` param
**Data Endpoint:** Floci's auth proxy on the `Endpoint` and `Port` returned by `DescribeClusters` (PostgreSQL wire protocol)

Floci emulates Amazon Redshift by managing a real [PostgreSQL](https://www.postgresql.org/) Docker container per cluster behind a Redshift-shaped control plane. Each cluster sits behind a lightweight auth proxy on the Floci host, so the endpoint is reachable from outside Docker and the master password is validated at the proxy, a `ModifyCluster` password change takes effect for new connections immediately. Redshift speaks the PostgreSQL wire protocol, so the cluster endpoint returned by `DescribeClusters` works with any standard PostgreSQL driver (`psql`, JDBC, `psycopg`, …).

> **Always read the host and port from `DescribeClusters`** rather than assuming a fixed port. PostgreSQL listens on `5432` *inside* the container; the port you connect to is dynamically assigned on the host and returned as `Clusters[0].Endpoint.Port`. Redshift's conventional port is `5439`, but the emulator does not bind it: use whatever `DescribeClusters` reports.

The container has **no persistent volume**: if the physical container survives a Floci restart it is adopted and its data is kept, but if the container itself is gone (host reboot, `docker rm`, a pruned dev box) the cluster comes back empty. Use `CreateClusterSnapshot` / `RestoreFromClusterSnapshot` to preserve data explicitly.

For running SQL without a PostgreSQL wire connection (the way Lambda and Step Functions do), see the [Redshift Data API](redshift-data.md).

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
| `RestoreFromClusterSnapshot` | Create a new cluster and load a snapshot's dump into it via `psql`; accepts `SnapshotIdentifier` or a same-account, same-region `SnapshotArn` |
| `CreateClusterParameterGroup` | Register a parameter group (metadata only) |
| `DescribeClusterParameterGroups` | List parameter groups, optionally filtered by name |
| `DescribeClusterParameters` | Return the parameters of a group, with any values set by `ModifyClusterParameterGroup` |
| `ModifyClusterParameterGroup` | Update parameter values on a group |
| `DeleteClusterParameterGroup` | Remove a parameter group |
| `CreateTags` | Add or overwrite tags on a cluster, snapshot, subnet group, parameter group or snapshot copy grant |
| `DeleteTags` | Remove tags by key from a resource |
| `DescribeTags` | List tagged resources and their tags |
| `CreateClusterSubnetGroup` | Register a cluster subnet group (metadata only) |
| `CreateIntegration` | Create a DynamoDB Streams to provisioned Redshift zero-ETL integration. Accepts `Description`, `KMSKeyId`, `AdditionalEncryptionContext` and `TagList` |
| `DescribeIntegrations` | List integrations with `Filters`, `MaxRecords` and `Marker` pagination, or the one an `IntegrationArn` names |
| `DeleteIntegration` | Remove a zero-ETL integration |
| `DescribeClusterSubnetGroups` | List subnet groups, optionally filtered by name |
| `ModifyClusterSubnetGroup` | Update a subnet group's description or subnet list |
| `DeleteClusterSubnetGroup` | Remove a subnet group |
| `CreateSnapshotCopyGrant` | Register a snapshot copy grant, defaulting `KmsKeyId` to the AWS-managed Redshift key. `SnapshotCopyGrantName` must be 1-63 characters, start with a lowercase letter, and contain only lowercase letters, digits and non-consecutive hyphens |
| `DescribeSnapshotCopyGrants` | List snapshot copy grants, optionally filtered by name, paged with `MaxRecords` and `Marker` |
| `DeleteSnapshotCopyGrant` | Remove a snapshot copy grant |
| `ModifyCluster` | Update node type, parameter group, security groups, Multi-AZ flag, or the master password |
| `DescribeClusterVersions` | Return the single emulated engine version and its parameter group family |
| `DescribeOrderableClusterOptions` | Return the static node types and cluster types, optionally filtered by `NodeType` or `ClusterVersion` |
| `ModifyClusterIamRoles` | Add or remove the IAM roles associated with a cluster; COPY and UNLOAD see the change on new connections. `DefaultIamRoleArn` is ignored |
| `DescribeLoggingStatus` | Return a cluster's stored audit-logging configuration |
| `EnableLogging` | Store audit-logging configuration (S3 bucket, CloudWatch, or S3 table via `LogDestinationType`); no logs are delivered |
| `DisableLogging` | Clear a cluster's audit-logging configuration |
| `RebootCluster` | Restart a cluster's container |
| `GetClusterCredentials` | Issue a short-lived DbUser / DbPassword pair the auth proxy and Data API accept for a non-master user |
| `GetClusterCredentialsWithIAM` | Issue short-lived credentials with the DbUser derived from the caller's IAM identity |
<!-- floci:actions:end -->

### Snapshot copy grants

- **`KmsKeyId` defaults to an alias ARN.** Floci has no per-account AWS-managed key, so
  `CreateSnapshotCopyGrant` without `KmsKeyId` stores
  `arn:aws:kms:<region>:<account>:alias/aws/redshift` where AWS would store the key ARN it
  resolves to. The value only has to round-trip through `DescribeSnapshotCopyGrants`, which is
  what Terraform reads back.
- **Tag limits are not validated.** The 50-tag count and the AWS key/value length limits are not
  enforced on grants, as elsewhere in Floci's Redshift tagging.
- **A missing grant reports different faults on different actions**, because AWS models them
  that way. `DescribeSnapshotCopyGrants` and `DeleteSnapshotCopyGrant` return
  `SnapshotCopyGrantNotFoundFault`, which the Redshift model pins at HTTP 400.
  `CreateTags`/`DeleteTags`/`DescribeTags` do not list that fault at all and return
  `ResourceNotFoundFault` (HTTP 404) for any missing resource, grants included.

## DynamoDB zero-ETL

The supported zero-ETL path is DynamoDB Streams to a provisioned Redshift cluster. The source ARN
must identify an enabled local DynamoDB stream, and the target ARN must identify an existing
provisioned Redshift cluster.

Each stream record is written to a stable landing table named `floci_zetl_<integration-id>`.
The landing table stores the event id, event name, source, region, sequence number, creation time,
and DynamoDB keys and images as JSON text. Record event ids are unique, so retries are idempotent.

The integration consumer has its own stream checkpoint and does not share Lambda event source
mapping state. Deleting an integration stops its consumer while retaining the landing table.

Items already present in the source table when `CreateIntegration` runs are backfilled into the
landing table with a paginated `Scan`, one page per poll tick, and the scan resumes after a Floci
restart. While the backfill runs, `DescribeIntegrations` reports `Status` as `syncing` (as a Floci
approximation to indicate that initial backfill is in progress); it becomes `active` once the scan is
exhausted. The landing table is an append-only log, so an item changed while its table is still being
backfilled can appear twice: once from the scan and once from the stream.

Serverless Redshift targets, schema inference, and relational projection are not supported in this
first implementation.

## CloudFormation

Floci provisions these resource types:

- `AWS::Redshift::Cluster`
- `AWS::Redshift::ClusterParameterGroup`
- `AWS::Redshift::ClusterSubnetGroup`
- `AWS::Redshift::ClusterSecurityGroup`

### Cluster Provisioning and References

For `AWS::Redshift::Cluster`:

- `Ref` returns the cluster identifier.
- `Fn::GetAtt` exposes `Endpoint.Address`, `Endpoint.Port`, and `ClusterNamespaceArn` (synthesised, stable).

Replacement occurs if `ClusterIdentifier`, `DBName`, `MasterUsername`, or `ClusterSubnetGroupName` changes. Other properties (such as `NodeType`, `MasterUserPassword`, `ClusterParameterGroupName`, and `VpcSecurityGroupIds`) update in place.

For `AWS::Redshift::ClusterParameterGroup`, `Parameters` is applied via `ModifyClusterParameterGroup` on both create and update. `Description` and `ParameterGroupFamily` are replacement properties, matching AWS: changing either creates a new parameter group instead of reusing the prior one.

### Gaps and Limitations

- `Port` is ignored: Floci assigns the dynamic host proxy port returned in `Endpoint.Port`.
- `DBName` other than `dev` is ignored: the emulated PostgreSQL container database is always `dev`.
- `NumberOfNodes` is not stored on cluster create: every emulated cluster is backed by a single PostgreSQL container.
- `ManageMasterPassword` creates a Redshift-owned Secrets Manager secret. The secret contains the
  managed username, password, endpoint, port, and database name. `MasterPasswordSecretKmsKeyId`
  selects the KMS key used for the secret metadata and is validated through KMS.
- `SnapshotIdentifier` is ignored: a fresh cluster is created instead of restoring from a snapshot.
- `AWS::Redshift::ClusterSecurityGroup` is accepted as metadata: Floci does not emulate the legacy EC2-Classic security group model.

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `FLOCI_SERVICES_REDSHIFT_ENABLED` | `true` | Enable or disable Redshift |
| `FLOCI_SERVICES_REDSHIFT_IMAGE_VERSION` | `postgres:15-alpine` | PostgreSQL Docker image backing each cluster |
| `FLOCI_SERVICES_REDSHIFT_DEFAULT_PORT` | `5439` | Reported Redshift port hint (the real host port is dynamic and comes from `DescribeClusters`) |
| `FLOCI_SERVICES_REDSHIFT_PROXY_BASE_PORT` | `7100` | Lowest host port the per-cluster auth proxies bind |
| `FLOCI_SERVICES_REDSHIFT_PROXY_MAX_PORT` | `7199` | Highest host port the per-cluster auth proxies bind |
| `FLOCI_SERVICES_REDSHIFT_ENDPOINT_HOST` | _(unset)_ | Hostname advertised in `DescribeClusters`; unset resolves from the Docker host |
| `FLOCI_SERVICES_REDSHIFT_PROXY_HANDSHAKE_TIMEOUT_MILLIS` | `10000` | Max time a client has to complete the startup/auth handshake before the proxy drops it |
| `FLOCI_SERVICES_REDSHIFT_PROXY_BACKEND_CONNECT_TIMEOUT_MILLIS` | `5000` | Max time the proxy waits for the backend TCP connect |
| `FLOCI_SERVICES_REDSHIFT_PROXY_MAX_CONNECTIONS` | `100` | Max concurrent connections per proxy before new ones are refused |

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

# Read host and port from DescribeClusters: the host port is dynamic.
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

## SQL Interceptor

Floci's Redshift auth proxy inspects frontend queries on the PostgreSQL wire protocol and rewrites common Redshift-specific table DDL so it runs on the plain PostgreSQL backend.

### DDL compatibility

- Redshift-only table DDL keywords are stripped before the statement is forwarded: `DISTSTYLE ALL|EVEN|KEY|AUTO`, `DISTKEY (<col>)` and column-level `DISTKEY`, `[COMPOUND|INTERLEAVED] SORTKEY (<cols>)` and column-level `SORTKEY`, and `ENCODE <codec>` for the real Redshift column encodings (`raw`, `az64`, `bytedict`, `delta`, `delta32k`, `lzo`, `mostly8`, `mostly16`, `mostly32`, `runlength`, `text255`, `text32k`, `zstd`) or `auto`.
- The rewrite only runs when the statement's first keyword is `CREATE TABLE` or `ALTER TABLE`. A `SELECT`, `INSERT`, function body, or string literal that merely contains one of these keywords is forwarded byte-for-byte. Single-quoted and dollar-quoted string literals are masked before the rewrite, so a keyword inside a quoted value (including in a later statement of a multi-statement query) is preserved.
- Columns legitimately named `distkey`, `sortkey`, or `encode` survive.

### Redshift Spectrum Phase 1

The PostgreSQL wire proxy supports a bounded Spectrum subset for CSV data in S3. The supported
statements are:

```sql
CREATE EXTERNAL SCHEMA <schema>
  FROM DATA CATALOG DATABASE '<database>' IAM_ROLE '<role>';

CREATE EXTERNAL TABLE <schema>.<table> (<name> <type>, ...)
  STORED AS TEXTFILE LOCATION 's3://<bucket>/<prefix>/'
  TBLPROPERTIES ('skip.header.line.count'='<n>');
```

Supported column types are `VARCHAR`, `CHAR`, `INTEGER`, `BIGINT`, `DECIMAL`, `BOOLEAN`, `DATE`,
and `TIMESTAMP`. CSV reads support `DELIMITER`, `QUOTE`, `ESCAPE`, `NULL AS`, and
`skip.header.line.count`. Objects under the location are read in lexicographic key order.

The query path supports `SELECT *`, explicit column projections, and simple `WHERE` predicates
over one external table. Queries with joins, grouping, ordering, subqueries, parameters, or
multiple statements are outside Phase 1 and are forwarded to PostgreSQL, which returns its own
error. Rows are materialized into a connection-local temporary table (`CREATE TEMP TABLE`) before
the rewritten query is sent to PostgreSQL. The temporary table is not visible to another
connection. Under the Extended Query protocol, the temporary table is dropped eagerly once
the cursor is exhausted or closed. Under the Simple Query protocol, the table remains
session-scoped and is cleaned up when the connection terminates, avoiding race conditions
with the streaming backend-to-client pump. Each query materializes its own table, so a
long-lived or pooled connection holds one copy per query until it closes.

`IAM_ROLE` is parsed and retained in the external schema metadata. Phase 1 does not yet assume
the role or evaluate its IAM policy for Spectrum reads. S3 authorization therefore follows the
current S3 emulator authorization mode, and role-specific Spectrum access enforcement is a
future phase. Parquet, JSON, Avro, ORC, partition discovery, `ALTER`, and `DROP` lifecycle
operations are not supported in Phase 1.

### COPY from S3

`COPY <table> [(<columns>)] FROM 's3://<bucket>/<keyOrPrefix>' [options]` sent over the Simple
Query protocol is emulated: Floci reads the object (or every object under the prefix, in key
order) through its own S3 service and streams the rows into the backing PostgreSQL container with
`COPY ... FROM STDIN`.

- Supported options: `DELIMITER`, `FORMAT CSV` (or a bare `CSV`), `GZIP`, `IGNOREHEADER <n>` and
  `HEADER`, `NULL AS`, `FORMAT AS JSON 'auto'`, `FORMAT AS JSON 'auto ignorecase'` (or bare `JSON 'auto'`),
  `MANIFEST`, and an explicit column list.
- The default framing is pipe-delimited text, matching Redshift. `FORMAT CSV` switches to CSV with
  a comma default delimiter.
- `FORMAT AS JSON 'auto'` loads JSON objects, mapping JSON keys to table columns with exact case
  sensitivity. `FORMAT AS JSON 'auto ignorecase'` performs case-insensitive key matching. Input
  objects can be separated by any whitespace, including multiline pretty-printed JSON. Non-object root
  values abort the load. When columns are not specified in the COPY statement, table column names and
  order are automatically discovered from the database catalog, but only over the **Simple Query
  protocol**. Over Extended Query (the default for a JDBC `PreparedStatement`, and for a plain
  `Statement` under recent pgjdbc versions) the column list is fixed by the time `Parse` is sent,
  before any backend round trip is possible, so catalog discovery cannot run there: omitting the column
  list fails the COPY with a clear error instead of silently guessing. Specify the column list explicitly
  for Extended Query, or connect with `preferQueryMode=simple` to use discovery. Nested objects and
  arrays are serialized as JSON strings.
- `MANIFEST` resolves file keys from a JSON manifest file (`{"entries": [{"url": "s3://...", "mandatory": boolean}]}`),
  compatible with output from `UNLOAD ... MANIFEST`. Missing files marked `mandatory: true` abort the load.
  When `mandatory` is omitted, it defaults to `false`. All entries in the manifest must reside in the same S3
  bucket as the manifest file itself; cross-bucket manifest entries are rejected as an intentional deviation.
- `IGNOREHEADER` and `HEADER` skip lines from the first resolved object only.
- `GZIP` is the only input compression recognized; `BZIP2`, `LZOP` and `ZSTD` are not.
- `IAM_ROLE '<role-arn>'` is supported. The role must be associated with the cluster (at
  `CreateCluster` or through `ModifyClusterIamRoles`; connections opened before a change keep the
  roles they started with), exist in
  the local IAM service, and trust Redshift to assume it. With
  `FLOCI_SERVICES_S3_ENFORCE_AUTH` off, S3 policy checks are skipped. With it on, the role's
  identity policy must allow the required S3 actions, and any bucket policy must not deny the
  request. `IAM_ROLE default` is not supported.
- Any other clause (`FIXEDWIDTH`, `PARQUET`, `AVRO`, `ORC`, `MAXERROR`,
  `DATEFORMAT`, `TIMEFORMAT`, `REGION`, `ENCODING`, `ESCAPE`, `REMOVEQUOTES`, `BLANKSASNULL`,
  `EMPTYASNULL`, `TRUNCATECOLUMNS`, `ACCEPTINVCHARS`, `CREDENTIALS`, and so on) is not
  recognized: the statement is forwarded unchanged and PostgreSQL returns its own error.
- A multi-statement query whose COPY is followed by another statement is not intercepted; send the
  COPY on its own.
- Extended Query COPY is supported when the complete statement is present in `Parse` and has no
  bind parameters. Zero-parameter JDBC `PreparedStatement` calls therefore work with pgjdbc's
  default extended mode. Statements containing bind parameters are forwarded unchanged.

### Limitations

- DDL rewriting works in both Simple Query (`'Q'`) and Extended Query (`Parse`) flows. COPY and
  UNLOAD interception in Extended Query is limited to zero-parameter statements fully present in
  `Parse`; parameterized statements fail open to PostgreSQL.
- The rewrite is textual (regex-based). It masks single-quoted string literals first, so `DEFAULT` / `CHECK` string values are safe, but it is **not** comment-aware and does not recognize escape strings (`E'...'`): an apostrophe inside a `--` or `/* */` comment can make the rewrite skip a Redshift clause. That fails safe: the statement then reaches PostgreSQL, which returns its own syntax error, but avoid apostrophes-in-comments in `CREATE TABLE` / `ALTER TABLE`.
- A `rewrite` failure or any statement the interceptor does not recognize is forwarded unmodified (fail-open); PostgreSQL then rejects the Redshift-only syntax itself.
- Simple Query ('Q') messages larger than 16 MiB bypass the interceptor and stream through verbatim without heap buffering; non-query traffic also streams through with no size limit.
- `GetClusterCredentials` / `GetClusterCredentialsWithIAM` mint a short-lived password held in memory (lost on restart). As in AWS, `GetClusterCredentials` prefixes the returned `DbUser` with `IAM:` when `AutoCreate` is false and `IAMA:` when it is true; that prefixed name is what the auth proxy and Data API accept. The returned `DbUser` is nominal: the session runs as the cluster master, not a distinct PostgreSQL role, so `current_user`, `GRANT`, and object ownership are the master's.

### UNLOAD to S3

`UNLOAD ('<select-statement>') TO 's3://<bucket>/<prefix>' [options]` runs the select on the
backing PostgreSQL container and writes
the result to S3 as one or more objects under `<prefix>`.

- Framing defaults to pipe-delimited text; `FORMAT CSV` (or `CSV`) switches to CSV
  with a `,` default. `DELIMITER`, `HEADER`, `NULL AS`, and `ADDQUOTES` are honoured
  (`ADDQUOTES` and `HEADER` force CSV framing on PostgreSQL 15).
- `PARALLEL ON` (default) names objects `<prefix>0000_part_00`,
  `<prefix>0001_part_00`, and so on; `PARALLEL OFF` names them `<prefix>000`,
  `<prefix>001`, and so on. The emulator has a single backend node, so more than one
  object appears only when the result exceeds the per-file size. When `HEADER` is
  set, the header row is repeated at the top of every object.
- `MAXFILESIZE [AS] <n> [MB|GB]` sets the per-file size; a bare number is bytes.
  Each file is buffered in memory, so the default is 6 MiB (real Redshift defaults
  to 6.2 GB) and a whole UNLOAD result is capped at 256 MiB; a larger result fails
  with a SQL error (54000). A `MAXFILESIZE` above that 256 MiB cap is not
  intercepted at all: the statement is forwarded and PostgreSQL reports its own error.
- A zero-row result still writes one object (empty, or the header row alone when
  `HEADER` is set).
- `GZIP` compresses each object and appends `.gz` to its key.
- `MANIFEST` writes `<prefix>manifest` listing every object with its
  `content_length`.
- Without `ALLOWOVERWRITE`, a non-empty target prefix fails with SQL error XX000 and the select
  does not run. A failed UNLOAD then removes any objects it had already written. With
  `ALLOWOVERWRITE` a failed UNLOAD leaves its objects in place (they may have replaced prior data,
  so they are not deleted); a `MANIFEST` request that fails this way can leave data objects without
  a manifest, and rerunning the same statement overwrites them.
- `IAM_ROLE '<role-arn>'` is supported. The role must be associated with the cluster, exist in
  the local IAM service, and trust Redshift to assume it. With
  `FLOCI_SERVICES_S3_ENFORCE_AUTH` off, S3 policy checks are skipped. With it on, the role's
  identity policy must allow the required S3 actions, and any bucket policy must not deny the
  request. `IAM_ROLE default` is not supported.
- Any other option (`PARQUET`, `ENCRYPTED`, `REGION`, `CREDENTIALS`,
  `ZSTD`, `EXTENSION`, `CLEANPATH`, `PARTITION`, and so on) is not intercepted; the
  statement is forwarded and PostgreSQL reports its own error.
- Extended Query UNLOAD is supported when the complete statement is present in `Parse` and has no
  bind parameters. Parameterized statements are forwarded unchanged.

## Catalog Views

When a Redshift cluster container starts, Floci bootstraps common Redshift system and catalog views into both `template1` (ensuring any future `CREATE DATABASE` inherits them automatically) and the active cluster database (`dev`). This ensures BI tools (Tableau, Looker, DBeaver), ORMs, and migration tools (Flyway, Liquibase, dbt) can introspect database schema metadata without missing-relation errors:

- `pg_table_def`: Table and column metadata (`schemaname`, `tablename`, `column`, `type`, `encoding`, `distkey`, `sortkey`, `notnull`).
- `svv_table_info`: Table-level summary metadata (`database`, `schema`, `table_id`, `table`, `encoded`, `diststyle`, `sortkey1`, `max_varchar`, `tbl_rows`, `size`).
- `svv_all_columns`: All columns across database schemas (`database_name`, `schema_name`, `table_name`, `column_name`, `data_type`, `is_nullable`).
- `svv_columns`: Column catalog list (`table_catalog`, `table_schema`, `table_name`, `column_name`, `ordinal_position`, `column_default`, `is_nullable`, `data_type`).
- `svv_tables`: Table catalog list (`table_catalog`, `table_schema`, `table_name`, `table_type`).
- `stv_tbl_perm`: Table persistence metadata (`id`, `name`, `db_id`, `temp`, `backup`).
- `stl_load_errors`: Table exposing the documented Redshift load-error schema for catalog and tooling compatibility.
- `svl_qlog`: Query execution log view (`userid`, `query`, `xid`, `pid`, `starttime`, `endtime`, `elapsed`, `aborted`, `label`).
- `pg_user_info`: User catalog information (`usesysid`, `usename`, `usecreatedb`, `usesuper`, `useconnlimit`, `syslogaccess`).
- `svl_user_info`: Standard Redshift user information view matching AWS documented columns.
- `pg_database_info`: Database catalog information (`datid`, `datname`, `datdba`, `encoding`, `datconnlimit`).
- `stv_sessions`: Active database sessions (`process`, `user_name`, `db_name`, `starttime`, `timeout_sec`).
- `stv_recents`: Recently executed queries (`userid`, `pid`, `process`, `query`, `starttime`, `duration`, `status`).
- `svv_transactions`: Current transaction status (`txn_owner`, `txn_db`, `xid`, `pid`, `txn_start`, `lock_mode`, `relation`, `granted`).
- `stv_slices`: Cluster slice metadata (`node`, `slice`, `localslice`, `type`).
- `stl_query`: Dynamic query execution log view mapped from `pg_stat_activity` (`query`, `xid`, `pid`, `userid`, `starttime`, `endtime`, `elapsed`, `querytxt`, `database`, `aborted`, `insert_pristine`, `concurrency_scaling_status`).
- `stv_wlm_query_state`: Dynamic WLM query state view (`xid`, `task`, `query`, `service_class`, `slot_count`, `wlm_start_time`, `queue_time`, `exec_time`, `state`, `query_priority`).
- `svv_diskusage`: Disk space usage summary per relation exposing full documented Redshift block layout columns (`db_id`, `name`, `slice`, `col`, `tbl`, `blocknum`, `num_values`, `minvalue`, `maxvalue`, `sb_pos`, `pinned`, `on_disk`, `modified`, `hdr_modified`, `unsorted`, `tombstone`, `preferred_diskno`, `temporary`, `newblock`) as well as compatibility aliases (`database`, `schema`, `table_id`, `size`, `used`).

These views expose the documented Redshift column names, types, and ordering mapped from PostgreSQL internal catalogs (`pg_catalog`, `information_schema`, `pg_stat_activity`), with deterministic placeholders where PostgreSQL cannot provide multi-node metrics.

## Out of Scope

- Real Redshift SQL semantics: the data plane is stock PostgreSQL. Redshift-only table DDL keywords (DISTSTYLE / DISTKEY / SORTKEY / ENCODE) are stripped so CREATE TABLE / ALTER TABLE executes (see [SQL Interceptor](#sql-interceptor)), but the distribution/sort behavior they request is not; SUPER and Spectrum features outside the documented [Phase 1 subset](#redshift-spectrum-phase-1) are not emulated.
- Multi-node clusters: `NodeType` and `NumberOfNodes` are stored as metadata; every cluster is a single PostgreSQL container.
- Parameter groups apply no real engine settings; values are stored and echoed back only.
- Subnet groups, VPC routing, and security groups are metadata only.
- Resize, pause/resume, IAM authentication, snapshot schedules, and cross-region snapshot copy.
- `IAM_ROLE default` and cross-account role ARNs are not supported for COPY or UNLOAD.
- The auth proxy validates the master user's password and any live `GetClusterCredentials` credential. Other non-master users pass straight through to PostgreSQL, which remains the authority for their credentials.
- A cluster using `ManageMasterPassword` keeps its generated password in sync with the
  Redshift-owned Secrets Manager secret. Updating `MasterUserPassword` through `ModifyCluster`
  updates the current secret version as well.
- IAM database authentication over the wire, and `sslmode=verify-full` against the self-signed proxy certificate.
