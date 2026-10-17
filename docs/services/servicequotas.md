# Service Quotas

**Protocol:** JSON 1.1 (`X-Amz-Target: ServiceQuotasV20190624.*`)
**Endpoint:** `POST http://localhost:4566/`
**Signing name:** `servicequotas`

Floci answers Service Quotas lookups from a generated in-memory catalog. Every service code
resolves to a quota list: a curated set with real AWS quota codes where tooling depends on
them — CodeBuild's `L-2DC20C30` ("Concurrently running builds") and Lambda's `L-B99A9384`
("Concurrent executions") — plus a deterministic generic set for any other service code.
Unmodeled values are deliberately generous so local pipelines that gate on quota headroom, such
as AWS Landing Zone Accelerator, never stall on a limit the emulator does not enforce. The
Organizations `Maximum number of accounts` quota uses AWS quota code `L-E619E033` and a local value
of 50. VPC's `L-FE5A380F` ("NAT gateways per Availability Zone", 5) and `L-2AEEBF1A` ("Rules per
network ACL", 20) carry their AWS default values, so tooling that compares a desired value against
the default before requesting an increase takes the same path it would against AWS.
EC2's `L-1216C47A` ("Running On-Demand Standard (A, C, D, H, I, M, R, T, Z)
instances") also reports its AWS default of 5. This catalog entry does not enforce
a vCPU admission limit on EC2.

Applied quotas and AWS default quotas return the same data, and quota values are static.
`RequestServiceQuotaIncrease` is accepted and validated, records the request, and lets you read
it back with `GetRequestedServiceQuotaChange` or list it with
`ListRequestedServiceQuotaChangeHistoryByQuota`, but it does not change any quota value. See
Limitations.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `ListServiceQuotas` | Lists the quotas for a service code; generated quotas for unknown codes |
| `GetServiceQuota` | Returns one quota by service and quota code, else `NoSuchResourceException` |
| `GetAWSDefaultServiceQuota` | Same as GetServiceQuota; defaults equal applied values |
| `ListAWSDefaultServiceQuotas` | Same as ListServiceQuotas; defaults equal applied values |
| `RequestServiceQuotaIncrease` | Validates and stores an increase request as `PENDING`; a second open request for the same quota is refused with `ResourceAlreadyExistsException` |
| `GetRequestedServiceQuotaChange` | Returns a stored request by id, else `NoSuchResourceException` |
| `ListRequestedServiceQuotaChangeHistoryByQuota` | Lists the stored requests for a service/quota pair, newest first |
<!-- floci:actions:end -->

## Limitations

- **Quota increase requests are persisted per region, but `Status` never advances.** A
  successful `RequestServiceQuotaIncrease` is stored under its own id and can be read back with
  `GetRequestedServiceQuotaChange` or listed with `ListRequestedServiceQuotaChangeHistoryByQuota`
  in the region that created it. Because a request stays `PENDING`, a second
  `RequestServiceQuotaIncrease` for the same quota in the same region is refused with
  `ResourceAlreadyExistsException`, matching AWS's one-open-request-per-quota rule. Nothing
  processes a request, however, so it stays `PENDING` and
  never reaches `APPROVED`, `CASE_OPENED`, or `DENIED`. `PENDING` is what real AWS returns on
  creation, so a caller that reads the creation response sees faithful data; a caller that polls
  for completion will wait forever.
- **Unknown service codes generate a quota catalog rather than failing.** This is deliberate, so
  the code has to be well-formed to be trusted: `ServiceCode` is validated against the modeled
  `[a-zA-Z][a-zA-Z0-9-]{1,63}` (max 63 characters) and a malformed one is rejected with
  `IllegalArgumentException` instead of returning invented quotas.
- **A requested increase does not change the quota.** `GetServiceQuota` continues to return
  the catalog value after a successful increase request. Quota values remain static by design
  so pipelines never stall on an unenforced limit.
- `ListRequestedServiceQuotaChangeHistory` (the account-wide variant without a quota filter) is
  not implemented.
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
