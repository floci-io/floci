# Glue

**Protocol:** JSON 1.1
**Endpoint:** `http://localhost:4566/`

Floci emulates the AWS Glue Data Catalog, allowing you to manage metadata for your Data Lake locally.

## Supported Actions

### Databases
`CreateDatabase` · `GetDatabase` · `GetDatabases` · `DeleteDatabase` · `UpdateDatabase`

### Tables
`CreateTable` · `GetTable` · `GetTables` · `DeleteTable` · `UpdateTable`

### Partitions
`CreatePartition` · `BatchCreatePartition` · `GetPartition` · `GetPartitions` · `DeletePartition`

## Integration with Athena

The Glue Data Catalog is automatically used by **Athena** to resolve table names to S3 locations and formats. When you define a table in Glue, Athena will know how to query the underlying S3 objects.

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a database
aws glue create-database --database-input '{"Name": "my_db"}' --endpoint-url $AWS_ENDPOINT_URL

# Create a table
aws glue create-table \
  --database-name my_db \
  --table-input '{
    "Name": "orders",
    "StorageDescriptor": {
      "Location": "s3://my-bucket/orders/",
      "InputFormat": "Parquet",
      "Columns": [
        {"Name": "id", "Type": "int"},
        {"Name": "amount", "Type": "double"}
      ]
    }
  }' \
  --endpoint-url $AWS_ENDPOINT_URL
```
