# IAM

**Protocol:** Query (XML) — `POST http://localhost:4566/` with `Action=` parameter

## AWS Sign-In login credentials

Floci also implements the AWS Sign-In data-plane flow used by the AWS CLI login credentials
provider. The local `GET /v1/authorize` endpoint performs the emulator's local sign-in and returns
a one-time PKCE authorization code. `POST /v1/token` exchanges that code (or a refresh token) for
15-minute temporary SigV4 credentials registered with the local IAM account.

```bash
aws login --endpoint-url http://localhost:4566 --region us-east-1
aws sts get-caller-identity --endpoint-url http://localhost:4566
```

The authorize endpoint first presents a local consent page, then redirects back to the callback
listener owned by the AWS CLI. Floci does not contact AWS or require a real AWS account. This
follows the AWS Sign-In `AuthorizeOAuth2Access` and `CreateOAuth2Token` wire shapes, including
one-time codes, PKCE verification, refresh-token expiry, and the `aws_sigv4` temporary credential
type.

## Supported Actions

### Users

| Action | Description |
|--------|-------------|
| CreateUser | Creates an IAM user in the local account. |
| GetUser | Returns a stored IAM user. |
| DeleteUser | Deletes an IAM user from the local IAM store. |
| ListUsers | Lists IAM users in the local account. |
| UpdateUser | Updates mutable IAM user fields. |
| TagUser | Adds tags to an IAM user. |
| UntagUser | Removes tags from an IAM user. |
| ListUserTags | Lists tags stored for an IAM user. |

### Groups

| Action | Description |
|--------|-------------|
| CreateGroup | Creates an IAM group. |
| GetGroup | Returns an IAM group and its users. |
| UpdateGroup | Renames a group and/or changes its path; ARN and stored users move with it. |
| DeleteGroup | Deletes an IAM group from the local IAM store. |
| ListGroups | Lists IAM groups in the local account. |
| AddUserToGroup | Adds a user to an IAM group. |
| RemoveUserFromGroup | Removes a user from an IAM group. |
| ListGroupsForUser | Lists groups that contain a user. |

### Roles

| Action | Description |
|--------|-------------|
| CreateRole | Creates an IAM role with an assume-role policy. |
| GetRole | Returns a stored IAM role. |
| DeleteRole | Deletes an IAM role from the local IAM store. |
| ListRoles | Lists IAM roles in the local account. |
| UpdateRole | Updates mutable IAM role fields. |
| CreateServiceLinkedRole | Creates a role under /aws-service-role/ for a service principal. |
| DeleteServiceLinkedRole | Deletes a service-linked role and returns a deletion task id. |
| GetServiceLinkedRoleDeletionStatus | Returns the status of a service-linked role deletion. |
| UpdateAssumeRolePolicy | Replaces a role's assume-role policy document. |
| TagRole | Adds tags to an IAM role. |
| UntagRole | Removes tags from an IAM role. |
| ListRoleTags | Lists tags stored for an IAM role. |

### Policies

| Action | Description |
|--------|-------------|
| CreatePolicy | Creates a customer-managed IAM policy. |
| GetPolicy | Returns metadata for a managed IAM policy. |
| DeletePolicy | Deletes a managed IAM policy. |
| ListPolicies | Lists managed IAM policies, including seeded AWS managed policies. |
| ListEntitiesForPolicy | Lists roles, users, and groups with a direct managed-policy attachment. |
| CreatePolicyVersion | Creates a new version of a managed policy. |
| GetPolicyVersion | Returns a managed policy version document. |
| DeletePolicyVersion | Deletes a non-default managed policy version. |
| ListPolicyVersions | Lists versions for a managed policy. |
| SetDefaultPolicyVersion | Sets the default version for a managed policy. |
| TagPolicy | Adds tags to a managed policy. |
| UntagPolicy | Removes tags from a managed policy. |
| ListPolicyTags | Lists tags stored for a managed policy. |

`ListEntitiesForPolicy` currently returns direct permissions-policy attachments. `EntityFilter`,
`PathPrefix`, `PolicyUsageFilter`, and pagination are not yet applied; responses return
`IsTruncated=false`.

### Permission Boundaries

| Action | Description |
|--------|-------------|
| PutUserPermissionsBoundary | Sets a managed policy as a user's permissions boundary. |
| DeleteUserPermissionsBoundary | Removes a user's permissions boundary. |
| PutRolePermissionsBoundary | Sets a managed policy as a role's permissions boundary. |
| DeleteRolePermissionsBoundary | Removes a role's permissions boundary. |

### Policy Attachments

| Action | Description |
|--------|-------------|
| AttachUserPolicy | Attaches a managed policy to a user. |
| DetachUserPolicy | Detaches a managed policy from a user. |
| ListAttachedUserPolicies | Lists managed policies attached to a user. |
| AttachGroupPolicy | Attaches a managed policy to a group. |
| DetachGroupPolicy | Detaches a managed policy from a group. |
| ListAttachedGroupPolicies | Lists managed policies attached to a group. |
| AttachRolePolicy | Attaches a managed policy to a role. |
| DetachRolePolicy | Detaches a managed policy from a role. |
| ListAttachedRolePolicies | Lists managed policies attached to a role. |

### Inline Policies

| Action | Description |
|--------|-------------|
| PutUserPolicy | Stores or replaces an inline policy on a user. |
| GetUserPolicy | Returns an inline policy stored on a user. |
| DeleteUserPolicy | Deletes an inline policy from a user. |
| ListUserPolicies | Lists inline policy names stored on a user. |
| PutGroupPolicy | Stores or replaces an inline policy on a group. |
| GetGroupPolicy | Returns an inline policy stored on a group. |
| DeleteGroupPolicy | Deletes an inline policy from a group. |
| ListGroupPolicies | Lists inline policy names stored on a group. |
| PutRolePolicy | Stores or replaces an inline policy on a role. |
| GetRolePolicy | Returns an inline policy stored on a role. |
| DeleteRolePolicy | Deletes an inline policy from a role. |
| ListRolePolicies | Lists inline policy names stored on a role. |

### Instance Profiles

| Action | Description |
|--------|-------------|
| CreateInstanceProfile | Creates an IAM instance profile. |
| GetInstanceProfile | Returns an instance profile and its roles. |
| DeleteInstanceProfile | Deletes an instance profile from the local IAM store. |
| ListInstanceProfiles | Lists IAM instance profiles. |
| AddRoleToInstanceProfile | Adds a role to an instance profile. |
| RemoveRoleFromInstanceProfile | Removes a role from an instance profile. |
| ListInstanceProfilesForRole | Lists instance profiles associated with a role. |

### Access Keys

| Action | Description |
|--------|-------------|
| CreateAccessKey | Creates access-key credentials for a user. |
| GetAccessKeyLastUsed | Returns the stored last-used metadata for an access key. |
| ListAccessKeys | Lists access keys for a user. |
| UpdateAccessKey | Updates an access key's status. |
| DeleteAccessKey | Deletes an access key from a user. |

### Account Aliases

| Action | Description |
|--------|-------------|
| ListAccountAliases | Lists the alias set for the account, or an empty list when none is set. |
| CreateAccountAlias | Sets the account alias. An account can hold only one. |
| DeleteAccountAlias | Removes the account alias. |

An account holds one alias, and AWS enforces that by replacement rather than rejection:
`CreateAccountAlias` with a new value silently swaps the current one. `EntityAlreadyExists` means
the requested name is taken — on AWS that includes names held by other accounts, since aliases are
globally unique, but the store here is per-account so only "you already hold this one" arises.

`DeleteAccountAlias` must name the current alias; a mismatch returns `NoSuchEntity`. Both verbs
apply the same pattern constraint, so a malformed value returns `ValidationError` on either.
Aliases are 3–63 characters of lowercase letters, digits and hyphens, may not start or end with a
hyphen, and may not contain two hyphens in a row — AWS's documented
`^[a-z0-9]([a-z0-9]|-(?!-)){1,61}[a-z0-9]$`. The `ValidationError` message is reproduced from AWS
verbatim and does not itself mention the consecutive-hyphen rule.

Set `FLOCI_SERVICES_IAM_ACCOUNT_ALIAS` to seed an alias at startup, for callers that expect to
read one without creating it first. It seeds the **default account** only, so a caller signing
with a credential that resolves to a different account still reads an empty list. Seeding is
skipped when an alias is already stored, so under `storage.mode: persistent` a changed value has
no effect on later starts — the skip is logged at debug with both values. `/_floci/state/reset`
clears the alias without re-seeding it, as it does the optional deployer principal; the seed
returns on restart.

### Account Password Policy

| Action | Description |
|--------|-------------|
| GetAccountPasswordPolicy | Returns the account's password policy. |
| UpdateAccountPasswordPolicy | Replaces the account's password policy wholesale. |
| DeleteAccountPasswordPolicy | Removes the account's password policy. |

An account holds one password policy. `UpdateAccountPasswordPolicy` replaces it wholesale rather
than merging — a field the caller omits resets to its AWS-documented default (`false` for the
boolean requirements, `AllowUsersToChangePassword` and `HardExpiry`; `6` for
`MinimumPasswordLength`; unset for the optional `MaxPasswordAge` and `PasswordReusePrevention`)
rather than carrying over the previous value. Unlike the two optional integer fields,
`HardExpiry` is never absent from the response — AWS documents it as a boolean that always
defaults to `false`, so `GetAccountPasswordPolicy` always echoes it back. `ExpirePasswords` is
derived, not stored: it reports `true` exactly when `MaxPasswordAge` is set.

`GetAccountPasswordPolicy` and `DeleteAccountPasswordPolicy` both return `NoSuchEntity` when no
policy has ever been set — a documented, expected result the Terraform provider's
`aws_iam_account_password_policy` resource branches on. `MinimumPasswordLength` must be 6–128,
`MaxPasswordAge` 1–1095, and `PasswordReusePrevention` 1–24; a value outside those ranges is
rejected with `ValidationError`. The integer parameters (`MinimumPasswordLength`, `MaxPasswordAge`,
`PasswordReusePrevention`) and the boolean parameters both reject anything that isn't parseable —
a malformed integer or a value other than `true`/`false` (case-insensitive) returns
`ValidationError` rather than silently falling back to a default.

### OIDC Identity Providers

| Action | Description |
|--------|-------------|
| CreateOpenIDConnectProvider | Creates an OIDC identity provider from an https URL. |
| GetOpenIDConnectProvider | Returns a provider's URL, client IDs, thumbprints and tags. |
| ListOpenIDConnectProviders | Lists the ARNs of stored OIDC providers. |
| DeleteOpenIDConnectProvider | Deletes an OIDC identity provider. |
| AddClientIDToOpenIDConnectProvider | Adds a client ID (audience) to a provider. |
| RemoveClientIDFromOpenIDConnectProvider | Removes a client ID from a provider. |
| UpdateOpenIDConnectProviderThumbprint | Replaces a provider's thumbprint list. |
| TagOpenIDConnectProvider | Adds tags to a provider. |
| UntagOpenIDConnectProvider | Removes tags from a provider. |
| ListOpenIDConnectProviderTags | Lists tags stored for a provider. |

A provider is identified by its URL, so the ARN is derived from it rather than from a generated
id: `https://oidc.eks.eu-central-1.amazonaws.com/id/EXAMPLE` becomes
`arn:aws:iam::<account>:oidc-provider/oidc.eks.eu-central-1.amazonaws.com/id/EXAMPLE`. Creating
the same URL twice returns `EntityAlreadyExists`. As on AWS, `GetOpenIDConnectProvider` reports
the URL **without** its scheme.

The URL must begin with `https://` and is at most 255 characters. It is not normalized, matching
AWS: a trailing slash or a difference in case produces a separate provider rather than a
duplicate.

A provider holds at most 100 client IDs (`LimitExceeded` beyond that) and 5 thumbprints
(`InvalidInput` beyond that). Adding a client ID that is already present, and removing one that
was never added, both succeed and change nothing, as they do on AWS.

Thumbprints are stored and echoed back but never validated against the remote endpoint, since
nothing here performs the TLS handshake they describe.

### Login Profiles

| Action | Description |
|--------|-------------|
| CreateLoginProfile | Creates a password login profile for a user. |
| DeleteLoginProfile | Deletes a user's login profile. |
| UpdateLoginProfile | Updates a user's login profile password settings. |

### Policy Simulation

| Action | Description |
|--------|-------------|
| SimulatePrincipalPolicy | Evaluates requested actions and resources against the resolved principal's policies. |

### Account

| Action | Description |
|--------|-------------|
| GetAccountSummary | Returns entity counts (users, groups, roles, customer-managed policies, instance profiles) and IAM quota values. Resources Floci does not track (MFA devices, SAML/OIDC providers, server certificates) are reported as zero rather than omitted. |

## AWS Managed Policies

Floci seeds a catalog of commonly-used AWS managed policies at startup. These are attachable immediately without any setup:

**General access**
`AdministratorAccess` · `PowerUserAccess` · `ReadOnlyAccess` · `IAMFullAccess` · `AmazonS3FullAccess` · `AmazonS3ReadOnlyAccess` · `AmazonDynamoDBFullAccess` · `AmazonEC2FullAccess` · `AmazonSQSFullAccess` · `AmazonSNSFullAccess` · `AmazonVPCFullAccess` · `CloudWatchFullAccess` · `AWSLambdaFullAccess`

**Lambda execution roles** (`arn:aws:iam::aws:policy/service-role/...`)
`AWSLambdaBasicExecutionRole` · `AWSLambdaBasicDurableExecutionRolePolicy` · `AWSLambdaDynamoDBExecutionRole` · `AWSLambdaKinesisExecutionRole` · `AWSLambdaMSKExecutionRole` · `AWSLambdaSQSQueueExecutionRole` · `AWSLambdaVPCAccessExecutionRole`

**ECS / EKS execution roles**
`AmazonECSTaskExecutionRolePolicy` · `AmazonEKSFargatePodExecutionRolePolicy`

**EKS cluster & node groups**
`AmazonEKSClusterPolicy` · `AmazonEKSServicePolicy` · `AmazonEKSVPCResourceController` · `AmazonEKSWorkerNodePolicy` · `AmazonEKS_CNI_Policy`

**Other execution roles**
`AmazonS3ObjectLambdaExecutionRolePolicy` · `CloudWatchLambdaInsightsExecutionRolePolicy` · `CloudWatchLambdaApplicationSignalsExecutionRolePolicy` · `AWSConfigRulesExecutionRole` · `AWSMSKReplicatorExecutionRole` · `AWS-SSM-DiagnosisAutomation-ExecutionRolePolicy` · `AWS-SSM-RemediationAutomation-ExecutionRolePolicy` · `AmazonSageMakerGeospatialExecutionRole` · `AmazonSageMakerCanvasEMRServerlessExecutionRolePolicy` · `SageMakerStudioBedrockFunctionExecutionRolePolicy` · `SageMakerStudioDomainExecutionRolePolicy` · `SageMakerStudioQueryExecutionRolePolicy` · `AmazonDataZoneDomainExecutionRolePolicy` · `AmazonBedrockAgentCoreMemoryBedrockModelInferenceExecutionRolePolicy` · `AWSPartnerCentralSellingResourceSnapshotJobExecutionRolePolicy`

All seeded policies use a permissive wildcard document since Floci does not enforce IAM policy evaluation by default.

## Optional Local Deployer Principal

Floci can seed a local IAM user for development workflows that expect a concrete caller identity before provisioning starts. This is disabled by default.

Enable it with:

```bash
FLOCI_SERVICES_IAM_SEED_DEPLOYER_PRINCIPAL=true
```

When enabled, Floci creates the `floci-deployer` user if it does not already exist, attaches `arn:aws:iam::aws:policy/AdministratorAccess`, and creates static `floci` / `floci` access-key credentials if that access key does not already exist. Existing users and access keys are preserved.

Requests signed with the seeded access key return the deployer user ARN from `sts:GetCallerIdentity`.

## IAM Enforcement Mode

By default Floci accepts any credentials without enforcing IAM policies — all requests are allowed through regardless of what policies are attached to the calling identity. This preserves backward compatibility and keeps the default setup frictionless.

Setting `enforcement-enabled: true` activates the policy evaluator as a JAX-RS request filter. Every inbound request is then evaluated against the identity-based policies of the calling IAM user or assumed role before it reaches the service handler.

### Enable enforcement

**Environment variable:**
```bash
FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED=true
```

Docker Compose:
```yaml
environment:
  FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED: "true"
```

### Evaluation rules

Policy evaluation follows the standard AWS precedence:

1. If [SCP enforcement](#service-control-policies-scps) is active, the action must be allowed at **every** organization level (root, OUs on the path, account) and explicitly denied at none — otherwise the request is denied before identity policies are consulted
2. An explicit **Deny** in any identity, session, or boundary policy → request is denied (HTTP 403 `AccessDeniedException`)
3. An explicit **Allow** in an identity policy creates the base grant
4. If a session policy is present, it must also explicitly allow the request
5. If a permission boundary is present, it must also explicitly allow the request
6. No matching effective allow → implicit deny (HTTP 403)

### Service control policies (SCPs)

When the caller's account belongs to an [Organizations](organizations.md) organization,
SCPs attached to the root, the OUs on the account's path, and the account itself can
participate in evaluation. Two flags must both be on:

```yaml
floci:
  services:
    iam:
      enforcement-enabled: true
    organizations:
      scp-enforcement-enabled: true
```

(env: `FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED` and
`FLOCI_SERVICES_ORGANIZATIONS_SCP_ENFORCEMENT_ENABLED`)

SCP semantics match AWS: SCPs never grant permissions — they cap what identity policies
may allow; the organization's **management account is exempt**; and an account outside
any organization is unaffected. The `test` credential and unknown access keys are never
SCP-denied.

**The account-root principal is subject to SCPs.** floci's account root is a bare
12-digit account-id access key (the LocalStack multi-account convention). It carries no
registered IAM identity, so it normally takes the unknown-key bypass. But when the access
key equals its own account ID **and** that account has an effective SCP ceiling
(`effectiveScpLevels != null` — i.e. it is a non-management member under a root with the
SCP policy type enabled and at least one attached SCP), floci synthesizes an
allow-everything root identity and evaluates the request against the SCP chain. In that
case **SCPs apply and nothing else does** — no identity policies, permission boundary, or
session policy attaches to the bare account key. If the account has no effective SCP
ceiling (the management account, an account outside any organization, or the SCP type
disabled), the bare key still bypasses enforcement entirely, and unknown `AKIA…` keys
always bypass unconditionally.

### Bypass rules

These identities always bypass enforcement (backward-compatible defaults):

| Identity | Behaviour |
|---|---|
| Access key `test` (the default dev credential) | Always allowed — no policy lookup |
| Unknown access key (not in IAM store) | Always allowed — backward-compatible with pre-existing keys |
| No `Authorization` header | Allowed — unauthenticated path (e.g. health checks) |
| Unresolvable IAM action for the request | Allowed — unknown mappings are permissive |

**Exception:** a bare 12-digit account-id key that equals its own account and sits under
an effective SCP ceiling is **not** treated as an unknown key — it is evaluated against
the SCP chain as the account root (see [Service control policies](#service-control-policies-scps)
above). Identity-policy enforcement of a member account still requires an assumable,
account-routable identity such as the `OrganizationAccountAccessRole` session; the bare
account key carries no identity policies of its own.

### Supported policy features

- **Identity-based policies**: inline user/group/role policies and managed attached policies.
- **Session policies**: inline policies passed during `sts:AssumeRole`.
- **Permission boundaries**: managed policies used to cap maximum permissions.
- **Action/Resource patterns**: literal matches, wildcards (`*`, `?`), and `NotAction`/`NotResource` blocks.
- **Conditions**: support for `Condition` blocks with multiple operators.
- **Effects**: `Allow` and `Deny`.

#### Supported Condition Operators:
- `StringEquals`, `StringNotEquals`, `StringEqualsIgnoreCase`, `StringNotEqualsIgnoreCase`
- `StringLike`, `StringNotLike`
- `ArnEquals`, `ArnLike`, `ArnNotEquals`, `ArnNotLike`
- `NumericEquals`, `NumericNotEquals`, `NumericLessThan`, `NumericGreaterThan` (and Equals variants)
- `DateEquals`, `DateNotEquals`, `DateLessThan`, `DateGreaterThan` (and Equals variants)
- `Bool`, `IpAddress`, `NotIpAddress`, `Null`
- Supports `...IfExists` variants for all operators.
- Set operators `ForAllValues:` and `ForAnyValue:` over multi-valued condition keys, in AWS's
  own spelling (the prefix match is case-sensitive). They compose with `IfExists`
  (`ForAnyValue:StringEqualsIfExists`). `ForAllValues:` over an empty set matches vacuously
  and `ForAnyValue:` over an empty set does not match, so pair `ForAllValues:` with
  `"Null":{"<key>":"false"}` as you would on AWS: `Null` treats a present-but-empty set as
  absent, so the guard fires either way.
- When a condition lists several values, a positive operator matches if the request value
  equals **any** of them; a negated operator (`StringNotEquals`, `ArnNotLike`, `NotIpAddress`,
  …) matches only if the request value differs from **all** of them. This is what makes
  `ForAllValues:StringNotEquals` on `dynamodb:Attributes` a usable deny-list.

#### Condition keys floci populates

A `Condition` operator can only match a key floci actually places in the request context.
floci populates:

- `s3:prefix`, `s3:delimiter`, `s3:max-keys`: from the S3 request parameters.
- `aws:PrincipalArn`: the caller's ARN, resolved from the signing access key. It is the
  IAM-user ARN for a user access key, the assumed-role ARN for an STS session, and
  `arn:aws:iam::<account>:root` for the bare account-id key (floci's account-root principal),
  matching the ARN shape AWS itself reports for the account root. It is **absent** only for
  unknown keys, where nothing about the caller can be resolved.
- `dynamodb:LeadingKeys`, `dynamodb:Attributes`, `dynamodb:Select`: from the DynamoDB request
  body, for `GetItem`, `PutItem`, `UpdateItem`, `DeleteItem`, `Query`, `BatchGetItem` and
  `BatchWriteItem` (`dynamodb:Select` for `Query` and `Scan`). `LeadingKeys` holds the
  partition-key values the request names: from `Key`, `Item`, the `KeyConditionExpression`
  equality, the legacy `KeyConditions` `EQ` entry, or each `RequestItems` entry. `Attributes`
  holds the attribute names the request *names* (item and key fields, `AttributesToGet`,
  projection / update / filter / condition / key-condition expressions, and
  `ExpressionAttributeNames`); a request with no projection returns every attribute while
  reporting only the names it mentions, exactly as on AWS, which is why AWS pairs `Attributes`
  with `dynamodb:Select`. Each key is **omitted** when it cannot be determined (unknown table,
  a `Key` that omits the partition attribute, an unparseable `KeyConditionExpression`, a
  multi-table batch), so a policy scoping access through it denies the request rather than
  allowing an unproven one.

  **Consequence:** with enforcement on and access scoped purely through `dynamodb:LeadingKeys`,
  a malformed request (such as a `GetItem` whose `Key` omits the partition attribute) is answered with
  `AccessDeniedException` instead of the `ValidationException` DynamoDB would return. Failing
  closed is the correct direction for a security boundary.

**Any other condition key is absent from the request context.** A plain (non-`IfExists`)
operator on an absent key makes the whole statement *not apply*: it neither matches nor
blocks. A `DenyRootUser`-style guardrail keyed on `aws:PrincipalArn` therefore fires against
the account root the same way it does on real AWS, consistent with the account root already
being bounded by SCPs (below): both forms of root enforcement now agree.

**Caveat:** `resolveCallerArn` hardcodes the assumed-role session name as `floci-session`,
so `aws:PrincipalArn` for an assumed-role caller will not match a condition that pins a
different session name. This matches what `sts:GetCallerIdentity` already reports.

**Not yet supported**: `NotPrincipal`, resource-based policies (S3 bucket policy, Lambda resource
policy), and `dynamodb:LeadingKeys` for `Scan`, `TransactWriteItems` / `TransactGetItems` and the
PartiQL operations.

### Assumed roles

When a caller uses `sts:AssumeRole` the returned session credentials are registered internally. Subsequent requests signed with those session credentials are evaluated against:
1. The **role's** attached and inline policies.
2. The **session policy** (if provided during `AssumeRole`), acting as an intersection filter.

### Example — minimal enforcement setup

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a user and get credentials
aws iam create-user --user-name alice
KEY=$(aws iam create-access-key --user-name alice --query 'AccessKey.[AccessKeyId,SecretAccessKey]' --output text)
AKID=$(echo $KEY | awk '{print $1}')
SECRET=$(echo $KEY | awk '{print $2}')

# Create and attach a policy that allows S3 list
POLICY_ARN=$(aws iam create-policy \
  --policy-name allow-s3-list \
  --policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:ListAllMyBuckets","Resource":"*"}]}' \
  --query 'Policy.Arn' --output text)

aws iam attach-user-policy --user-name alice --policy-arn $POLICY_ARN

# alice can now list buckets
AWS_ACCESS_KEY_ID=$AKID AWS_SECRET_ACCESS_KEY=$SECRET \
  aws s3 ls
```

## Service Control Policies (SCPs)

When `FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED=true` and
`FLOCI_SERVICES_ORGANIZATIONS_SCP_ENFORCEMENT_ENABLED=true`, service control policies attached to
the caller account's organization participate in policy evaluation.

The SCP chain is resolved root → OUs on the path → account, and every level must allow the action
for the request to proceed. SCPs never grant permissions on their own — they are a ceiling applied
before identity policies are consulted, exactly as on AWS. Organizations is resolved lazily, so IAM
does not gain a hard dependency on it: with the Organizations service absent or the flag off, the
chain is skipped entirely and evaluation falls back to identity policies alone.

A member account's own bare 12-digit access key is floci's account-root principal. It is not a
registered IAM identity, but like the AWS account root it is still bounded by SCPs, so an SCP `Deny`
(for example a `DenyLeaveOrganization` guardrail) blocks it. What that key does *not* carry is any
identity policy — it behaves as allow-everything bounded only by the SCP ceiling. To exercise
**identity-policy** enforcement, use account-routable credentials for the member instead, most
naturally the `ASIA…` session from assuming its `OrganizationAccountAccessRole`.

Note that `aws:PrincipalArn` is populated for the account-root principal too, as
`arn:aws:iam::<account>:root`, so a principal-scoped guardrail keyed on `aws:PrincipalArn`
(for example a `DenyRootUser` statement matching `arn:aws:iam::*:root`) fires against it —
consistent with the account root already being bounded by SCPs above.

A level containing an SCP document that fails to parse denies every action at that level. The
ceiling cannot tell what an unreadable guardrail would have said, and every target also carries
`FullAWSAccess`, so dropping the bad document would leave the level allowing everything.

The ceiling is attached by the request filter, which is the only producer of SCP levels. Two
evaluation paths therefore run without one and are **not** SCP-bounded: `SimulatePrincipalPolicy`,
and the field-level authorization check on the AppSync GraphQL IAM-auth path (the coarse AppSync
request itself still passes through the filter). Both are deliberate non-goals — bounding them
would require IAM to resolve the caller's organization directly, which is the dependency the
lazily-resolved `ScpProvider` exists to avoid.

## Bypass rules

Enforcement is deliberately permissive in a few cases, so that enabling it does not break workloads
the emulator cannot reason about:

| Case | Behaviour |
| --- | --- |
| Unresolvable action | Allowed. An action the registry cannot resolve is not evaluated. |
| `sts:GetCallerIdentity` | Always allowed — AWS returns caller identity even when a policy denies it. |
| Unknown access key | Allowed. A key that resolves to no IAM identity bypasses enforcement. |
| Bare account-id key with no SCP ceiling | Allowed. With no organization or SCP enforcement off, the account root keeps the historical bypass. |
| Bare account-id key **with** an SCP ceiling | Enforced as the account root, bounded by the SCP chain. |

## Service-linked roles

`CreateServiceLinkedRole` puts a role under `/aws-service-role/<principal>/` and marks it as
service-linked. As on AWS, a role carrying that mark is protected: `AttachRolePolicy`,
`DetachRolePolicy`, `PutRolePolicy`, `DeleteRolePolicy`, `PutRolePermissionsBoundary`,
`DeleteRolePermissionsBoundary`, `UpdateRole`, `UpdateAssumeRolePolicy`, `AddRoleToInstanceProfile`,
`RemoveRoleFromInstanceProfile` and `DeleteRole` all answer `UnmodifiableEntity` and name the
linked service to go through instead. `TagRole` and `UntagRole` are allowed, as on AWS. Within the
IAM API `DeleteServiceLinkedRole` is the only way to remove such a role — the emulator's own
`/_floci/state/reset` still clears it along with everything else.

Three deviations to be aware of:

- **The role name is derived locally and will not match AWS for most services.** AWS lets each
  linked service choose the name, and it is not computable from the service principal —
  `lex.amazonaws.com` yields `AWSServiceRoleForLexBots` there, where Floci derives
  `AWSServiceRoleForLex`. Read the name back from the create response rather than hardcoding
  it, and do not rely on a name observed locally matching the one AWS mints.
- **Deletion is synchronous.** `DeleteServiceLinkedRole` completes before it returns, so the
  task id it hands back is already finished and `GetServiceLinkedRoleDeletionStatus` always
  reports `SUCCEEDED`. The `IN_PROGRESS`, `NOT_STARTED` and `FAILED` states never occur, and no
  failure `Reason` is ever returned — a poll loop works, but its failure branch is never taken.
- **`CreateRole` accepts the `/aws-service-role/` path, which AWS reserves.** AWS rejects that
  prefix on `CreateRole`; Floci allows it and treats the result as an ordinary role, since the
  service-linked mark comes from the action that minted the role rather than from its path. Such
  a role stays fully modifiable, and `DeleteServiceLinkedRole` answers `NoSuchEntity` for it.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_IAM_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED` | `false` | Enforce IAM policies on all inbound requests |
| `FLOCI_SERVICES_IAM_SEED_DEPLOYER_PRINCIPAL` | `false` | Seed the optional `floci-deployer` user and `floci` / `floci` access key |
| `FLOCI_SERVICES_IAM_ACCOUNT_ALIAS` | _(unset)_ | Seed an account alias at startup; unset means the account has no alias |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a role
aws iam create-role \
  --role-name lambda-execution-role \
  --assume-role-policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Principal": {"Service": "lambda.amazonaws.com"},
      "Action": "sts:AssumeRole"
    }]
  }' \
  --endpoint-url $AWS_ENDPOINT_URL

# Attach a managed policy
aws iam attach-role-policy \
  --role-name lambda-execution-role \
  --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole \
  --endpoint-url $AWS_ENDPOINT_URL

# Create a user
aws iam create-user --user-name alice --endpoint-url $AWS_ENDPOINT_URL

# Create an access key
aws iam create-access-key --user-name alice --endpoint-url $AWS_ENDPOINT_URL

# List roles
aws iam list-roles --endpoint-url $AWS_ENDPOINT_URL
```
