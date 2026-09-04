# IAM Identity Center (SSO Admin)

**Protocol:** JSON 1.1 (`X-Amz-Target: SWBExternalService.*`)
**Signing name:** `sso`

Floci supports the SSO Admin operations used to manage IAM Identity Center permission sets and account assignments locally.

## Supported operations

`ListInstances`, `ListPermissionSets`, `CreatePermissionSet`, `DescribePermissionSet`, `UpdatePermissionSet`, `ListManagedPoliciesInPermissionSet`, `AttachManagedPolicyToPermissionSet`, `DetachManagedPolicyFromPermissionSet`, `DeleteInlinePolicyFromPermissionSet`, `PutInlinePolicyToPermissionSet`, `ListAccountAssignments`, `CreateAccountAssignment`, and `DescribeAccountAssignmentCreationStatus`.

State is isolated by caller account through Floci storage.

## AWS-compatible failures and state

Permission-set names, ARNs, session durations, managed-policy ARNs, inline policies, account IDs, principal types, pagination, and duplicate assignments are validated before state is changed. Missing resources return `ResourceNotFoundException`; duplicate or incompatible state returns `ConflictException`; invalid input returns `ValidationException`; enforced local limits return `ServiceQuotaExceededException`.

Account-assignment creation returns an operation record that can be read with `DescribeAccountAssignmentCreationStatus`. Provider-side `InternalServerException` and `ThrottlingException` are part of the AWS model but are not injected artificially by Floci.

See the [AWS SSO Admin API Reference](https://docs.aws.amazon.com/singlesignon/latest/APIReference/welcome.html).
