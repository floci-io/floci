# Identity Store

**Protocol:** JSON 1.1 (`X-Amz-Target: AWSIdentityStore.*`)
**Signing name:** `identitystore`

Floci supports the Identity Store operations used by local IAM Identity Center provisioning.

## Supported operations

`ListGroups`, `CreateGroup`, `ListUsers`, `CreateUser`, `IsMemberInGroups`, and `CreateGroupMembership`.

Groups, users, and memberships are isolated by identity store and caller account and are persisted through `StorageFactory`.

## AWS-compatible failures

Floci validates required identifiers, names, filters, membership references, pagination, and duplicate group/user/membership state. Deterministic failures use the modeled AWS errors, including `ValidationException`, `ConflictException`, `ResourceNotFoundException`, and `ServiceQuotaExceededException` where the local limit is enforceable.

AWS also models provider-side failures such as `InternalServerException` and `ThrottlingException`. Floci does not synthesize those failures without a real state or request condition that causes them.

See the [AWS Identity Store API Reference](https://docs.aws.amazon.com/singlesignon/latest/IdentityStoreAPIReference/welcome.html).
