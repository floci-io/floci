# IAM

**Protocol:** Query (XML) — `POST http://localhost:4566/` with `Action=` parameter

## Supported Actions

<!-- floci:actions:start -->
| Action |
| --- |
| `CreateUser` |
| `GetUser` |
| `DeleteUser` |
| `ListUsers` |
| `UpdateUser` |
| `TagUser` |
| `UntagUser` |
| `ListUserTags` |
| `CreateGroup` |
| `GetGroup` |
| `DeleteGroup` |
| `ListGroups` |
| `AddUserToGroup` |
| `RemoveUserFromGroup` |
| `ListGroupsForUser` |
| `CreateRole` |
| `GetRole` |
| `DeleteRole` |
| `ListRoles` |
| `UpdateRole` |
| `UpdateAssumeRolePolicy` |
| `TagRole` |
| `UntagRole` |
| `ListRoleTags` |
| `CreatePolicy` |
| `GetPolicy` |
| `DeletePolicy` |
| `ListPolicies` |
| `CreatePolicyVersion` |
| `GetPolicyVersion` |
| `DeletePolicyVersion` |
| `ListPolicyVersions` |
| `SetDefaultPolicyVersion` |
| `TagPolicy` |
| `UntagPolicy` |
| `ListPolicyTags` |
| `AttachUserPolicy` |
| `DetachUserPolicy` |
| `ListAttachedUserPolicies` |
| `AttachGroupPolicy` |
| `DetachGroupPolicy` |
| `ListAttachedGroupPolicies` |
| `AttachRolePolicy` |
| `DetachRolePolicy` |
| `ListAttachedRolePolicies` |
| `PutUserPolicy` |
| `GetUserPolicy` |
| `DeleteUserPolicy` |
| `ListUserPolicies` |
| `PutGroupPolicy` |
| `GetGroupPolicy` |
| `DeleteGroupPolicy` |
| `ListGroupPolicies` |
| `PutRolePolicy` |
| `GetRolePolicy` |
| `DeleteRolePolicy` |
| `ListRolePolicies` |
| `CreateAccessKey` |
| `DeleteAccessKey` |
| `ListAccessKeys` |
| `UpdateAccessKey` |
| `CreateInstanceProfile` |
| `GetInstanceProfile` |
| `DeleteInstanceProfile` |
| `ListInstanceProfiles` |
| `AddRoleToInstanceProfile` |
| `RemoveRoleFromInstanceProfile` |
| `ListInstanceProfilesForRole` |
| `PutUserPermissionsBoundary` |
| `DeleteUserPermissionsBoundary` |
| `PutRolePermissionsBoundary` |
| `DeleteRolePermissionsBoundary` |
<!-- floci:actions:end -->
## AWS Managed Policies

Floci seeds a catalog of commonly-used AWS managed policies at startup. These are attachable immediately without any setup:

**General access**
`AdministratorAccess` · `PowerUserAccess` · `ReadOnlyAccess` · `IAMFullAccess` · `AmazonS3FullAccess` · `AmazonS3ReadOnlyAccess` · `AmazonDynamoDBFullAccess` · `AmazonEC2FullAccess` · `AmazonSQSFullAccess` · `AmazonSNSFullAccess` · `AmazonVPCFullAccess` · `CloudWatchFullAccess` · `AWSLambdaFullAccess`

**Lambda execution roles** (`arn:aws:iam::aws:policy/service-role/...`)
`AWSLambdaBasicExecutionRole` · `AWSLambdaBasicDurableExecutionRolePolicy` · `AWSLambdaDynamoDBExecutionRole` · `AWSLambdaKinesisExecutionRole` · `AWSLambdaMSKExecutionRole` · `AWSLambdaSQSQueueExecutionRole` · `AWSLambdaVPCAccessExecutionRole`

**ECS / EKS execution roles**
`AmazonECSTaskExecutionRolePolicy` · `AmazonEKSFargatePodExecutionRolePolicy`

**Other execution roles**
`AmazonS3ObjectLambdaExecutionRolePolicy` · `CloudWatchLambdaInsightsExecutionRolePolicy` · `CloudWatchLambdaApplicationSignalsExecutionRolePolicy` · `AWSConfigRulesExecutionRole` · `AWSMSKReplicatorExecutionRole` · `AWS-SSM-DiagnosisAutomation-ExecutionRolePolicy` · `AWS-SSM-RemediationAutomation-ExecutionRolePolicy` · `AmazonSageMakerGeospatialExecutionRole` · `AmazonSageMakerCanvasEMRServerlessExecutionRolePolicy` · `SageMakerStudioBedrockFunctionExecutionRolePolicy` · `SageMakerStudioDomainExecutionRolePolicy` · `SageMakerStudioQueryExecutionRolePolicy` · `AmazonDataZoneDomainExecutionRolePolicy` · `AmazonBedrockAgentCoreMemoryBedrockModelInferenceExecutionRolePolicy` · `AWSPartnerCentralSellingResourceSnapshotJobExecutionRolePolicy`

All seeded policies use a permissive wildcard document since Floci does not enforce IAM policy evaluation by default.

## IAM Enforcement Mode

By default Floci accepts any credentials without enforcing IAM policies — all requests are allowed through regardless of what policies are attached to the calling identity. This preserves backward compatibility and keeps the default setup frictionless.

Setting `enforcement-enabled: true` activates the policy evaluator as a JAX-RS request filter. Every inbound request is then evaluated against the identity-based policies of the calling IAM user or assumed role before it reaches the service handler.

### Enable enforcement

**Environment variable:**
```bash
FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED=true
```

**application.yml:**
```yaml
floci:
  services:
    iam:
      enforcement-enabled: true
```

### Evaluation rules

Policy evaluation follows the standard AWS precedence:

1. An explicit **Deny** in any policy → request is denied (HTTP 403 `AccessDeniedException`)
2. An explicit **Allow** in any policy → request is allowed
3. No matching statement → implicit deny (HTTP 403)

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
