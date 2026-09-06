# Amazon Inspector

**Protocol:** REST JSON

**Endpoint:** `http://localhost:4566`

Floci implements the Amazon Inspector organization operations used by local security-governance workflows.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `ListDelegatedAdminAccounts` | - |
| `EnableDelegatedAdminAccount` | - |
| `DisableDelegatedAdminAccount` | - |
| `BatchGetAccountStatus` | - |
| `Enable` | - |
| `UpdateOrganizationConfiguration` | - |
| `DescribeOrganizationConfiguration` | - |
<!-- floci:actions:end -->

## Supported behavior

The supported surface includes delegated administrator management, `BatchGetAccountStatus`, Inspector enablement, and organization auto-enable configuration. Delegation is reflected into the delegated account so organization configuration can be managed through credentials for that administrator account.

`BatchGetAccountStatus` accepts an omitted or empty `accountIds` list and resolves it to the caller account. Explicit enablement supports `EC2`, `ECR`, `LAMBDA`, `LAMBDA_CODE`, and `CODE_REPOSITORY`, with the documented request-size limits. Explicit enablement uses the account-state values `ENABLING` and `ENABLED` so SDK and deployment waiters can converge.

Organization auto-enable supports `ec2`, `ecr`, `lambda`, `lambdaCode`, and `codeRepository`. Only the delegated administrator account can update or describe organization configuration.

## AWS-compatible failures

Invalid account IDs, resource types, pagination input, and request shapes return `ValidationException`. Conflicting delegated-administrator state returns `ConflictException`. Organization configuration from a non-administrator account returns `AccessDeniedException`, and disabling an administrator that is not configured returns `ResourceNotFoundException`.

AWS also models internal and throttling failures. Floci does not synthesize provider-side failures without a local triggering condition.

See the [Amazon Inspector API Reference](https://docs.aws.amazon.com/inspector/v2/APIReference/Welcome.html).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_INSPECTOR2_ENABLED` | `true` | Enable or disable Amazon Inspector |
