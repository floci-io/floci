# Amazon Detective

Floci implements the REST JSON Detective organization and behavior-graph operations used by local security-governance workflows.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `ListOrganizationAdminAccounts` | Lists the Detective administrator account configured for the organization. |
| `EnableOrganizationAdminAccount` | Designates a Detective administrator account and enables its organization behavior graph. |
| `ListGraphs` | Lists behavior graphs for the current account and Region. |
| `DescribeOrganizationConfiguration` | Returns the organization auto-enable setting for a behavior graph. |
| `UpdateOrganizationConfiguration` | Updates the organization auto-enable setting for a behavior graph. |
| `ListMembers` | Lists organization member accounts in a behavior graph. |
| `CreateMembers` | Enables organization accounts as behavior-graph members. |
| `StartMonitoringMember` | Starts data contribution for an accepted but disabled member account. |
<!-- floci:actions:end -->

For organization behavior graphs, member accounts can be created without an email address. Duplicate member requests are returned through `UnprocessedAccounts`, while successfully processed accounts are returned through `Members`. `ListMembers` accepts the AWS-documented `MaxResults` range of 1 through 200.

Organization configuration accepts an optional `AutoEnable` field and requires the behavior graph ARN. Successful `EnableOrganizationAdminAccount`, `UpdateOrganizationConfiguration`, and `StartMonitoringMember` operations return an empty HTTP 200 response body, matching the AWS API contract.

Invalid graph, account, member, and pagination data returns modeled `ValidationException` or `ResourceNotFoundException` responses. Incompatible member transitions return `ConflictException`, and the 1,200-member behavior-graph quota is enforced with `ServiceQuotaExceededException`. Provider-side internal and throttling errors are not injected artificially.

See the [Amazon Detective API Reference](https://docs.aws.amazon.com/detective/latest/APIReference/Welcome.html).
