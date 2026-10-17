# RDS Data API

**Protocol:** REST JSON
**Endpoint:** `POST http://localhost:4566/{operation}`
**Backing data plane:** Local RDS MySQL / MariaDB / PostgreSQL containers

Floci implements the AWS RDS Data API routes used by AWS SDK clients and executes raw SQL against local RDS resources created through the RDS emulator. It supports MySQL, MariaDB, and PostgreSQL resources for local development workflows that already use `ExecuteStatement` and transactions.

For the upstream API shape, see the AWS RDS Data API documentation:

- [Using the Data API for Aurora DB clusters](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/data-api.html)
- [Data API operations](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/data-api-operations.html)
- [`ExecuteStatement`](https://docs.aws.amazon.com/rdsdataservice/latest/APIReference/API_ExecuteStatement.html)
- [`BeginTransaction`](https://docs.aws.amazon.com/rdsdataservice/latest/APIReference/API_BeginTransaction.html)
- [`CommitTransaction`](https://docs.aws.amazon.com/rdsdataservice/latest/APIReference/API_CommitTransaction.html)
- [`RollbackTransaction`](https://docs.aws.amazon.com/rdsdataservice/latest/APIReference/API_RollbackTransaction.html)
- [`BatchExecuteStatement`](https://docs.aws.amazon.com/rdsdataservice/latest/APIReference/API_BatchExecuteStatement.html)

## Supported Actions

| Action | Route | Required request fields | Description |
|---|---|---|---|
| `ExecuteStatement` | `POST /Execute` | `resourceArn`, `secretArn`, `sql` | Execute raw SQL against a local RDS cluster or instance |
| `BeginTransaction` | `POST /BeginTransaction` | `resourceArn`, `secretArn` | Open a JDBC transaction and return a transaction ID |
| `CommitTransaction` | `POST /CommitTransaction` | `resourceArn`, `secretArn`, `transactionId` | Commit an open transaction |
| `RollbackTransaction` | `POST /RollbackTransaction` | `resourceArn`, `secretArn`, `transactionId` | Roll back an open transaction |
| `BatchExecuteStatement` | `POST /BatchExecute` | `resourceArn`, `secretArn`, `sql` | Run one SQL statement once per entry in `parameterSets` |

The deprecated `ExecuteSql` operation is recognized at `POST /ExecuteSql` and returns an AWS-style `BadRequestException`.

## Compatibility Notes

- `resourceArn` and `secretArn` are required on Data API requests. `resourceArn` must identify an existing local RDS cluster or instance.
- Resolving a cluster ARN retries missing active member relays even if the cluster backend is already running. A failed member relay does not block SQL through the healthy cluster backend and is retried on a subsequent request. Healthy relays are not rebound and stopped members remain stopped. See [recoverable RDS backend startup failures](rds.md#recoverable-backend-startup-failures).
- `database` is optional when the resolved RDS resource has a database name; otherwise it must be provided. Transactional `ExecuteStatement` requests must use the same database as the active transaction when `database` is present.
- Transaction requests validate `resourceArn` against the active transaction resource. Floci resolves accepted ARN aliases to the local resource before comparing transaction identity.
- Requests sharing a `transactionId` do not queue behind an active request: `ExecuteStatement`, `BatchExecuteStatement`, `CommitTransaction`, and `RollbackTransaction` immediately return HTTP 400 `DatabaseErrorException` with `Transaction is still running a query`. Retry after the active request finishes. Other transactions remain independent, and expiry cleanup skips active requests. The idle timeout starts when the statement or batch finishes.
- MySQL, MariaDB, and PostgreSQL resources are supported. Aurora PostgreSQL resources resolve to the same PostgreSQL execution path.
- SQL is sent directly to the local database engine through JDBC. `SqlParameter` binding is supported for all engines: named `:placeholder` markers are rewritten to positional JDBC bind parameters and executed through a `PreparedStatement`. Colons inside string literals, quoted/backtick identifiers, comments, PostgreSQL `::` casts, and PostgreSQL dollar-quoted strings are left untouched. A placeholder used more than once binds its value at each position. Supported value variants are `stringValue`, `booleanValue`, `longValue`, `doubleValue`, `blobValue`, and `isNull`; `typeHint` values `DECIMAL`, `TIMESTAMP`, `DATE`, `TIME`, `UUID`, and `JSON` are honored. `arrayValue` parameters are not supported yet and return `BadRequestException`, as do malformed `parameters` payloads (not a JSON array, or an entry missing `name`).
- Result records include Data API field variants such as `stringValue`, `longValue`, `blobValue`, `booleanValue`, `doubleValue`, `arrayValue`, and `isNull`. One-dimensional SQL arrays use `longValues`, `stringValues`, `booleanValues`, or `doubleValues` according to their JDBC element type, including typed empty arrays. Decimal arrays use strings to preserve precision. Multidimensional arrays return `UnsupportedResultException`, as documented for `ExecuteStatement`. Arrays with null elements or binary elements also return `UnsupportedResultException`: the supported `ArrayValue` members cannot represent those elements without losing information. PostgreSQL bit-string arrays are rejected with the same error before JDBC decodes them as booleans; PostgreSQL boolean arrays remain supported. A null array column returns `isNull`. `records` is returned only for statements that produce a result set, empty when the query matched no rows, and is omitted entirely from the response to a statement that reports an update count, as AWS does. An `INSERT`, `UPDATE`, `DELETE`, or DDL statement therefore answers with `numberOfRecordsUpdated` and `generatedFields` and no `records` field at all.
- `includeResultMetadata` returns the full AWS `ColumnMetadata` shape per column: `label`, `name`, `type`, `typeName`, `tableName`, `schemaName`, `nullable`, `precision`, `scale`, `isSigned`, `isCaseSensitive`, `isCurrency`, `isAutoIncrement`, and `arrayBaseColumnType`. `label` is the result-set label (the alias when the query aliases a column) and `name` is the underlying column name, falling back to the label for computed columns. Clients that hydrate rows by `label` need it present. `type` is a JDBC type code and `typeName` is the engine's own type name (for example `4` and `int4` for a PostgreSQL integer), both taken from the driver so they match the engine actually serving the query. `arrayBaseColumnType` is the JDBC element type of a non-null array value, including an empty array. It remains `0` for non-array columns, queries matching no rows, or array columns containing only null values, because JDBC result metadata alone does not expose the element type.
- `ExecuteStatement` returns `generatedFields` for statements that report an update count on MySQL and MariaDB, carrying the auto-increment keys the engine generated. As on Aurora PostgreSQL, PostgreSQL resources always return an empty list — use a `RETURNING` clause to read generated values. `generatedFields` is omitted for statements that return a result set.
- `BatchExecuteStatement` runs the statement once per entry in `parameterSets` through a JDBC batch and returns one `updateResults` entry per set, each carrying the `generatedFields` that set produced under the same engine rules as `ExecuteStatement` — a set inserting several rows reports one field per generated key. On MySQL and MariaDB the sets are executed one at a time so each entry owns its own keys, because `getGeneratedKeys()` after a JDBC batch reports every key the batch generated with nothing tying a key back to the set that produced it. It accepts `transactionId`, so a batch can take part in a Data API transaction; without one the batch commits automatically. An absent or empty `parameterSets` runs nothing and returns an empty `updateResults`: AWS runs the statement once per parameter set provided and points a caller who wants a single parameterless execution at one empty set (`[[]]`, which runs once here) or at `ExecuteStatement`. A statement that returns rows rather than an update count — a `SELECT`, or a CTE ending in one — is rejected with `BadRequestException` before any set runs, because a batch reports one `UpdateResult` per set and has nowhere to carry a result set, and AWS documents the operation as taking a DML statement. The check reads the driver's description of the prepared statement, so nothing executes first, and a DML statement that also reports rows through a PostgreSQL `RETURNING` clause is left alone: the batch still performs its writes, and `generatedFields` is empty on PostgreSQL anyway.
- A result set in which any single row is larger than 64 KB is rejected with `UnsupportedResultException` and the message `Packet for query is too large`, as the Data API troubleshooting guide describes. A row is measured by its values as the driver returns them (the raw length of a binary value, the UTF-8 length of anything else), so JSON escaping and base64 encoding in the response do not count toward it.
- A PostgreSQL result set with a `point`, `box`, `circle`, `line`, `lseg`, `path`, `polygon`, `interval`, or `money` column is rejected with `UnsupportedResultException`, as on Aurora PostgreSQL. Cast the column to text to read it.
- Each executed SQL string must contain one statement. Multi-statement input is rejected with `ValidationException` and `Multistatements aren't supported.` before any statement runs, including in a transaction or a nonempty batch. Semicolons inside quoted literals, identifiers, comments, or dollar-quoted strings do not separate statements.
- SQL errors are returned as `DatabaseErrorException` so AWS SDK callers can handle database failures with normal AWS error decoding.
- If `secretArn` points to a local Secrets Manager secret with JSON credentials (`username` or `user`, plus `password`), those credentials are used. A missing or unavailable secret returns `SecretsErrorException`; malformed or incomplete credentials return `InvalidSecretException`. Floci does not fall back to the resolved RDS resource's master credentials when a secret ARN was supplied.
- `formatRecordsAs=JSON`, `formattedRecords`, and `resultSetOptions` are not implemented yet, and `ExecuteStatement` requests asking for those result modes return `BadRequestException`. They are `ExecuteStatement`-only fields in the AWS API, so `BatchExecuteStatement` — whose input is limited to `resourceArn`, `secretArn`, `sql`, `database`, `schema`, `parameterSets`, and `transactionId` — ignores them as AWS does rather than rejecting them.
- RDS records a cluster's `HttpEndpointEnabled` (`EnableHttpEndpoint` / `DisableHttpEndpoint`, see [RDS](rds.md#data-api-endpoint)), but the Data API does not check it: availability is controlled by `FLOCI_SERVICES_RDS_DATA_ENABLED` and whether the target local RDS resource is running.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_RDS_DATA_ENABLED` | `true` | Enable or disable the RDS Data API service |
| `FLOCI_SERVICES_RDS_DATA_TRANSACTION_TTL_SECONDS` | `180` | Idle timeout, in seconds, before leaked Data API transactions expire |
| `FLOCI_SERVICES_RDS_DATA_DATABASE_RESUMING_EXCEPTION_ENABLED` | `false` | Return `DatabaseResumingException` (HTTP 400) while an auto-paused Aurora backend resumes, instead of waiting for it |

The RDS Data API also requires the RDS service itself to be enabled because it resolves `resourceArn` values to local RDS containers.

With `database-resuming-exception-enabled=true`, `ExecuteStatement`, `BatchExecuteStatement`,
and `BeginTransaction` initiate resume in the background and return the retryable error before
opening a database connection. Calls made while resume is in progress return the same error;
retry after a few seconds. Once ready, requests run normally. Existing transactions keep their
backend awake until commit, rollback, or expiry. The default remains blocking resume for local
compatibility; TCP database connections and internal RDS commands always wait for resume.

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws rds create-db-cluster \
  --db-cluster-identifier appdb \
  --engine aurora-mysql \
  --master-username admin \
  --master-user-password secret123 \
  --database-name app \
  --endpoint-url "$AWS_ENDPOINT_URL"

RESOURCE_ARN=$(aws rds describe-db-clusters \
  --db-cluster-identifier appdb \
  --query 'DBClusters[0].DBClusterArn' \
  --output text \
  --endpoint-url "$AWS_ENDPOINT_URL")

SECRET_ARN=$(aws secretsmanager create-secret \
  --name appdb/data-api \
  --secret-string '{"username":"admin","password":"secret123"}' \
  --query ARN \
  --output text \
  --endpoint-url "$AWS_ENDPOINT_URL")

aws rds-data execute-statement \
  --resource-arn "$RESOURCE_ARN" \
  --secret-arn "$SECRET_ARN" \
  --database app \
  --sql "select 1 as count" \
  --include-result-metadata \
  --endpoint-url "$AWS_ENDPOINT_URL"

aws rds-data batch-execute-statement \
  --resource-arn "$RESOURCE_ARN" \
  --secret-arn "$SECRET_ARN" \
  --database app \
  --sql "insert into items (id, title) values (:id, :title)" \
  --parameter-sets '[
    [{"name":"id","value":{"longValue":1}},{"name":"title","value":{"stringValue":"first"}}],
    [{"name":"id","value":{"longValue":2}},{"name":"title","value":{"stringValue":"second"}}]
  ]' \
  --endpoint-url "$AWS_ENDPOINT_URL"
```
