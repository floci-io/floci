# S3 Vectors

**Protocol:** REST JSON
**Endpoint:** `POST /{OperationName}` (e.g. `POST /CreateVectorBucket`); tagging uses `GET`, `POST`, and `DELETE /tags/{resourceArn}`

Floci emulates the S3 Vectors API: vector buckets, vector indexes, and vector
storage with similarity queries. All operations use the AWS REST JSON wire
shape, so the AWS SDK and CLI `s3vectors` clients work without modification.

## Supported Operations

| Category | Operations |
|---|---|
| **Vector buckets** | CreateVectorBucket, GetVectorBucket, ListVectorBuckets, DeleteVectorBucket, PutVectorBucketPolicy, GetVectorBucketPolicy, DeleteVectorBucketPolicy |
| **Indexes** | CreateIndex, GetIndex, ListIndexes, DeleteIndex |
| **Vectors** | PutVectors, GetVectors, ListVectors, DeleteVectors, QueryVectors |
| **Tags** | TagResource, UntagResource, ListTagsForResource |

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

Bucket and index names follow the same rules, as S3 Vectors applies them: 3 to 63
characters, labels of lowercase letters, numbers and hyphens that begin and end with a
letter or number, joined by single dots, not an IPv4 address, and without the prefix
`sthree-` or the suffixes `-s3alias` and `--x-s3`. `CreateVectorBucket` and
`CreateIndex` refuse any other name with a `ValidationException`, CloudFormation with a
`ValidationError`.

Index dimensions must be between 1 and 4096. Metadata configuration accepts up to
10 unique non-filterable keys, each a non-blank string of 1 to 63 characters.
Queries reject filters on these keys, including filters nested in logical expressions.

Writes to the same bucket are serialized across policies, tags, indexes, vectors, and
bucket deletion so concurrent changes do not overwrite each other or restore deleted data.

Bucket policies can be attached, retrieved, and deleted by either `vectorBucketName` or
`vectorBucketArn`. `PutVectorBucketPolicy` validates that the policy is a JSON object and
stores it with the bucket; `GetVectorBucketPolicy` returns `NotFoundException` when no
policy is attached. Bucket policies are stored and returned, but Floci does not evaluate
or enforce them on requests.

A bucket ARN or an index ARN must be of the request's partition, Region and account, as S3
Vectors requires: one of another partition or Region is refused with a `ValidationException`
("Unexpected value for ARN partition component" or "Unexpected value for ARN region
component"), and one of another account with an `AccessDeniedException`.

Encryption settings are stored and returned; Floci does not perform KMS encryption.
`TagResource`, `UntagResource`, and `ListTagsForResource` work with vector bucket
and index ARNs. Tagging merges keys and replaces the values of existing keys;
untagging removes only the requested keys. Each resource can have up to 50 tags,
including tags supplied during creation. Invalid tags or updates that exceed that limit
leave its tags unchanged. A legacy resource above the limit can still be listed and
untagged until it is within the limit. Tag operations apply the ARN scope checks above
and reject malformed JSON, including trailing content, before changing tags.
Tag changes use the configured storage backend and survive restarts in persistent storage modes.

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
