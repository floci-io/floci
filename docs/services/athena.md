# Athena

**Protocol:** JSON 1.1
**Endpoint:** `http://localhost:4566/`

Floci emulates Amazon Athena with **real SQL execution** powered by a [floci-duck](https://hub.docker.com/r/floci/floci-duck) sidecar container running DuckDB. When a query is submitted, Floci spins up the sidecar on first use, registers every Glue database as a DuckDB schema (`CREATE SCHEMA IF NOT EXISTS "<schema>"`) with qualified views (`"<db>"."<table>"`) pointing to S3 data, creates unqualified view aliases for the query context database in DuckDB's default `main` schema, then executes the SQL and stores results as CSV in S3.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateNamedQuery` | Saves SQL text in a workgroup and returns its ID |
| `GetNamedQuery` | Returns a saved query's name, database, SQL text, description and workgroup |
| `BatchGetNamedQuery` | Returns up to 50 saved queries and per-ID errors for missing queries |
| `ListNamedQueries` | Lists saved query IDs in a workgroup with pagination |
| `UpdateNamedQuery` | Updates a saved query's name, SQL text and description |
| `DeleteNamedQuery` | Deletes a saved query |
| `StartQueryExecution` | Submits a SQL query; executed asynchronously via DuckDB |
| `GetQueryExecution` | Returns query status (`QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`) |
| `GetQueryResults` | Returns the result set for a completed query |
| `ListQueryExecutions` | Returns a list of past query executions |
| `StopQueryExecution` | Cancels a running query |
| `GetWorkGroup` | Returns information about a workgroup |
| `ListWorkGroups` | Lists all workgroups |
| `CreateWorkGroup` | Creates a new workgroup |
| `UpdateWorkGroup` | Updates a workgroup's description, state and configuration |
| `ListDataCatalogs` | Lists the built-in `AwsDataCatalog` plus every registered data catalog |
| `GetDataCatalog` | Returns one data catalog, or `InvalidRequestException` when it does not exist |
| `CreateDataCatalog` | Registers a `LAMBDA`, `GLUE`, `HIVE` or `FEDERATED` data catalog |
| `UpdateDataCatalog` | Replaces the type, description and parameters of a data catalog |
| `DeleteDataCatalog` | Deletes a data catalog and returns the record it removed |
| `TagResource` | Adds tags to a workgroup or data catalog ARN |
| `UntagResource` | Removes tags from a workgroup or data catalog ARN |
| `ListDatabases` | - |
| `ListTableMetadata` | - |
| `GetTableMetadata` | - |
| `ListTagsForResource` | Returns the tags on a workgroup or data catalog |
| `DeleteWorkGroup` | Deletes a workgroup |
<!-- floci:actions:end -->

Named queries store SQL text without executing it or requiring the database to
exist. `CreateNamedQuery` defaults to the `primary` workgroup; a named workgroup
must already exist. `ClientRequestToken` is optional; requests without a token
create a new query each time. The AWS SDK and CLI supply a token automatically.
When supplied, it must contain 32 to 128 characters. Retrying the same token returns
the original ID, while changing its creation parameters returns
`InvalidRequestException`. Saved queries and retry requests use Athena's configured
storage mode and are isolated by account and region.

Every request is checked by `RequestConstraintCheck` against the types, required
members, bounds, patterns and enums generated from botocore's Athena service model.
`tools/aws/regen_athena_shapes.py` vendors every operation's input constraints;
`make aws-data-sync` regenerates them and `make aws-data-check` checks for drift.

`UpdateNamedQuery` changes the name, SQL text, and optionally the description;
the database and workgroup stay fixed. Omitting the description preserves it,
and an empty description clears it. A missing query returns
`InvalidRequestException` from get or update; batch get reports it under
`UnprocessedNamedQueryIds`. Deleting an absent query succeeds.

`DeleteWorkGroup` rejects a workgroup containing saved queries unless
`RecursiveDeleteOption` is `true`. Recursive deletion removes the group's saved
queries and creation retry records in the caller's account and region before
removing the workgroup. Recreating the same workgroup name starts with no saved
queries. The `primary` workgroup cannot be deleted.

`ListNamedQueries` defaults to `primary` and returns IDs in deterministic order.
`MaxResults` accepts 0 to 50; Floci uses 50 when it is omitted, null, or zero,
matching its `ListQueryExecutions` convention because AWS documents neither a
default nor the behavior of zero. Pass the opaque `NextToken` to continue; final
pages omit it. Tokens are scoped to the action, account, region, and workgroup,
and remain resumable after a cursor query is deleted. Invalid sizes or tokens
return `InvalidRequestException`.

`ListDataCatalogs` accepts `MaxResults` from 2 to 50. Floci uses 50 when it is
omitted, since the API reference does not document a default. The built-in
`AwsDataCatalog` stays first, followed by registered catalogs in name order.
`GetDataCatalog` requires `Name`; `ListDatabases`, `ListTableMetadata` and
`GetTableMetadata` require `CatalogName`. Name `AwsDataCatalog` explicitly to
read the built-in catalog. Floci reads databases and tables from the account's
Glue Data Catalog alone, so these three refuse a catalog whose metadata lives
elsewhere, a `LAMBDA`, `HIVE` or `FEDERATED` one or a `GLUE` one registered for
another account's `catalog-id`, with `MetadataException`, and a catalog that is
not registered with `InvalidRequestException`.
Pass the opaque `NextToken` to continue; final pages omit it. Tokens are scoped
to the action, caller account, and request region and remain resumable after a
cursor catalog is deleted. Malformed tokens, tokens outside the 1-to-1024
character range, and invalid page sizes return `InvalidRequestException`.

`ListQueryExecutions` returns at most `MaxResults` IDs from the requested
`WorkGroup`, defaulting to `primary`. Floci uses a bounded page of 50 when the
page size is omitted, null, or zero; the API reference permits zero but does not
specify its behavior or a default. Positive page sizes must not exceed 50.
IDs are ordered deterministically, and the final page omits `NextToken`.
Continuation tokens are scoped to the action, account, region, and workgroup;
invalid sizes, malformed tokens, and tokens outside 1 to 1024 characters return
`InvalidRequestException`.

A workgroup reports what its request gave and AWS's defaults for the rest. With
no `ResultConfiguration`, it reports none (`primary` reports an empty one, as on
AWS); a query then names its own output location, and Floci writes results to
`s3://floci-athena-results/results/` when neither does. With no `EngineVersion`,
it selects `AUTO`, reported with the effective version `Athena engine version 3`.
`ManagedQueryResultsConfiguration` (`Enabled` and an optional
`EncryptionConfiguration.KmsKey`) and `EnableMinimumEncryptionConfiguration`
round-trip through `CreateWorkGroup`, `UpdateWorkGroup`
(`ManagedQueryResultsConfigurationUpdates`, with `RemoveEncryptionConfiguration`)
and `GetWorkGroup`; Floci records the setting and keeps no separate managed
results storage.

## How it works

1. **Lazy sidecar start**: On the first `StartQueryExecution` call, Floci checks for a local `floci/floci-duck:latest` image and starts the container. Subsequent queries reuse the running container.
2. **Glue DDL injection**: Floci registers every Glue database as a DuckDB schema (`CREATE SCHEMA IF NOT EXISTS "<schema>"`) and creates qualified views (`CREATE OR REPLACE VIEW "<db>"."<table>"`) mapping each table to its S3 location via DuckDB's `read_parquet`, `read_json_auto`, or `read_csv_auto` functions, chosen based on the table's `InputFormat` or SerDe serialization library (a table with partition projection reads only its partition folders; see [Partition projection](#partition-projection)). In addition, tables in the query context database receive unqualified view aliases (`CREATE OR REPLACE VIEW "<table>"` in DuckDB's default `main` schema), so queries can reference tables as both `FROM db.table` and `FROM table`.
3. **Query execution**: The user's SQL is wrapped in `COPY (...) TO 's3://...' (FORMAT CSV, HEADER)` and executed. Results are written directly to the output S3 path.
4. **Results retrieval**: `GetQueryResults` reads the CSV back from S3 and returns it in the standard Athena `ResultSet` shape. Each column's `Type` comes from DuckDB describing the query, mapped to the Athena name (`bigint`, `integer`, `double`, `boolean`, `date`, `time`, `timestamp`, `decimal` and so on), with `Precision` and `Scale` for a decimal; a column whose type has no Athena name, such as an interval, and every column of a query DuckDB cannot describe are reported as `varchar`. DuckDB widens `sum` and `count_if` over integers to `HUGEINT`, which is reported as `bigint`, Trino's type for them; a sum past `bigint`'s range, which Athena fails with `NUMERIC_VALUE_OUT_OF_RANGE`, is returned instead.

## Format inference

**Iceberg tables are checked first**, ahead of the `StorageDescriptor` heuristic below: a table whose `Parameters.table_type` is `ICEBERG` (case-insensitive), as set by `pyiceberg`'s `GlueCatalog` and AWS's own Glue-Iceberg integration, is read via `iceberg_scan('<metadata_location>')`, using `Parameters.metadata_location` from the same Glue table. Iceberg tables never populate `InputFormat`/`SerializationLibrary` (they aren't read via a Hive input format), so without this check they always fell through to `read_csv_auto` and failed on the table's binary Parquet data files. `iceberg_scan` resolves the table through its real manifest list, so multi-snapshot tables (after updates, deletes, or repeated appends) read correctly instead of a naive glob picking up every data file ever written under the table's location. The `iceberg` DuckDB extension is installed and loaded once when the generated DDL contains at least one Iceberg table. A table flagged `ICEBERG` but missing `metadata_location` falls back to the format-sniffed heuristic below instead of emitting an unusable `iceberg_scan('')`.

For every other table, the DuckDB read function is chosen from the Glue table's `StorageDescriptor`:

| Condition | Read function |
|---|---|
| `InputFormat` or `SerializationLibrary` contains `parquet` | `read_parquet` |
| `InputFormat` or `SerializationLibrary` contains `json` | `read_json_auto` |
| `InputFormat` contains `hive` | `read_json_auto` |
| Anything else | `read_csv_auto` |

## Partition projection

A Glue table with `projection.enabled` set to `true` is read the way Athena reads it, from its partition layout rather than from its whole location:

- **Read path**: only the partition folders are read, `<location>/<key>=*/...` in the default `column=value` layout, or the `storage.location.template` with each `${key}` placeholder as a wildcard (one per folder, for a `date` format that holds slashes). Anything else stored under the location, such as query results, is not read as table data.
- **Partition values**: in the default layout, DuckDB's Hive partitioning supplies each key's value. With a template, the view takes each value from the object's S3 path by matching it against the template, and casts it to the key's declared type. A `date` projection whose `format` holds slashes (`yyyy/MM/dd/HH`, the Firehose layout) spans that many folders behind one placeholder. A repeated placeholder must hold the same value at each occurrence, and an object whose path value does not parse as its key's type is not read.
- **Validation**: as on Athena, a query fails when it leaves an `injected` key without a static equality condition in its `WHERE` clause (`CONSTRAINT_VIOLATION`), or when it reads a table whose template has no placeholder for one of its keys (`INVALID_TABLE_PROPERTY`). Such a table gets no view, so the other tables stay queryable.

Not yet supported:

- Values outside the projection (a date before the range or after `NOW`, an unlisted `enum` value) are read; Athena returns zero rows for them.
- A `date` or `timestamp` key with a `format` other than ISO is not parsed, so its objects are not read.
- The object path backs the template's values but is not exposed: Athena's `"$path"` pseudo-column cannot be selected.

## Configuration

| Property | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_ATHENA_MOCK` | `false` | Set to `true` to disable DuckDB execution: queries immediately succeed with empty results |
| `FLOCI_SERVICES_DUCK_DEFAULT_IMAGE` | `floci/floci-duck:latest` | DuckDB sidecar image pulled on first use |
| `FLOCI_SERVICES_DUCK_URL` | *(unset)* | Point to an existing floci-duck instance and skip container management |

## Example: simple query

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Start a query
QUERY_ID=$(aws athena start-query-execution \
  --query-string "SELECT 42 AS answer" \
  --query 'QueryExecutionId' \
  --output text)

# Wait for completion
aws athena get-query-execution --query-execution-id $QUERY_ID

# Get results
aws athena get-query-results --query-execution-id $QUERY_ID
```

## Example: data lake query (S3 + Glue + Athena)

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# 1. Create S3 bucket and upload data
aws s3 mb s3://my-data-lake
echo '{"id":1,"amount":10.0}
{"id":2,"amount":20.0}
{"id":3,"amount":30.0}' | aws s3 cp - s3://my-data-lake/orders/data.json

# 2. Register table in Glue
aws glue create-database --database-input '{"Name":"analytics"}'

aws glue create-table \
  --database-name analytics \
  --table-input '{
    "Name": "orders",
    "StorageDescriptor": {
      "Location": "s3://my-data-lake/orders/",
      "InputFormat": "org.apache.hadoop.mapred.TextInputFormat",
      "OutputFormat": "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat",
      "SerdeInfo": {
        "SerializationLibrary": "org.openx.data.jsonserde.JsonSerDe"
      },
      "Columns": [
        {"Name": "id",     "Type": "int"},
        {"Name": "amount", "Type": "double"}
      ]
    }
  }'

# 3. Run Athena query
QUERY_ID=$(aws athena start-query-execution \
  --query-string "SELECT sum(amount) AS total FROM orders" \
  --query-execution-context Database=analytics \
  --query 'QueryExecutionId' \
  --output text)

# 4. Poll until done
while true; do
  STATE=$(aws athena get-query-execution \
    --query-execution-id $QUERY_ID \
    --query 'QueryExecution.Status.State' \
    --output text)
  [ "$STATE" = "SUCCEEDED" ] && break
  [ "$STATE" = "FAILED" ] && echo "Query failed" && exit 1
  sleep 1
done

# 5. Fetch results
aws athena get-query-results --query-execution-id $QUERY_ID
```

## Shared sidecar with S3 Select

The floci-duck sidecar is shared between Athena and S3 Select. Once started by the first Athena query, it is also used by `SelectObjectContent` for CSV (with `FileHeaderInfo=USE`), JSON, and Parquet inputs. If Athena has not yet executed a query, S3 Select falls back to the built-in Java evaluator for CSV and JSON. Parquet always requires the sidecar.

See [S3 Select](s3.md#s3-select) for details on execution modes and supported SQL operators.

## Mock mode

Set `FLOCI_SERVICES_ATHENA_MOCK=true` to skip DuckDB entirely for Athena. In this mode queries transition to `SUCCEEDED` immediately with an empty result set, useful for unit tests that only exercise the Athena state machine, not the query results.

When mock mode is enabled the sidecar does **not** start. S3 Select will use the Java evaluator for CSV and JSON. Parquet queries will fail unless `FLOCI_SERVICES_DUCK_URL` points to an already-running floci-duck instance.
