# AWS App Runner

**Protocol:** JSON 1.0 (`X-Amz-Target: AppRunner.<Operation>`)
**Endpoint:** `http://localhost:4566/` (SigV4 service `apprunner`)

## Supported Actions

| Action | Description |
|---|---|
| `CreateAutoScalingConfiguration` | Create a revision; `ACTIVE` immediately, revisions increment per name |
| `DescribeAutoScalingConfiguration` | Describe by full ARN, `name/revision` ARN, or bare-name ARN (resolves the latest active revision) |
| `DeleteAutoScalingConfiguration` | Delete a revision, or every revision of a name with `DeleteAllRevisions`; rejected while a service still references it |
| `ListAutoScalingConfigurations` | List revisions, always including the account default configuration |
| `CreateObservabilityConfiguration` | Create a revision; `ACTIVE` immediately, revisions increment per name |
| `DescribeObservabilityConfiguration` | Describe by full ARN, `name/revision` ARN, or bare-name ARN |
| `DeleteObservabilityConfiguration` | Delete a revision; moves to `INACTIVE` and drops from listings |
| `ListObservabilityConfigurations` | List active revisions, with optional name and `LatestOnly` filtering |
| `CreateVpcConnector` | Create a VPC connector; `ACTIVE` immediately, requires at least one subnet |
| `DescribeVpcConnector` | Describe a VPC connector by ARN |
| `DeleteVpcConnector` | Delete a VPC connector; moves to `INACTIVE` and drops from listings |
| `ListVpcConnectors` | List active VPC connectors |
| `CreateVpcIngressConnection` | Create a VPC ingress connection; `AVAILABLE` immediately, requires `IngressVpcConfiguration` |
| `DescribeVpcIngressConnection` | Describe a VPC ingress connection by ARN |
| `DeleteVpcIngressConnection` | Delete a VPC ingress connection; moves to `DELETED` and drops from listings |
| `ListVpcIngressConnections` | List VPC ingress connections, with optional `ServiceArn` filtering |
| `CreateConnection` | Create a GitHub/Bitbucket connection; `AVAILABLE` immediately |
| `DeleteConnection` | Delete a connection |
| `ListConnections` | List connections, with optional name filtering |
| `CreateService` | Create a service; `RUNNING` immediately with a plausible `awsapprunner.com` URL that resolves nowhere |
| `DescribeService` | Describe a service by ARN |
| `UpdateService` | Update source, instance, health check, network or observability configuration; stays `RUNNING` |
| `DeleteService` | Delete a service; moves to `DELETED` and drops from listings |
| `ListServices` | List non-deleted services |
| `PauseService` / `ResumeService` | Toggle between `PAUSED` and `RUNNING` |
| `StartDeployment` | Record a `START_DEPLOYMENT` operation against the service |
| `ListOperations` | List recorded operations for a service, most recent first |
| `TagResource` / `UntagResource` / `ListTagsForResource` | Tag any App Runner resource by ARN |

No container is built, pushed or run: `CreateService` accepts any
`SourceConfiguration` and the resulting `ServiceUrl` resolves nowhere.
Every resource reports its terminal state as soon as its create call
returns, so `terraform-provider-aws`'s waiters complete on the first poll.

Deleting a resource moves it to the deleted state its own status enum
defines (`INACTIVE` for auto scaling and observability configurations and
VPC connectors, `DELETED` for connections, services and VPC ingress
connections) and drops it from list operations, matching what App Runner's
own API documents.

Every other App Runner action (custom domains, deployment listing beyond
what `StartDeployment` records) returns a clean `UnknownOperationException`
rather than a stub success.
