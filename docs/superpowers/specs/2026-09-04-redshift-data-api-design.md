# Redshift Data API (`redshift-data`) — design

Sub-project **A** of the Redshift API expansion epic
(`2026-09-04-redshift-api-epic-design.md`). Adds Floci's emulation of the Amazon
Redshift Data API: the `application/x-amz-json-1.1` HTTP API that Lambda, Step
Functions, and EventBridge use to run SQL on a cluster without opening a
PostgreSQL wire connection.

## Problem

The Redshift Data API is the primary way serverless code talks to Redshift. Floci
has none of it: an SDK `RedshiftDataClient.executeStatement(...)` against the
emulator gets an unknown-operation error. Callers that use it (very common in
Step Functions state machines and EventBridge Scheduler targets) cannot run
against Floci at all.

The API is **asynchronous in shape**: `ExecuteStatement` returns a statement `Id`
immediately, the caller polls `DescribeStatement` until `Status` is `FINISHED`
(or `FAILED` / `ABORTED`), then calls `GetStatementResult` to page through rows.
Boto3 has no waiter for this; callers hand-roll the poll loop, and Step Functions'
`redshift:executeStatement.sync` integration does the polling itself.

## Goals

- New `redshift-data` service, registered like other JSON 1.1 services, gated on
  `redshift.enabled && redshift-data.enabled`.
- Resolve a target cluster from either `ClusterIdentifier` (+ `DbUser`,
  `Database`) or `SecretArn`; reject `WorkgroupName` with a clear
  `ValidationException`.
- Execute SQL against the cluster's PostgreSQL container over JDBC, synchronously,
  inside the `ExecuteStatement` / `BatchExecuteStatement` call.
- Store each statement's metadata and result set in memory, swept on a 24 h TTL.
- Answer the full polling contract: `DescribeStatement`, `GetStatementResult`,
  `GetStatementResultV2`, `ListStatements`, `CancelStatement`.
- Answer the schema-introspection operations: `ListDatabases`, `ListSchemas`,
  `ListTables`, `DescribeTable`.
- Map JDBC types to the Data API `Field` union and `ColumnMetadata` shape.
- Bind typed `SqlParameters` / `Parameters` into a `PreparedStatement`.
- Docs page + Service Matrix row.

## Non-goals

- **Redshift Serverless** (`WorkgroupName`, workgroup ARNs). Rejected, not served.
- **True background execution.** No `SUBMITTED` → `STARTED` → `FINISHED`
  progression over wall-clock time; a statement is terminal by the time
  `ExecuteStatement` returns. `CancelStatement` on an already-finished statement
  returns success without doing anything, which is AWS-compatible for a completed
  statement.
- **Persistent result sets.** Statements and results are lost on Floci restart.
  `ListStatements` returns only what is in memory. Documented.
- **`ExecuteSql` / `BatchExecuteSql`** (the deprecated pre-2020 operations). Return
  `ValidationException` like the RDS Data API controller does for `ExecuteSql`.
- **`WithEvent` EventBridge emission.** The parameter is accepted and ignored; no
  event is published. (A later change can wire it to the EventBridge service.)
- **Result pagination beyond a simple offset cursor.** `NextToken` is an opaque
  encoded row offset; no server-side statement cursor.

## API surface

All operations are `POST /` with `X-Amz-Target: RedshiftData.<Operation>` and an
`application/x-amz-json-1.1` body.

### `ExecuteStatement`

Request fields used: `Sql` (required), `ClusterIdentifier`, `Database`, `DbUser`,
`SecretArn`, `StatementName`, `Parameters` (list of `{name, value}`),
`ResultFormat` (`JSON` default | `CSV` — stored, read back by
`GetStatementResultV2`), `WithEvent` (ignored), `ClientToken` (ignored).
`WorkgroupName` present → `ValidationException`.

Behaviour:

1. Resolve the target (see Resource resolution).
2. Open a JDBC connection to the cluster container.
3. If `Parameters` present, `PreparedStatement` with typed binds; else a plain
   `Statement`.
4. Execute. A single `Sql` may be one statement only (AWS rejects multi-statement
   `Sql` here — use `BatchExecuteStatement`); if `;`-split yields more than one
   non-empty statement, `ValidationException`.
5. Capture: `updateCount` or a `ResultSet` fully materialised into rows +
   `ColumnMetadata`, `ResultRows`, `ResultSize` (sum of serialized field bytes),
   `Duration` (nanos), start/end timestamps.
6. Store under a fresh UUID `Id`.

Response: `Id`, `ClusterIdentifier` (echo, null for secret-only), `CreatedAt`,
`Database`, `DbUser`, `DbGroups` (echo), `WorkgroupName` (null), `HasResultSet`
(bool), `SessionId` (null).

Failure to execute is **not** an HTTP error — the statement is stored with
`Status=FAILED` and `Error=<message>`, and `ExecuteStatement` still returns 200
with the `Id`. This matches AWS: execution errors surface through
`DescribeStatement`.

### `BatchExecuteStatement`

Same as `ExecuteStatement` but `Sqls` is a list. Each element becomes a
sub-statement. Executed in order on one connection, one transaction (autocommit
off, commit at the end; on any failure, roll back and mark the batch `FAILED`
with the failing sub-statement's error). The parent `Id` has `SubStatements` in
`DescribeStatement`; each sub-statement has its own id `<parentId>:<n>`, `Sql`,
`Status`, `ResultRows`, `Duration`, `HasResultSet`. `GetStatementResult` on the
parent id returns the last sub-statement that produced rows (AWS behaviour); on a
sub-statement id it returns that one's rows.

### `DescribeStatement`

Request: `Id`. Response: `Id`, `Status`
(`PICKED`|`STARTED`|`FINISHED`|`FAILED`|`ABORTED` — Floci only ever returns
`FINISHED`, `FAILED`, or `ABORTED` after a `CancelStatement`), `CreatedAt`,
`UpdatedAt`, `Duration`, `Error`, `QueryString` (the `Sql`), `RedshiftPid` (a
stable fake, e.g. hash of id), `RedshiftQueryId` (fake int), `ResultRows`,
`ResultSize`, `HasResultSet`, `ClusterIdentifier`, `Database`, `DbUser`,
`WorkgroupName` (null), `SubStatements` (batch only), `ResultFormat`.
Unknown `Id` → `ResourceNotFoundException`.

### `GetStatementResult` / `GetStatementResultV2`

Request: `Id`, `NextToken` (optional). Response `GetStatementResult`:
`ColumnMetadata` (list), `Records` (list of list of `Field`), `TotalNumRows`,
`NextToken`. `GetStatementResultV2` returns the same rows in the V2 envelope:
`ColumnMetadata`, `Records` as `FormattedRecords` when `ResultFormat=CSV`, else
the same typed `Records`; `ResultFormat`, `TotalNumRows`, `NextToken`. Floci
implements the typed path for both and the CSV path for V2 when the stored
statement was run with `ResultFormat=CSV` (default `JSON`).

`Id` with no result set (an update statement) → `ValidationException`
("Statement has no result set"), matching AWS. Unknown `Id` →
`ResourceNotFoundException`. Page size fixed at 1000 rows; `NextToken` is
`base64(offset)`.

### `ListStatements`

Request: `MaxResults` (default 100), `NextToken`, `StatementName` (exact or
prefix via `StatementName` + no wildcard — exact match only, AWS also supports
prefix; Floci does exact), `Status` (filter), `RoleLevel` (ignored — always
single-user). Response: `Statements` (list of `{Id, QueryString, QueryStrings
(the `Sqls` list for a batch, else a single-element list), Status, CreatedAt,
UpdatedAt, StatementName, IsBatchStatement, ResultRows, ResultSize, Duration}`),
`NextToken`. Sorted newest first.

### `CancelStatement`

Request: `Id`. If unknown → `ResourceNotFoundException`. If found (always already
terminal in Floci) → set `Status=ABORTED` only when it was not `FINISHED`;
respond `{Status: true}`. AWS returns `Status: true` on a successful cancel
request regardless.

### `ListDatabases`

Request: target fields + `MaxResults`, `NextToken`. Runs
`SELECT datname FROM pg_database WHERE datistemplate = false ORDER BY datname`.
Response: `Databases` (list of names), `NextToken`.

### `ListSchemas`

Request: target + `SchemaPattern` (optional SQL `LIKE`), `Database`,
`ConnectedDatabase`. Runs against `information_schema.schemata`. Response:
`Schemas`, `NextToken`.

### `ListTables`

Request: target + `SchemaPattern`, `TablePattern`, `Database`. Runs against
`information_schema.tables`. Response: `Tables` (list of
`{name, type, schema}` — `type` is `TABLE` / `VIEW`), `NextToken`.

### `DescribeTable`

Request: target + `Database`, `Schema`, `Table`, `ConnectedDatabase`. Runs
against `information_schema.columns`. Response: `TableName`, `ColumnList` (list of
`{name, typeName, length, nullable, isCaseSensitive, isCurrency, isSigned,
precision, scale, schemaName, tableName, columnDefault, label}`), `NextToken`.

## Design

All new code under
`src/main/java/io/github/hectorvent/floci/services/redshiftdata/`, mirroring
`services/rdsdata/`.

### Components

| Class | Role | Modelled on |
|---|---|---|
| `RedshiftDataJsonHandler` | `handle(action, request, region)` switch, reached through `AwsJson11Controller` | `AthenaJsonHandler` |
| `RedshiftDataService` | Orchestrates resolve → connect → execute → map → store; owns the statement store | `RdsDataService` |
| `RedshiftDataResourceResolver` | `ClusterIdentifier` or `SecretArn` → `DatabaseTarget(host, port, db, user, password)`; rejects `WorkgroupName` and cross-region ARNs | `RdsDataResourceResolver` |
| `RedshiftDataConnectionFactory` | `DriverManager` connection to the container's PostgreSQL; single engine, so no engine switch | `RdsDataConnectionFactory` |
| `RedshiftDataFieldMapper` | JDBC `ResultSet` row → `List<Field>`; `ResultSetMetaData` → `List<ColumnMetadata>`; `ResultSize` accounting | `RdsDataFieldMapper` |
| `RedshiftDataColumnMetadata` | DTO for one column's metadata | `RdsDataColumnMetadata` |
| `RedshiftDataSqlParameters` | Bind `Parameters` `{name, value}` into a `PreparedStatement` by `:name` rewrite | `RdsDataSqlParameters` |
| `RedshiftDataStatementStore` | `ConcurrentHashMap<String, StoredStatement>` + scheduled 24 h TTL sweep; `StoredStatement` holds metadata, rows, column metadata, error | new |

`RedshiftDataService implements Resettable` so `clear()` empties the store between
tests, like `RdsDataService`.

### Resource resolution

```
resolve(request, region) -> DatabaseTarget
  if request has WorkgroupName:
      throw ValidationException("Redshift Serverless is not emulated by Floci")
  if request has SecretArn:
      secret = secretsManagerService.getSecretValue(secretArn, null, null, region)
      creds  = parse JSON { "username": ..., "password": ... }
      clusterId = request.ClusterIdentifier   (required alongside SecretArn)
      cluster   = redshiftService.describeClusters(clusterId).get(0)
      user      = creds.username ; password = creds.password
  else:
      clusterId = request.ClusterIdentifier   (required)
      dbUser    = request.DbUser              (required)
      cluster   = redshiftService.describeClusters(clusterId).get(0)
      user      = dbUser
      password  = cluster.masterPassword  if dbUser == cluster.masterUsername
                  else  -> see note
  database = request.Database (required)
  host, port = cluster.containerHost, cluster.containerPort   (direct to container)
  if host blank or port <= 0:
      throw ValidationException("Cluster runtime is not available for Data API execution")
  return DatabaseTarget(arn, host, port, database, user, password)
```

Note on non-master `DbUser`: the emulator's auth proxy only authenticates the
master user; PostgreSQL owns everyone else. For the Data API we connect **straight
to the container**, so a non-master `DbUser` needs a real PostgreSQL role. Until
sub-project B (`GetClusterCredentials`) lands, a `DbUser` that is not the cluster
master and has no PostgreSQL password is a `ValidationException`
("DbUser <x> is not the cluster master; create it first or use GetClusterCredentials").
Sub-project B replaces this branch with a temp-credential lookup. This limitation
is documented.

`RedshiftService` currently exposes `describeClusters(String)` returning
`List<Cluster>` and throwing `ClusterNotFound` — reuse it. No new method needed on
the control plane. `Cluster` already carries `containerHost`, `containerPort`,
`masterUsername`, `masterPassword`.

### Execution

`RedshiftDataService.executeStatement`:

```
target = resolver.resolve(request, region)
id     = UUID
stored = new StoredStatement(id, sql, statementName, target, now)
try (Connection c = connectionFactory.open(target)) {
    if (parameters) bind via RedshiftDataSqlParameters
    long t0 = nanoTime()
    boolean hasRs = stmt.execute()
    if (hasRs) {
        ResultSet rs = stmt.getResultSet()
        stored.columns = fieldMapper.columns(rs.getMetaData())
        stored.rows    = fieldMapper.rows(rs)        // fully materialised
        stored.resultRows = stored.rows.size()
    } else {
        stored.resultRows = stmt.getUpdateCount()    // AWS reports affected rows here
        stored.hasResultSet = false
    }
    stored.durationNanos = nanoTime() - t0
    stored.resultSize = fieldMapper.serializedSize(stored.rows)
    stored.status = FINISHED
} catch (SQLException e) {
    stored.status = FAILED
    stored.error  = e.getMessage()
}
store.put(id, stored)
return executeResponse(stored)
```

`BatchExecuteStatement` wraps the loop in `c.setAutoCommit(false)` … `c.commit()` /
`c.rollback()`, one `StoredStatement` per `Sqls` element plus a parent that
references them.

### Type mapping (`RedshiftDataFieldMapper`)

`Field` is a union — exactly one of `stringValue`, `longValue`, `doubleValue`,
`booleanValue`, `blobValue` (base64), `isNull` (true). Mapping from
`ResultSetMetaData.getColumnType`:

| JDBC type | `Field` |
|---|---|
| `BIT`, `BOOLEAN` | `booleanValue` |
| `TINYINT`, `SMALLINT`, `INTEGER`, `BIGINT` | `longValue` |
| `REAL`, `FLOAT`, `DOUBLE` | `doubleValue` |
| `NUMERIC`, `DECIMAL` | `stringValue` (AWS returns numeric as string) |
| `CHAR`, `VARCHAR`, `LONGVARCHAR`, `DATE`, `TIME`, `TIMESTAMP`, `TIMESTAMP_WITH_TIMEZONE`, `OTHER` (uuid, json, jsonb, super) | `stringValue` |
| `BINARY`, `VARBINARY`, `LONGVARBINARY` | `blobValue` |
| SQL `NULL` (`rs.wasNull()`) | `isNull = true` |

`ColumnMetadata` per column: `name`, `label`, `typeName` (`md.getColumnTypeName`),
`nullable` (0/1/2), `length` (`getColumnDisplaySize`), `precision`, `scale`,
`isCaseSensitive`, `isCurrency`, `isSigned`, `schemaName`, `tableName`,
`columnDefault` (null — JDBC metadata does not carry it here).

`serializedSize` ≈ sum over fields of the UTF-8 byte length of the string form (or
base64 length for blobs); good enough for the `ResultSize` estimate AWS reports.

### Statement store & TTL

`RedshiftDataStatementStore`:

- `ConcurrentHashMap<String, StoredStatement>`.
- A `ScheduledExecutorService` (single daemon thread) sweeps every 30 min, evicting
  entries whose `createdAt` is older than `resultTtlHours` (default 24).
- `clear()` for `Resettable`.
- No size cap in v1; a follow-up can add an LRU bound if memory is a concern in
  long-running dev sessions.

### Registration & config

- `EmulatorConfig`:
  ```java
  interface RedshiftDataServiceConfig {
      @WithDefault("true")  boolean enabled();
      @WithDefault("24")    int resultTtlHours();
  }
  RedshiftDataServiceConfig redshiftData();   // in the services group
  ```
- `application.yml` under `services:`:
  ```yaml
  redshift-data:
    enabled: true
    result-ttl-hours: 24
  ```
- `ResolvedServiceCatalog`: new `descriptor(...)` row —
  external key `redshift-data`, `ServiceProtocol.JSON`,
  `protocols(ServiceProtocol.JSON)`, target prefix `Set.of("RedshiftData.")`,
  endpoint token `Set.of("redshift-data")`, no controller class (dispatched by
  `AwsJson11Controller`), enabled =
  `config.services().redshift().enabled() && config.services().redshiftData().enabled()`.
- `AwsJson11Controller`: inject `RedshiftDataJsonHandler`, add
  `case "redshift-data" -> redshiftDataJsonHandler.handle(action, request, region);`
  to the dispatch switch.
- IAM: add `AmazonRedshiftDataFullAccess` to `src/main/resources/iam/managed-policies.yaml`
  if the enforcement tests require the managed policy to exist.

## Testing

### Unit

- **`RedshiftDataResourceResolverTest`** — `ClusterIdentifier` + `DbUser` happy
  path; `SecretArn` path with a stubbed `SecretsManagerService`; `WorkgroupName`
  → `ValidationException`; unknown cluster → `ClusterNotFound`; cross-region ARN
  rejected; cluster with no container runtime → `ValidationException`.
- **`RedshiftDataFieldMapperTest`** — one row per JDBC type from an in-memory
  `ResultSet` (H2 or a hand-rolled fake); `NULL` → `isNull`; `ColumnMetadata`
  shape; `serializedSize` monotonic.
- **`RedshiftDataSqlParametersTest`** — `:name` rewrite, typed binds, missing
  parameter → `ValidationException`, SQL-injection-safe (bind not concatenate).
- **`RedshiftDataStatementStoreTest`** — put/get, TTL eviction with a clock hook,
  `clear()`.
- **`RedshiftDataServiceTest`** — with a mocked resolver + a real embedded
  PostgreSQL (Testcontainers, as `RdsDataServiceTest` does) or a mocked
  connection: `ExecuteStatement` FINISHED + result stored; execution error →
  stored `FAILED`, 200 response; `BatchExecuteStatement` rollback on the second
  statement; `GetStatementResult` on an update statement → `ValidationException`;
  `CancelStatement` on unknown id → `ResourceNotFoundException`;
  `DescribeStatement` sub-statements for a batch.

### Integration — `RedshiftDataApiIntegrationTest` (`@QuarkusTest`)

1. `RedshiftService.createCluster` a real cluster (Docker).
2. Drive the HTTP endpoint with the AWS SDK v2 `RedshiftDataClient` pointed at the
   Quarkus test port:
   - `executeStatement("CREATE TABLE t (id int, name varchar)")` → poll
     `describeStatement` → `FINISHED`.
   - `executeStatement("INSERT INTO t VALUES (1, 'a'), (2, 'b')")` →
     `ResultRows == 2` in `describeStatement`.
   - `executeStatement("SELECT * FROM t ORDER BY id")` →
     `getStatementResult` → 2 records, `ColumnMetadata` names `id`, `name`,
     first row `longValue=1`, `stringValue="a"`.
   - `batchExecuteStatement(["INSERT …", "SELECT count(*) FROM t"])` →
     `describeStatement` parent has 2 `SubStatements`; `getStatementResult` on the
     parent → the count row.
   - Parameterised: `executeStatement("SELECT * FROM t WHERE id = :id",
     Parameters=[{name:id, value:2}])` → 1 row.
   - `listDatabases` contains `dev`; `listTables` contains `t`;
     `describeTable("t")` lists columns `id`, `name`.
   - `cancelStatement` on a finished id → `{Status: true}`.
   - `WorkgroupName` set → `ValidationException`.
3. `@AfterEach` deletes the cluster.

### Compatibility — `compatibility-tests/sdk-test-java`

`RedshiftDataOperationsTest` using `software.amazon.awssdk.services.redshiftdata.RedshiftDataClient`
against a running Floci: create-cluster (Redshift client) → execute/describe/
get-result round trip → delete-cluster. Mirrors the existing
`RedshiftOperationsTest`.

## Docs

- New `docs/services/redshift-data.md`: endpoint, auth modes (ClusterIdentifier +
  DbUser, SecretArn), the synchronous-execution / immediate-FINISHED behaviour,
  the "results lost on restart" and "non-master DbUser needs GetClusterCredentials
  (sub-project B)" limitations, `WorkgroupName` unsupported, an AWS CLI example
  (`aws redshift-data execute-statement …` / `describe-statement` /
  `get-statement-result`) and a boto3 example.
- Add the `redshift-data` actions to the Service Matrix row (merge gate).
- Cross-link from `docs/services/redshift.md`.

## Commit plan

1. `feat(redshift-data): resource resolver, connection factory, statement store`
   — resolver + factory + store + config interface + `application.yml` block +
   unit tests.
2. `feat(redshift-data): field mapper and SQL parameter binding` — mapper +
   column metadata DTO + parameters + unit tests.
3. `feat(redshift-data): execute, batch, describe, get-result, list, cancel` —
   `RedshiftDataService` + `RedshiftDataJsonHandler` + `AwsJson11Controller`
   dispatch + `ResolvedServiceCatalog` descriptor + `RedshiftDataServiceTest`.
4. `feat(redshift-data): schema introspection operations` — `ListDatabases` /
   `ListSchemas` / `ListTables` / `DescribeTable`.
5. `test(redshift-data): end-to-end Data API over a real cluster` —
   `RedshiftDataApiIntegrationTest` + compatibility test.
6. `docs(redshift-data): document the Redshift Data API surface` —
   `docs/services/redshift-data.md` + Service Matrix row + cross-links.

## PR description

Sub-project A of the Redshift API expansion epic. New `redshift-data` service,
JSON 1.1, gated on `redshift.enabled && redshift-data.enabled`. State explicitly:
synchronous execution (statements terminal by the time `ExecuteStatement`
returns), results in-memory with 24 h TTL (lost on restart), `ClusterIdentifier +
DbUser` and `SecretArn` auth only, `WorkgroupName` / Redshift Serverless rejected,
non-master `DbUser` deferred to sub-project B.
