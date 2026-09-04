# Amazon Macie

Floci implements the REST JSON organization surfaces used to configure Macie locally.

## Supported behavior

The supported surface includes delegated administrator listing and enablement, Macie session lookup and enablement, and organization auto-enable configuration. Delegation is visible in both the management-account and delegated-account request scopes so the delegated account can continue the workflow using the normal AWS API.

`GetMacieSession` returns `ResourceNotFoundException` before Macie is enabled. Enabling an already enabled session or attempting incompatible administrator state returns `ConflictException`. Request validation and missing-resource behavior use the modeled `ValidationException` and `ResourceNotFoundException` responses.

AWS also models provider-side `InternalServerException`, `ServiceQuotaExceededException`, and `ThrottlingException`; these are not injected artificially.

See the [Amazon Macie API Reference](https://docs.aws.amazon.com/macie/latest/APIReference/Welcome.html).
