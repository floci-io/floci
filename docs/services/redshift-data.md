# Redshift Data API

**Protocol:** JSON 1.1
**Endpoint:** `POST http://localhost:4566/` with `X-Amz-Target: RedshiftData.<Operation>` and `Content-Type: application/x-amz-json-1.1`
**Backing data plane:** the PostgreSQL container behind a Redshift cluster created through the Redshift emulator

Floci emulates the Amazon Redshift Data API: the HTTP API that Lambda, Step Functions, and EventBridge use to run SQL on a cluster without opening a PostgreSQL wire connection. Floci resolves the target cluster, connects straight to its container over JDBC, runs the SQL, and stores the statement and its result set in memory so the polling operations (`DescribeStatement`, `GetStatementResult`) can read them back.

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
| `ExecuteStatement` | Run one SQL statement synchronously against the cluster container and store the result |
| `BatchExecuteStatement` | Run `Sqls` in order on one connection in a single transaction |
| `DescribeStatement` | Return a stored statement's status, timings, row counts, and sub-statements |
| `GetStatementResult` | Page through a finished statement's rows as typed `Records` |
| `GetStatementResultV2` | Same rows in the V2 envelope, with `CSVRecords` when the statement used `ResultFormat=CSV` |
| `ListStatements` | List stored statements newest first, filtered by `StatementName` or `Status` |
| `CancelStatement` | Mark a non-finished statement `ABORTED`; returns `{ "Status": true }` |
| `ListDatabases` | List databases on the cluster (`pg_database`) |
| `ListSchemas` | List schemas, optionally filtered by `SchemaPattern` |
| `ListTables` | List tables and views, optionally filtered by `SchemaPattern` and `TablePattern` |
| `DescribeTable` | List a table's columns from `information_schema.columns` |
<!-- floci:actions:end -->

## Authentication modes

A request identifies its target cluster one of two ways:

- **`ClusterIdentifier` + `DbUser` + `Database`.** The `DbUser` must be the cluster master user. Floci connects directly to the container, so a non-master `DbUser` needs a real PostgreSQL role; until `GetClusterCredentials` is emulated, a non-master `DbUser` returns `ValidationException`.
- **`SecretArn` + `ClusterIdentifier` + `Database`.** The secret must be a local Secrets Manager secret holding JSON credentials (`username` or `user`, plus `password`). A cross-region `SecretArn` is rejected.

`WorkgroupName` (Amazon Redshift Serverless) is rejected with `ValidationException`. Redshift Serverless is not emulated.

## Compatibility Notes

- **Execution is synchronous.** `ExecuteStatement` runs the SQL and returns only once the statement is terminal. `DescribeStatement` reports `FINISHED` or `FAILED`, or `ABORTED` after a `CancelStatement`. It never reports `SUBMITTED` or `STARTED`, and there is no progression over wall-clock time.
- **Execution errors are not HTTP errors.** A statement that fails to run is stored with `Status=FAILED` and an `Error` message, and `ExecuteStatement` still returns 200 with the statement `Id`. Callers see the failure through `DescribeStatement`, matching AWS.
- **Results are in memory.** Statement metadata and result sets are held in a store swept on a 24 hour TTL and are lost when Floci restarts. `ListStatements` returns only what is currently in memory.
- **`ExecuteStatement` takes one statement.** If the `Sql` contains more than one statement separated by `;`, it is rejected with `ValidationException`. Use `BatchExecuteStatement` for multiple statements.
- **`BatchExecuteStatement`** runs every entry of `Sqls` in order on one connection in a single transaction: it commits at the end, and on the first failing sub-statement it rolls back and marks the batch `FAILED` with that sub-statement's error. `DescribeStatement` on the parent id returns `SubStatements`, one per entry, each with its own id `<parentId>:<n>`. `GetStatementResult` on the parent id returns the rows of the last sub-statement that produced a result set; on a sub-statement id it returns that sub-statement's rows.
- **`GetStatementResult` and `GetStatementResultV2`** return the typed `Records` shape. `GetStatementResultV2` also returns `ResultFormat`, and when the statement was run with `ResultFormat=CSV` it returns `Records` as `CSVRecords` strings. A statement that produced no result set (an `INSERT`, `UPDATE`, `DELETE`, or DDL statement) returns `ValidationException` with the message `Statement has no result set`.
- **Paging.** The page size is 1000 rows. `NextToken` is an opaque base64 row offset; there is no server-side cursor.
- **`SqlParameters` / `Parameters`** are bound into a `PreparedStatement`. Named `:placeholder` markers are rewritten to positional JDBC bind parameters. Colons inside string literals, quoted identifiers, comments, PostgreSQL `::` casts, and dollar-quoted strings are left untouched. Redshift Data API parameter values are always strings on the wire; PostgreSQL coerces each bound value to the column type.
- **`CancelStatement`** returns `{ "Status": true }`. In Floci a statement is already terminal by the time it can be cancelled, so `CancelStatement` sets `Status=ABORTED` only when the statement was not already `FINISHED`. An unknown statement id returns `ResourceNotFoundException`.
- **`WithEvent`** is accepted and ignored: no EventBridge event is published.
- **`ExecuteSql` and `BatchExecuteSql`** (the deprecated pre-2020 operations) return `ValidationException`.
- **Type mapping.** JDBC `BOOLEAN` and `BIT` map to `booleanValue`; integer types to `longValue`; floating-point types to `doubleValue`; `NUMERIC` and `DECIMAL` to `stringValue` (as AWS does); binary types to `blobValue`; everything else, including dates, timestamps, uuid, and json, to `stringValue`. A SQL `NULL` maps to `isNull`.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_REDSHIFT_DATA_ENABLED` | `true` | Enable or disable the Redshift Data API service |
| `FLOCI_SERVICES_REDSHIFT_DATA_RESULT_TTL_HOURS` | `24` | Hours a finished statement and its result set are kept in memory before the sweep evicts them |

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
  --sql "create table t (id int, name varchar(20))" \
  --query Id --output text \
  --endpoint-url "$AWS_ENDPOINT_URL")

aws redshift-data describe-statement --id "$STATEMENT_ID" --endpoint-url "$AWS_ENDPOINT_URL"

aws redshift-data execute-statement \
  --cluster-identifier wh --db-user admin --database dev \
  --sql "insert into t values (1, 'a'), (2, 'b')" \
  --endpoint-url "$AWS_ENDPOINT_URL"

SELECT_ID=$(aws redshift-data execute-statement \
  --cluster-identifier wh --db-user admin --database dev \
  --sql "select id, name from t order by id" \
  --query Id --output text \
  --endpoint-url "$AWS_ENDPOINT_URL")

aws redshift-data get-statement-result --id "$SELECT_ID" --endpoint-url "$AWS_ENDPOINT_URL"
```

```python
import boto3

data = boto3.client("redshift-data", endpoint_url="http://localhost:4566")

started = data.execute_statement(
    ClusterIdentifier="wh", DbUser="admin", Database="dev",
    Sql="select id, name from t where id = :id",
    Parameters=[{"name": "id", "value": "2"}],
)
statement_id = started["Id"]

while data.describe_statement(Id=statement_id)["Status"] not in ("FINISHED", "FAILED", "ABORTED"):
    pass

result = data.get_statement_result(Id=statement_id)
print(result["ColumnMetadata"], result["Records"])
```
