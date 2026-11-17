# AWS Account Management

**Protocol:** REST JSON

**Endpoint:** `http://localhost:4566`

Floci supports alternate contacts, primary contact information and account information over the AWS REST JSON protocol.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `PutAlternateContact` | - |
| `GetAlternateContact` | - |
| `DeleteAlternateContact` | - |
| `PutContactInformation` | Creates or replaces the primary contact information of an account (`POST /putContactInformation`) |
| `GetContactInformation` | Returns the primary contact information of an account and its phone verification status (`POST /getContactInformation`) |
| `GetAccountInformation` | Returns the ID, name, creation date and state of an account (`POST /getAccountInformation`) |
| `PutAccountName` | Renames an account (`POST /putAccountName`) |
<!-- floci:actions:end -->

Alternate contacts and primary contact information are isolated by caller account and stored through `StorageFactory`.

An account in an organization takes its name, creation date and state from its Organizations record. `PutAccountName` renames that record, so `DescribeAccount` and `ListAccounts` answer the new name at once. `AccountCreatedDate` is the `JoinedTimestamp` of an account that Organizations `CreateAccount` made; an invited account and the management account have none. An account that `CloseAccount` left `PENDING_CLOSURE`, a state Account Management does not have, reports `ACTIVE`.

`PutAccountName` also keeps the name for the account itself through `StorageFactory`, so an account that leaves its organization answers the last name it was given, and an account that joins one takes the name Organizations gives it. An account in no organization is `ACTIVE`. It has no creation date, and no name until `PutAccountName` gives it one, because Floci has no sign-up to take them from.

## AWS-compatible failures

Requests of all seven operations are checked by `RequestConstraintCheck` against the member types, required members, lengths, patterns and enum values generated from botocore's Account model, such as the `AlternateContactType` values (`BILLING`, `OPERATIONS`, `SECURITY`), the `PhoneNumber` pattern of `PutContactInformation` (a `+` followed by digits, spaces, parentheses, or hyphens), and the `AccountName` of `PutAccountName` (1 to 50 printable ASCII characters other than `<` and `>`). `tools/aws/regen_account_shapes.py` vendors them; `make aws-data-sync` regenerates them and `make aws-data-check` checks for drift. A member of the wrong JSON type fails with `SerializationException` and any other violation with `ValidationException`. `PutContactInformation` also requires `StateOrRegion` for the countries the API reference documents as requiring it (`US`, `CA`, `GB`, `DE`, `JP`, `IN`, `BR`), a rule the model does not carry. `GetAlternateContact` and `DeleteAlternateContact` return `ResourceNotFoundException` when the requested contact has not been configured, and `GetContactInformation` when no contact information has been stored.

A request that names an `AccountId` runs in the organizations context: the caller must be the organization's management account or its delegated administrator for Account Management, the organization must have all features and trusted access for Account Management enabled, and the `AccountId` must name a member account of that organization other than the management account. Any other request that names an `AccountId` returns `AccessDeniedException`, including the management account naming itself, whose own contacts and account information are reached by leaving `AccountId` out.

Three behaviors are Floci's own. An account has no primary contact until `PutContactInformation` stores one, because Floci has no sign-up contact to return. `CountryCode` is checked for its two-character length only, not against the ISO 3166-1 list. `GetContactInformation` always reports `VerificationStatus` as `NOT_SUPPORTED`, because Floci does not verify phone numbers.

AWS models provider-side `InternalServerException` and `TooManyRequestsException`; Floci does not synthesize those failures without an actual triggering condition.

See the [AWS Account Management API Reference](https://docs.aws.amazon.com/accounts/latest/reference/API_Operations.html).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_ACCOUNT_ENABLED` | `true` | Enable or disable AWS Account Management |
