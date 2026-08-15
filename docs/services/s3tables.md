# S3 Tables

**Protocol:** REST JSON
**Signing service:** `s3tables`
**Endpoint:** `http://localhost:4566`

Floci emulates the S3 Tables control plane for local development, Terraform
convergence, and SDK integration tests. It is a separate AWS service from S3:
S3 Tables requests are signed as `s3tables` and use REST JSON routes such as
`/buckets` and `/tables/{tableBucketARN}/{namespace}`.

## Metadata-only scope

This service stores table-bucket, namespace, table, policy, maintenance
configuration, and metadata-location state. It does **not** run an Apache
Iceberg engine or create data-plane artifacts: manifests, warehouse objects,
queries, compaction, snapshot expiry, replication, encryption-management, and
metrics are intentionally outside this implementation.

## Supported operations

| Area | Operations |
|---|---|
| Table buckets | CreateTableBucket, GetTableBucket, ListTableBuckets, DeleteTableBucket |
| Bucket policy | PutTableBucketPolicy, GetTableBucketPolicy, DeleteTableBucketPolicy |
| Bucket maintenance | PutTableBucketMaintenanceConfiguration, GetTableBucketMaintenanceConfiguration |
| Namespaces | CreateNamespace, GetNamespace, ListNamespaces, DeleteNamespace |
| Tables | CreateTable, GetTable, ListTables, DeleteTable, RenameTable |
| Metadata location | GetTableMetadataLocation, UpdateTableMetadataLocation |
| Table policy | PutTablePolicy, GetTablePolicy, DeleteTablePolicy |
| Table maintenance | PutTableMaintenanceConfiguration, GetTableMaintenanceConfiguration |

Tables must use `ICEBERG` format. `UpdateTableMetadataLocation` implements
optimistic concurrency: supply the table's current `versionToken`; a stale
token returns `ConflictException`.

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

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_S3TABLES_ENABLED` | `true` | Enables the S3 Tables REST JSON service. |
| `FLOCI_STORAGE_SERVICES_S3TABLES_MODE` | Global storage mode | Optional per-service storage-mode override. |
| `FLOCI_STORAGE_SERVICES_S3TABLES_FLUSH_INTERVAL_MS` | `5000` | Persistence flush interval for hybrid storage. |
