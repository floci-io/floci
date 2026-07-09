# Secrets Manager

**Protocol:** JSON 1.1 (`X-Amz-Target: secretsmanager.*`)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateSecret` | Create a new secret |
| `GetSecretValue` | Retrieve the current secret value |
| `PutSecretValue` | Update the secret value (new version) |
| `UpdateSecret` | Update secret metadata or value |
| `DescribeSecret` | Get secret metadata and version info |
| `ListSecrets` | List all secrets |
| `DeleteSecret` | Delete a secret (with recovery window) |
| `RestoreSecret` | - |
| `RotateSecret` | Trigger secret rotation via a Lambda |
| `TagResource` | Tag a secret |
| `UntagResource` | Remove tags |
| `ListSecretVersionIds` | List all versions of a secret |
| `GetResourcePolicy` | Get the resource policy |
| `GetRandomPassword` | Generate a random password |
| `BatchGetSecretValue` | Retrieve multiple secret values in one call |
| `DeleteResourcePolicy` | Remove the resource policy |
| `PutResourcePolicy` | Attach a resource policy |
| `UpdateSecretVersionStage` | Move a staging label between versions |
<!-- floci:actions:end -->

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_SECRETSMANAGER_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_SECRETSMANAGER_DEFAULT_RECOVERY_WINDOW_DAYS` | `30` | Days before a deleted secret is permanently purged |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a string secret
aws secretsmanager create-secret \
  --name /app/database-url \
  --secret-string "postgresql://admin:secret@localhost/mydb" \
  --endpoint-url $AWS_ENDPOINT_URL

# Create a JSON secret
aws secretsmanager create-secret \
  --name /app/api-keys \
  --secret-string '{"stripe":"sk_test_xxx","sendgrid":"SG.xxx"}' \
  --endpoint-url $AWS_ENDPOINT_URL

# Retrieve a secret
aws secretsmanager get-secret-value \
  --secret-id /app/database-url \
  --endpoint-url $AWS_ENDPOINT_URL

# Update a secret
aws secretsmanager put-secret-value \
  --secret-id /app/database-url \
  --secret-string "postgresql://admin:new-password@localhost/mydb" \
  --endpoint-url $AWS_ENDPOINT_URL

# List secrets
aws secretsmanager list-secrets --endpoint-url $AWS_ENDPOINT_URL

# Delete (with recovery window)
aws secretsmanager delete-secret \
  --secret-id /app/database-url \
  --recovery-window-in-days 7 \
  --endpoint-url $AWS_ENDPOINT_URL

# Delete immediately (no recovery)
aws secretsmanager delete-secret \
  --secret-id /app/database-url \
  --force-delete-without-recovery \
  --endpoint-url $AWS_ENDPOINT_URL

# Generate a random password
aws secretsmanager get-random-password \
  --password-length 24 \
  --exclude-punctuation \
  --endpoint-url $AWS_ENDPOINT_URL

# Batch-fetch multiple secrets in one call
aws secretsmanager batch-get-secret-value \
  --secret-id-list /app/database-url /app/api-keys \
  --endpoint-url $AWS_ENDPOINT_URL

# Move the AWSCURRENT label to a different version (e.g. during a rotation)
aws secretsmanager update-secret-version-stage \
  --secret-id /app/database-url \
  --version-stage AWSCURRENT \
  --move-to-version-id <new-version-id> \
  --remove-from-version-id <old-version-id> \
  --endpoint-url $AWS_ENDPOINT_URL
```
