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

The browser callback is still handled by the AWS CLI; Floci does not contact AWS or require a real
AWS account. This follows the AWS Sign-In `AuthorizeOAuth2Access` and `CreateOAuth2Token` wire
shapes, including one-time codes, PKCE verification, refresh-token expiry, and the `aws_sigv4`
temporary credential type.

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

1. An explicit **Deny** in any identity, session, or boundary policy → request is denied (HTTP 403 `AccessDeniedException`)
2. An explicit **Allow** in an identity policy creates the base grant
3. If a session policy is present, it must also explicitly allow the request
4. If a permission boundary is present, it must also explicitly allow the request
5. No matching effective allow → implicit deny (HTTP 403)

### Bypass rules

These identities always bypass enforcement (backward-compatible defaults):

| Identity | Behaviour |
|---|---|
| Access key `test` (the default dev credential) | Always allowed — no policy lookup |
| Unknown access key (not in IAM store) | Always allowed — backward-compatible with pre-existing keys |
| No `Authorization` header | Allowed — unauthenticated path (e.g. health checks) |
| Unresolvable IAM action for the request | Allowed — unknown mappings are permissive |

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

**Not yet supported**: `NotPrincipal`, resource-based policies (S3 bucket policy, Lambda resource policy).

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
