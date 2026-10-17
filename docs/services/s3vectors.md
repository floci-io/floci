# S3 Vectors

**Protocol:** REST JSON
**Endpoint:** `POST /{OperationName}` (e.g. `POST /CreateVectorBucket`)

Floci emulates the S3 Vectors API: vector buckets, vector indexes, and vector
storage with similarity queries. All operations use the AWS REST JSON wire
shape, so the AWS SDK and CLI `s3vectors` clients work without modification.

## Supported Operations

| Category | Operations |
|---|---|
| **Vector buckets** | CreateVectorBucket, GetVectorBucket, ListVectorBuckets, DeleteVectorBucket |
| **Indexes** | CreateIndex, GetIndex, ListIndexes, DeleteIndex |
| **Vectors** | PutVectors, GetVectors, DeleteVectors, QueryVectors |

## Example

```bash
aws s3vectors create-vector-bucket --vector-bucket-name my-vectors \
  --endpoint-url http://localhost:4566

aws s3vectors create-index --vector-bucket-name my-vectors \
  --index-name embeddings --dimension 4 --distance-metric cosine \
  --data-type float32 \
  --endpoint-url http://localhost:4566

aws s3vectors put-vectors --vector-bucket-name my-vectors --index-name embeddings \
  --vectors '[{"key":"a","data":{"float32":[0.1,0.2,0.3,0.4]}}]' \
  --endpoint-url http://localhost:4566

aws s3vectors query-vectors --vector-bucket-name my-vectors --index-name embeddings \
  --query-vector '{"float32":[0.1,0.2,0.3,0.4]}' --top-k 1 \
  --endpoint-url http://localhost:4566
```

## Metadata filters

`QueryVectors` supports metadata filters with `$eq`, `$ne`, `$gt`, `$gte`,
`$lt`, `$lte`, `$in`, `$nin`, and `$exists` field predicates. `$and` and `$or`
recursively combine filters, while multiple clauses in the same object are
implicitly combined with AND. A scalar field value is shorthand for `$eq`.

```bash
aws s3vectors query-vectors --vector-bucket-name my-vectors --index-name embeddings \
  --query-vector '{"float32":[0.1,0.2,0.3,0.4]}' --top-k 5 \
  --filter '{"$and":[{"tenant":"tenant-a"},{"year":{"$gte":2024}}]}' \
  --endpoint-url http://localhost:4566
```

Filters are evaluated before similarity ranking and `topK` truncation, so a
nearer vector that does not match the filter cannot consume a result slot.

## Resource configuration

Vector buckets and indexes retain their creation times, encryption settings, and
creation-time tags. Get and list responses include creation times; index responses
also include metadata configuration and encryption settings. Indexes inherit their
bucket's encryption settings unless overridden. These fields use the existing
S3 Vectors storage backend.

Index dimensions must be between 1 and 4096. Metadata configuration accepts up to
10 non-filterable keys, each a non-blank string of 1 to 63 characters. Queries reject
filters on these keys, including filters nested in logical expressions.

Writes to the same bucket are serialized across tags, indexes, vectors, and bucket
deletion so concurrent changes do not overwrite each other or restore deleted data.

Encryption settings are stored and returned; Floci does not perform KMS encryption.
Standalone S3 Vectors tagging APIs are not implemented.
