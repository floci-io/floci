# SSM Parameter Store

**Protocol:** JSON 1.1 (`X-Amz-Target: AmazonSSM.*`)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

<!-- floci:actions:start -->
| Action |
| --- |
| `PutParameter` |
| `GetParameter` |
| `GetParameters` |
| `GetParametersByPath` |
| `DeleteParameter` |
| `DeleteParameters` |
| `GetParameterHistory` |
| `DescribeParameters` |
| `LabelParameterVersion` |
| `AddTagsToResource` |
| `ListTagsForResource` |
| `RemoveTagsFromResource` |
| `SendCommand` |
| `GetCommandInvocation` |
| `ListCommands` |
| `ListCommandInvocations` |
| `CancelCommand` |
| `DescribeInstanceInformation` |
| `UpdateInstanceInformation` |
<!-- floci:actions:end -->

## Configuration

```yaml
floci:
  services:
    ssm:
      enabled: true
      max-parameter-history: 5   # Versions retained per parameter
  storage:
    services:
      ssm:
        mode: memory
        flush-interval-ms: 5000
```

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
```

## Parameter Types

All AWS parameter types are accepted: `String`, `StringList`, `SecureString`.

!!! note
    `SecureString` parameters are stored as-is without actual KMS encryption in Floci. The type is preserved and returned correctly, but the value is not encrypted at rest.