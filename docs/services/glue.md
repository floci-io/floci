# Glue

**Protocol:** JSON 1.1
**Endpoint:** `http://localhost:4566/`

Floci emulates the AWS Glue Data Catalog (databases, tables, partitions, functions, connections) and Glue Schema Registry, allowing you to manage local data lake metadata and schema-version workflows.

## Supported Actions

### Data Catalog

#### Databases

| Action | Description |
|--------|-------------|
| CreateDatabase | Creates a database in the local Glue Data Catalog. |
| GetDatabase | Returns a stored Data Catalog database. |
| GetDatabases | Lists databases in the local Glue Data Catalog. |
| DeleteDatabase | Deletes a database from the local Glue Data Catalog. |

#### Tables

| Action | Description |
|--------|-------------|
| CreateTable | Creates a table definition in the local Glue Data Catalog. |
| GetTable | Returns a stored table definition and resolves schema references when possible. |
| GetTables | Lists table definitions for a database. |
| UpdateTable | Replaces a table definition; the previous version is archived unless `SkipArchive` is set. |
| DeleteTable | Deletes a table definition from a database. |
| BatchDeleteTable | Deletes several tables of a database, reporting the missing ones in `Errors`. |
| GetTableVersions | Lists the current and archived versions of a table, newest first. |
| GetTableVersion | Returns one version by `VersionId`, or the current version when none is given. |
| DeleteTableVersion | Deletes an archived version; the current version cannot be deleted, as on AWS. |
| BatchDeleteTableVersion | Deletes several archived versions, reporting the ones not deleted in `Errors`. |
| SearchTables | Searches every table of the catalog by `SearchText` (substring, or exact when quoted), property `Filters` (string keys use the tokenised match of the API reference, time keys honour `Comparator`, other keys read table parameters) and `SortCriteria`, paged by `MaxResults` and `NextToken`. |

#### Partitions

| Action | Description |
|--------|-------------|
| CreatePartition | Creates a partition for a Data Catalog table. |
| GetPartitions | Lists partitions stored for a Data Catalog table. |
| BatchDeletePartition | Deletes up to 25 partitions, reporting the ones not found in `Errors`. |
| CreatePartitionIndex | Registers a partition index on a table. Keys must name partition columns, and a table holds at most 3 indexes. The index reports `CREATING` before `ACTIVE`. |
| GetPartitionIndexes | Lists a table's partition indexes, each with its keys resolved to name and type. |
| DeletePartitionIndex | Removes a partition index from a table. The index reports `DELETING` before it disappears. |

Partition indexes carry the AWS lifecycle. A new index reports `CREATING` and then `ACTIVE`; a
deleted one reports `DELETING` and then disappears. As on AWS, only one index per table may be
created or deleted at a time, and an index that is still `CREATING` cannot be deleted yet.

The transitions are driven by reads rather than by a timer, so they are deterministic: each
`GetPartitionIndexes` reports the current state and settles it, and a client that polls (as the
Terraform provider does) converges on its next call. The `FAILED` state is not emulated, since it
only arises from a backfill failure.

#### User-defined Functions

| Action | Description |
|--------|-------------|
| CreateUserDefinedFunction | Creates a user-defined function in the Data Catalog. |
| GetUserDefinedFunction | Returns a stored user-defined function. |
| GetUserDefinedFunctions | Lists user-defined functions for a database. |
| UpdateUserDefinedFunction | Updates a stored user-defined function. |
| DeleteUserDefinedFunction | Deletes a user-defined function from a database. |

#### Connections

| Action | Description |
|--------|-------------|
| CreateConnection | Creates a connection definition from a `ConnectionInput`, with optional `Tags`, and answers `CreateConnectionStatus: READY`. |
| GetConnection | Returns a stored connection. `HidePassword` omits `PASSWORD` and `ENCRYPTED_PASSWORD` from `ConnectionProperties`. |
| GetConnections | Lists connections, narrowed by `Filter.MatchCriteria`, `Filter.ConnectionType` and `Filter.ConnectionSchemaVersion`, paged by `MaxResults` and `NextToken`. |
| UpdateConnection | Redefines a connection from a full `ConnectionInput`, as on AWS: members left out of the input are dropped; the name and `CreationTime` are kept. |
| DeleteConnection | Deletes a connection and its tags. |
| BatchDeleteConnection | Deletes up to 25 connections, reporting the ones not found in `Errors`. |
| TestConnection | Accepts a connection name or an inline `TestConnectionInput` and answers with an empty body. |

`ConnectionInput` is validated against the API reference: `Name` (1 to 255 characters), `ConnectionType`
(the documented enumeration) and `ConnectionProperties` (the documented key list, at most 100 entries, and
empty for a `NETWORK` connection) are required; `MatchCriteria` holds at most 10 entries. Credentials given
under `AuthenticationConfiguration` are accepted and never returned by a read. A connection that uses
`AuthenticationConfiguration` or the `SparkProperties`, `AthenaProperties` or `PythonProperties` maps reports
`ConnectionSchemaVersion` 2; the classic JDBC, Kafka and network shape reports 1.

`HidePassword` removes `PASSWORD` and `ENCRYPTED_PASSWORD`, the two members the API reference defines as the
connection's password. The Kafka credentials (`KAFKA_CLIENT_KEYSTORE_PASSWORD`, `KAFKA_CLIENT_KEY_PASSWORD`,
`KAFKA_SASL_PLAIN_PASSWORD`, `KAFKA_SASL_SCRAM_PASSWORD` and their `ENCRYPTED_` forms) are returned as stored: the
reference does not say whether the flag covers them, so Floci does not guess. The `ENCRYPTED_` forms are what the
catalog's `ConnectionPasswordEncryption` setting produces (see Encryption settings below).

`TestConnection` is asynchronous on AWS and returns nothing; Floci checks the request's shape and accepts it
without opening a socket to the data store, since no job or crawler runs against a connection yet.

#### Resource policy

| Action | Description |
|--------|-------------|
| PutResourcePolicy | Sets the catalog's resource policy from `PolicyInJson` and returns its `PolicyHash`. `PolicyExistsCondition` (`NOT_EXIST`, `MUST_EXIST`, `NONE`) and `PolicyHashCondition` are checked against the stored policy and fail with `ConditionCheckFailureException`. |
| GetResourcePolicy | Returns the catalog policy with its hash and timestamps, or `EntityNotFoundException` when none is set. |
| GetResourcePolicies | Lists the catalog policy (zero or one entry) under `GetResourcePoliciesResponseList`, paged. |
| DeleteResourcePolicy | Deletes the catalog policy; honours `PolicyHashCondition`; `EntityNotFoundException` when none is set. |

The catalog holds one policy, as on AWS. `PolicyInJson` must be a JSON object. AWS documents `PolicyHash` only as
an opaque value to echo back; Floci derives it from the document, so the same policy always has the same hash.
`ResourceArn` ("for internal use only" in the reference) and `EnableHybrid` are accepted. Per-resource policies
that Resource Access Manager creates on AWS are not emulated, so `GetResourcePolicies` never has more than one entry.

#### Encryption settings

| Action | Description |
|--------|-------------|
| GetDataCatalogEncryptionSettings | Returns the catalog's security configuration: `EncryptionAtRest` (`CatalogEncryptionMode` `DISABLED` by default) and `ConnectionPasswordEncryption` (`ReturnConnectionPasswordEncrypted` false by default). |
| PutDataCatalogEncryptionSettings | Replaces the configuration. `CatalogEncryptionMode` is `DISABLED`, `SSE-KMS` or `SSE-KMS-WITH-SERVICE-ROLE`; `ReturnConnectionPasswordEncrypted` is required inside its block. A block left out keeps its default. |

`EncryptionAtRest` is stored and reported; catalog metadata is not encrypted on disk, which nothing observes through
the API. `ConnectionPasswordEncryption` is applied: while `ReturnConnectionPasswordEncrypted` is true, a connection
created or updated has each password property encrypted with `AwsKmsKeyId` through Floci's KMS and stored under the
name the `Connection` structure documents for it (`PASSWORD` as `ENCRYPTED_PASSWORD`, and the four Kafka passwords as
their `ENCRYPTED_KAFKA_*` forms). Reads return the ciphertext, base64-encoded, which `KMS.Decrypt` turns back into
the password. A missing key, or none configured, fails the create or update with `GlueEncryptionException`, the error
`CreateConnection` and `UpdateConnection` list for a failed encryption operation. Connections created before the setting
was switched on keep their plaintext: the developer guide ("Encrypting connection passwords") states that whether a
password is encrypted "was determined when the connection was created or updated", so the setting is not applied
retroactively.

#### Security configurations

| Action | Description |
|--------|-------------|
| CreateSecurityConfiguration | Creates a named security configuration. |
| GetSecurityConfiguration | Returns a named security configuration. |
| GetSecurityConfigurations | Lists stored security configurations. `MaxResults` and `NextToken` are currently ignored, so the complete list is returned. |
| DeleteSecurityConfiguration | Deletes a named security configuration. |

#### Jobs

| Action | Description |
|--------|-------------|
| CreateJob | Creates a new job definition. |
| GetJob | Retrieves an existing job definition. |
| GetJobs | Retrieves all current job definitions. |
| ListJobs | Lists job names, optionally only those carrying every given tag. |
| BatchGetJobs | Retrieves several job definitions by name and reports the names not found. |
| UpdateJob | Updates an existing job definition. |
| DeleteJob | Deletes a specified job definition and its runs. |
| StartJobRun | Starts a run of a job, with per-run arguments and capacity overrides. |
| GetJobRun | Retrieves one run of a job. |
| GetJobRuns | Lists a job's runs, newest first. |
| BatchStopJobRun | Stops running runs of a job and reports the rest in `Errors`. |

Job runs do not execute the job's script. A run follows Glue's state machine and response shape: it
takes the job's worker type, worker count, timeout and Glue version unless the request overrides them,
and `MaxConcurrentRuns` (1 when unset) rejects a start with `ConcurrentRunsExceededException` unless
`JobRunQueuingEnabled` is set. With the default `job-run-duration-seconds` of 0 a run is `SUCCEEDED` as
soon as it starts. A positive value keeps each run `RUNNING` for that many seconds, so a test can observe
`BatchStopJobRun` (the run ends `STOPPED`) and the concurrency limit; a run whose duration exceeds the job
timeout ends `TIMEOUT` when the timeout is reached. Queued runs are admitted straight away rather than
waiting in `WAITING`.

#### Crawlers

| Action | Description |
|--------|-------------|
| CreateCrawler | Creates a new crawler with specified targets, role, configuration, and optional schedule. |
| GetCrawler | Retrieves metadata for a specified crawler. |
| GetCrawlers | Retrieves metadata for all crawlers defined in the customer account. |
| ListCrawlers | Lists crawler names, optionally only those carrying every given tag. |
| BatchGetCrawlers | Retrieves several crawlers by name and reports the names not found. |
| UpdateCrawler | Updates a crawler; refused with `CrawlerRunningException` while a crawl runs. |
| DeleteCrawler | Removes a crawler and its crawl history; refused with `CrawlerRunningException` while a crawl runs. |
| StartCrawler | Starts a crawl. |
| StopCrawler | Stops the running crawl, which is then recorded as `CANCELLED`. |
| GetCrawlerMetrics | Reports time left, last and median runtime for the named crawlers, or for all of them. |
| UpdateCrawlerSchedule | Replaces a crawler's cron schedule. |
| StartCrawlerSchedule | Sets a crawler's schedule to `SCHEDULED`. |
| StopCrawlerSchedule | Sets a crawler's schedule to `NOT_SCHEDULED`. |

Crawls do not read the data store or write tables. A crawl follows the crawler's state machine:
`GetCrawler` reports `RUNNING` with `CrawlElapsedTime` while it runs, then `READY` with a `LastCrawl`
of `SUCCEEDED` (or `CANCELLED` after `StopCrawler`). With the default `crawler-run-duration-seconds` of
0 a crawl finishes as soon as it starts; a positive value keeps the crawler `RUNNING` that long.
`GetCrawlerMetrics` reports zero tables created, updated and deleted. Schedules are stored and their
state can be switched, but a schedule never starts a crawl on its own.

#### Triggers

| Action | Description |
|--------|-------------|
| CreateTrigger | Creates an `ON_DEMAND`, `SCHEDULED` or `CONDITIONAL` trigger whose actions start jobs or crawlers. |
| GetTrigger | Retrieves a trigger. |
| GetTriggers | Retrieves triggers; with `DependentJobName`, those that start the job, or all when none do. |
| ListTriggers | Lists trigger names, with the same `DependentJobName` rule and an optional tag filter. |
| BatchGetTriggers | Retrieves several triggers by name and reports the names not found. |
| UpdateTrigger | Applies the members a `TriggerUpdate` sets and returns the trigger. |
| DeleteTrigger | Deletes a trigger; deleting one that does not exist succeeds. |
| StartTrigger | Runs an `ON_DEMAND` trigger's actions now, or activates a `SCHEDULED` or `CONDITIONAL` one. |
| StopTrigger | Deactivates a `SCHEDULED` or `CONDITIONAL` trigger. |

An activated `CONDITIONAL` trigger fires for each job run or crawl its predicate watches that finishes
in the named state (`ANY` on each matching completion, `AND` on a matching completion once every
condition's latest outcome matches). Runs finished before the trigger was activated do not count.
Floci evaluates conditional triggers after each request that starts, stops or reads job runs or crawls,
so with the default run durations of 0 a whole chain of triggers completes within the request that
starts it; with a positive duration the trigger fires on the first such request after the watched run
finishes. One request runs at most 25 firing rounds; a longer chain continues on the next such request.
As an emulator safeguard (AWS has no such limit), triggers start at most 100 runs on behalf of the run
that set off a chain, the one no trigger started, so a loop of triggers of any shape stops there.
A run a trigger starts carries its `TriggerName`. `SCHEDULED` triggers are stored and can
be activated, but no timer fires them. `EVENT` triggers and `WorkflowName` need workflows, which are
not emulated yet.

#### Classifiers

| Action | Description |
|--------|-------------|
| CreateClassifier | Creates a Grok, XML, JSON, or CSV classifier. |
| GetClassifier | Retrieves a classifier by name. |
| GetClassifiers | Lists classifiers, paged by `MaxResults` and `NextToken`. |
| UpdateClassifier | Partially updates a classifier without changing its type. |
| DeleteClassifier | Deletes a classifier by name. |

### Schema Registry

#### Registries

| Action | Description |
|--------|-------------|
| CreateRegistry | Creates a schema registry. |
| GetRegistry | Returns a stored schema registry. |
| ListRegistries | Lists schema registries. |
| UpdateRegistry | Updates a schema registry's stored metadata. |
| DeleteRegistry | Deletes a schema registry. |

#### Schemas

| Action | Description |
|--------|-------------|
| CreateSchema | Creates a schema in a registry with the supplied data format and compatibility mode. |
| GetSchema | Returns a stored schema. |
| ListSchemas | Lists schemas in a registry. |
| UpdateSchema | Updates schema metadata or compatibility settings. |
| DeleteSchema | Deletes a schema from a registry. |

#### Versions

| Action | Description |
|--------|-------------|
| RegisterSchemaVersion | Registers a new schema version definition. |
| GetSchemaByDefinition | Finds a schema version that matches a supplied definition. |
| GetSchemaVersion | Returns a stored schema version. |
| ListSchemaVersions | Lists versions for a schema. |
| DeleteSchemaVersions | Deletes schema versions from a schema. |
| GetSchemaVersionsDiff | Returns the diff between two schema version numbers. |
| CheckSchemaVersionValidity | Validates a schema definition for the supplied data format. |

#### Metadata and Tags

| Action | Description |
|--------|-------------|
| PutSchemaVersionMetadata | Adds metadata to a schema version. |
| RemoveSchemaVersionMetadata | Removes metadata from a schema version. |
| QuerySchemaVersionMetadata | Returns metadata stored for matching schema versions. |
| TagResource | Adds tags to a Glue schema registry resource. |
| UntagResource | Removes tags from a Glue schema registry resource. |
| GetTags | Returns tags stored for a Glue schema registry resource. |

Supported schema formats are `AVRO`, `JSON`, and `PROTOBUF`. Compatibility modes are `NONE`, `DISABLED`, `BACKWARD`, `BACKWARD_ALL`, `FORWARD`, `FORWARD_ALL`, `FULL`, and `FULL_ALL`.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_GLUE_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_GLUE_JOB_RUN_DURATION_SECONDS` | `0` | Seconds a job run stays `RUNNING` before it succeeds; `0` finishes it as soon as it starts |
| `FLOCI_SERVICES_GLUE_CRAWLER_RUN_DURATION_SECONDS` | `0` | Seconds a crawl keeps the crawler `RUNNING` before it succeeds; `0` finishes it as soon as it starts |

## Integration with Athena

The Glue Data Catalog is automatically used by **Athena** to resolve table names to S3 locations and formats. When you submit an Athena query, Floci reads all Glue tables for the target database and generates DuckDB views on top of the underlying S3 objects before executing the SQL.

Tables can reference a Schema Registry schema version through `StorageDescriptor.SchemaReference`. On `GetTable` and `GetTables`, Floci resolves the schema definition into Glue columns when possible.

A table whose `Parameters.table_type` is `ICEBERG` (case-insensitive), as set by `pyiceberg`'s `GlueCatalog` and AWS's own Glue-Iceberg integration, is read via `iceberg_scan` against `Parameters.metadata_location` instead, following the table's real manifest list rather than its `StorageDescriptor`. See [Athena's format inference](athena.md#format-inference) for the full explanation.

For every other table, the DuckDB read function is selected based on the table's `StorageDescriptor.InputFormat` and `StorageDescriptor.SerdeInfo.SerializationLibrary`:

| Condition | DuckDB function |
|---|---|
| `InputFormat` or `SerializationLibrary` contains `parquet` | `read_parquet` |
| `InputFormat` or `SerializationLibrary` contains `json` | `read_json_auto` |
| `InputFormat` contains `hive` | `read_json_auto` |
| Anything else | `read_csv_auto` |

## Data Catalog Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a database
aws glue create-database \
  --database-input '{"Name": "analytics"}' \
  --endpoint-url $AWS_ENDPOINT_URL

# Create a JSON table (standard AWS format for NDJSON data)
aws glue create-table \
  --database-name analytics \
  --table-input '{
    "Name": "orders",
    "StorageDescriptor": {
      "Location": "s3://my-bucket/orders/",
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
  }' \
  --endpoint-url $AWS_ENDPOINT_URL

# Create a Parquet table
aws glue create-table \
  --database-name analytics \
  --table-input '{
    "Name": "events",
    "StorageDescriptor": {
      "Location": "s3://my-bucket/events/",
      "InputFormat": "org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat",
      "SerdeInfo": {
        "SerializationLibrary": "org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe"
      },
      "Columns": [
        {"Name": "event_id", "Type": "string"},
        {"Name": "ts",       "Type": "bigint"}
      ]
    }
  }' \
  --endpoint-url $AWS_ENDPOINT_URL
```

## Schema Registry Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

cat > /tmp/order.avsc <<'JSON'
{"type":"record","name":"Order","namespace":"example","fields":[{"name":"id","type":"long"}]}
JSON

cat > /tmp/order-v2.avsc <<'JSON'
{"type":"record","name":"Order","namespace":"example","fields":[{"name":"id","type":"long"},{"name":"amount","type":["null","double"],"default":null}]}
JSON

aws glue create-registry \
  --registry-name local-registry \
  --endpoint-url $AWS_ENDPOINT_URL

aws glue create-schema \
  --registry-id RegistryName=local-registry \
  --schema-name orders \
  --data-format AVRO \
  --compatibility BACKWARD \
  --schema-definition file:///tmp/order.avsc \
  --endpoint-url $AWS_ENDPOINT_URL

aws glue register-schema-version \
  --schema-id RegistryName=local-registry,SchemaName=orders \
  --schema-definition file:///tmp/order-v2.avsc \
  --endpoint-url $AWS_ENDPOINT_URL

aws glue list-schema-versions \
  --schema-id RegistryName=local-registry,SchemaName=orders \
  --endpoint-url $AWS_ENDPOINT_URL
```
