# IAM Identity Center (SSO Admin)

**Protocol:** JSON 1.1 (`X-Amz-Target: SWBExternalService.*`)
**Signing name:** `sso`

Floci supports the SSO Admin operations used to manage IAM Identity Center permission sets and account assignments locally.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `ListInstances` | Lists the local IAM Identity Center instance. |
| `ListPermissionSets` | Lists permission sets with AWS-compatible pagination. |
| `CreatePermissionSet` | Creates a permission set. |
| `DescribePermissionSet` | Describes a permission set. |
| `UpdatePermissionSet` | Updates mutable permission-set settings. |
| `ListManagedPoliciesInPermissionSet` | Lists attached AWS managed policies with AWS-compatible pagination. |
| `AttachManagedPolicyToPermissionSet` | Attaches an AWS managed policy. |
| `DetachManagedPolicyFromPermissionSet` | Detaches an AWS managed policy. |
| `DeleteInlinePolicyFromPermissionSet` | Deletes the inline policy. |
| `PutInlinePolicyToPermissionSet` | Creates or replaces the inline policy. |
| `ListAccountAssignments` | Lists account assignments with AWS-compatible pagination. |
| `CreateAccountAssignment` | Creates an account assignment and operation record. |
| `DescribeAccountAssignmentCreationStatus` | Describes account-assignment creation status. |
<!-- floci:actions:end -->

State is isolated by caller account through Floci storage.

## AWS-compatible failures and state

Permission-set names, ARNs, session durations, managed-policy ARNs, inline policies, account IDs, principal types, pagination, and duplicate assignments are validated before state is changed. Missing resources return `ResourceNotFoundException`; duplicate or incompatible state returns `ConflictException`; invalid input returns `ValidationException`; enforced local limits return `ServiceQuotaExceededException`.

Account-assignment creation returns an operation record that can be read with `DescribeAccountAssignmentCreationStatus`. Provider-side `InternalServerException` and `ThrottlingException` are part of the AWS model but are not injected artificially by Floci.

See the [AWS SSO Admin API Reference](https://docs.aws.amazon.com/singlesignon/latest/APIReference/welcome.html).
