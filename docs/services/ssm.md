# SSM

**Protocol:** JSON 1.1 (`X-Amz-Target: AmazonSSM.*`)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

### Parameter Store

| Action | Description |
|---|---|
| `PutParameter` | Create or update a parameter |
| `GetParameter` | Get a single parameter by name |
| `GetParameters` | Get multiple parameters by name |
| `GetParametersByPath` | Get all parameters under a path prefix |
| `DeleteParameter` | Delete a parameter |
| `DeleteParameters` | Delete multiple parameters |
| `GetParameterHistory` | List all versions of a parameter |
| `DescribeParameters` | List parameters with optional filters |
| `LabelParameterVersion` | Attach a label to a specific version |
| `AddTagsToResource` | Tag a parameter |
| `ListTagsForResource` | List tags on a parameter |
| `RemoveTagsFromResource` | Remove tags from a parameter |
| `DescribePatchBaselines` | List AWS-owned predefined patch baselines (filter by `OWNER`, `OPERATING_SYSTEM`, `NAME_PREFIX`) |
| `GetDefaultPatchBaseline` | Get the default patch baseline id for an operating system |

### Run Command

| Action | Description |
|---|---|
| `UpdateInstanceInformation` | Register or update an SSM agent record for an instance |
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

If the target is not a Floci EC2 container, or if the document is not supported for direct execution, Floci falls back to the SSM agent polling flow. In that mode, `SendCommand` queues an ec2messages payload and the invocation completes after an agent calls `SendReply`.

Direct command output follows the AWS inline output limits: first 24,000 characters of stdout and first 8,000 characters of stderr. Commands that exceed `TimeoutSeconds` are constrained inside the target container when the container has the `timeout` command available, and terminal timeout results are marked `TimedOut` with `StatusDetails` set to `Execution Timed Out`; commands with nonzero exit codes are marked `Failed`.

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

## Parameter Types

All AWS parameter types are accepted: `String`, `StringList`, `SecureString`.

!!! note
    `SecureString` parameters are stored as-is without actual KMS encryption in Floci. The type is preserved and returned correctly, but the value is not encrypted at rest.
