# Glue

**Protocol:** JSON 1.1
**Endpoint:** `http://localhost:4566/`

Floci emulates the AWS Glue Data Catalog and Glue Schema Registry, allowing you to manage local data lake metadata and schema-version workflows.

## Supported Actions

### Data Catalog

| Area | Actions |
|---|---|
| Databases | `CreateDatabase` · `GetDatabase` · `GetDatabases` |
| Tables | `CreateTable` · `GetTable` · `GetTables` · `DeleteTable` |
| Partitions | `CreatePartition` · `GetPartitions` |

### Schema Registry

| Area | Actions |
|---|---|
| Registries | `CreateRegistry` · `GetRegistry` · `ListRegistries` · `UpdateRegistry` · `DeleteRegistry` |
| Schemas | `CreateSchema` · `GetSchema` · `ListSchemas` · `UpdateSchema` · `DeleteSchema` |
| Versions | `RegisterSchemaVersion` · `GetSchemaByDefinition` · `GetSchemaVersion` · `ListSchemaVersions` · `DeleteSchemaVersions` · `GetSchemaVersionsDiff` · `CheckSchemaVersionValidity` |
| Metadata and tags | `PutSchemaVersionMetadata` · `RemoveSchemaVersionMetadata` · `QuerySchemaVersionMetadata` · `TagResource` · `UntagResource` · `GetTags` |

Supported schema formats are `AVRO`, `JSON`, and `PROTOBUF`. Compatibility modes are `NONE`, `DISABLED`, `BACKWARD`, `BACKWARD_ALL`, `FORWARD`, `FORWARD_ALL`, `FULL`, and `FULL_ALL`.

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
