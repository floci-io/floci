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
| `ListSessions` | List caller-owned session metadata, with lifecycle status, target filters and pagination |
| `CancelStatement` | Cancel queued or running SQL; returns whether cancellation was accepted, and rejects terminal statements |
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

## Session reuse

`ExecuteStatement` and `BatchExecuteStatement` accept `SessionKeepAliveSeconds`, an integer from
0 to 86400. A positive value retains the JDBC connection after the statement completes and
returns its `SessionId`. Subsequent execute or batch calls can send just that `SessionId` and
their SQL. The database, cluster or workgroup, database user and secret are inherited from the
first request. Optional target fields on a reuse request must match the original session.
Temporary tables and session settings survive between calls, and Execute and Batch can share
the same session. Metadata operations such as `ListTables` still open independent connections.

`BEGIN` or `START TRANSACTION` opens an explicit transaction for later Execute calls;
`COMMIT`/`END` commits it, and `ROLLBACK`/`ABORT` rolls it back. SQL errors and accepted running cancellation
leave an explicit transaction aborted: later Execute calls fail until COMMIT or ROLLBACK ends
the block, rolling back its changes. A batch remains a transaction boundary: its success commits the
connection and its failure rolls it back, including any transaction opened by earlier Execute
calls on that session. Transaction-control modifiers beyond optional `WORK` or `TRANSACTION`
are rejected before execution. These cross-call transaction details have not been verified against live AWS.

Batch audit records track successful user changes through transaction boundaries and savepoints.
`ROLLBACK TO` discards changes after the named savepoint, while `RELEASE` keeps them pending until
commit. A full rollback discards pending changes; changes committed earlier in the batch retain
their audit records if a later statement fails. Batch entries support transaction-control commands
and multiple SQL statements. Audit state advances after each successful command, while an entry's
result metadata preserves its first command's result and its original SQL text.

An omitted keep-alive on a reuse request preserves the previous idle timeout. A supplied value
replaces it after successful admission, and the idle deadline starts when the query completes.
Sending 0 with an existing `SessionId` closes the connection after that query. Omitting both
fields, or sending 0 without a session id, keeps the existing connection-per-request behavior.

Only one query can be admitted to a session at a time, including while that query waits for a
worker. A different query against a busy session returns `ValidationException`; it is not queued
behind the first query. Retrying the first query's `ClientToken` returns its original statement
and session ids. Queue or admission-storage failures release their reservation without changing
an existing session's idle deadline. Each cluster or workgroup supports up to 500 retained
sessions, shared across callers and database names.

Sessions are scoped to the signing account, Region and caller independently of `WithEvent`.
Floci resolves the caller's IAM ARN when possible and otherwise uses the access key identity;
an internal caller can supply its captured principal. A foreign, expired or reset session returns
`ResourceNotFoundException`. These exact error choices and optional-field matching rules have
not been compared with live AWS. A running session lasts at most 24 hours from creation;
a one-second sweep cancels active work and closes connections at that deadline.

Session ids appear in Execute, Batch, DescribeStatement and ListStatements responses. The id
is saved with statement metadata and idempotency receipts, but live connections are runtime-only:
reset and shutdown close them, and restarting Floci never reopens a session. A session request
with an existing receipt can still replay its original response after the connection expires or
after restart, subject to the normal receipt lifetime and caller ownership.

### Listing sessions

`ListSessions` returns session metadata created in the last 24 hours. It defaults to
`AVAILABLE` and `BUSY` sessions; `Status=CLOSED` includes retained metadata for sessions
closed explicitly, expired or retired after a connection failure. `SessionId` retrieves
one visible session regardless of status and cannot be combined with `Status`,
`ClusterIdentifier`, `WorkgroupName` or `Database`. A missing or foreign session id
returns `ResourceNotFoundException`.

Filter by `ClusterIdentifier` or `WorkgroupName` (name or ARN), optionally with
`Database`. Cluster and workgroup filters are mutually exclusive. `MaxResults` accepts
0 through 100; omitted or zero uses 100. Continue with `NextToken` and the same filters
and ownership scope. Results are ordered by creation timestamp and session id; this
ordering and the default page size are Floci choices, not claims about AWS.

Sessions remain isolated by account and Region. `RoleLevel` defaults to true and
lists sessions for the caller's IAM role across assumed-role sessions. False limits
results to the current caller session. Unresolved credentials are isolated by access
key; Floci does not model Identity Center identity-enhanced session restrictions.

`CreatedAt`, `UpdatedAt` and `SessionTtl` are epoch-second timestamps with the same
millisecond precision as statement timestamps.
`CurrentStatementId` appears only while the session is `BUSY`, including admitted
work waiting for a worker. `SessionAliveSeconds` reports the selected keep-alive.
Closed history contains metadata only, does not consume the 500-live-session quota,
and expires 24 hours after session creation. History is runtime-only: reset, shutdown
and restart remove it even when statement persistence is enabled. Failed admissions
do not create session history. The exact queued-state reporting and lifecycle timestamp
choices have not been compared with live AWS.

Unexpected synchronous connection-cleanup failures are reported to the affected operation.
Reset still attempts to close the remaining sessions and clear statement and receipt state before
reporting cleanup errors. Opportunistic expiration during `ListSessions` logs cleanup failures
without failing the listing. Background expiration failures are logged without stopping later sweeps.
An already-closed connection (`SQLSTATE 08003`) is a benign cleanup outcome.

```python
created = data.execute_statement(
    WorkgroupName="analytics", Database="dev", SessionKeepAliveSeconds=60,
    Sql="CREATE TEMP TABLE staging(id integer)")
# Poll DescribeStatement until FINISHED before submitting another query in this session.
session_id = created["SessionId"]
inserted = data.execute_statement(SessionId=session_id, Sql="INSERT INTO staging VALUES (42)")
# Poll inserted["Id"] before the next call.
selected = data.execute_statement(SessionId=session_id, Sql="SELECT * FROM staging")
```

See [AWS session reuse guidance](https://docs.aws.amazon.com/redshift/latest/mgmt/data-api.html)
for the idle timeout, 24-hour lifetime and single-query constraints.

## Compatibility Notes

- **Execution is asynchronous.** `ExecuteStatement` and `BatchExecuteStatement` return an `Id` after admission to the shared bounded worker pool. `DescribeStatement` progresses through `SUBMITTED`, `STARTED`, and `FINISHED`, `FAILED` or `ABORTED`. Poll before reading results. Optional `WaitTimeSeconds` accepts integers from 1 to 30 and waits up to that duration. HTTP, Step Functions and Scheduler use the same executor. Saturation returns `ActiveStatementsExceededException`.
- **Execution errors are not HTTP errors.** A statement that fails to run is stored with `Status=FAILED` and an `Error` message, and `ExecuteStatement` still returns 200 with the statement `Id`. Callers see the failure through `DescribeStatement`, matching AWS.
- **Storage and recovery.** Statement metadata and results use the configured storage mode. A sweep evicts terminal statements older than the 24 hour TTL; active statements are retained. With persistence enabled, interrupted nonterminal statements become `FAILED` after restart, without replaying SQL or emitting events. Memory mode loses results on restart.
- **`ExecuteStatement` takes one statement.** If the `Sql` contains more than one statement separated by `;`, it is rejected with `ValidationException`. Use `BatchExecuteStatement` for multiple statements.
- **`BatchExecuteStatement`** runs every entry of `Sqls` in order on one connection in a single transaction: it commits at the end, and on the first failing sub-statement it rolls back and marks the batch `FAILED` with that sub-statement's error. `DescribeStatement` on the parent id returns `SubStatements`, one per entry, each with its own id `<parentId>:<n>`. `GetStatementResult` on the parent id returns the rows of the last sub-statement that produced a result set; on a sub-statement id it returns that sub-statement's rows.
- **`GetStatementResult` and `GetStatementResultV2`** return the typed `Records` shape. `GetStatementResultV2` also returns `ResultFormat`, and when the statement was run with `ResultFormat=CSV` it returns `Records` as `CSVRecords` strings. A statement that produced no result set (an `INSERT`, `UPDATE`, `DELETE`, or DDL statement) returns `ValidationException` with the message `Statement has no result set`.
- **Paging.** The page size is 1000 rows. `NextToken` is an opaque base64 row offset; there is no server-side cursor.
- **`SqlParameters` / `Parameters`** are bound into a `PreparedStatement`. Named `:placeholder` markers are rewritten to positional JDBC bind parameters. Colons inside string literals, quoted identifiers, comments, PostgreSQL `::` casts, and dollar-quoted strings are left untouched. Redshift Data API parameter values are always strings on the wire; PostgreSQL coerces each bound value to the column type.
- **Batch `Parameters`** are shared by every entry in `Sqls`. Each statement can use a subset of the supplied parameters, and every supplied parameter must be referenced by at least one statement. Missing, unused, duplicate or malformed parameters are rejected with `ValidationException` before executing the batch. Parameter names can contain letters, digits and underscores, including names consisting only of digits. Values must be non-empty strings. `DescribeStatement` and `ListStatements` return the original batch parameters in `QueryParameters`; SQL text retains its named placeholders.
- **`CancelStatement`** returns `{ "Status": true }` when cancellation is accepted for queued or running work. Running transactional SQL receives JDBC cancellation and rolls back before reaching `ABORTED`; cancelling a batch rolls back its earlier writes. Cancelling a statement that is already terminal returns `ValidationException` ("Could not cancel a query that is already in ABORTED state with ID: <id>"), as AWS does. Repeated cancellation and statements that already own commit return `false` in Floci. Commands requiring auto-commit, including Spectrum external DDL and `VACUUM`, own completion before performing irreversible work and reject cancellation during that work. An unknown statement id returns `ResourceNotFoundException`.
- **`WithEvent=true`** publishes one terminal status event per parent statement to the default EventBridge bus in the captured account and region. Batch children do not emit events. False or omitted `WithEvent` emits none. Delivery occurs after terminal metadata is stored, on a separate bounded pool; disabled EventBridge, rejected delivery or target failures do not change the SQL outcome. Delivery is best effort, with no durable outbox or restart replay.
- **`ExecuteSql` and `BatchExecuteSql`** (the deprecated pre-2020 operations) return `ValidationException`.
- **Type mapping.** JDBC `BOOLEAN` and `BIT` map to `booleanValue`; integer types to `longValue`; floating-point types to `doubleValue`; `NUMERIC` and `DECIMAL` to `stringValue` (as AWS does); binary types to `blobValue`; everything else, including dates, timestamps, and uuid, to `stringValue`. A SQL `NULL` maps to `isNull`. A result column of type `line`, `json`, or `jsonb` fails the statement with the Redshift error text.

## Client token retries

`ExecuteStatement` and `BatchExecuteStatement` accept an optional `ClientToken`, a case-sensitive
string of 1 to 64 characters. Repeating an admitted request with the same token returns the
original statement `Id` and `CreatedAt` without running SQL or publishing its terminal event
again. This applies while the statement is queued or running and after it finishes, fails or is
cancelled. Omit the token, or use a new one, to submit independent work. AWS SDKs normally generate
the token automatically and keep it for their retries.

Tokens expire 8 hours after first admission. Retries do not extend this deadline. Validation,
reported storage errors and queue-admission failures do not consume the token. An SQL execution failure after
admission does consume it. `WaitTimeSeconds` on a retry can wait for the original execution without
submitting another one.

Request validation and database-target resolution run before admission coordination for new connections. Only requests
sharing an account, region, execute operation and token share an admission lock; tokenless requests
use no token lock. The executor reserves queue capacity before writing admission state, so a full
queue cannot create a receipt that reappears after restart. Workers run SQL only after admission
writes succeed. Reset still signals cancellation if clearing either storage backend reports an
error. Expired receipts are removed in one atomic batch per sweep, with concurrently replaced
receipts retained.

Receipts use the configured storage mode. Persistent, hybrid and WAL modes retain them across a
normal restart; memory mode loses them. Interrupted statements keep the existing recovery behavior:
they become `FAILED`, and SQL is never replayed automatically. This does not provide a distributed
transaction between PostgreSQL and Floci storage or an exactly-once guarantee after a host crash.
Persistent and WAL modes use atomic admission writes that report write failures before enqueueing
SQL. Hybrid mode retains its write-behind behavior: disk flush failures are logged while the live
in-memory state remains available.
The token deadline is independent of result retention: if a configured result TTL shorter than
8 hours removes the statement, its receipt still returns the original admission response until
the token expires, without restoring results.

Floci scopes tokens by account, region and execute operation. Requests for new connections validate
and resolve the database target before looking up a receipt, including on retries. SessionId-only
retries check an existing receipt first so a closed connection does not prevent replay; receipts
for retained sessions additionally check caller ownership. Within that scope, reusing
a token with changed SQL, parameters or event settings replays the original operation; it does not
change that operation. The precise AWS behavior for changed payloads, different IAM principals,
cross-operation reuse and responses after result eviction has not been verified against a live
AWS cluster. These are explicit Floci policies. See the
[AWS Data API guide](https://docs.aws.amazon.com/redshift/latest/mgmt/data-api.html) for the documented
8-hour token window and SDK retry behavior.

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

The legacy Phase 1 catalog is not the Glue-backed relational path.

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
