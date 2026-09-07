# Identity Store

**Protocol:** JSON 1.1 (`X-Amz-Target: AWSIdentityStore.*`)

**Signing name:** `identitystore`

Floci emulates the AWS Identity Store management API used by IAM Identity Center. Identity store resources are keyed by `IdentityStoreId` rather than the caller account, matching AWS behavior that allows authorized member accounts to access the same identity store.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateGroup` | - |
| `DeleteGroup` | - |
| `DescribeGroup` | - |
| `GetGroupId` | - |
| `ListGroups` | - |
| `UpdateGroup` | - |
| `CreateUser` | - |
| `DeleteUser` | - |
| `DescribeUser` | - |
| `GetUserId` | - |
| `ListUsers` | - |
| `UpdateUser` | - |
| `CreateGroupMembership` | - |
| `DeleteGroupMembership` | - |
| `DescribeGroupMembership` | - |
| `GetGroupMembershipId` | - |
| `IsMemberInGroups` | - |
| `ListGroupMemberships` | - |
| `ListGroupMembershipsForMember` | - |
<!-- floci:actions:end -->

## Behavior

Groups, users, and group memberships are persisted through `StorageFactory` and implement the complete AWS SDK Java v2 Identity Store operation surface. New resource identifiers use the normal `1234567890-UUID` format for `d-1234567890` identity stores, while legacy UUID-form identity store identifiers use UUID resource identifiers.

`ListGroups`, `ListUsers`, `ListGroupMemberships`, and `ListGroupMembershipsForMember` support AWS-style pagination with a maximum `MaxResults` of 100. The deprecated `ListGroups` and `ListUsers` filters remain supported for SDK compatibility. `GetGroupId` and `GetUserId` support alternate identifiers, including the documented unique attribute paths and external identifiers when those values are present in the stored resource.

`UpdateGroup` and `UpdateUser` apply `AttributeOperation` updates, including removals when `AttributeValue` is omitted. User updates support nested attributes and the `aws:identitystore:enterprise` extension. User extensions are returned by `DescribeUser` and `ListUsers` only when requested through `Extensions`, as in AWS.

Deleting a user or group removes its related local group memberships so subsequent membership queries do not retain dangling references.

## AWS-compatible failures

Floci validates identity store and resource identifier formats, filter shapes, pagination bounds, membership references, alternate-identifier unions, duplicate user/group names, duplicate memberships, reserved names, operation counts, and local user/group quotas. Deterministic failures use modeled AWS errors including `ValidationException`, `ConflictException`, `ResourceNotFoundException`, and `ServiceQuotaExceededException`.

AWS also models provider-side errors such as `AccessDeniedException`, `InternalServerException`, and `ThrottlingException`. Floci does not synthesize those failures without a request or emulator-state condition that causes them.

See the [AWS Identity Store API Reference](https://docs.aws.amazon.com/singlesignon/latest/IdentityStoreAPIReference/welcome.html).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_IDENTITYSTORE_ENABLED` | `true` | Enable or disable Identity Store |
