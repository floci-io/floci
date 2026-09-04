# Amazon Inspector

Floci implements the REST JSON Inspector organization operations used by local security-governance workflows.

## Supported behavior

The supported surface includes delegated administrator management, `BatchGetAccountStatus`, Inspector enablement, and organization auto-enable configuration. Delegation is reflected into the delegated account and reports Inspector as enabled there, matching the delegated-administrator workflow.

Explicit enablement uses the AWS account-state values `ENABLING` and `ENABLED`. Organization auto-enable validates the `ec2`, `ecr`, `lambda`, and `lambdaCode` fields. Missing delegation where it is required returns `AccessDeniedException`; invalid account IDs, resource types, and request shapes return `ValidationException`; conflicting delegated-administrator state returns `ConflictException`.

AWS also models internal and throttling failures. Floci does not synthesize provider-side failures without a local triggering condition.

See the [Amazon Inspector API Reference](https://docs.aws.amazon.com/inspector/v2/APIReference/Welcome.html).
