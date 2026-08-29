# Organizations

**Protocol:** JSON 1.1 (`X-Amz-Target: AWSOrganizationsV20161128.*`)
**Endpoint:** `POST http://localhost:4566/`

Floci emulates the full AWS Organizations control plane: the organization and its root,
the organizational-unit tree, member accounts, policies of every type, tags, trusted
service access, delegated administrators, the organization resource policy, and the
invitation/handshake flow.

## Accounts are real Floci accounts

Floci resolves any 12-digit access key id straight to an AWS account, so an account created
by `CreateAccount` is immediately usable as a caller identity against every other service:

```bash
aws --endpoint-url http://localhost:4566 organizations create-organization --feature-set ALL
aws --endpoint-url http://localhost:4566 organizations create-account \
    --email dev@example.com --account-name Dev
# ... then call as that account
AWS_ACCESS_KEY_ID=<new-account-id> AWS_SECRET_ACCESS_KEY=x \
  aws --endpoint-url http://localhost:4566 organizations describe-organization
```

## Management account vs member accounts

Authorization mirrors AWS. Every mutating action is restricted to the **management account**
— the account that called `CreateOrganization` — and returns `AccessDeniedException` otherwise.
Member accounts can read the organization they belong to (`DescribeOrganization`, `ListRoots`,
`ListParents`, `DescribeAccount`, `DescribePolicy`), act on handshakes addressed to them, and
call `LeaveOrganization`. An account in no organization gets
`AWSOrganizationsNotInUseException`.

## Behaviour notes

- The AWS-managed `p-FullAWSAccess` SCP is created with the organization and attached to the
  root, every new OU and every new account. Detaching the last service control policy from a
  target is rejected with `ConstraintViolationException`, as on AWS.
- A `CONSOLIDATED_BILLING` organization has no available policy types. `EnableAllFeatures`
  promotes it to `ALL`; with no member accounts the handshake completes immediately. With member
  accounts it stays `REQUESTED` until one of them calls `AcceptHandshake` — AWS requires *every*
  member to approve, which Floci simplifies to the first acceptance.
- `CreateAccount` returns a `CreateAccountStatus` you can poll with
  `DescribeCreateAccountStatus`, matching the asynchronous AWS contract. A duplicate email
  produces `State=FAILED` with `FailureReason=EMAIL_ALREADY_EXISTS` rather than an error.
- `DescribeEffectivePolicy` merges every policy of the requested type down the
  root → OU → target chain, with the closest ancestor winning on conflicting keys. As on AWS,
  the access-control types (`SERVICE_CONTROL_POLICY`, `RESOURCE_CONTROL_POLICY`) are rejected.
- Handshakes expire 15 days after they are created and report `EXPIRED` from then on.
- Organizations ARNs are global and carry no region:
  `arn:aws:organizations::123456789012:organization/o-abc1234567`.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateOrganization` | Creates an organization with the calling account as the management account. |
| `DescribeOrganization` | Returns information about the organization the calling account belongs to. |
| `DeleteOrganization` | Deletes the organization; only the management account may remain in it. |
| `EnableAllFeatures` | Upgrades a consolidated-billing organization to all features via a handshake. |
| `ListRoots` | Lists the roots defined in the organization. |
| `CreateOrganizationalUnit` | Creates an OU under the specified root or parent OU. |
| `UpdateOrganizationalUnit` | Renames the specified organizational unit. |
| `DeleteOrganizationalUnit` | Deletes an organizational unit that contains no accounts or child OUs. |
| `DescribeOrganizationalUnit` | Returns information about the specified organizational unit. |
| `ListOrganizationalUnitsForParent` | Lists the OUs directly under the specified root or parent OU. |
| `ListParents` | Lists the parent of the specified account or organizational unit. |
| `ListChildren` | Lists the accounts or OUs directly under the specified root or parent OU. |
| `CreateAccount` | Creates a member account in the organization and returns its request status. |
| `CreateGovCloudAccount` | Creates a member account together with a linked AWS GovCloud (US) account. |
| `DescribeCreateAccountStatus` | Returns the status of an account-creation request. |
| `ListCreateAccountStatus` | Lists account-creation requests, optionally filtered by state. |
| `DescribeAccount` | Returns information about the specified member account. |
| `ListAccounts` | Lists every account in the organization. |
| `ListAccountsForParent` | Lists the accounts directly under the specified root or OU. |
| `ListAccountsWithInvalidEffectivePolicy` | Always returns an empty list; effective-policy validation is not modeled. |
| `MoveAccount` | Moves an account from one root or OU to another. |
| `RemoveAccountFromOrganization` | Removes a member account from the organization. |
| `LeaveOrganization` | Removes the calling member account from its organization. |
| `CloseAccount` | Closes a member account, moving it to PENDING_CLOSURE. |
| `CreatePolicy` | Creates a policy of the specified type in the organization. |
| `UpdatePolicy` | Updates the name, description or content of a customer-managed policy. |
| `DeletePolicy` | Deletes a policy that is not attached to any target. |
| `DescribePolicy` | Returns a policy's summary and content. |
| `ListPolicies` | Lists the policies of the specified type in the organization. |
| `AttachPolicy` | Attaches a policy to a root, organizational unit or account. |
| `DetachPolicy` | Detaches a policy from a root, organizational unit or account. |
| `ListPoliciesForTarget` | Lists the policies of a given type attached directly to a target. |
| `ListTargetsForPolicy` | Lists the roots, OUs and accounts a policy is attached to. |
| `EnablePolicyType` | Enables a policy type on the specified root. |
| `DisablePolicyType` | Disables a policy type on the specified root. |
| `DescribeEffectivePolicy` | Returns the merged policy of the given type that applies to a target. |
| `TagResource` | Adds or overwrites tags on a root, OU, account or policy. |
| `UntagResource` | Removes the specified tags from a root, OU, account or policy. |
| `ListTagsForResource` | Lists the tags on a root, OU, account or policy. |
| `EnableAWSServiceAccess` | Enables trusted access for an AWS service principal. |
| `DisableAWSServiceAccess` | Disables trusted access for an AWS service principal. |
| `ListAWSServiceAccessForOrganization` | Lists the service principals with trusted access enabled. |
| `RegisterDelegatedAdministrator` | Registers a member account as a delegated administrator for a service. |
| `DeregisterDelegatedAdministrator` | Removes a member account's delegated administrator role for a service. |
| `ListDelegatedAdministrators` | Lists the delegated administrators in the organization. |
| `ListDelegatedServicesForAccount` | Lists the services a member account is a delegated administrator for. |
| `PutResourcePolicy` | Creates or updates the organization's resource policy. |
| `DescribeResourcePolicy` | Returns the organization's resource policy. |
| `DeleteResourcePolicy` | Deletes the organization's resource policy. |
| `InviteAccountToOrganization` | Sends an invitation handshake to an account or email address. |
| `AcceptHandshake` | Accepts a handshake, joining the organization for an invitation. |
| `DeclineHandshake` | Declines a handshake addressed to the calling account. |
| `CancelHandshake` | Cancels an open handshake the calling account originated. |
| `DescribeHandshake` | Returns information about the specified handshake. |
| `ListHandshakesForAccount` | Lists the handshakes that involve the calling account. |
| `ListHandshakesForOrganization` | Lists the handshakes associated with the organization. |
<!-- floci:actions:end -->

## Configuration

| Environment variable | Default | Description |
| --- | --- | --- |
| `FLOCI_SERVICES_ORGANIZATIONS_ENABLED` | `true` | Enables the service |
| `FLOCI_SERVICES_ORGANIZATIONS_SCP_ENFORCEMENT_ENABLED` | `false` | When `true` (and IAM enforcement is enabled), attached service control policies participate in IAM policy evaluation |
| `FLOCI_SERVICES_ORGANIZATIONS_MANAGEMENT_ACCOUNT_EMAIL` | unset | Email reported for the organization's management account (`DescribeOrganization` master account, `ListAccounts`). Unset falls back to the built-in default |
| `FLOCI_STORAGE_SERVICES_ORGANIZATIONS_MODE` | inherits `FLOCI_STORAGE_MODE` | Storage mode override |
| `FLOCI_STORAGE_SERVICES_ORGANIZATIONS_FLUSH_INTERVAL_MS` | `5000` | Hybrid/WAL flush interval |

## SCP enforcement

With `FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED=true` and
`FLOCI_SERVICES_ORGANIZATIONS_SCP_ENFORCEMENT_ENABLED=true`, service control policies attached to
the root, OUs, and accounts participate in IAM policy evaluation: an action must be allowed at every
level of the account's chain, before the caller's identity policies are consulted. SCPs never grant
permissions on their own. See
[Service Control Policies (SCPs)](iam.md#service-control-policies-scps) for the evaluation order,
the account-root behaviour, and the cases that bypass enforcement.

The evaluation itself lives in the IAM enforcement layer — this service stores the policies and
resolves the chain, but with IAM enforcement off the flag has no effect.

## Example

```bash
aws --endpoint-url http://localhost:4566 organizations create-organization --feature-set ALL

ROOT_ID=$(aws --endpoint-url http://localhost:4566 organizations list-roots \
  --query 'Roots[0].Id' --output text)

aws --endpoint-url http://localhost:4566 organizations create-organizational-unit \
  --parent-id "$ROOT_ID" --name workloads

aws --endpoint-url http://localhost:4566 organizations create-account \
  --email member@example.com --account-name "workload-account"

aws --endpoint-url http://localhost:4566 organizations list-accounts
```
