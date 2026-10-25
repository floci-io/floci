# DynamoDB

**Protocol:** JSON 1.0 (`X-Amz-Target: DynamoDB_20120810.*`)
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
| `SearchVectors` | Rank a vector index by distance from a query vector |
| `BatchWriteItem` | Write/delete up to 25 items across tables |
| `BatchGetItem` | Read up to 100 items across tables |
| `TransactWriteItems` | ACID write transaction |
| `TransactGetItems` | ACID read transaction |
| `DescribeTimeToLive` | Get TTL configuration |
| `UpdateTimeToLive` | Enable/disable TTL on a table |
| `TagResource` | Tag a table |
| `UntagResource` | Remove tags |
| `ListTagsOfResource` | List tags |
| `DescribeContinuousBackups` | Get the point-in-time recovery status and restore window |
| `UpdateContinuousBackups` | Enable or disable point-in-time recovery |
| `RestoreTableToPointInTime` | Create a new table from a table as it was at a point in time |
| `CreateBackup` | Take an on-demand backup of a table |
| `DescribeBackup` | Get a backup and the table it was taken from |
| `ListBackups` | List backups by table, type and creation time |
| `DeleteBackup` | Delete a backup |
| `RestoreTableFromBackup` | Create a new table from a backup |
| `DescribeKinesisStreamingDestination` | List Kinesis streaming destinations |
| `EnableKinesisStreamingDestination` | Enable Kinesis streaming for a table |
| `DisableKinesisStreamingDestination` | Disable Kinesis streaming for a table |
| `ExportTableToPointInTime` | Export table data, or the changes of a period, to S3 |
| `CreateGlobalTable` | Make an existing table a global table (2017.11.29) |
| `DescribeGlobalTable` | Read a global table's replication group |
| `UpdateGlobalTable` | Add or remove replica regions |
| `ListGlobalTables` | List global tables, optionally filtered by region |
| `DescribeExport` | Get export status and metadata |
| `ListExports` | List exports, optionally filtered by table ARN |
| `ImportTable` | Create a table and load DynamoDB JSON from S3 into it |
| `DescribeImport` | Get import status and metadata |
| `ListImports` | List imports, optionally filtered by table ARN |

## Legacy 2011-12-05 clients

The JSON 1.0 target `DynamoDB_20111205` supports `DescribeTable`, `Query` and
`BatchWriteItem`. These operations use the selected DynamoDB backend and the
caller's account and region. Table schemas, query conditions and continuation
keys, batch delete keys, unprocessed writes and consumed capacity are translated
between the two API versions. Missing tables return `ResourceNotFoundException`.
Other operations under this legacy target return `UnknownOperationException`;
the modern target remains the full API.

The explicit Boto 2 suite in `compatibility-tests/sdk-test-python/legacy/`
exercises table discovery, paginated key queries and
batch deletion against the same tables used by Boto 3. The wire contracts follow
the AWS [2011-12-05 DescribeTable](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/API_DescribeTables_v20111205.html),
[Query](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/API_Query_v20111205.html)
and [BatchWriteItem](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/API_BatchWriteItem_v20111205.html)
documentation.

## Legacy conditional requests

The native backend accepts `KeyConditions` together with `FilterExpression`.
For legacy `Expected` conditions it also accepts `ComparisonOperator` with a single
`Value`, including EQ and NE, just as the live AWS service does. Supplying both
`Value` and `AttributeValueList`, omitting a required operand, or supplying the wrong
number of operands returns `ValidationException` without changing the item.
`NULL` and `NOT_NULL` take no operands.

These behaviors were verified against AWS in `us-east-1` on October 12, 2026:
40 pairs of equivalent conditional writes, matching and nonmatching queries,
and 30 operand cases for each of `PutItem`, `UpdateItem` and `DeleteItem`.
The SDK regression cases and recorded error messages are in
`compatibility-tests/sdk-test-python/tests/test_dynamodb_legacy_expected.py` and
`tests/fixtures/dynamodb-legacy-expected-aws.json` within that suite.

AWS's [ExpectedAttributeValue documentation](https://docs.aws.amazon.com/sdk-for-python/v4/reference/clients/dynamodb/structures/ExpectedAttributeValue/)
describes the parameter families as incompatible more broadly. Floci preserves
the combinations accepted by the live service; this does not make every mixture
of legacy and expression parameters valid.

## Streams {#streams}

DynamoDB Streams are supported via a separate target (`DynamoDBStreams_20120810`):

| Action | Description |
|---|---|
| `ListStreams` | List all streams |
| `DescribeStream` | Get stream and shard info |
| `GetShardIterator` | Get a shard iterator |
| `GetRecords` | Read stream records from a shard |

Redshift zero-ETL integrations can consume these stream records directly. See the
[Redshift DynamoDB zero-ETL](redshift.md#dynamodb-zero-etl) section for the supported target,
landing table, checkpoint, and retry behavior.

Stream records are held in memory. `POST /_floci/state/reset` removes every stream along with the
tables, so `ListStreams` and `DescribeStream` no longer return the streams of tables created before
the reset.

## Time to Live

With TTL enabled, a sweep runs every 60 seconds and deletes the items whose TTL attribute holds an
epoch time in the past. Each deletion writes a `REMOVE` record to the table's stream and is
forwarded to an active Kinesis streaming destination. Between sweeps, expired items remain visible
to reads until they are physically deleted, as on AWS. Use a filter expression on the TTL attribute
to exclude expired items from a Query or Scan result.

As in AWS, the records of a TTL deletion carry the DynamoDB service identity, which a `DeleteItem`
removal does not. The member names depend on where the record is read. The DynamoDB Streams API
(`GetRecords`) capitalises them, as the SDK's `Identity` shape expects:

```json
"userIdentity": {"PrincipalId": "dynamodb.amazonaws.com", "Type": "Service"}
```

The records that Lambda event source mappings and EventBridge Pipes deliver and filter use the
lowercase names the DynamoDB developer guide shows, and so does the Kinesis streaming destination
record:

```json
"userIdentity": {"type": "Service", "principalId": "dynamodb.amazonaws.com"}
```

A Lambda event source mapping or an EventBridge pipe that should see only TTL deletions filters on
it with the pattern
`{"userIdentity": {"type": ["Service"], "principalId": ["dynamodb.amazonaws.com"]}}`.

## DynamoDB Local backend

By default Floci stores tables in its own engine, the `native` backend. With
`FLOCI_SERVICES_DYNAMODB_BACKEND=local`, Floci forwards DynamoDB calls to an
[Amazon DynamoDB Local](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/DynamoDBLocal.html)
instance that you run.

Floci keeps serving the DynamoDB and DynamoDB Streams APIs on port 4566, in JSON and CBOR, with
the same authentication, IAM enforcement and CRC32 checksums as the native backend. Each call is
forwarded to DynamoDB Local over SigV4-signed HTTP/1.1. Floci never retries a forwarded request.
Floci does not start DynamoDB Local. Run it yourself, for example as a container:

```yaml
services:
  dynamodb-local:
    image: amazon/dynamodb-local:3.3.1
    command: ["-jar", "DynamoDBLocal.jar", "-inMemory"]
  floci:
    image: floci/floci:latest
    ports:
      - "4566:4566"
    environment:
      FLOCI_SERVICES_DYNAMODB_BACKEND: local
      FLOCI_SERVICES_DYNAMODB_LOCAL_ENDPOINT: http://dynamodb-local:8000
    depends_on:
      - dynamodb-local
```

Do not start DynamoDB Local with `-sharedDb`. It merges every account and region into one database.

Floci is tested with DynamoDB Local 3.3.1 and does not check the version it connects to. It relies
on DynamoDB Local's fixed `ddblocal` ARNs and on its separate database per access key and region.
A version that changes either one breaks ARN translation or account and region isolation.

At startup Floci waits up to 30 seconds for DynamoDB Local to answer, then fails. Any
non-success answer to that probe fails startup at once. `GET /_floci/info` reports the active
backend in `dynamodb_backend` (`native` or `local`).

### Accounts, regions and ARNs

Each account and region is a separate DynamoDB Local namespace. Floci signs forwarded calls with
the access key `floci<account-id>` and the caller's region, and DynamoDB Local keeps one database
per access key and region. A call from account `000000000000` in `eu-west-1` is signed with access
key `floci000000000000` and region `eu-west-1`.

Table, index and stream ARNs in replies are public ARNs for the caller's account and region, such
as `arn:aws:dynamodb:eu-west-1:000000000000:table/Users`. Floci rewrites the `ddblocal` ARNs that
DynamoDB Local returns. Stream records carry the caller's region. An ARN for another account or
region is rejected.

### What Floci adds

- **Tags.** DynamoDB Local has no tagging, so Floci handles `TagResource`, `UntagResource`,
  `ListTagsOfResource` and `Tags` on `CreateTable` itself. It keeps the tags in its DynamoDB
  storage, in the file `dynamodb-local-tags.json`. The tags follow Floci's storage mode, so with
  `memory` storage they are lost when Floci restarts while the tables stay in DynamoDB Local.
- **Stream consumers.** Lambda event source mappings, EventBridge Pipes and other Floci stream
  consumers read the DynamoDB Local streams through the Streams API.
- **Time to live deletions.** DynamoDB Local deletes expired items itself and writes each deletion
  to the stream with the event name `UNKNOWN_TO_SDK_VERSION` and no `userIdentity`. Floci's
  `GetRecords` returns it as AWS writes it, a `REMOVE` record with the DynamoDB service
  `userIdentity` (see [Time to Live](#time-to-live)), so every stream consumer and event filter
  sees the AWS shape.

### Limits

- **No emulator reset.** `POST /_floci/state/reset` is refused with HTTP 409. Reset the DynamoDB
  Local instance instead.
- **No replicas.** Replicas and global table replica updates (`UpdateTable` with `ReplicaUpdates`)
  are refused with a `ValidationException`. A CloudFormation global table with no extra replica
  regions deploys as a single table.
- **No `TableId`.** DynamoDB Local returns none, so `DescribeTable` has no `TableId` and
  CloudFormation `Fn::GetAtt` on `TableId` is empty.
- **Short names in batch replies.** Batch replies keyed by table always use the short table name,
  including when the request named the table by ARN.
- **No tag validation.** Floci does not check tag counts or tag keys.
- **Resource Explorer searches regions Floci knows.** DynamoDB Local cannot list its namespaces,
  so Resource Explorer lists every table in each region where Floci's storage holds at least one
  table record, including tables created there directly on DynamoDB Local. A region with no such
  record is not searched: one whose tables were all created directly on DynamoDB Local, or whose
  records were lost with `memory` storage.
- **No data migration.** Data does not move between `native` and `local`. After a switch, you see
  what that engine holds.
- **DynamoDB Local sets the feature set.** Operations and features follow DynamoDB Local; see the
  [DynamoDB Local documentation](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/DynamoDBLocal.html)
  for what it supports. The other sections of this page describe the native backend.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_DYNAMODB_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_STORAGE_SERVICES_DYNAMODB_MODE` | *(global default)* | Storage mode override for DynamoDB (`memory`, `persistent`, `hybrid`, `wal`) |
| `FLOCI_STORAGE_SERVICES_DYNAMODB_FLUSH_INTERVAL_MS` | `5000` | Flush interval for `hybrid`/`wal` storage modes (milliseconds) |
| `FLOCI_STORAGE_SERVICES_DYNAMODB_ITEM_LAYOUT` | `single-file` | How items are laid out on disk: `single-file` or `per-table`. See [Item layout on disk](#item-layout-on-disk) |
| `FLOCI_STORAGE_SERVICES_DYNAMODB_ITEM_SHARDS` | `1` | With the `per-table` layout, how many files each table is split into |
| `FLOCI_SERVICES_DYNAMODB_VECTOR_INDEX_ALLOCATION_SECONDS` | `4` | Seconds a vector index added by `UpdateTable` spends in resource allocation |
| `FLOCI_SERVICES_DYNAMODB_VECTOR_INDEX_BACKFILL_SECONDS` | `10` | Seconds that index then spends backfilling before it goes `ACTIVE` |
| `FLOCI_SERVICES_DYNAMODB_LATEST_RESTORABLE_LAG_SECONDS` | `300` | Seconds `LatestRestorableDateTime` of point-in-time recovery trails the current time. Set `0` to restore the latest writes with `UseLatestRestorableTime` |
| `FLOCI_SERVICES_DYNAMODB_BACKEND` | `native` | Engine behind the DynamoDB API: `native` or `local`, case-insensitive. Any other value fails startup. See [DynamoDB Local backend](#dynamodb-local-backend) |
| `FLOCI_SERVICES_DYNAMODB_LOCAL_ENDPOINT` | *(none)* | Base URL of DynamoDB Local, for example `http://dynamodb-local:8000`. Required when the backend is `local`. Must be `http` or `https` with a host. AWS endpoints (hosts under `amazonaws.com`, `amazonaws.com.cn` or `api.aws`) are rejected |
| `FLOCI_SERVICES_DYNAMODB_LOCAL_CONNECT_TIMEOUT_SECONDS` | `2` | Seconds to wait for a connection to DynamoDB Local |
| `FLOCI_SERVICES_DYNAMODB_LOCAL_REQUEST_TIMEOUT_SECONDS` | `10` | Seconds to wait for DynamoDB Local to answer a forwarded request |

### Storage and Performance

Under `persistent` storage mode, single-item writes (`PutItem`, `UpdateItem`, `DeleteItem`) flush the affected table to disk synchronously. Batch operations (`BatchWriteItem` and `TransactWriteItems`) batch disk flushes per affected table across the entire operation, rather than flushing on every individual item mutation.

For write-heavy workloads under persistent setups, configuring `FLOCI_STORAGE_SERVICES_DYNAMODB_MODE=wal` or `hybrid` is recommended to avoid full-file rewrites on each write operation:
- `wal`: Uses an append-only write-ahead log with background compaction.
- `hybrid`: Keeps data in memory with periodic asynchronous disk flushes controlled by `FLOCI_STORAGE_SERVICES_DYNAMODB_FLUSH_INTERVAL_MS`.

### Item layout on disk

With the default `single-file` layout, every table's items live in one `dynamodb-items.json`, and any item write saves all of them: `persistent` rewrites the file on the write, `hybrid` on the next flush, and `wal` appends the whole table to its log and rewrites the whole snapshot on compaction. A small table that changes often, such as a heartbeat table, then keeps rewriting every large table beside it.

`FLOCI_STORAGE_SERVICES_DYNAMODB_ITEM_LAYOUT=per-table` stores items under `dynamodb-items/` instead, as `FLOCI_STORAGE_SERVICES_DYNAMODB_ITEM_SHARDS` files per table (one by default). An item belongs to the file chosen by a hash of its key, and a write saves only the items it touched:

| Mode | What an item write costs on disk |
|---|---|
| `persistent` | Rewrites the one file that holds the item |
| `hybrid` | Marks that file dirty; dirty files are rewritten on the flush interval |
| `wal` | Appends one record for the item to `dynamodb-items/journal.wal`; dirty files are rewritten and the journal emptied on `FLOCI_STORAGE_WAL_COMPACTION_INTERVAL_MS` |

On first start with `per-table`, in any storage mode, an existing `dynamodb-items.json` or a `wal`-mode `dynamodb-items-snapshot.json` and `dynamodb-items.wal` is migrated into the directory, and those files are renamed with a `.migrated` suffix. When both kinds exist, the one written last is migrated. The migration is written to a staging directory and moved in place in one step, so a crash leaves either no directory or a complete one. If it cannot be written, Floci fails to start and the old files stay untouched. Once the directory holds data, old files are ignored. Switching back to `single-file` does not read the directory. A write that cannot be saved where its mode promises fails with an error, instead of only being logged: in `persistent` mode the shard file must be written, and in `wal` mode a change the journal cannot take is written to its shard file at once. The change stays in memory and is written by the next flush. Changing the shard count rewrites the files on the next start. Starting `per-table` in `persistent` or `hybrid` mode after a `wal` run that ended without compacting writes the changes left in `journal.wal` into the shard files and removes it. If `journal.wal` cannot be read, Floci fails to start and leaves it untouched. The other DynamoDB stores (tables, backups, point-in-time recovery history, exports, imports) keep their single files.

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

Deviations from AWS:

- **No index is stored.** A `Query` or `Scan` on an index reads the base table and applies the
  index's key schema and projection on the way out.
- **Index reads are immediately consistent.** A GSI on AWS is eventually consistent, so code that
  tolerates replication lag never exercises that wait here.
- **A new index is `ACTIVE` at once.** AWS copies the table's items into a new index first, which
  takes minutes on a large table. Nothing is copied here, so a waiter returns straight away.

## Vector indexes

A vector index serves `SearchVectors`, which ranks a table's items by the distance between a query
vector and the vector attribute each item carries. Indexes are declared on `CreateTable` or added
later with `UpdateTable`, on `PAY_PER_REQUEST` tables only. An item that does not carry the vector
attribute, or that lacks the index's `HASH` search schema attribute, is still written but stays out
of that index. `Query`, `Scan` and PartiQL cannot read a vector index.

```bash
# A table with a vector index
aws dynamodb create-table \
  --table-name Docs \
  --attribute-definitions AttributeName=docId,AttributeType=S \
  --key-schema AttributeName=docId,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST \
  --vector-indexes '[{
    "IndexName": "embedding-index",
    "VectorAttribute": {"AttributeName": "embedding"},
    "Projection": {"ProjectionType": "ALL"},
    "Dimensions": 3,
    "DistanceFunction": "COSINE"
  }]' \
  --endpoint-url $AWS_ENDPOINT_URL

# Write an item carrying a vector
aws dynamodb put-item \
  --table-name Docs \
  --item '{"docId":{"S":"d1"},"title":{"S":"first"},"embedding":{"L":[{"N":"1"},{"N":"0"},{"N":"0"}]}}' \
  --endpoint-url $AWS_ENDPOINT_URL

# Search it
aws dynamodb search-vectors \
  --table-name Docs \
  --index-name embedding-index \
  --search-vector '[{"N":"1"},{"N":"0"},{"N":"0"}]' \
  --top-k 5 \
  --endpoint-url $AWS_ENDPOINT_URL
```

`DistanceFunction` is `COSINE`, `EUCLIDEAN` or `DOT_PRODUCT`. The first two rank the lowest score
first, `DOT_PRODUCT` the highest. The vector attribute is left out of a result unless
`--projection-expression` names it, and when it is named the values returned are the index's own
32 bit copies, so a `1` written to the table comes back as `1.0`.

Deviations from AWS:

- **The search is exact, not approximate.** AWS may return a slightly different set or order on a
  large index. Scoring every item makes a result here repeatable.
- **No index is stored, as above.** Every search reads the vectors from the base table and converts
  them, so the work grows with items times dimensions. A large index is slower than AWS.
- **A written vector is searchable at once.** AWS copies it into the index in the background, so
  code that polls for a new vector never waits here.
- **`ItemCount` and `IndexSizeBytes` always report 0.** That is what AWS reports for a fresh index,
  because it refreshes both roughly every six hours.
- **An index created with its table is `ACTIVE` at once.** One added by `UpdateTable` walks a
  resource allocation phase and then a backfill phase. Their lengths are
  `FLOCI_SERVICES_DYNAMODB_VECTOR_INDEX_ALLOCATION_SECONDS` and
  `FLOCI_SERVICES_DYNAMODB_VECTOR_INDEX_BACKFILL_SECONDS`, 4 and 10 seconds by default, against
  minutes on AWS.
- **`SearchVectors` is served on Floci's ordinary endpoint.** AWS gives the operation a dedicated
  search endpoint. The SDKs honor an endpoint override, so this is invisible to callers.
- **`VectorSearchRequestBytes` is a fixed figure, not a measured one.** It uses the same 1024 byte
  floor AWS uses. AWS's own value above that floor is not deterministic.

## Export to S3

Export table data to an S3 bucket as gzip-compressed lines of DynamoDB JSON (`DYNAMODB_JSON`, the default) or Amazon Ion text (`ION`). As on AWS, the table needs point-in-time recovery:

```bash
# Create a bucket to receive the export
aws s3 mb s3://my-exports --endpoint-url $AWS_ENDPOINT_URL

# Turn on point-in-time recovery
aws dynamodb update-continuous-backups --table-name Users \
  --point-in-time-recovery-specification PointInTimeRecoveryEnabled=true --endpoint-url $AWS_ENDPOINT_URL

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

The export writes `_started`, `manifest-summary.json`, `manifest-files.json` and an `.md5` file for each manifest to `s3://<bucket>/<prefix>/AWSDynamoDB/<exportId>/`, and the items to a `.json.gz` or `.ion.gz` file in its `data/` folder, the layout AWS uses. AWS writes one data file per partition. Floci writes one. An Ion line is `$ion_1_0 {Item:{...}}`, with sets annotated as `$dynamodb_SS`, `$dynamodb_NS` and `$dynamodb_BS`, and numbers as Ion decimals. As on AWS, every file is a multipart upload of one part, so its ETag ends in `-1`. As on AWS, an `S3Prefix` shorter than 3 characters or longer than 948 UTF-8 bytes is refused, and no slash is added after a prefix that ends with one.

The export holds the items as they were at `ExportTime`, or at the request time when `ExportTime` is not set. As on AWS, `ExportTime` must not be before `EarliestRestorableDateTime` or the table creation, and must not be in the future. A time after `LatestRestorableDateTime` is accepted.

An incremental export writes the items that changed in a period:

```bash
aws dynamodb export-table-to-point-in-time \
  --table-arn arn:aws:dynamodb:us-east-1:000000000000:table/Users \
  --s3-bucket my-exports \
  --export-type INCREMENTAL_EXPORT \
  --incremental-export-specification ExportFromTime=2026-01-01T00:00:00Z,ExportToTime=2026-01-01T01:00:00Z,ExportViewType=NEW_AND_OLD_IMAGES \
  --endpoint-url $AWS_ENDPOINT_URL
```

The period starts at `ExportFromTime` and ends just before `ExportToTime`, or at the request time when `ExportToTime` is not set. Each line is a record with `Metadata.WriteTimestampMicros`, `Keys`, and the `OldImage` and `NewImage` of the item. `ExportViewType` `NEW_IMAGE` leaves out `OldImage`. An item that ends the period as it started it, such as one inserted and then deleted, gets no record. As on AWS, the images list attribute names and set members in sorted order, and the data files go to the shared `<prefix>/AWSDynamoDB/data/` folder. As on AWS, the period must be at least 15 minutes and at most 24 hours, must not start before the table creation or `EarliestRestorableDateTime`, and must not end in the future. Floci keeps the time of a write in milliseconds, so `WriteTimestampMicros` always ends in `000`.

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

## On-demand backups

`CreateBackup` copies the items and settings of a table at the time of the call. The answer says `CREATING`, and the backup is `AVAILABLE` at once. A backup outlives its table. `ListBackups` returns backups in creation order and sets `LastEvaluatedBackupArn` only when more backups remain.

```bash
BACKUP_ARN=$(aws dynamodb create-backup --table-name Users --backup-name nightly \
  --query BackupDetails.BackupArn --output text --endpoint-url $AWS_ENDPOINT_URL)

aws dynamodb restore-table-from-backup --backup-arn $BACKUP_ARN \
  --target-table-name UsersRestored --endpoint-url $AWS_ENDPOINT_URL
```

`RestoreTableFromBackup` creates a new table with the keys, indexes, billing mode, throughput and encryption of the backup. As on AWS, the new table has no stream, TTL, point-in-time recovery, tags or deletion protection, and its table class is `STANDARD`. The overrides follow AWS. A GSI or LSI override must match a source index by key schema and projection, and a vector index override must match one by name. `BillingModeOverride` `PROVISIONED` needs throughput for the table and for every GSI that is kept. To restore a backup into another region, call `RestoreTableFromBackup` in that region with `SSESpecificationOverride`.

Deviations from AWS: a restore is done before the response is sent. The response still says `CREATING` with `RestoreInProgress: true`, as on AWS, but the table is `ACTIVE` right away. So `BackupInUseException` and `TableInUseException` for a restore in progress never happen. `BackupSizeBytes` is always 0. Backups made by AWS Backup (`BackupType` `AWS_BACKUP`) are not emulated.

## Point-in-time recovery

While point-in-time recovery is enabled, Floci keeps every item change of the table. `RestoreTableToPointInTime` creates a new table with the items as they were at `RestoreDateTime`, or at `LatestRestorableDateTime` with `UseLatestRestorableTime`. As on AWS, the new table takes the current settings of the source table, not the settings at that time. The overrides are the same as for `RestoreTableFromBackup`, and a source in another region needs `SSESpecificationOverride`.

```bash
aws dynamodb update-continuous-backups --table-name Users \
  --point-in-time-recovery-specification PointInTimeRecoveryEnabled=true --endpoint-url $AWS_ENDPOINT_URL

aws dynamodb restore-table-to-point-in-time --source-table-name Users \
  --target-table-name UsersAnHourAgo --restore-date-time $(($(date +%s) - 3600)) --endpoint-url $AWS_ENDPOINT_URL
```

`EarliestRestorableDateTime` is the time point-in-time recovery was enabled, or the start of the recovery period once that has passed. `LatestRestorableDateTime` trails the current time by `FLOCI_SERVICES_DYNAMODB_LATEST_RESTORABLE_LAG_SECONDS`, 5 minutes by default like AWS. As on AWS, a `RestoreDateTime` after it is still accepted up to the current time. Disabling point-in-time recovery drops the change history. The history survives a restart in the persistent storage modes.

Deleting a table with point-in-time recovery enabled leaves a `SYSTEM` backup named `<table>$DeletedTableBackup`. It holds the table as it was at deletion, cannot be deleted, and expires after 35 days. `ListBackups` shows it with `BackupType` `SYSTEM` or `ALL`, and `RestoreTableFromBackup` restores it.

Deviations from AWS: enabling point-in-time recovery right after `CreateTable` is accepted at once. AWS sometimes refuses it for a few seconds with `ContinuousBackupsUnavailableException`. `EarliestRestorableDateTime` is rounded down to the second, as on AWS. A restore or an export at that time on AWS leaves out the writes made earlier in that second, before point-in-time recovery was enabled. Floci keeps changes only from the moment it is enabled, so it includes those writes.


## Kinesis change data capture (CDC)

When a table has an **ACTIVE** Kinesis streaming destination (see
`EnableKinesisStreamingDestination`), every item change, `INSERT`, `MODIFY`, and `REMOVE`,
including TTL expirations, is forwarded to the destination stream as a Kinesis record in the
AWS CDC envelope (`eventName`, `dynamodb.Keys`, `NewImage`/`OldImage`, `ApproximateCreationDateTime`).

`ApproximateCreationDateTime` follows the destination's
`EnableKinesisStreamingConfiguration.ApproximateCreationDateTimePrecision`: epoch milliseconds for
`MILLISECOND` (the default) and epoch microseconds for `MICROSECOND`. The precision is stamped on each
record as `dynamodb.ApproximateCreationDateTimePrecision`, and reported by
`DescribeKinesisStreamingDestination` as well as in the `EnableKinesisStreamingConfiguration` member of
the `EnableKinesisStreamingDestination` and `DisableKinesisStreamingDestination` responses.

Enabling a Kinesis streaming destination does not change the table's DynamoDB Streams setting.
Kinesis forwarding works whether or not `StreamSpecification.StreamEnabled` is set.

Both calls take effect at once, so `EnableKinesisStreamingDestination` answers `ACTIVE` and
`DisableKinesisStreamingDestination` answers `DISABLED`. AWS reports the transitional `ENABLING` and
`DISABLING` first, because it enables and disables the destination in the background.

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

### Single-statement PartiQL IAM actions

With IAM enforcement enabled, `ExecuteStatement` checks `dynamodb:PartiQLSelect`,
`dynamodb:PartiQLInsert`, `dynamodb:PartiQLUpdate` or `dynamodb:PartiQLDelete` according to the
statement's operation. A policy can grant that action on the specific table ARN; a wildcard
resource or a `dynamodb:ExecuteStatement` permission is not required. This mapping covers the
single-statement API; batch and transaction authorization are separate paths.
