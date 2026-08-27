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

Applied quotas and AWS default quotas return the same data, and quota values are static.
`RequestServiceQuotaIncrease` is accepted and validated but does not change any quota value —
see Limitations.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `ListServiceQuotas` | Lists the quotas for a service code; generated quotas for unknown codes |
| `GetServiceQuota` | Returns one quota by service and quota code, else `NoSuchResourceException` |
| `GetAWSDefaultServiceQuota` | Same as GetServiceQuota; defaults equal applied values |
| `ListAWSDefaultServiceQuotas` | Same as ListServiceQuotas; defaults equal applied values |
| `RequestServiceQuotaIncrease` | Validates and echoes an increase request as `PENDING`; not persisted, quota unchanged |
<!-- floci:actions:end -->

## Limitations

- **Quota increase requests are not persisted.** `RequestServiceQuotaIncrease` validates its
  input, resolves the quota, and returns a well-formed `RequestedQuota`, but keeps no state.
  The request is observable only in the response that creates it.
- **`Status` is always `PENDING` and never advances.** Nothing processes requests, so no
  request ever reaches `APPROVED`, `CASE_OPENED`, or `DENIED`. `PENDING` is what real AWS
  returns on creation, so a caller that only reads the creation response sees faithful data;
  a caller that polls for completion will wait forever.
- **Unknown service codes generate a quota catalog rather than failing.** This is deliberate, so
  the code has to be well-formed to be trusted: `ServiceCode` is validated against the modeled
  `[a-zA-Z][a-zA-Z0-9-]{1,63}` (max 63 characters) and a malformed one is rejected with
  `IllegalArgumentException` instead of returning invented quotas.
- **A requested increase does not change the quota.** `GetServiceQuota` continues to return
  the catalog value after a successful increase request. Quota values remain static by design
  so pipelines never stall on an unenforced limit.
- `GetRequestedServiceQuotaChange` and `ListRequestedServiceQuotaChangeHistory` are not
  implemented — there is no request store for them to read.
- `CaseId` is never returned; no support case is opened. `SupportCaseAllowed` in the request is
  accepted and ignored, as the emulator has no case-opening path either way.
- The quota-increase template operations (`PutServiceQuotaIncreaseRequestIntoTemplate` and
  related) are not implemented.

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
