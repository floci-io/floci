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

Query executions are kept per account and region, as AWS keeps them. A query
execution is listed and answered only in the region it was started in:
`GetQueryExecution`, `GetQueryResults` and `StopQueryExecution` naming one
started in another region, or one Athena holds none of, return
`InvalidRequestException` with `QueryExecution <id> was not found`. An execution
stored before Floci recorded each execution's region is answered in every region.

`GetQueryResults` on a query that has not succeeded returns
`InvalidRequestException` worded by its state, as AWS words it: `Query has not
yet finished. Current state: <state>` for one still queued or running, `Query did
not finish successfully. Final query state: FAILED` for a failed one, and `Could
not find results` for a cancelled one.

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
2. **Glue DDL injection**: Floci registers every Glue database as a DuckDB schema (`CREATE SCHEMA IF NOT EXISTS "<schema>"`) and, for each table the query names, creates qualified views (`CREATE OR REPLACE VIEW "<db>"."<table>"`) mapping each table to its S3 location via DuckDB's `read_parquet`, `read_json_auto`, or `read_csv_auto` functions, chosen based on the table's `InputFormat` or SerDe serialization library (a table with partition projection reads only its partition folders; see [Partition projection](#partition-projection)). In addition, tables in the query context database receive unqualified view aliases (`CREATE OR REPLACE VIEW "<table>"` in DuckDB's default `main` schema), so queries can reference tables as both `FROM db.table` and `FROM table`.
3. **Query execution**: The user's SQL is wrapped in `COPY (...) TO 's3://...' (FORMAT CSV, HEADER)` and executed. Results are written directly to the output S3 path. How the query string and its `ExecutionParameters` are read is described in [Query strings and parameters](#query-strings-and-parameters).
4. **Results retrieval**: `GetQueryResults` reads the CSV back from S3 and returns it in the standard Athena `ResultSet` shape. Each column's `Type` comes from DuckDB describing the query, mapped to the Athena name (`bigint`, `integer`, `double`, `boolean`, `date`, `time`, `timestamp`, `decimal` and so on), with `Precision` and `Scale` for a decimal; a column whose type has no Athena name, such as an interval, and every column of a query DuckDB cannot describe are reported as `varchar`. DuckDB widens `sum` and `count_if` over integers to `HUGEINT`, which is reported as `bigint`, Trino's type for them; a sum past `bigint`'s range, which Athena fails with `NUMERIC_VALUE_OUT_OF_RANGE`, is returned instead. A `timestamp` value is rendered as Athena renders it, with at least three fractional digits (`2026-03-14 09:30:00.000`); a timestamp literal written with fewer, which Athena renders at its own precision, is padded too. The result CSV object in S3 keeps DuckDB's form, as does `CAST(ts AS varchar)` inside a query.

## Query strings and parameters

`StartQueryExecution` reads a query string as Athena engine version 3 does:

- The whitespace and semicolons around the statement are dropped: `GetQueryExecution` returns `SELECT 1 ;` and `;SELECT 1` as `SELECT 1`. A comment at either end stays.
- A query holding more than one statement, meaning anything (a comment included) on both sides of a semicolon, is rejected with an `InvalidRequestException` whose `AthenaErrorCode` is `MALFORMED_QUERY`. A semicolon inside a literal, a quoted identifier (backtick-quoted included) or a comment separates nothing; as on Athena, Hive's backslash escapes are not read here. Where Trino and DuckDB end a nested block comment at different places, a second statement under either reading counts.
- When the query runs on DuckDB (so not in mock mode, nor for Hive DDL, `CREATE DATABASE` or `VACUUM`), its `ExecutionParameters` are bound to its `?` placeholders in order. Each value is the SQL text of an expression: a string is passed quoted (`'eu'`), a number bare (`10`), and `SELECT ? * 2` with `1 + 1` gives `4`. A value that is not one expression, because it holds a semicolon, a comma outside parentheses or brackets, or ones that don't balance or pair up (`(1]`), or leaves a quote open, is bound as a string of its text with whitespace and comments dropped, as Athena binds it; so is a lone name (`x`, `"x"`), even where a column has it, while `TRUE`, `NULL`, `CURRENT_DATE` and the like stay expressions. A `?` inside a literal, a quoted identifier or a comment is not a placeholder. A query without placeholders runs whatever parameters it is given; otherwise a count that does not match, no parameters at all included, fails the query with `INVALID_PARAMETER_USAGE: line 1:1: Incorrect number of parameters: expected 2 but found 1`. `GetQueryExecution` does not return the parameters, and named prepared statements (`PREPARE` / `EXECUTE ... USING`) are not supported.

## Trino SQL on DuckDB

Athena engine version 3 runs Trino SQL; Floci runs it on DuckDB, so a few Trino constructs are bridged before the query reaches the sidecar:

- **JSON functions.** `json_extract_scalar`, `json_parse`, `json_format`, `json_size` and `is_json_scalar` are defined for every query and answer as Trino does: `json_extract_scalar` returns a scalar as text and `NULL` for an object, an array, a JSON `null` or a missing path, and `json_parse` and `json_format` give compact JSON. Their paths accept Trino's bracketed member names (`$["a b"]`). `json_extract` and `json_array_length` are DuckDB's own and already match. `json_array_get` and `json_array_contains` are not bridged.
- **Types in `CAST` and `TRY_CAST`.** `ARRAY<T>` and `ARRAY(T)` become DuckDB's `T[]`, `MAP<K, V>` becomes `MAP(K, V)` and `ROW(name T, ...)` becomes `STRUCT(name T, ...)`, so `CROSS JOIN UNNEST(CAST(json_parse(col) AS ARRAY<JSON>)) AS t(item)` works.
- **Multi-argument `UNNEST`.** `CROSS JOIN UNNEST(a, b) AS t (x, y)`, which zips the arrays by position and pads the shorter with `NULL` (a `NULL` array counting as empty), runs as `CROSS JOIN LATERAL (SELECT unnest(a), unnest(b)) AS t (x, y)`, which DuckDB zips and pads the same way, under `LEFT JOIN ... ON TRUE` too. The alias and a column list naming one column per array are needed; a source without them is left for DuckDB to reject. A single-argument `UNNEST` of an array, with or without `WITH ORDINALITY`, runs as written. Not yet bridged: a multi-argument `UNNEST ... WITH ORDINALITY`, and `UNNEST` of a `MAP` into key and value columns.
- **Result types.** A JSON result column is reported as `json` and an unsigned count such as `json_array_length` as `bigint`, as Athena reports them.

## Format inference

**Iceberg tables are checked first**, ahead of the `StorageDescriptor` heuristic below: a table whose `Parameters.table_type` is `ICEBERG` (case-insensitive), as set by `pyiceberg`'s `GlueCatalog` and AWS's own Glue-Iceberg integration, is read via `iceberg_scan('<metadata_location>')`, using `Parameters.metadata_location` from the same Glue table. Iceberg tables never populate `InputFormat`/`SerializationLibrary` (they aren't read via a Hive input format), so without this check they always fell through to `read_csv_auto` and failed on the table's binary Parquet data files. `iceberg_scan` resolves the table through its real manifest list, so multi-snapshot tables (after updates, deletes, or repeated appends) read correctly instead of a naive glob picking up every data file ever written under the table's location. The `iceberg` DuckDB extension is installed and loaded once when the generated DDL contains at least one Iceberg table. A table flagged `ICEBERG` but missing `metadata_location` falls back to the format-sniffed heuristic below instead of emitting an unusable `iceberg_scan('')`.

For every other table, the DuckDB read function is chosen from the Glue table's `StorageDescriptor`:

| Condition | Read function |
|---|---|
| `InputFormat` or `SerializationLibrary` contains `parquet` | `read_parquet` |
| `InputFormat` or `SerializationLibrary` contains `json` | `read_json_auto` |
| `InputFormat` contains `hive` | `read_json_auto` |
| Anything else | `read_csv_auto` |

A delimited-text table that declares its CSV options (`field.delim` or `separatorChar`, `skip.header.line.count`) in its table, storage descriptor or SerDe parameters, such as a `LazySimpleSerDe` table over headerless TSV, is read with `read_csv` and exactly those options instead of being sniffed, its files' columns named after the declared data columns. Each data column and Hive partition value takes its declared type, and a value that type cannot hold reads as NULL, as with Hive's `LazySimpleSerDe`. A row with fewer fields than the declared columns (a file written before `ALTER TABLE ADD COLUMNS`) gets NULL for the rest, and fields past them are dropped. For a `LazySimpleSerDe` table, `\N` reads as NULL unless `serialization.null.format` says otherwise, an empty field is an empty string, and a quote is text. Compressed files (`.gz`, `.zst`) are read as they are.

A data column declared `timestamp` in a Parquet table, or in a delimited-text table that declares its CSV options (above), reads as Athena reads it, as a `timestamp(3)`: the UTC wall-clock time, truncated to milliseconds. That holds however a Parquet file stores it: UTC-adjusted (which DuckDB would otherwise read as a `timestamp with time zone`), in micro- or nanoseconds, or as INT96. A Parquet file whose column holds something else is read by its physical type, as Athena reads it: an INT64 without a logical type as epoch milliseconds, while a string (even an ISO one) or a date fails the query with Athena's `HIVE_BAD_DATA` or `SERIALIZATION_ERROR` (without the file's path). Any other physical type fails the query with DuckDB's cast error. A query that doesn't select such a column is unaffected. In a JSON table, or a delimited-text table read by sniffing, a `timestamp` column keeps the type DuckDB infers, and a `timestamp` partition key keeps its microseconds.

Only the tables a query names get views (by database and name; a name only a string literal or a comment holds doesn't count), since DuckDB binds every view of the batch before the query runs: a table whose view cannot be built (files that do not match its declared format or schema) then fails the queries that read it and no others. A query that reads `information_schema` or is a `SHOW` statement gets a view for every table.

A partitioned table without partition projection is read through the partitions registered for it in Glue, as Athena reads it: each partition from its own location, with its values as the partition keys. The partitions come from `ALTER TABLE ADD PARTITION`, `MSCK REPAIR TABLE` or Glue's `CreatePartition` and `BatchCreatePartition`. A partition without a location of its own is read at `<table location>/key=value/...`. A folder under the table's location that no partition names isn't read, a partition's location may lie outside it, a non-Hive layout (`2026/11/16/`) is read through the partitions added for it, and a table with no partitions registered answers no rows until they are loaded. The partitions are read together with their columns matched by name, so a partition written before a column was added reads NULL for it. A partition value its key's type cannot hold fails the query with Athena's `HIVE_INVALID_PARTITION_VALUE`, and a partition that does not give one value for each key, which Glue refuses on AWS, is not read.

A table whose location holds no data, or only folder markers (keys ending in `/`), answers no rows with its declared columns as their types, as Athena does: its view selects no rows rather than reading the empty location, which DuckDB refuses to bind. Nested and binary columns take the types the engine reads them as (`array<string>` as `VARCHAR[]`, `binary` as `BLOB`). For a table with partition projection, only the objects its read path reaches count. A location holding another glob character (`?`, a bracket or a brace), which Athena does not take in a location, is read as before.

## Partition projection

A Glue table with `projection.enabled` set to `true` is read the way Athena reads it, from its partition layout rather than from its whole location:

- **Read path**: only the partition folders are read, `<location>/<key>=*/...` in the default `column=value` layout, or the `storage.location.template` with each `${key}` placeholder as a wildcard (one per folder, for a `date` format that holds slashes). Anything else stored under the location, such as query results, is not read as table data.
- **Partition values**: in the default layout, DuckDB's Hive partitioning supplies each key's value, read as text and cast to the key's declared type, so a `string` key holding `2026-11-14` stays a string; a folder whose value the key's type cannot hold (`dt=latest` for a `date`) is not read. A `date` or `timestamp` key whose date projection sets a `format` is parsed with it in either layout (`dt=20261116` for `yyyyMMdd`); a format using pattern letters other than `yyyy`, `yy`, `MM`, `dd`, `HH`, `mm` and `ss` is cast as an ISO value. With a template, the view takes each value from the object's S3 path by matching it against the template, and casts it to the key's declared type. A `date` projection whose `format` holds slashes (`yyyy/MM/dd/HH`, the Firehose layout) spans that many folders behind one placeholder. A repeated placeholder must hold the same value at each occurrence, and an object whose path value does not parse as its key's type is not read.
- **Validation**: as on Athena, a query fails when it leaves an `injected` key without a static equality condition in its `WHERE` clause (`CONSTRAINT_VIOLATION`), or when it reads a table whose template has no placeholder for one of its keys (`INVALID_TABLE_PROPERTY`). Such a table gets no view, so the other tables stay queryable.

Not yet supported:

- Values outside the projection (a date before the range or after `NOW`, an unlisted `enum` value) are read; Athena returns zero rows for them.
- A `date` or `timestamp` key with a `format` other than ISO is not parsed, so its objects are not read.
- The object path backs the template's values but is not exposed: Athena's `"$path"` pseudo-column cannot be selected.

## Hive DDL

Hive DDL runs against the Glue Data Catalog rather than the query engine, as it does on Athena, and its query reports `StatementType` `DDL`:

- `CREATE EXTERNAL TABLE [IF NOT EXISTS]` creates the Glue table: its columns and `PARTITIONED BY` keys, `COMMENT`, `CLUSTERED BY ... INTO n BUCKETS`, `ROW FORMAT DELIMITED` (stored as the `LazySimpleSerDe` parameters Hive uses, `field.delim` and the rest) or `ROW FORMAT SERDE ... WITH SERDEPROPERTIES`, `STORED AS` (`TEXTFILE`, the default, `PARQUET`, `ORC`, `AVRO`, `SEQUENCEFILE`, `RCFILE`, `ION`, or `INPUTFORMAT ... OUTPUTFORMAT ...`), `LOCATION` and `TBLPROPERTIES`. Names are lower-cased and string literals take Hive's backslash escapes, so `FIELDS TERMINATED BY '\t'` stores a tab. `SORTED BY` is kept as the table's sort columns. Every format is registered, but Floci's query engine reads only Parquet, JSON and delimited text (see [Format inference](#format-inference)): an `ORC`, `AVRO`, `ION`, `RCFILE` or `SEQUENCEFILE` table serves other Glue clients, such as Spark on EMR, and Athena queries on it fail. A `CREATE TABLE` without `EXTERNAL` fails unless it creates an Iceberg table (see [Iceberg tables](#iceberg-tables)) or is a `CREATE TABLE AS`, which goes to the query engine.
- `DROP TABLE [IF EXISTS]` removes a Hive table's metadata and leaves its data in S3. An Iceberg table is a managed table, so dropping it deletes the objects under its location too, as Athena does.
- `ALTER TABLE ... ADD [IF NOT EXISTS] PARTITION (...) [LOCATION '...'] [PARTITION ...]` creates the partitions; one without a `LOCATION` is placed at `<table location>/key=value/...`. `ALTER TABLE ... DROP [IF EXISTS] PARTITION (...)[, PARTITION ...]` deletes them. A partition specification has to name every partition key.
- `MSCK REPAIR TABLE` lists the table's location and adds a partition for every Hive-style `key=value/...` folder path that holds an object and has none yet, with that folder, spelled as it is in S3, as the partition's location. A folder name with an invalid escape is skipped. Like Athena it only adds partitions, never removes them.
- `SHOW PARTITIONS` returns one `partition` column, a `key=value[/key=value...]` row per partition, values escaped as Hive escapes folder names.

Without `IF [NOT] EXISTS`, a table or partition that already exists, or does not, fails the query. Other `ALTER TABLE` statements are not supported.

### Query executions and named queries under IAM enforcement

With IAM enforcement on, a request naming a query execution by its `QueryExecutionId` (`GetQueryExecution`,
`GetQueryResults`, `GetQueryRuntimeStatistics`, `StopQueryExecution`) or a named query by its `NamedQueryId`
(`GetNamedQuery`, `UpdateNamedQuery`, `DeleteNamedQuery`) is checked on the workgroup it runs in,
`arn:aws:athena:<region>:<account>:workgroup/<name>`, `primary` for one naming none, as the Service Authorization
Reference gives these actions the workgroup. A query execution is checked on its workgroup in the Region it was
started in, whichever Region the request is sent to, so a grant on another Region's workgroup of the same name does
not reach it and a deny on its own workgroup holds. An id Athena holds none of names no workgroup, and the request is
checked as one naming none.

### Glue permissions under IAM enforcement

Athena runs a DDL statement against the Glue Data Catalog with the caller's identity, so with IAM enforcement on (`floci.services.iam.enforcement-enabled`) a statement of the default catalog also needs the Glue permissions Athena's user guide gives it in [Examples of database and table-level permissions](https://docs.aws.amazon.com/athena/latest/ug/fine-grained-access-to-glue-resources.html). Floci checks each action of the guide's example policy for the statement on each resource of it: the catalog, and the database and table the statement names. For example, `CREATE DATABASE` needs `glue:GetDatabase` and `glue:CreateDatabase`, and `CREATE TABLE` needs `glue:GetDatabase` and `glue:GetDatabases` on the catalog and database, and `glue:GetTables`, `glue:GetTable`, `glue:GetPartitions` and `glue:CreateTable` on the catalog, database and table. The guide's examples are what Floci requires in full, `glue:CreateDatabase` for `MSCK REPAIR TABLE` included, so a policy that grants less is refused even where AWS might allow it. As the guide says, `DROP TABLE` of a table without partitions needs no partition action.

A missing permission does not refuse `StartQueryExecution`. The query starts and fails, as on AWS, with a `StateChangeReason` of `Insufficient permissions to execute the query. ` followed by the denial of the first Glue request refused, for example `User: arn:aws:iam::123456789012:user/analyst is not authorized to perform: glue:GetDatabase on resource: arn:aws:glue:us-east-1:123456789012:catalog because no identity-based policy allows the glue:GetDatabase action `.

The statements checked are `CREATE DATABASE`, `CREATE [EXTERNAL] TABLE`, `DROP TABLE` and `MSCK REPAIR TABLE`, the ones the guide lists that Floci runs. The guide gives no permissions for `ALTER TABLE ... ADD PARTITION`, `ALTER TABLE ... DROP PARTITION`, `SHOW PARTITIONS`, `VACUUM`, `SELECT` or other DML, so those are not checked against Glue, and neither are statements in an [S3 Tables catalog](#s3-tables-catalogs). The table of permissions is vendored from the guide's page by `tools/aws/fetch_athena_glue_ddl_permissions.py` and read into the IAM request table by `tools/aws/regen_iam_request_resources.py`.

## Iceberg tables

Athena manages Iceberg tables through AWS Glue's Iceberg REST catalog, which Floci serves (see [Glue's Iceberg REST endpoint](glue.md#iceberg-rest-endpoint)):

- `CREATE TABLE [IF NOT EXISTS] [db.]table (col type [COMMENT '...'], ...) [PARTITIONED BY (col | transform, ...)] LOCATION 's3://...' TBLPROPERTIES ('table_type'='ICEBERG' [, ...])` creates an empty Iceberg v2 table: a Glue table whose `table_type` is `ICEBERG` and whose `metadata_location` names its first metadata file. The partition transforms are `year(ts)`, `month(ts)`, `day(ts)`, `hour(ts)`, `bucket(N, col)` and `truncate(L, col)`, each on the column types Athena lists for it (`year`, `month` and `day` on a date or timestamp, `hour` on a timestamp, `bucket` on an int, bigint, decimal, date, timestamp, string or binary, `truncate` on an int, bigint, decimal or string); `bucket` and `truncate` need a positive width. As on Athena, only Athena's Iceberg table properties are accepted (`format`, `write_compression`, `optimize_rewrite_data_file_threshold`, `optimize_rewrite_delete_file_threshold`, `vacuum_min_snapshots_to_keep`, `vacuum_max_snapshot_age_seconds`, `vacuum_max_metadata_files_to_keep` and `write_data_path_enabled`), and `tinyint`, `smallint` and `char` columns are refused. Floci writes data files as Parquet, so a `format` other than `parquet` is refused. The query reports `StatementType` `DDL`.
- `INSERT INTO`, `UPDATE`, `DELETE FROM` and `MERGE INTO` on an Iceberg table run in the query engine, which attaches Glue's Iceberg REST catalog for the statement, with a token for the calling account and Region that is revoked when the statement ends, and writes the table through it. DuckDB writes the data, delete and manifest files, and each commit moves the Glue table's `metadata_location`, so Spark's `GlueCatalog` and later Athena queries read it.
- `VACUUM [db.]table` expires the snapshots older than `vacuum_max_snapshot_age_seconds` (default 432000, 5 days), except the newest `vacuum_min_snapshots_to_keep` (default 1) of each branch and every tagged snapshot. It then deletes the table's metadata files beyond the newest `vacuum_max_metadata_files_to_keep` (default 100), and every file under the table's location that no remaining snapshot references and that is older than the maximum snapshot age, orphan files included. Files are deleted only from a bucket the calling account owns. While one of Floci's own writes to the table is running, `VACUUM` expires snapshots but leaves the files for its next run; another engine's write still in flight is protected only by the age, as on Athena. A table at a bucket's root has its snapshots expired but no files deleted.
- `DROP TABLE` deletes an Iceberg table's files too (see [Hive DDL](#hive-ddl)).

`CREATE TABLE AS` with `table_type = 'ICEBERG'` and `OPTIMIZE` are not supported yet.

### S3 Tables catalogs

A table bucket integrated with the Glue Data Catalog (see [Glue's catalogs and Amazon S3 Tables](glue.md#catalogs-and-amazon-s3-tables)) is the catalog `s3tablescatalog/<bucket>`, with its namespaces as databases. With that catalog in the query's `QueryExecutionContext` (`{"Catalog": "s3tablescatalog/<bucket>", "Database": "<namespace>"}`), or named in the query (`"s3tablescatalog/<bucket>".<namespace>.<table>`):

- DDL statements run in the context catalog, naming a table `[namespace.]table` as Athena's do. `CREATE DATABASE` creates a namespace, and `CREATE TABLE ... TBLPROPERTIES ('table_type'='iceberg')` an Iceberg table in the bucket; it names no `LOCATION`, since S3 Tables decides where the table lives.
- `SELECT`, `INSERT INTO`, `UPDATE`, `DELETE FROM` and `MERGE INTO` run in the query engine, which attaches each S3 Tables catalog the query names under that name, through Glue's Iceberg REST endpoint with a token for the calling account. A context catalog's namespace resolves names given without one. With no `Database`, the context database is Athena's `default`, so only the names the query qualifies resolve, unless the bucket has a `default` namespace. A write naming `awsdatacatalog.<database>.<table>` writes the default catalog's Iceberg table.
- `DROP TABLE`, in the context catalog, deletes the table, data and all.

As on Athena, `OPTIMIZE` and `VACUUM` are refused (S3 manages compaction and snapshots), and so are Hive DDL statements. `CREATE TABLE AS` into an S3 Tables catalog is not supported yet.

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

`CREATE DATABASE`, the [Hive DDL](#hive-ddl) statements and an Iceberg `CREATE TABLE` still change the Glue catalog in mock mode, and `SHOW PARTITIONS` still returns its rows, since none of them needs the sidecar. Writes to an Iceberg table and `VACUUM` need it, so they succeed without effect.

When mock mode is enabled the sidecar does **not** start. S3 Select will use the Java evaluator for CSV and JSON. Parquet queries will fail unless `FLOCI_SERVICES_DUCK_URL` points to an already-running floci-duck instance.
