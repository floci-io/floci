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
creation-time tags. Get and list responses include creation times. `GetIndex`
returns encryption settings and includes metadata configuration only when
non-filterable keys are configured. Indexes inherit their bucket's encryption
settings unless overridden. These fields use the existing S3 Vectors storage backend.

On first read, older stored resources with missing encryption settings receive the
bucket default, or `AES256` when the bucket has no setting. Missing creation times
are set to the migration time and saved through the configured storage backend;
the original creation time is unavailable. Later reads and restarts retain that value.

Index dimensions must be between 1 and 4096. Metadata configuration accepts up to
10 unique non-filterable keys, each a non-blank string of 1 to 63 characters.
Queries reject filters on these keys, including filters nested in logical expressions.

Writes to the same bucket are serialized across tags, indexes, vectors, and bucket
deletion so concurrent changes do not overwrite each other or restore deleted data.

Encryption settings are stored and returned; Floci does not perform KMS encryption.
Standalone S3 Vectors tagging APIs are not implemented.

## CloudFormation

`AWS::S3Vectors::VectorBucket` and `AWS::S3Vectors::Index` create real S3 Vectors
resources. Both return their ARN from `Ref` and expose `CreationTime` through
`Fn::GetAtt`. The ARN attributes are `VectorBucketArn` and `IndexArn`, respectively.
An index accepts either `VectorBucketName` or `VectorBucketArn`.

```yaml
Resources:
  Vectors:
    Type: AWS::S3Vectors::VectorBucket
    Properties:
      EncryptionConfiguration:
        SseType: AES256
  Embeddings:
    Type: AWS::S3Vectors::Index
    Properties:
      VectorBucketArn: !Ref Vectors
      DataType: float32
      Dimension: 3
      DistanceMetric: cosine
      MetadataConfiguration:
        NonFilterableMetadataKeys: [text]
```

Omitted names are generated in lowercase. Tag changes preserve resource identity
and vector data, including removing tags dropped from the template. Other property
changes replace the resource; a custom-named resource needs a different name when
replacement would otherwise reuse its existing identity. Replacement cleanup and
rollback use the normal CloudFormation lifecycle and honor `UpdateReplacePolicy`.
Stack deletion removes indexes before their bucket. A bucket containing indexes
outside the stack causes deletion to fail.
