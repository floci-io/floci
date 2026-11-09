# AWS Security Hub

**Protocol:** REST JSON

**Endpoint:** `http://localhost:4566`

Floci implements the AWS Security Hub CSPM organization and central-configuration surfaces used by local security-governance workflows.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `ListOrganizationAdminAccounts` | - |
| `EnableOrganizationAdminAccount` | - |
| `DisableOrganizationAdminAccount` | Removes the delegated administrator; only the management account may call it. The former administrator keeps Security Hub, and its organization members become `Removed` |
| `GetMembers` | Returns the named members whatever their status; an account that is not a member is listed in `UnprocessedAccounts` |
| `DisassociateMembers` | Sets the named members to `Removed`; an account that is not a member is `ResourceNotFoundException`, and then none of the batch changes |
| `DeleteMembers` | Deletes invited members; an organization account or a non member is listed in `UnprocessedAccounts`, since AWS allows only disassociating organization accounts |
| `DescribeHub` | - |
| `EnableSecurityHub` | - |
| `UpdateSecurityHubConfiguration` | - |
| `DescribeStandards` | Lists the standards Security Hub offers: CIS 1.2.0, 1.4.0, 3.0.0 and 5.0.0, AFSBP, NIST 800-53 Rev5, PCI DSS 3.2.1 and 4.0.1, and the resource tagging standard |
| `BatchEnableStandards` | Subscribes standards (`READY` at once); an unknown standards ARN is `InvalidInputException`, a disabled hub `InvalidAccessException`. `EnableSecurityHub` subscribes AFSBP and CIS 1.2.0 unless `EnableDefaultStandards` is false |
| `BatchDisableStandards` | Removes subscriptions, answered as `DELETING`; an unknown one is `ResourceNotFoundException`, and then none of the batch is removed |
| `GetEnabledStandards` | Lists subscriptions, all or the named ones. Controls and findings are not evaluated |
| `BatchImportFindings` | Imports up to 100 ASFF findings for the caller's default product, with per-finding failures |
| `GetFindings` | Reads imported findings with eight string-filter fields and cursor pagination |
| `ListFindingAggregators` | - |
| `CreateFindingAggregator` | - |
| `GetFindingAggregator` | - |
| `UpdateFindingAggregator` | - |
| `DescribeOrganizationConfiguration` | - |
| `UpdateOrganizationConfiguration` | - |
| `ListConfigurationPolicies` | - |
| `CreateConfigurationPolicy` | - |
| `GetConfigurationPolicy` | - |
| `UpdateConfigurationPolicy` | - |
| `GetConfigurationPolicyAssociation` | - |
| `StartConfigurationPolicyAssociation` | - |
| `StartConfigurationPolicyDisassociation` | - |
| `ListConfigurationPolicyAssociations` | - |
| `CreateMembers` | Adds up to 50 accounts. The delegated administrator's organization accounts become `Enabled` at once, with Security Hub and its default standards enabled for them; other accounts are `Created` |
| `ListMembers` | Lists `Enabled` members, or every member with `OnlyAssociated=false`, in account order with up to 50 per page |
| `ListTagsForResource` | - |
| `TagResource` | - |
| `UntagResource` | - |
<!-- floci:actions:end -->

## Supported behavior

The supported surface includes Security Hub CSPM enablement and configuration, delegated administrator management, finding aggregators, organization configuration, configuration policies, policy associations, and resource tags.

`EnableOrganizationAdminAccount` supports the AWS `SecurityHub` and `SecurityHubV2` feature values. Legacy `SecurityHub` delegation enables Security Hub CSPM for the delegated administrator in the current Region, matching AWS organization behavior.

Member accounts follow the AWS organization model. `CreateMembers` from the delegated `SecurityHub` administrator adds an account of the same organization as an `Enabled` member, without an invitation, and enables Security Hub CSPM with its default standards in that account unless it is already enabled. The organization management account is the exception: it must enable Security Hub CSPM itself, and until it does it is reported in `UnprocessedAccounts`. Any other account is added as `Created`; `InviteMembers` and the member side of invitations are not implemented. `CreateMembers` on a `Removed` member adds it again.

`POST /members` and `GET /members` are shared with Macie, which serves `CreateMember` and `ListMembers` at the same paths. Floci routes each request by the service in its SigV4 credential scope; an unsigned request reaches Macie, as before.

Finding aggregators support `ALL_REGIONS`, `ALL_REGIONS_EXCEPT_SPECIFIED`, `SPECIFIED_REGIONS`, and `NO_REGIONS`. `GetFindingAggregator` accepts the complete finding-aggregator ARN carried in the greedy AWS REST path.

`BatchImportFindings` stores findings in the requested Region and reports invalid items individually. `GetFindings` reads findings from that Region and supports the `EQUALS`, `NOT_EQUALS`, and `PREFIX` comparisons for its documented string filters. `SortCriteria`, other filter comparisons, cross-Region aggregation of findings, and `BatchUpdateFindings` are not implemented.

Central organization configuration models asynchronous convergence. Changing to `CENTRAL` first reports organization status `PENDING`; a subsequent poll converges to `ENABLED`. Central configuration forces `AutoEnable` to `false` and `AutoEnableStandards` to `NONE`, as AWS does in the home and linked Regions. Configuration-policy association behaves similarly with `PENDING` followed by `SUCCESS`. Disassociation is represented as a pending transition before the association disappears.

Configuration-policy and hub tags are persisted and returned through the shared Security Hub tagging routes. Tag keys and values use the AWS Security Hub constraints, including the reserved `aws:` prefix restriction.

## AWS-compatible failures

Floci validates administrator account IDs and features, finding-aggregator modes and Region lists, central-configuration input, policy names and documents, target identifiers, tags, and association state. Deterministic failures use AWS-modeled errors, including `InvalidInputException`, `InvalidAccessException`, `ResourceNotFoundException`, `ResourceConflictException`, and `LimitExceededException`.

AWS also defines provider-side `InternalException` and rate-limit failures. Floci does not inject those failures without a request or local state condition that causes them.

See the [AWS Security Hub API Reference](https://docs.aws.amazon.com/securityhub/1.0/APIReference/Welcome.html).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_SECURITYHUB_ENABLED` | `true` | Enable or disable AWS Security Hub CSPM |
