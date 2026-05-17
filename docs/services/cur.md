# Cost and Usage Reports (`cur:*`)

**Protocol:** JSON 1.1
**Header:** `X-Amz-Target: AWSOrigamiServiceGatewayService.<Action>`
**Endpoint prefix:** `cur`

Floci emulates the legacy AWS Cost and Usage Report (CUR) API. Report
definitions are persisted in Floci's storage backend; emission produces
real Parquet artifacts in Floci's S3 service via the `floci-duck` sidecar.

## Supported Operations

| Operation | Notes |
|-----------|-------|
| `PutReportDefinition` | Creates a new report; rejects duplicates with `DuplicateReportNameException`; enforces a 5-report-per-account limit |
| `ModifyReportDefinition` | Replaces an existing report's mutable fields |
| `DescribeReportDefinitions` | Returns every report owned by the calling account |
| `DeleteReportDefinition` | Idempotent; removing a missing report returns 200 |
| `TagResource` / `UntagResource` / `ListTagsForResource` | Stub responses (empty bodies) so SDK clients that probe for them succeed |

## Validation rules

- `ReportName`: alphanumerics + `-_`, max 256 chars
- `TimeUnit`: `HOURLY` / `DAILY` / `MONTHLY`
- `Format`: `Parquet` (CSV emission not yet implemented; `textORcsv` returns `ValidationException`)
- `Compression`: `Parquet` (`ZIP` / `GZIP` not yet implemented)
- `ReportVersioning`: `CREATE_NEW_REPORT` / `OVERWRITE_REPORT`
- `AdditionalArtifacts`: subset of `REDSHIFT` / `QUICKSIGHT` / `ATHENA`
- `AdditionalSchemaElements`: subset of `RESOURCES` / `SPLIT_COST_ALLOCATION_DATA` / `MANUAL_DISCOUNT_COMPATIBILITY`
- Required: `ReportName`, `TimeUnit`, `Format`, `Compression`, `S3Bucket`, `S3Region`

## Storage keys

Report definitions are persisted as
`<accountId>::<region>::<reportName>` so the same name is allowed in
different regions or different accounts.

## Emission

Emission produces a Parquet artifact at
`s3://<S3Bucket>/<S3Prefix>/<reportName>/<runId>.parquet`. Each run gets a
fresh UUID, so concurrent emissions never clobber each other.

The pipeline:

1. The shared `EmissionEngine` collects `UsageLine` rows from every
   service that implements the `ResourceUsageEnumerator` SPI introduced
   in [Cost Explorer](ce.md).
2. `FocusRowProjector` converts those rows to FOCUS 1.2 / CUR 2.0 column
   shape using the bundled [Pricing snapshot](pricing.md).
3. The rows are staged as newline-delimited JSON in
   `s3://floci-cur-staging/cur-staging/<reportName>/<runId>.ndjson`.
4. The `floci-duck` sidecar runs
   `COPY (SELECT * FROM read_json_auto(...)) TO 's3://...' (FORMAT PARQUET)`
   to produce the final Parquet object back in Floci S3.
5. The staging object is deleted in a best-effort `finally` block.

### `FLOCI_SERVICES_CUR_EMIT_MODE`

| Value | Behavior |
|-------|----------|
| `synchronous` (default) | Emit on every `PutReportDefinition` / `ModifyReportDefinition` |
| `daily` | Emit every 24h via a CUR-owned scheduled executor (separate from EventBridge Scheduler) |
| `off` | Management plane only — no emission |

`synchronous` mode swallows emission errors so the management mutation
always succeeds; the failure is reflected in
`ReportStatus.LastStatus = ERROR` on the persisted definition.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_CUR_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_CUR_EMIT_MODE` | `synchronous` | Run mode (see above) |
| `FLOCI_SERVICES_CUR_STAGING_BUCKET` | `floci-cur-staging` | S3 bucket used to stage NDJSON before DuckDB writes Parquet |

The `floci-duck` sidecar is started lazily on the first emission, the
same way Athena starts it.

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

aws cur put-report-definition --report-definition '{
  "ReportName": "monthly-report",
  "TimeUnit": "MONTHLY",
  "Format": "Parquet",
  "Compression": "Parquet",
  "AdditionalSchemaElements": ["RESOURCES"],
  "S3Bucket": "my-billing",
  "S3Prefix": "reports",
  "S3Region": "us-east-1",
  "AdditionalArtifacts": ["ATHENA"],
  "ReportVersioning": "OVERWRITE_REPORT"
}'

aws cur describe-report-definitions

aws s3 ls s3://my-billing/reports/monthly-report/
```

```python
import boto3, json

cur = boto3.client(
    "cur",
    endpoint_url="http://localhost:4566",
    region_name="us-east-1",
)
cur.put_report_definition(ReportDefinition={
    "ReportName": "monthly-report",
    "TimeUnit": "MONTHLY",
    "Format": "Parquet",
    "Compression": "Parquet",
    "AdditionalSchemaElements": ["RESOURCES"],
    "S3Bucket": "my-billing",
    "S3Prefix": "reports",
    "S3Region": "us-east-1",
})

# Read the resulting Parquet via DuckDB or pyarrow.
import pyarrow.dataset as ds
table = ds.dataset("s3://my-billing/reports/monthly-report/", format="parquet").to_table()
print(table.column_names)
```

## Out of Scope

- Resource-tag-keyed report selection beyond what the bundled
  enumerators emit
- `RefreshClosedReports` semantics (accepted but not retroactively
  re-emitted)
- Server-side validation of S3 bucket policies on the destination
  bucket (Floci's S3 service still applies its own bucket-policy
  checks during the write)
