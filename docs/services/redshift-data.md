# Redshift Data API

**Protocol:** JSON 1.1
**Endpoint:** `POST http://localhost:4566/` with `X-Amz-Target: RedshiftData.<Operation>` and `Content-Type: application/x-amz-json-1.1`
**Backing data plane:** the PostgreSQL container behind a Redshift cluster created through the Redshift emulator

Floci emulates the Amazon Redshift Data API: the HTTP API that Lambda, Step Functions, and EventBridge use to run SQL on a cluster without opening a PostgreSQL wire connection. Floci resolves the target cluster, connects straight to its container over JDBC, queues the SQL on a shared worker pool, and stores the statement and its result set through the configured storage backend so the polling operations (`DescribeStatement`, `GetStatementResult`) can read them back.

For the upstream API shape, see the AWS documentation:

- [Using the Amazon Redshift Data API](https://docs.aws.amazon.com/redshift/latest/mgmt/data-api.html)
- [`ExecuteStatement`](https://docs.aws.amazon.com/redshift-data/latest/APIReference/API_ExecuteStatement.html)
- [`BatchExecuteStatement`](https://docs.aws.amazon.com/redshift-data/latest/APIReference/API_BatchExecuteStatement.html)
- [`DescribeStatement`](https://docs.aws.amazon.com/redshift-data/latest/APIReference/API_DescribeStatement.html)
- [`GetStatementResult`](https://docs.aws.amazon.com/redshift-data/latest/APIReference/API_GetStatementResult.html)

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `ExecuteStatement` | Submit one SQL statement asynchronously against the cluster container |
| `BatchExecuteStatement` | Run `Sqls` in order on one connection in a single transaction |
| `DescribeStatement` | Return a stored statement's status, timings, row counts, and sub-statements |
| `GetStatementResult` | Page through a finished statement's rows as typed `Records` |
| `GetStatementResultV2` | Same rows in the V2 envelope, with `CSVRecords` when the statement used `ResultFormat=CSV` |
| `ListStatements` | List stored statements newest first, filtered by `StatementName` or `Status` |
| `CancelStatement` | Cancel queued or running SQL; returns whether cancellation was accepted |
| `ListDatabases` | List databases on the cluster (`pg_database`) |
| `ListSchemas` | List schemas, optionally filtered by `SchemaPattern` |
| `ListTables` | List tables and views, optionally filtered by `SchemaPattern` and `TablePattern` |
| `DescribeTable` | List a table's columns from `information_schema.columns` |
<!-- floci:actions:end -->

## Authentication modes

A request identifies its target cluster or serverless workgroup one of three ways:

- **`ClusterIdentifier` + `DbUser` + `Database`.** The `DbUser` is the cluster master, or the prefixed name returned by `GetClusterCredentials` / `GetClusterCredentialsWithIAM` (for example `IAM:analyst`) while that credential is unexpired. Floci connects to the container as the cluster master in both cases. Any other `DbUser` returns `ValidationException`.
- **`SecretArn` + `ClusterIdentifier` + `Database`.** The secret must be a local Secrets Manager secret holding JSON credentials (`username` or `user`, plus `password`). A cross-region `SecretArn` is rejected.

- **`WorkgroupName` + `Database`, with or without `SecretArn`.** `WorkgroupName` is a workgroup name or workgroup ARN created through [Redshift Serverless](redshift-serverless.md). `Database` must be the namespace `dbName`. Without a `SecretArn`, Floci connects as the namespace admin; AWS derives the database user from the signing identity (`IAM:<name>`), and `DbUser` is not used. With a `SecretArn`, the secret must hold credentials the workgroup backend accepts. Sending `ClusterIdentifier` as well returns `ValidationException`, and so does a workgroup whose runtime is not running.

A statement run against a workgroup reports `WorkgroupName` and omits `ClusterIdentifier`, and the reverse holds for a cluster statement. `ListDatabases`, `ListSchemas`, `ListTables` and `DescribeTable` accept `WorkgroupName` the same way.

## Compatibility Notes

- **Execution is asynchronous.** `ExecuteStatement` and `BatchExecuteStatement` return an `Id` after admission to the shared bounded worker pool. `DescribeStatement` progresses through `SUBMITTED`, `STARTED`, and `FINISHED`, `FAILED` or `ABORTED`. Poll before reading results. Optional `WaitTimeSeconds` accepts integers from 1 to 30 and waits up to that duration. HTTP, Step Functions and Scheduler use the same executor. Saturation returns `ActiveStatementsExceededException`.
- **Execution errors are not HTTP errors.** A statement that fails to run is stored with `Status=FAILED` and an `Error` message, and `ExecuteStatement` still returns 200 with the statement `Id`. Callers see the failure through `DescribeStatement`, matching AWS.
- **Storage and recovery.** Statement metadata and results use the configured storage mode. A sweep evicts terminal statements older than the 24 hour TTL; active statements are retained. With persistence enabled, interrupted nonterminal statements become `FAILED` after restart, without replaying SQL or emitting events. Memory mode loses results on restart.
- **`ExecuteStatement` takes one statement.** If the `Sql` contains more than one statement separated by `;`, it is rejected with `ValidationException`. Use `BatchExecuteStatement` for multiple statements.
- **`BatchExecuteStatement`** runs every entry of `Sqls` in order on one connection in a single transaction: it commits at the end, and on the first failing sub-statement it rolls back and marks the batch `FAILED` with that sub-statement's error. `DescribeStatement` on the parent id returns `SubStatements`, one per entry, each with its own id `<parentId>:<n>`. `GetStatementResult` on the parent id returns the rows of the last sub-statement that produced a result set; on a sub-statement id it returns that sub-statement's rows.
- **`GetStatementResult` and `GetStatementResultV2`** return the typed `Records` shape. `GetStatementResultV2` also returns `ResultFormat`, and when the statement was run with `ResultFormat=CSV` it returns `Records` as `CSVRecords` strings. A statement that produced no result set (an `INSERT`, `UPDATE`, `DELETE`, or DDL statement) returns `ValidationException` with the message `Statement has no result set`.
- **Paging.** The page size is 1000 rows. `NextToken` is an opaque base64 row offset; there is no server-side cursor.
- **`SqlParameters` / `Parameters`** are bound into a `PreparedStatement`. Named `:placeholder` markers are rewritten to positional JDBC bind parameters. Colons inside string literals, quoted identifiers, comments, PostgreSQL `::` casts, and dollar-quoted strings are left untouched. Redshift Data API parameter values are always strings on the wire; PostgreSQL coerces each bound value to the column type.
- **`CancelStatement`** returns `{ "Status": true }` when cancellation is accepted for queued or running work. Running transactional SQL receives JDBC cancellation and rolls back before reaching `ABORTED`; cancelling a batch rolls back its earlier writes. Terminal statements, repeated cancellation and statements that already own commit return `false` in Floci. Commands requiring auto-commit, including Spectrum external DDL and `VACUUM`, own completion before performing irreversible work and reject cancellation during that work. An unknown statement id returns `ResourceNotFoundException`.
- **`WithEvent=true`** publishes one terminal status event per parent statement to the default EventBridge bus in the captured account and region. Batch children do not emit events. False or omitted `WithEvent` emits none. Delivery occurs after terminal metadata is stored, on a separate bounded pool; disabled EventBridge, rejected delivery or target failures do not change the SQL outcome. Delivery is best effort, with no durable outbox or restart replay.
- **`ExecuteSql` and `BatchExecuteSql`** (the deprecated pre-2020 operations) return `ValidationException`.
- **Type mapping.** JDBC `BOOLEAN` and `BIT` map to `booleanValue`; integer types to `longValue`; floating-point types to `doubleValue`; `NUMERIC` and `DECIMAL` to `stringValue` (as AWS does); binary types to `blobValue`; everything else, including dates, timestamps, and uuid, to `stringValue`. A SQL `NULL` maps to `isNull`. A result column of type `line`, `json`, or `jsonb` fails the statement with the Redshift error text.

## Statement status events

[Redshift Data API status events](https://docs.aws.amazon.com/redshift/latest/mgmt/data-api-monitoring-events.html)
use source `aws.redshift-data` and detail type `Redshift Data Statement Status Change`. The
`detail` contains `statementId`, optional `statementName` and resolved `principal`,
`redshiftQueryId`, `state`, `rows` and `expireAt` in epoch seconds. Query ids match
`DescribeStatement`; Floci derives them from statement ids rather than backend query ids.
`resources` contains the cluster ARN, or the workgroup ARN for Floci's Serverless support.
EventBridge rules can route these events to SQS, Lambda or other supported targets.

## Spectrum relational queries

The Data API prepares Glue-backed external CSV tables on the same JDBC connection that executes
the SQL. JOINs with internal tables, multiple external tables, grouping, ordering and parameters
follow the [Redshift Spectrum relational path](redshift.md#relational-queries-over-glue-backed-external-csv-tables).
Create the external schema through either the wire endpoint or the Data API; both use the same
runtime and database binding. Batch preparation runs inside the existing batch transaction, so
failure rolls back the batch. External read or preparation failures are stored as `FAILED` and
reported by `DescribeStatement`, as other SQL execution failures are.
Create external schemas and tables outside a batch: `CREATE EXTERNAL TABLE` cannot run inside a transaction block on AWS, and a batch runs as one transaction, so both statements are rejected there.

Data API session reuse is not supported. The legacy Phase 1 catalog
is not the Glue-backed relational path.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_REDSHIFT_DATA_ENABLED` | `true` | Enable or disable the Redshift Data API service |
| `FLOCI_SERVICES_REDSHIFT_DATA_RESULT_TTL_HOURS` | `24` | Hours terminal statement metadata and results are retained before eviction |
| `FLOCI_SERVICES_REDSHIFT_DATA_MAX_CONCURRENT_STATEMENTS` | `10` | Number of shared SQL workers |
| `FLOCI_SERVICES_REDSHIFT_DATA_QUEUE_CAPACITY` | `100` | Maximum queued statements, also the event delivery queue capacity |
| `FLOCI_SERVICES_REDSHIFT_DATA_SHUTDOWN_TIMEOUT_SECONDS` | `10` | Shared deadline for draining SQL and event delivery on shutdown |

The Redshift Data API also requires the Redshift service itself to be enabled, because it resolves `ClusterIdentifier` values to local Redshift clusters.

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws redshift create-cluster \
  --cluster-identifier wh \
  --node-type dc2.large \
  --master-username admin \
  --master-user-password Secret123 \
  --endpoint-url "$AWS_ENDPOINT_URL"

STATEMENT_ID=$(aws redshift-data execute-statement \
  --cluster-identifier wh \
  --db-user admin \
  --database dev \
  --sql "create table t (id int, name varchar(20))" --wait-time-seconds 30 \
  --query Id --output text \
  --endpoint-url "$AWS_ENDPOINT_URL")

aws redshift-data describe-statement --id "$STATEMENT_ID" --endpoint-url "$AWS_ENDPOINT_URL"

aws redshift-data execute-statement \
  --cluster-identifier wh --db-user admin --database dev \
  --sql "insert into t values (1, 'a'), (2, 'b')" --wait-time-seconds 30 \
  --endpoint-url "$AWS_ENDPOINT_URL"

SELECT_ID=$(aws redshift-data execute-statement \
  --cluster-identifier wh --db-user admin --database dev \
  --sql "select id, name from t order by id" --wait-time-seconds 30 \
  --query Id --output text \
  --endpoint-url "$AWS_ENDPOINT_URL")

aws redshift-data get-statement-result --id "$SELECT_ID" --endpoint-url "$AWS_ENDPOINT_URL"
```

```python
import time
import boto3

data = boto3.client("redshift-data", endpoint_url="http://localhost:4566")

started = data.execute_statement(
    ClusterIdentifier="wh", DbUser="admin", Database="dev",
    Sql="select id, name from t where id = :id",
    Parameters=[{"name": "id", "value": "2"}],
)
statement_id = started["Id"]

while data.describe_statement(Id=statement_id)["Status"] not in ("FINISHED", "FAILED", "ABORTED"):
    time.sleep(0.2)

result = data.get_statement_result(Id=statement_id)
print(result["ColumnMetadata"], result["Records"])
```
