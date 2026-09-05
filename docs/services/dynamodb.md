# DynamoDB

**Protocol:** JSON 1.1 (`X-Amz-Target: DynamoDB_20120810.*`)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

| Action | Description |
|---|---|
| `CreateTable` | Create a table with indexes |
| `DeleteTable` | Delete a table |
| `DescribeTable` | Get table metadata |
| `ListTables` | List all tables |
| `UpdateTable` | Update throughput, indexes, streams |
| `PutItem` | Write an item |
| `GetItem` | Read an item by primary key |
| `DeleteItem` | Delete an item |
| `UpdateItem` | Partially update an item |
| `Query` | Query by partition key with optional filter |
| `Scan` | Full table scan with optional filter |
| `BatchWriteItem` | Write/delete up to 25 items across tables |
| `BatchGetItem` | Read up to 100 items across tables |
| `TransactWriteItems` | ACID write transaction |
| `TransactGetItems` | ACID read transaction |
| `DescribeTimeToLive` | Get TTL configuration |
| `UpdateTimeToLive` | Enable/disable TTL on a table |
| `TagResource` | Tag a table |
| `UntagResource` | Remove tags |
| `ListTagsOfResource` | List tags |
| `DescribeContinuousBackups` | Get PITR backup configuration |
| `UpdateContinuousBackups` | Enable/disable PITR |
| `DescribeKinesisStreamingDestination` | List Kinesis streaming destinations |
| `EnableKinesisStreamingDestination` | Enable Kinesis streaming for a table |
| `DisableKinesisStreamingDestination` | Disable Kinesis streaming for a table |
| `ExportTableToPointInTime` | Export table data to S3 as gzip NDJSON |
| `DescribeExport` | Get export status and metadata |
| `ListExports` | List exports, optionally filtered by table ARN |

## Streams {#streams}

DynamoDB Streams are supported via a separate target (`DynamoDBStreams_20120810`):

| Action | Description |
|---|---|
| `ListStreams` | List all streams |
| `DescribeStream` | Get stream and shard info |
| `GetShardIterator` | Get a shard iterator |
| `GetRecords` | Read stream records from a shard |

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_DYNAMODB_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_STORAGE_SERVICES_DYNAMODB_MODE` | *(global default)* | Storage mode override for DynamoDB (`memory`, `persistent`, `hybrid`, `wal`) |
| `FLOCI_STORAGE_SERVICES_DYNAMODB_FLUSH_INTERVAL_MS` | `5000` | Flush interval for `hybrid`/`wal` storage modes (milliseconds) |

### Storage and Performance

Under `persistent` storage mode, single-item writes (`PutItem`, `UpdateItem`, `DeleteItem`) flush the affected table to disk synchronously. Batch operations (`BatchWriteItem` and `TransactWriteItems`) batch disk flushes per affected table across the entire operation, rather than flushing on every individual item mutation.

For write-heavy workloads under persistent setups, configuring `FLOCI_STORAGE_SERVICES_DYNAMODB_MODE=wal` or `hybrid` is recommended to avoid full-file rewrites on each write operation:
- `wal`: Uses an append-only write-ahead log with background compaction.
- `hybrid`: Keeps data in memory with periodic asynchronous disk flushes controlled by `FLOCI_STORAGE_SERVICES_DYNAMODB_FLUSH_INTERVAL_MS`.

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a table
aws dynamodb create-table \
  --table-name Users \
  --attribute-definitions \
    AttributeName=userId,AttributeType=S \
  --key-schema \
    AttributeName=userId,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST \
  --endpoint-url $AWS_ENDPOINT_URL

# Put an item
aws dynamodb put-item \
  --table-name Users \
  --item '{"userId":{"S":"u1"},"name":{"S":"Alice"},"age":{"N":"30"}}' \
  --endpoint-url $AWS_ENDPOINT_URL

# Get an item
aws dynamodb get-item \
  --table-name Users \
  --key '{"userId":{"S":"u1"}}' \
  --endpoint-url $AWS_ENDPOINT_URL

# Query (partition key)
aws dynamodb query \
  --table-name Users \
  --key-condition-expression "userId = :id" \
  --expression-attribute-values '{":id":{"S":"u1"}}' \
  --endpoint-url $AWS_ENDPOINT_URL

# Scan with filter
aws dynamodb scan \
  --table-name Users \
  --filter-expression "age > :min" \
  --expression-attribute-values '{":min":{"N":"25"}}' \
  --endpoint-url $AWS_ENDPOINT_URL

# Enable TTL
aws dynamodb update-time-to-live \
  --table-name Users \
  --time-to-live-specification Enabled=true,AttributeName=expiresAt \
  --endpoint-url $AWS_ENDPOINT_URL

# Enable Streams
aws dynamodb update-table \
  --table-name Users \
  --stream-specification StreamEnabled=true,StreamViewType=NEW_AND_OLD_IMAGES \
  --endpoint-url $AWS_ENDPOINT_URL
```

## Global Secondary Indexes

```bash
aws dynamodb create-table \
  --table-name Orders \
  --attribute-definitions \
    AttributeName=orderId,AttributeType=S \
    AttributeName=customerId,AttributeType=S \
  --key-schema AttributeName=orderId,KeyType=HASH \
  --global-secondary-indexes '[{
    "IndexName": "CustomerIndex",
    "KeySchema": [{"AttributeName":"customerId","KeyType":"HASH"}],
    "Projection": {"ProjectionType":"ALL"}
  }]' \
  --billing-mode PAY_PER_REQUEST \
  --endpoint-url $AWS_ENDPOINT_URL
```

## Export to S3

Export table data to an S3 bucket as gzip-compressed NDJSON (DynamoDB JSON format):

```bash
# Create a bucket to receive the export
aws s3 mb s3://my-exports --endpoint-url $AWS_ENDPOINT_URL

# Start an export
EXPORT_ARN=$(aws dynamodb export-table-to-point-in-time \
  --table-arn arn:aws:dynamodb:us-east-1:000000000000:table/Users \
  --s3-bucket my-exports \
  --s3-prefix exports \
  --export-format DYNAMODB_JSON \
  --query ExportDescription.ExportArn --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

# Poll until COMPLETED
aws dynamodb describe-export \
  --export-arn $EXPORT_ARN \
  --query ExportDescription.ExportStatus \
  --endpoint-url $AWS_ENDPOINT_URL

# List exports for a table
aws dynamodb list-exports \
  --table-arn arn:aws:dynamodb:us-east-1:000000000000:table/Users \
  --endpoint-url $AWS_ENDPOINT_URL
```

The export writes to `s3://<bucket>/<prefix>/AWSDynamoDB/<exportId>/data/` as one or more `.json.gz` files, along with `manifest-summary.json` and `manifest-files.json`, the same layout as real AWS DynamoDB exports.