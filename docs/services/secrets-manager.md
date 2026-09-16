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
| `RestoreSecret` | Cancel a scheduled deletion and restore the secret |
| `RotateSecret` | Trigger secret rotation, via a Lambda or by the owning service |
| `CancelRotateSecret` | Turn off automatic rotation and report any half-staged `AWSPENDING` version |
| `TagResource` | Tag a secret |
| `UntagResource` | Remove tags |
| `ListSecretVersionIds` | List all versions of a secret |
| `GetResourcePolicy` | Get the resource policy |
| `GetRandomPassword` | Generate a random password |
| `BatchGetSecretValue` | Retrieve multiple secret values in one call |
| `DeleteResourcePolicy` | Remove the resource policy |
| `PutResourcePolicy` | Attach a resource policy |
| `UpdateSecretVersionStage` | Move a staging label between versions |
| `ValidateResourcePolicy` | Check a resource policy for overly broad principals |
| `ReplicateSecretToRegions` | Replicate a secret into other regions |
| `RemoveRegionsFromReplication` | Delete replicas from the given regions |
| `StopReplicationToReplica` | Promote a replica to a standalone primary |
<!-- floci:actions:end -->

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_SECRETSMANAGER_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_SECRETSMANAGER_DEFAULT_RECOVERY_WINDOW_DAYS` | `30` | Days before a deleted secret is permanently purged |
| `FLOCI_SERVICES_SECRETSMANAGER_SCHEDULED_ROTATION_ENABLED` | `true` | Run the background sweep that fires rotations when a schedule comes due |
| `FLOCI_SERVICES_SECRETSMANAGER_ROTATION_TICK_SECONDS` | `60` | How often that sweep looks for due rotations |

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

# Turn on scheduled rotation, then turn it back off
aws secretsmanager rotate-secret \
  --secret-id /app/database-url \
  --rotation-lambda-arn arn:aws:lambda:us-east-1:000000000000:function:my-rotator \
  --rotation-rules '{"ScheduleExpression":"rate(10 days)"}' \
  --endpoint-url $AWS_ENDPOINT_URL

aws secretsmanager cancel-rotate-secret \
  --secret-id /app/database-url \
  --endpoint-url $AWS_ENDPOINT_URL

# Replicate to another region, then promote the replica to its own primary
aws secretsmanager replicate-secret-to-regions \
  --secret-id /app/database-url \
  --add-replica-regions Region=eu-west-1 \
  --endpoint-url $AWS_ENDPOINT_URL

aws secretsmanager stop-replication-to-replica \
  --secret-id /app/database-url \
  --region eu-west-1 \
  --endpoint-url $AWS_ENDPOINT_URL

# Check a resource policy before attaching it
aws secretsmanager validate-resource-policy \
  --secret-id /app/database-url \
  --resource-policy file://policy.json \
  --endpoint-url $AWS_ENDPOINT_URL
```

## Behaviour notes

- **Rotation schedules fire.** A background sweep (see the config table) rotates any secret whose
  `NextRotationDate` has passed. `AutomaticallyAfterDays`, `rate()` and `cron()` are all honoured.
- **Replication is synchronous.** Real AWS reports `InProgress` first; floci copies the secret
  immediately, so a replica is `InSync` by the time the call returns. Replicas are read-only and
  track later writes to the primary.
- **Secret values are stored in the clear.** `KmsKeyId` is validated — a missing key is rejected,
  and a disabled or pending-deletion key raises `EncryptionFailure` — but values are not actually
  encrypted, since encrypting with the emulator's own KMS would protect nothing.
- **`ValidateResourcePolicy` and `BlockPublicPolicy` check for wildcard principals only.** AWS runs
  the policy through Zelkova; a pass here is weaker than a pass on AWS. `PutResourcePolicy` with
  `BlockPublicPolicy` raises `PublicPolicyException` for a wildcard principal, and — as on AWS —
  leaves such a policy alone when the flag is absent.
- **`PrimaryRegion` and `ReplicationStatus` appear only on multi-region secrets**, matching AWS's
  "only returns fields that have a value" rule — a standalone secret carries neither.
- **Rotation schedule limits are enforced**: `AutomaticallyAfterDays` 1–1000, `Duration` as `Nh`,
  and `rate()` in hours or days no faster than every four hours.
- **Known deviation:** `RotateSecret` with `RotateImmediately: false` invokes the rotation
  function's `testSecret` step but does not first create the throwaway `AWSPENDING` version that
  AWS creates and removes, so a rotation function whose `testSecret` reads that version by id
  will not find it.
