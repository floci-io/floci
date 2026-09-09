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
