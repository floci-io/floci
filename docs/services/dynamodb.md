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
| `ImportTable` | Create a table and load DynamoDB JSON from S3 into it |
| `DescribeImport` | Get import status and metadata |
| `ListImports` | List imports, optionally filtered by table ARN |

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

## Import from S3

Create a new table and load it from newline-delimited DynamoDB JSON objects in S3. Each line is `{"Item": {...}}`, the format an export writes:

```bash
# Upload the data
printf '{"Item":{"userId":{"S":"u1"}}}\n{"Item":{"userId":{"S":"u2"}}}\n' > data.json
aws s3 cp data.json s3://my-exports/imports/data.json --endpoint-url $AWS_ENDPOINT_URL

# Start an import
IMPORT_ARN=$(aws dynamodb import-table \
  --s3-bucket-source S3Bucket=my-exports,S3KeyPrefix=imports/ \
  --input-format DYNAMODB_JSON \
  --input-compression-type NONE \
  --table-creation-parameters '{"TableName":"UsersCopy","AttributeDefinitions":[{"AttributeName":"userId","AttributeType":"S"}],"KeySchema":[{"AttributeName":"userId","KeyType":"HASH"}],"BillingMode":"PAY_PER_REQUEST"}' \
  --query ImportTableDescription.ImportArn --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

# Poll until COMPLETED
aws dynamodb describe-import \
  --import-arn $IMPORT_ARN \
  --query ImportTableDescription.ImportStatus \
  --endpoint-url $AWS_ENDPOINT_URL

# List imports
aws dynamodb list-imports --endpoint-url $AWS_ENDPOINT_URL
```

The import reads every object under the key prefix. Point it at the `data/` prefix of an export with `--input-compression-type GZIP` to load an export back. Floci does not evaluate bucket policies, so an `S3BucketOwner` that is not the caller's account fails the import with `S3AccessDenied`, as AWS does without a policy grant. The table stays in `CREATING` until the import finishes, then becomes `ACTIVE`. `DeleteTable` and `UpdateTable` return `ResourceInUseException` while the table is `CREATING`. Item calls such as `GetItem`, `PutItem`, `Query` and `Scan` return `ResourceNotFoundException` until the table is `ACTIVE`, as on AWS. A line that is not valid DynamoDB JSON or does not match the key schema is skipped and counted in `ErrorCount`. An object that cannot be read, for example a plain file under a `GZIP` import, is skipped and counted as one error. A missing bucket or an empty prefix ends the import as `FAILED` with a `FailureCode`. A reused `ClientToken` with different parameters returns `ImportConflictException`.

Deviations from AWS: only `InputFormat` `DYNAMODB_JSON` with `InputCompressionType` `NONE` or `GZIP` is accepted. `CSV`, `ION` and `ZSTD` are rejected with a `ValidationException`.


## Kinesis change data capture (CDC)

When a table has an **ACTIVE** Kinesis streaming destination (see
`EnableKinesisStreamingDestination`), every item change, `INSERT`, `MODIFY`, and `REMOVE`,
including TTL expirations, is forwarded to the destination stream as a Kinesis record in the
AWS CDC envelope (`eventName`, `dynamodb.Keys`, `NewImage`/`OldImage`, `ApproximateCreationDateTime`).

### Delivery contract

Forwarding is **bounded best-effort with in-process retry**. A write is never blocked or failed by
the destination stream: the change event is enqueued and delivered on a background drain, so a slow or
unavailable Kinesis stream cannot stall a `PutItem`/`UpdateItem`/`DeleteItem` or the TTL sweep.

The drain gives these guarantees per destination:

- **Retries** transient/unknown send failures with capped exponential backoff (250 ms doubling to an
  8 s cap, up to 10 attempts) rather than dropping the record on the first exception.
- **Preserves FIFO** across retries: the head record is not skipped past (stronger than AWS, which may
  reorder or duplicate).
- **Drops a poison record** immediately on a deterministic terminal failure
  (`ValidationException`/`InvalidArgumentException`) so it cannot wedge the queue behind it.
- **Gives up an episode** after the retry budget is exhausted, dropping the buffered records and marking
  the destination `GAVE_UP`; a later change event starts a fresh episode and self-heals once the stream
  recovers.
- **Bounds memory** at 1000 buffered records per destination, evicting the oldest on overflow.

Disabling a destination or deleting the table discards that destination's buffered records and stops all
future sends for it; a send already in flight at that instant may still complete (teardown cannot recall
an in-flight request, an inherent and harmless race). Buffered records are held **in memory only**: they
are not durable and are lost on restart (this is an emulator, not an at-least-once pipeline). Records that are permanently dropped (terminal, give-up,
or overflow) are counted and logged, and per-destination delivery health (forwarded/retried/dropped
counts, queue depth, last error, and current health) is tracked for inspection.
```
