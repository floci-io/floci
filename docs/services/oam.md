# CloudWatch Observability Access Manager (`oam:*`)

**Protocol:** REST JSON
**Signing name:** `oam`
**API version:** `2022-06-10`

Floci emulates the CloudWatch Observability Access Manager control plane for cross-account observability links. Sinks and links are account and Region scoped, and attached-link discovery crosses source-account storage partitions in the same way the AWS API exposes source links to a monitoring sink.

## Supported operations

| Operation | Notes |
|---|---|
| `CreateSink` | Creates the single sink allowed for the calling account and Region |
| `GetSink` | Returns one sink by ARN |
| `ListSinks` | Lists sinks for the calling account and Region |
| `PutSinkPolicy` | Stores and validates the sink resource policy |
| `GetSinkPolicy` | Returns the current policy for a sink |
| `DeleteSink` | Rejects deletion while links remain attached |
| `CreateLink` | Creates a source-account link after evaluating the sink policy |
| `GetLink` | Returns one source-account link |
| `ListLinks` | Lists links owned by the calling source account |
| `ListAttachedLinks` | Lists links from all source accounts attached to a sink |
| `UpdateLink` | Replaces shared resource types and optional metric/log filters |
| `DeleteLink` | Deletes a source-account link |
| `ListTagsForResource` | Returns tags for a sink or link |
| `TagResource` | Adds or replaces tags on a sink or link |
| `UntagResource` | Removes tag keys from a sink or link |

## Sink policies

`PutSinkPolicy` accepts IAM-style JSON policies. For `CreateLink`, Floci evaluates the policy's `Effect`, `Principal`, `Action`, `Resource`, and the `oam:ResourceTypes` condition. Individual account principals, account root ARNs, wildcard principals, exact sink ARNs, and wildcard resources are supported.

Organization-based conditions are not currently resolved through AWS Organizations metadata. Policies that depend on organization condition keys should use explicit account principals when testing locally.

## Resource types and filters

Floci accepts the resource types modeled by the current OAM API:

- `AWS::CloudWatch::Metric`
- `AWS::Logs::LogGroup`
- `AWS::XRay::Trace`
- `AWS::ApplicationInsights::Application`
- `AWS::InternetMonitor::Monitor`
- `AWS::ApplicationSignals::Service`
- `AWS::ApplicationSignals::ServiceLevelObjective`

Metric and log-group filters are persisted with the link. Updating a filter to `*` removes that filter, matching the AWS update contract.

## Quotas and validation

- One sink per account per Region.
- Up to five links per source account per Region.
- `ListSinks` accepts `MaxResults` from 1 to 100.
- `ListLinks` accepts `MaxResults` from 1 to 5.
- `ListAttachedLinks` accepts `MaxResults` from 1 to 1000.
- A sink cannot be deleted while links are attached.
- Sinks and links support up to 50 tags.

Floci resolves the account-derived label variables to the local account ID because the emulator has no AWS account email/name directory attached to OAM itself.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_OAM_ENABLED` | `true` | Enable or disable OAM |

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

sink_arn=$(aws oam create-sink --name local-observability --query Arn --output text)

aws oam put-sink-policy \
  --sink-identifier "$sink_arn" \
  --policy '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":"*","Action":"oam:CreateLink","Resource":"*"}]}'

aws oam create-link \
  --sink-identifier "$sink_arn" \
  --label-template local-source \
  --resource-types AWS::CloudWatch::Metric AWS::Logs::LogGroup
```
