# Service Quotas

**Protocol:** JSON 1.1 (`X-Amz-Target: ServiceQuotasV20190624.*`)
**Endpoint:** `POST http://localhost:4566/`
**Signing name:** `servicequotas`

Floci answers Service Quotas lookups from a generated in-memory catalog. Every service code
resolves to a quota list: a curated set with real AWS quota codes where tooling depends on
them — CodeBuild's `L-2DC20C30` ("Concurrently running builds") and Lambda's `L-B99A9384`
("Concurrent executions") — plus a deterministic generic set for any other service code. All
values are deliberately generous (5000) so local pipelines that gate on quota headroom, such
as AWS Landing Zone Accelerator, never stall on a limit the emulator does not enforce.

Applied quotas and AWS default quotas return the same data, and quota values are static:
`RequestServiceQuotaIncrease` and the other write operations are not implemented.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `ListServiceQuotas` | Lists the quotas for a service code; generated quotas for unknown codes |
| `GetServiceQuota` | Returns one quota by service and quota code, else `NoSuchResourceException` |
| `GetAWSDefaultServiceQuota` | Same as GetServiceQuota; defaults equal applied values |
| `ListAWSDefaultServiceQuotas` | Same as ListServiceQuotas; defaults equal applied values |
<!-- floci:actions:end -->

## Configuration

| Environment variable | Default | Description |
| --- | --- | --- |
| `FLOCI_SERVICES_SERVICEQUOTAS_ENABLED` | `true` | Enables the service |

## Example

```bash
aws --endpoint-url http://localhost:4566 service-quotas list-service-quotas \
  --service-code codebuild

aws --endpoint-url http://localhost:4566 service-quotas get-service-quota \
  --service-code codebuild --quota-code L-2DC20C30
```
