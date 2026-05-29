# BCM Data Exports (`bcm-data-exports:*`)

**Protocol:** JSON 1.1
**Header:** `X-Amz-Target: AWSBillingAndCostManagementDataExports.<Action>`
**Endpoint prefix:** `bcm-data-exports`

Floci emulates the BCM Data Exports management plane that ships with
CUR 2.0 / FOCUS 1.2. Exports share the same Parquet emission engine as
the legacy [`cur:*`](cur.md) service — the two are alternative
management surfaces over one underlying export pipeline.

## Supported Operations

| Operation | Notes |
|-----------|-------|
| `CreateExport` | Creates an export; rejects duplicate `Name` with `ValidationException` |
| `GetExport` | Returns one export by ARN; missing ARN returns `ResourceNotFoundException` |
| `ListExports` | Returns every export owned by the calling account |
| `UpdateExport` | Replaces mutable fields on an existing export |
| `DeleteExport` | Idempotent; deletes orphan executions too |
| `ListExecutions` | Returns all execution records for an export |
| `GetExecution` | Returns one execution record |

## Validation rules

- `Export.Name`: alphanumerics + `-_`, max 128 chars
- `Export.DataQuery.QueryStatement`: required (free-form SQL — Floci does
  not parse it; the export shape is determined by the bundled FOCUS
  schema)
- `Export.DestinationConfigurations.S3Destination`: required;
  `S3Bucket` + `S3Region` mandatory
- `S3OutputConfigurations.Format`: `PARQUET` (CSV emission not yet implemented; `TEXT_OR_CSV` returns `ValidationException`)
- `S3OutputConfigurations.Compression`: `PARQUET` (`GZIP` not yet implemented)
- `S3OutputConfigurations.Overwrite`: `CREATE_NEW_REPORT` / `OVERWRITE_REPORT`
- `S3OutputConfigurations.OutputType`: `CUSTOM`
- `RefreshCadence.Frequency`: `SYNCHRONOUS` (the only AWS-supported
  value at the time of writing)

## Storage keys

Account-scoped throughout:

- Export: `<accountId>::<exportArn>`
- Execution: `<accountId>::<exportArn>::<executionId>`

Deleting an export removes all of its executions in the same call to
avoid orphan records.

## Execution lifecycle

A successful `CreateExport` in `synchronous` mode produces exactly one
execution record:

```
INITIATION_IN_PROCESS  (recorded at create-time)
        |
        v
DELIVERY_SUCCESS  or  DELIVERY_FAILURE
```

Status transitions on `UpdateExport` too. Both `success` and `failure`
end states are visible via `GetExecution.Execution.ExecutionStatus`.

## Emission

The Parquet pipeline is the same as for [`cur:*`](cur.md): rows from
the `ResourceUsageEnumerator` SPI go through `FocusRowProjector`, are
staged as NDJSON in `floci-cur-staging`, and written as Parquet by the
`floci-duck` sidecar via `COPY ... TO ... (FORMAT PARQUET)`. Each
execution produces an artifact at
`s3://<S3Bucket>/<S3Prefix>/<Name>/<runId>.parquet`.

### `FLOCI_SERVICES_BCM_DATA_EXPORTS_EMIT_MODE`

| Value | Behavior |
|-------|----------|
| `synchronous` (default) | Emit on every `CreateExport` / `UpdateExport` |
| `daily` | Emit every 24h via the shared CUR scheduled executor |
| `off` | Management plane only — no emission |

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_BCM_DATA_EXPORTS_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_BCM_DATA_EXPORTS_EMIT_MODE` | `synchronous` | Run mode (see above) |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

aws bcm-data-exports create-export --export '{
  "Name": "focus-monthly",
  "DataQuery": {"QueryStatement": "SELECT * FROM COST_AND_USAGE_REPORT"},
  "DestinationConfigurations": {
    "S3Destination": {
      "S3Bucket": "my-billing",
      "S3Prefix": "focus",
      "S3Region": "us-east-1",
      "S3OutputConfigurations": {
        "Format": "PARQUET",
        "Compression": "PARQUET",
        "OutputType": "CUSTOM",
        "Overwrite": "OVERWRITE_REPORT"
      }
    }
  },
  "RefreshCadence": {"Frequency": "SYNCHRONOUS"}
}'

aws bcm-data-exports list-exports

aws bcm-data-exports list-executions \
  --export-arn arn:aws:bcm-data-exports:us-east-1:000000000000:export/focus-monthly
```

```python
import boto3

client = boto3.client(
    "bcm-data-exports",
    endpoint_url="http://localhost:4566",
    region_name="us-east-1",
)
resp = client.create_export(Export={
    "Name": "focus-monthly",
    "DataQuery": {"QueryStatement": "SELECT * FROM COST_AND_USAGE_REPORT"},
    "DestinationConfigurations": {"S3Destination": {
        "S3Bucket": "my-billing",
        "S3Prefix": "focus",
        "S3Region": "us-east-1",
        "S3OutputConfigurations": {
            "Format": "PARQUET", "Compression": "PARQUET",
            "OutputType": "CUSTOM", "Overwrite": "OVERWRITE_REPORT",
        },
    }},
    "RefreshCadence": {"Frequency": "SYNCHRONOUS"},
})
print(resp["ExportArn"])
```

## Out of Scope

- Custom SQL evaluation in `DataQuery.QueryStatement` — Floci ignores
  the SQL and emits the FOCUS shape directly. The query string is
  persisted faithfully so SDK round-trips work, but it has no effect on
  the Parquet output.
- Cost categories, billing views, and pricing-model overrides
- Real `RefreshCadence` scheduling beyond `SYNCHRONOUS` and the
  `daily` Floci-internal mode
