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
| `DescribeHub` | - |
| `EnableSecurityHub` | - |
| `UpdateSecurityHubConfiguration` | - |
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
| `ListTagsForResource` | - |
| `TagResource` | - |
| `UntagResource` | - |
<!-- floci:actions:end -->

## Supported behavior

The supported surface includes Security Hub CSPM enablement and configuration, delegated administrator management, finding aggregators, organization configuration, configuration policies, policy associations, and resource tags.

`EnableOrganizationAdminAccount` supports the AWS `SecurityHub` and `SecurityHubV2` feature values. Legacy `SecurityHub` delegation enables Security Hub CSPM for the delegated administrator in the current Region, matching AWS organization behavior.

Finding aggregators support `ALL_REGIONS`, `ALL_REGIONS_EXCEPT_SPECIFIED`, `SPECIFIED_REGIONS`, and `NO_REGIONS`. `GetFindingAggregator` accepts the complete finding-aggregator ARN carried in the greedy AWS REST path.

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
