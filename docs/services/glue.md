# Glue

**Protocol:** JSON 1.1
**Endpoint:** `http://localhost:4566/`

Floci emulates the AWS Glue Data Catalog and Glue Schema Registry, allowing you to manage local data lake metadata and schema-version workflows.

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
| DeleteTable | Deletes a table definition from a database. |

#### Partitions

| Action | Description |
|--------|-------------|
| CreatePartition | Creates a partition for a Data Catalog table. |
| GetPartitions | Lists partitions stored for a Data Catalog table. |

#### User-defined Functions

| Action | Description |
|--------|-------------|
| CreateUserDefinedFunction | Creates a user-defined function in the Data Catalog. |
| GetUserDefinedFunction | Returns a stored user-defined function. |
| GetUserDefinedFunctions | Lists user-defined functions for a database. |
| UpdateUserDefinedFunction | Updates a stored user-defined function. |
| DeleteUserDefinedFunction | Deletes a user-defined function from a database. |

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

## Integration with Athena

The Glue Data Catalog is automatically used by **Athena** to resolve table names to S3 locations and formats. When you submit an Athena query, Floci reads all Glue tables for the target database and generates DuckDB views on top of the underlying S3 objects before executing the SQL.

Tables can reference a Schema Registry schema version through `StorageDescriptor.SchemaReference`. On `GetTable` and `GetTables`, Floci resolves the schema definition into Glue columns when possible.

The DuckDB read function is selected based on the table's `StorageDescriptor.InputFormat` and `StorageDescriptor.SerdeInfo.SerializationLibrary`:

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
