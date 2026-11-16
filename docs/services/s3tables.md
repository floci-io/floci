# S3 Tables

**Protocol:** REST JSON
**Signing service:** `s3tables`
**Endpoint:** `http://localhost:4566`

Floci emulates the S3 Tables control plane for local development, Terraform
convergence, and SDK integration tests. It is a separate AWS service from S3:
S3 Tables requests are signed as `s3tables` and use REST JSON routes such as
`/buckets` and `/tables/{tableBucketARN}/{namespace}`.

## Scope

This service stores table-bucket, namespace, table, policy, maintenance
configuration, and metadata-location state, and gives every table a warehouse:
a bucket of its own, named like the ones AWS creates (`<id>--table-s3`), which
`GetTable` and `GetTableMetadataLocation` report as `warehouseLocation`. An
Iceberg client such as Spark's `S3TablesCatalog` writes the table's data and
metadata files there through Floci's S3 and commits each new metadata file
with `UpdateTableMetadataLocation`. Deleting the table deletes its warehouse
and everything in it, every object version included. A warehouse bucket is the
table's storage, not a general purpose bucket, so `ListBuckets` leaves it out. Floci does not run an Iceberg engine itself: compaction,
snapshot expiry, replication, data encryption, and metrics are outside
this implementation.

A table's ARN ends in an id Floci mints when it creates the table
(`arn:aws:s3tables:<region>:<account>:bucket/<bucket>/table/<id>`), as AWS's
do, so two tables of one name in different namespaces have their own ARNs, and
a rename keeps it. `GetTable` takes either `tableArn` or the table bucket's
ARN, namespace and name. A table created by an earlier Floci keeps the ARN it
was stored with, which ends in its name.

## Supported operations

| Area | Operations |
|---|---|
| Table buckets | CreateTableBucket, GetTableBucket, ListTableBuckets, DeleteTableBucket |
| Bucket encryption | GetTableBucketEncryption |
| Bucket policy | PutTableBucketPolicy, GetTableBucketPolicy, DeleteTableBucketPolicy |
| Bucket storage class | GetTableBucketStorageClass |
| Bucket maintenance | PutTableBucketMaintenanceConfiguration, GetTableBucketMaintenanceConfiguration |
| Namespaces | CreateNamespace, GetNamespace, ListNamespaces, DeleteNamespace |
| Tables | CreateTable, GetTable, ListTables, DeleteTable, RenameTable |
| Metadata location | GetTableMetadataLocation, UpdateTableMetadataLocation |
| Table policy | PutTablePolicy, GetTablePolicy, DeleteTablePolicy |
| Table maintenance | PutTableMaintenanceConfiguration, GetTableMaintenanceConfiguration |

Tables must use `ICEBERG` format. `UpdateTableMetadataLocation` implements
optimistic concurrency: supply the table's current `versionToken`; a stale
token returns `ConflictException`.

`GetTableBucketEncryption` returns the encryption configuration stored by
`CreateTableBucket`, including a configured KMS key ARN. Buckets created
without an explicit configuration, including those loaded from older
snapshots, report `AES256`. This is configuration readback; Floci does not
encrypt table data or perform KMS cryptographic operations. Updating or
deleting a bucket's encryption configuration is not implemented.

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

bucket_arn=$(aws s3tables create-table-bucket --name local-warehouse --query arn --output text)
aws s3tables create-namespace --table-bucket-arn "$bucket_arn" --namespace analytics
aws s3tables create-table \
  --table-bucket-arn "$bucket_arn" \
  --namespace analytics \
  --name events \
  --format ICEBERG
```

`GetTableBucketStorageClass` returns the storage-class configuration supplied to
`CreateTableBucket`. An omitted configuration, including in older snapshots,
reports `STANDARD`; an explicit `INTELLIGENT_TIERING` setting is preserved.
This is bucket configuration readback only. Storage-class updates, per-table
storage-class reads and inheritance, and physical tier transitions are not
implemented.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_S3TABLES_ENABLED` | `true` | Enables the S3 Tables REST JSON service. |
| `FLOCI_STORAGE_SERVICES_S3TABLES_MODE` | Global storage mode | Optional per-service storage-mode override. |
| `FLOCI_STORAGE_SERVICES_S3TABLES_FLUSH_INTERVAL_MS` | `5000` | Persistence flush interval for hybrid storage. |
