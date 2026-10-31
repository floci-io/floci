# SSM

**Protocol:** JSON 1.1 (`X-Amz-Target: AmazonSSM.*`)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

### Parameter Store

| Action | Description |
|---|---|
| `PutParameter` | Create or update a parameter |
| `GetParameter` | Get a single parameter by name, optionally `name:version` or `name:label` |
| `GetParameters` | Get multiple parameters by name, with the same `name:version` and `name:label` selectors |
| `GetParametersByPath` | Get all parameters under a path prefix |
| `DeleteParameter` | Delete a parameter |
| `DeleteParameters` | Delete multiple parameters |
| `GetParameterHistory` | List all versions of a parameter |
| `DescribeParameters` | List parameters with optional filters |
| `LabelParameterVersion` | Attach a label to a specific version |
| `UnlabelParameterVersion` | Remove labels from a specific version |
| `AddTagsToResource` | Tag a parameter |
| `ListTagsForResource` | List tags on a parameter |
| `RemoveTagsFromResource` | Remove tags from a parameter |
| `PutResourcePolicy` | Attach or update the resource policy of an Advanced-tier parameter |
| `GetResourcePolicies` | List the resource policy attached to a parameter |
| `DeleteResourcePolicy` | Remove a parameter's resource policy |
| `DescribePatchBaselines` | List AWS-owned predefined patch baselines (filter by `OWNER`, `OPERATING_SYSTEM`, `NAME_PREFIX`) |
| `GetDefaultPatchBaseline` | Get the default patch baseline id for an operating system |

`DescribeParameters` includes the stored parameter's `ARN` in each metadata entry, matching
`GetParameter`. This applies to filtered and paginated results as well.

`GetParametersByPath` supports `Type` and `KeyId` entries in `ParameterFilters`, with `Equals`
(the default) or `BeginsWith`. Values within one filter combine with OR; multiple
filters combine with AND. Filtering also applies to public AMI parameters and
respects `Path`, `Recursive`, and region selection. Unknown or missing keys and keys
not allowed for this action are rejected with `InvalidFilterKey`.
`Label` filters accept only `Equals` and return the stored versions carrying the requested
labels, including older versions. Moving or removing a label changes subsequent results.
The same OR/AND rules apply to label filters, and a version matching several requested
labels appears only once. Type and KeyId filters match the labeled version's metadata.
Public AMI parameters have no stored labels and are excluded from label queries.
`MaxResults` and `NextToken` paginate the matching versions in name/version order; queries
without a label filter continue to return the latest version of each parameter.

### Run Command

| Action | Description |
|---|---|
| `UpdateInstanceInformation` | Register or update an SSM agent record for an instance. Does not create an EC2 instance or register the container with IMDS (see [Run Command Execution](#run-command-execution)) |
| `DescribeInstanceInformation` | List registered SSM managed instances |
| `SendCommand` | Create command invocations for target instances |
| `GetCommandInvocation` | Return a command invocation result |
| `ListCommands` | List command records |
| `ListCommandInvocations` | List command invocation records |
| `CancelCommand` | Cancel pending or in-progress command invocations |

### ec2messages Agent Protocol

| Action | Description |
|---|---|
| `GetMessages` | Agent polls for pending command messages |
| `AcknowledgeMessage` | Agent acknowledges receipt of a command message |
| `SendReply` | Agent reports command output and status |

## Run Command Execution

`SendCommand` supports the `AWS-RunShellScript` document. For EC2 instances launched by Floci in real Docker mode, Floci creates the command invocation, returns the command response, and then runs the script asynchronously inside the target instance container. Callers observe completion through `GetCommandInvocation`. `stdout`, `stderr`, response code, start time, and end time are recorded on the invocation.

For public `SendCommand`, set `TimeoutSeconds` to a delivery/start allowance from 30 to
2,592,000 seconds. The default is 3,600 seconds. Floci does not automatically expire
commands waiting for an agent, so a command that is never delivered can remain pending
indefinitely. The document's execution allowance and internal CodeDeploy hook budgets
are separate.

If the target is not a Floci EC2 container, or if the document is not supported for direct execution, Floci falls back to the SSM agent polling flow. In that mode, `SendCommand` queues an ec2messages payload and the invocation completes after an agent calls `SendReply`.

Background execution retains the issuing account and region. Agent queues and message correlations
use that same account, region and instance identity; polling or completing a message from another
account or region leaves the owner's work untouched. Pending messages remain in memory and are lost
on restart.

!!! note "Managed instance registration is independent of EC2 and IMDS"
    Registering a container's SSM agent with `UpdateInstanceInformation` only creates the managed instance record. It does not create an EC2 instance, does not install the link-local `169.254.169.254` proxy in the container, and does not register the container with the [EC2 IMDS server](ec2.md#imds-and-ssm-managed-instances). A container that was only registered with SSM therefore has no working IMDS endpoint and no instance profile credentials, and `SendCommand` against it uses the agent polling flow rather than direct execution.

    To get a container that both answers IMDS and runs `SendCommand` directly, launch it with EC2 `RunInstances` first and then register that same container's SSM agent. Floci logs a warning when an agent registers from a container that is not backed by a Floci EC2 instance.

Direct command output follows the AWS inline output limits: first 24,000 characters of stdout and first 8,000 characters of stderr. Commands that exceed their execution budget are constrained inside the target container when the container has the `timeout` command available, and terminal timeout results are marked `TimedOut` with `StatusDetails` set to `Execution Timed Out`; commands with nonzero exit codes are marked `Failed`.

### Shell command timeout budgets

For public `SendCommand` requests using the exact `AWS-RunShellScript` document name,
`TimeoutSeconds` is the delivery/start allowance. `Parameters.executionTimeout` is a separate
execution allowance, defaulting to 3600 seconds and accepting one decimal string from 1 to
172800. The direct worker uses this execution allowance; agent payloads retain the public
document's 3600-second default and carry an explicit parameter unchanged.

`Command.TimeoutSeconds` still reports the delivery allowance. `ExpiresAfter` includes both
allowances for these shell requests. This corrects metadata and execution budgets; it does not
add automatic delivery expiration, restart recovery, cleanup retries or late-start fencing.
Internal CodeDeploy hook budgets and non-shell document handling keep their existing behavior.
Missing or empty `commands`, malformed shell parameter wire shapes and invalid execution limits
are refused before command creation
with `InvalidParameters`/HTTP400. This is Floci's chosen mapping; exact AWS error wording and
empty-list normalization were not observed live. Other document parameters and delivery-timeout
coercion/upper-bound handling are not a complete document-schema implementation.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_SSM_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_SSM_MAX_PARAMETER_HISTORY` | `5` | Number of parameter versions retained per parameter |
| `FLOCI_STORAGE_SERVICES_SSM_MODE` | *(global default)* | Storage mode override for SSM (`memory`, `persistent`, `hybrid`, `wal`) |
| `FLOCI_STORAGE_SERVICES_SSM_FLUSH_INTERVAL_MS` | `5000` | Flush interval for `hybrid`/`wal` storage modes (milliseconds) |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Store parameters
aws ssm put-parameter --endpoint-url $AWS_ENDPOINT_URL \
  --name /app/db/host --value "localhost" --type String

aws ssm put-parameter --endpoint-url $AWS_ENDPOINT_URL \
  --name /app/db/password --value "secret" --type SecureString

# Retrieve
aws ssm get-parameter --endpoint-url $AWS_ENDPOINT_URL \
  --name /app/db/host

aws ssm get-parameters-by-path --endpoint-url $AWS_ENDPOINT_URL \
  --path /app/ --recursive

# Delete
aws ssm delete-parameter --endpoint-url $AWS_ENDPOINT_URL \
  --name /app/db/host

# Run a shell command on a Floci EC2 instance
aws ssm send-command --endpoint-url $AWS_ENDPOINT_URL \
  --instance-ids i-0123456789abcdef0 \
  --document-name AWS-RunShellScript \
  --parameters commands='["echo hello"]'
```

## Public AMI Parameters

AWS publishes AMI id lookup parameters under `/aws/service/ami-amazon-linux-latest/` and EKS
optimized node AMI lookup parameters under `/aws/service/eks/optimized-ami/` in every account,
and tools such as Terraform, Karpenter and eksctl read them without any setup. Floci answers the
documented Amazon Linux 2 and Amazon Linux 2023 names from its EC2 image catalog across supported
architectures and Kubernetes versions, so `GetParameter`, `GetParameters` and `GetParametersByPath`
resolve them to the catalog's AMI ids. As on AWS they are read-only and belong to no account,
so they do not appear in `DescribeParameters`.

Floci also answers the Amazon Linux 2023 ECS optimized AMI `recommended/image_id` and
`recommended` parameters under `/aws/service/ecs/optimized-ami/`. The image ID points to
the catalog's x86_64 Amazon Linux 2023 image. The `recommended` value is JSON with the
matching image ID and name; its ECS agent and runtime versions are `unknown` because
the catalog image is synthetic and does not represent an installed ECS runtime.

```bash
aws ssm get-parameter --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64
aws ssm get-parameter --name /aws/service/eks/optimized-ami/1.31/amazon-linux-2023/x86_64/standard/recommended/image_id
aws ssm get-parameter --name /aws/service/ecs/optimized-ami/amazon-linux-2023/recommended/image_id
```

## Secrets Manager References

`GetParameter` and `GetParameters` answer `/aws/reference/secretsmanager/<secret-id>` from
Secrets Manager, so an application, an ECS task secret or a CodeBuild `parameter-store`
variable can read a secret through Parameter Store.
The answer is a `SecureString` with the secret's value and ARN and `Version` 0, and
`SourceResult` carries the Secrets Manager `GetSecretValue` result as a JSON string, without
`secretBinary` for a binary secret. A
`:<version-id>` or `:<staging-label>` suffix, such as `:AWSPREVIOUS`, selects a version and is
echoed as `Selector`. As on AWS, `WithDecryption` must be true, and a missing secret is
`ParameterNotFound`; `GetParameters` lists a reference it cannot answer in `InvalidParameters`.
With IAM enforcement on, the caller also needs `secretsmanager:GetSecretValue` on the secret,
and a refusal fails the whole call with a `ValidationException`, as it does on AWS.

```bash
aws ssm get-parameter --name /aws/reference/secretsmanager/my-app/api-key --with-decryption
```

## Parameter ARNs

A parameter's ARN is `arn:<partition>:ssm:<region>:<account>:parameter/<name without leading slash>`
for both a top-level name such as `db-host` and a path such as `/app/db/host`, as on AWS.
The CloudFormation `Arn` attribute of an `AWS::SSM::Parameter` is the same ARN, and
`AddTagsToResource`, `ListTagsForResource` and `RemoveTagsFromResource` accept it as `ResourceId`.
An ECS container secret's `valueFrom` accepts it too.

`GetParameter` and `GetParameters` accept the returned ARN, optionally followed by
`:version` or `:label`, for parameters in the caller's account and request region.
Batch reads keep unresolved inputs in `InvalidParameters` in their original form.
ARNs naming another account, region or partition never resolve to a same-name local
parameter. Cross-account shared parameters are not modeled. Secrets Manager
references continue to use `/aws/reference/secretsmanager/<secret-id>`, not a
parameter ARN.

## Parameter Resource Policies

`PutResourcePolicy`, `GetResourcePolicies` and `DeleteResourcePolicy` take the parameter ARN as
`ResourceArn`. Only an Advanced-tier parameter can have a policy, and it has at most one; a
`SecureString` encrypted with the default `aws/ssm` key cannot have one.
`PutResourcePolicy` returns the policy's `PolicyId` and `PolicyHash`; updating or deleting the
policy takes both, and a hash that is no longer current fails with
`ResourcePolicyConflictException`. A policy over 1024 bytes fails with
`ResourcePolicyLimitExceededException`, and a document that is not a JSON object with
`MalformedResourcePolicyDocumentException`. An overwriting `PutParameter` keeps the policy, and
is refused with `ResourcePolicyInvalidParameterException` when it would leave the policy on a
parameter that could not take one; deleting the parameter removes it. With IAM enforcement on, the three actions are checked against
the parameter `ResourceArn` names. Floci stores the policy but does not evaluate it against callers
or create the AWS RAM resource share AWS makes for a shared parameter.

## Parameter Types

All AWS parameter types are accepted: `String`, `StringList`, `SecureString`.

!!! note
    `SecureString` parameters are stored as-is without actual KMS encryption in Floci. The type is preserved and returned correctly, but the value is not encrypted at rest.

## Parameter Store Change events

Like AWS, Floci publishes a `Parameter Store Change` event (`source: aws.ssm`) to the account's
**default** EventBridge bus after a successful parameter write, so a rule matching `aws.ssm` fires
from Parameter Store activity. `resources` holds the parameter ARN, and the event carries the
account and region of the call.

| Call | Events | `detail` |
|---|---|---|
| `PutParameter`, new name | one `Create` | `operation`, `name`, `type`, and `description` when set |
| `PutParameter` overwriting an existing name | one `Update`, even when the value is unchanged | as for `Create` |
| `DeleteParameter` | one `Delete` | as for `Create` |
| `DeleteParameters` | one `Delete` per deleted name, none for a missing name | as for `Create` |
| `LabelParameterVersion` | one `LabelParameterVersion` per valid label it attaches or moves | as for `Create`, from the labelled version, plus `label`, `fromVersion` (the version the label left as a string, `""` for a new label) and `toVersion` |
| `UnlabelParameterVersion` | one `UnlabelParameterVersion` when it removes a label, none otherwise | as for `Create`, from the unlabelled version, plus `labels` (the labels removed), `fromVersion` (that version as a string) and `toVersion` (`""`) |

Parameters a CloudFormation stack creates or deletes publish the same events. A call that fails,
such as a `PutParameter` on an existing name without `Overwrite`, publishes nothing, and so do
`AddTagsToResource`, a label already on the requested version and an invalid label. Publishing
is best effort: a delivery failure is logged and never fails or undoes the write.
