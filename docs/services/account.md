# AWS Account Management

**Protocol:** REST JSON

**Endpoint:** `http://localhost:4566`

Floci supports alternate-contact management over the AWS REST JSON protocol.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `PutAlternateContact` | - |
| `GetAlternateContact` | - |
<!-- floci:actions:end -->

Alternate contacts are isolated by caller account and stored through `StorageFactory`.

## AWS-compatible failures

`PutAlternateContact` validates the contact type, name, title, email address, phone number, and optional target account ID. `GetAlternateContact` returns `ResourceNotFoundException` when the requested contact has not been configured. Invalid request data uses `ValidationException` and unsupported cross-account access uses `AccessDeniedException`.

AWS models provider-side `InternalServerException` and `TooManyRequestsException`; Floci does not synthesize those failures without an actual triggering condition.

See the [AWS Account Management API Reference](https://docs.aws.amazon.com/accounts/latest/reference/API_Operations.html).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_ACCOUNT_ENABLED` | `true` | Enable or disable AWS Account Management |
