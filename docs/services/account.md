# AWS Account Management

**Protocol:** REST JSON

**Endpoint:** `http://localhost:4566`

Floci supports alternate contacts and primary contact information over the AWS REST JSON protocol.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `PutAlternateContact` | - |
| `GetAlternateContact` | - |
| `DeleteAlternateContact` | - |
| `PutContactInformation` | Creates or replaces the primary contact information of an account (`POST /putContactInformation`) |
| `GetContactInformation` | Returns the primary contact information of an account and its phone verification status (`POST /getContactInformation`) |
<!-- floci:actions:end -->

Alternate contacts and primary contact information are isolated by caller account and stored through `StorageFactory`.

## AWS-compatible failures

`PutAlternateContact` validates the contact type, name, title, email address, phone number, and optional target account ID. `GetAlternateContact` returns `ResourceNotFoundException` when the requested contact has not been configured. Invalid request data uses `ValidationException` and unsupported cross-account access uses `AccessDeniedException`.

`PutContactInformation` validates that the required members are present, the documented length of every member, the `PhoneNumber` pattern (a `+` followed by digits, spaces, parentheses, or hyphens), and that `StateOrRegion` is present for the countries AWS documents as requiring it (`US`, `CA`, `GB`, `DE`, `JP`, `IN`, `BR`). `GetContactInformation` returns `ResourceNotFoundException` when no contact information has been stored. Both operations apply the same target account ID rules as the alternate-contact operations.

Three behaviors are Floci's own. An account has no primary contact until `PutContactInformation` stores one, because Floci has no sign-up contact to return. `CountryCode` is checked for its two-character length only, not against the ISO 3166-1 list. `GetContactInformation` always reports `VerificationStatus` as `NOT_SUPPORTED`, because Floci does not verify phone numbers.

AWS models provider-side `InternalServerException` and `TooManyRequestsException`; Floci does not synthesize those failures without an actual triggering condition.

See the [AWS Account Management API Reference](https://docs.aws.amazon.com/accounts/latest/reference/API_Operations.html).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_ACCOUNT_ENABLED` | `true` | Enable or disable AWS Account Management |
