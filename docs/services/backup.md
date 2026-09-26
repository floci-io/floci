# AWS Backup

**Protocol:** REST JSON  
**Endpoint:** `http://localhost:4566/`  
**Credential scope:** `backup`

## Supported Actions

### Backup Vaults

| Action | Method | Path | Description |
|---|---|---|---|
| `CreateBackupVault` | `PUT` | `/backup-vaults/{backupVaultName}` | Create a backup vault |
| `DescribeBackupVault` | `GET` | `/backup-vaults/{backupVaultName}` | Describe a backup vault |
| `DeleteBackupVault` | `DELETE` | `/backup-vaults/{backupVaultName}` | Delete an empty backup vault, locked or not |
| `ListBackupVaults` | `GET` | `/backup-vaults/` | List all backup vaults |

### Backup Vault Access Policy

| Action | Method | Path | Description |
|---|---|---|---|
| `PutBackupVaultAccessPolicy` | `PUT` | `/backup-vaults/{backupVaultName}/access-policy` | Attach a resource policy to a vault |
| `GetBackupVaultAccessPolicy` | `GET` | `/backup-vaults/{backupVaultName}/access-policy` | Read a vault's resource policy |
| `DeleteBackupVaultAccessPolicy` | `DELETE` | `/backup-vaults/{backupVaultName}/access-policy` | Remove a vault's resource policy |

### Backup Vault Notifications

| Action | Method | Path | Description |
|---|---|---|---|
| `PutBackupVaultNotifications` | `PUT` | `/backup-vaults/{backupVaultName}/notification-configuration` | Send vault events to an SNS topic |
| `GetBackupVaultNotifications` | `GET` | `/backup-vaults/{backupVaultName}/notification-configuration` | Read a vault's notification configuration |
| `DeleteBackupVaultNotifications` | `DELETE` | `/backup-vaults/{backupVaultName}/notification-configuration` | Remove a vault's notification configuration |

### Backup Vault Lock

| Action | Method | Path | Description |
|---|---|---|---|
| `PutBackupVaultLockConfiguration` | `PUT` | `/backup-vaults/{backupVaultName}/vault-lock` | Apply a governance or compliance lock |
| `DeleteBackupVaultLockConfiguration` | `DELETE` | `/backup-vaults/{backupVaultName}/vault-lock` | Remove a lock that is still changeable |

### Backup Plans

| Action | Method | Path | Description |
|---|---|---|---|
| `CreateBackupPlan` | `PUT` | `/backup/plans/` | Create a backup plan with rules |
| `GetBackupPlan` | `GET` | `/backup/plans/{backupPlanId}/` | Get backup plan details |
| `UpdateBackupPlan` | `POST` | `/backup/plans/{backupPlanId}` | Update a backup plan |
| `DeleteBackupPlan` | `DELETE` | `/backup/plans/{backupPlanId}` | Delete a backup plan (fails if selections exist) |
| `ListBackupPlans` | `GET` | `/backup/plans/` | List all backup plans |

### Backup Selections

| Action | Method | Path | Description |
|---|---|---|---|
| `CreateBackupSelection` | `PUT` | `/backup/plans/{backupPlanId}/selections/` | Assign resources to a backup plan |
| `GetBackupSelection` | `GET` | `/backup/plans/{backupPlanId}/selections/{selectionId}` | Get selection details |
| `DeleteBackupSelection` | `DELETE` | `/backup/plans/{backupPlanId}/selections/{selectionId}` | Remove a resource selection |
| `ListBackupSelections` | `GET` | `/backup/plans/{backupPlanId}/selections/` | List selections for a plan |

### Backup Jobs

| Action | Method | Path | Description |
|---|---|---|---|
| `StartBackupJob` | `PUT` | `/backup-jobs` | Start an on-demand backup job |
| `DescribeBackupJob` | `GET` | `/backup-jobs/{backupJobId}` | Get backup job status |
| `StopBackupJob` | `POST` | `/backup-jobs/{backupJobId}` | Stop a running backup job |
| `ListBackupJobs` | `GET` | `/backup-jobs/` | List backup jobs with optional filters |

### Recovery Points

| Action | Method | Path | Description |
|---|---|---|---|
| `DescribeRecoveryPoint` | `GET` | `/backup-vaults/{backupVaultName}/recovery-points/{recoveryPointArn}` | Describe a recovery point |
| `ListRecoveryPointsByBackupVault` | `GET` | `/backup-vaults/{backupVaultName}/recovery-points/` | List recovery points in a vault |
| `DeleteRecoveryPoint` | `DELETE` | `/backup-vaults/{backupVaultName}/recovery-points/{recoveryPointArn}` | Delete a recovery point |

### Tagging

| Action | Method | Path | Description |
|---|---|---|---|
| `ListTags` | `GET` | `/tags/{resourceArn}` | List tags on a backup resource |
| `TagResource` | `POST` | `/tags/{resourceArn}` | Add tags to a backup resource |
| `UntagResource` | `POST` | `/untag/{resourceArn}` | Remove tags from a backup resource |

### Other

| Action | Method | Path | Description |
|---|---|---|---|
| `GetSupportedResourceTypes` | `GET` | `/supported-resource-types` | List resource types supported for backup |

## Job Lifecycle

Backup jobs transition through states automatically after `StartBackupJob`:

```
CREATED → RUNNING (after ~1 s) → COMPLETED (after job-completion-delay-seconds)
```

When a job reaches `COMPLETED`:
- A recovery point is created in the target vault
- The vault's `NumberOfRecoveryPoints` counter is incremented
- `StopBackupJob` on a `CREATED` or `RUNNING` job transitions it to `ABORTING → ABORTED`

The completion delay is configurable:

```bash
FLOCI_SERVICES_BACKUP_JOB_COMPLETION_DELAY_SECONDS=3
```

Use a shorter delay (e.g. `1`) in test environments to speed up job-completion assertions.

## Supported Resource Types

`GetSupportedResourceTypes` returns the following resource type codes:

`S3`, `RDS`, `DynamoDB`, `EFS`, `EC2`, `EBS`, `Aurora`, `DocumentDB`, `Neptune`, `FSx`, `VirtualMachine`

Actual backup is simulated — no data is read from or written to the referenced resources.

## Constraints

- **DeleteBackupVault** returns `InvalidRequestException` (400) if the vault contains recovery points.
- **DeleteBackupPlan** returns `InvalidRequestException` (400) if the plan has active selections.
- **CreateBackupVault** returns `AlreadyExistsException` (400) on duplicate vault names within the same region.
- **Vault Lock has two modes, and `ChangeableForDays` selects them -- in the direction
  that reads backwards.** *Present* means **compliance** mode: `LockDate` is set that many
  days ahead, the lock can still be changed or deleted before it, and on and after it both
  `PutBackupVaultLockConfiguration` and `DeleteBackupVaultLockConfiguration` return
  `InvalidRequestException` (400). *Absent* means **governance** mode: no `LockDate` is
  set and the lock can be changed or removed at any time. `ChangeableForDays` below 3 is
  rejected, matching AWS's 72-hour cooling-off period; retention days below 1 are rejected.
- **A lock does not stop the vault being deleted.** It protects the recovery points, so an
  empty vault can be deleted even under a compliance lock. The non-empty rule above is the
  one that refuses.
- **What the lock actually enforces, in both directions.**
  `DeleteRecoveryPoint` returns `InvalidRequestException` (400) for a recovery point younger
  than `MinRetentionDays` while the vault is locked, and `StartBackupJob` returns
  `InvalidParameterValueException` (400) when the lifecycle's `DeleteAfterDays` falls outside
  `[MinRetentionDays, MaxRetentionDays]`, **including when the lifecycle is omitted and the
  lock sets a maximum** -- retaining indefinitely is the largest retention there is, so it
  exceeds every finite ceiling, and waving it through would leave the ceiling bypassable by
  dropping one member. An absent lifecycle under a minimum-only lock is accepted, because a
  floor is satisfied by indefinite retention. That scoping is Floci's reading rather than a
  quotation: the reference states the rule for a lifecycle outside the window and does not
  spell out the absent case. Both are the documented purpose of the lock: it
  "prevents the deletion of recovery points before their retention periods expire", and
  "backup jobs will fail if the lifecycle policy of the backup plan is outside the vault
  lock's retention period". **Governance mode enforces here exactly as compliance mode
  does.** On real AWS a principal holding `backup:DisableGovernanceRetention` can override a
  governance lock; Floci does not model that permission, so it does not grant the override.
  That is stricter than AWS for that one privileged caller and identical for every other.
- **The Vault Lock day limits are per field, because AWS documents them per field.**
  `ChangeableForDays` must be 3 or greater and at most 36,500. `MaxRetentionDays` is at
  most 36,500. `MinRetentionDays` has a documented minimum of 1 day and **no documented
  maximum**, so Floci applies none: bounding it would risk refusing a value real AWS
  accepts. `MaxRetentionDays` below `MinRetentionDays` is rejected when both are
  supplied.
- **PutBackupVaultNotifications** rejects any `BackupVaultEvents` value outside the
  documented set with `InvalidParameterValueException` (400), rather than storing it. All
  30 documented values are accepted, including the ones AWS marks deprecated.
- **A missing parameter and an empty one are different errors.**
  `PutBackupVaultNotifications` returns `MissingParameterValueException` (400) when
  `SNSTopicArn` or `BackupVaultEvents` is absent, which is the error AWS lists in that
  operation's Errors section, and `InvalidParameterValueException` (400) when one is
  present but unusable (`"SNSTopicArn": ""`, or `"BackupVaultEvents": []`). An SDK maps
  the two onto different typed exceptions. The reference names the absent case; the
  present-but-unusable one is Floci's reading, not a quotation.
- **`PutBackupVaultAccessPolicy` accepts a request that omits `Policy`.** AWS documents
  `Policy` as `Required: No`, so refusing one would fail a call real AWS accepts. What
  AWS then stores is **not documented, and we have not measured it**; Floci **clears**
  the stored policy, so a subsequent `GetBackupVaultAccessPolicy` returns
  `ResourceNotFoundException` (400). That is a deliberate choice rather than an observed
  AWS behaviour: it leaves the vault carrying exactly the policy the request carried,
  instead of retaining one the caller did not send. A `Policy` that is present but empty
  is a different case and is rejected with `InvalidParameterValueException` (400).
- **`Policy` must be a JSON object.** A document that does not parse, or parses to
  something other than an object, is rejected with `InvalidParameterValueException` (400)
  rather than stored. Syntax only: Floci does not evaluate what the document grants.
- **`SNSTopicArn` must be a JSON string.** A number or boolean is rejected with
  `InvalidParameterValueException` (400) rather than coerced, so a request carrying
  `"SNSTopicArn": 123` fails instead of storing the topic `"123"`.
- Deleting an access policy or notification configuration that was never set is a no-op,
  so a destroy re-run does not fail.
- **A vault's policy and notification configuration are keyed per vault, not per vault
  name**, so a vault carries only the configuration applied to it. Reusing a deleted vault's
  name therefore starts clean, and cleaning up a deleted vault cannot reach a configuration
  belonging to a later vault of the same name. Deleting a vault removes both, dependents
  first: the three stores have no transaction between them, and that order keeps any
  observable intermediate state a vault without configuration rather than a configuration
  without a vault.
- The access policy and notification configuration are **not** returned by
  `DescribeBackupVault`, matching AWS: each is reachable only through its own `Get`
  operation. The lock fields (`Locked`, `LockDate`, `MinRetentionDays`,
  `MaxRetentionDays`) are on the vault, also matching AWS.

## Configuration

| Property | Env var | Default | Description |
|---|---|---|---|
| `floci.services.backup.enabled` | `FLOCI_SERVICES_BACKUP_ENABLED` | `true` | Enable / disable the service |
| `floci.services.backup.job-completion-delay-seconds` | `FLOCI_SERVICES_BACKUP_JOB_COMPLETION_DELAY_SECONDS` | `3` | Seconds from job start until `COMPLETED` |

## Not Yet Supported

- **Notification delivery.** `PutBackupVaultNotifications` stores the configuration and
  `GetBackupVaultNotifications` reports it, but no vault event is ever published to the SNS
  topic: a subscriber receives nothing when a backup job completes. The configuration is
  modelled, the delivery is not, so a Terraform configuration that attaches notifications
  applies and reads back correctly while a test asserting on a received message will fail.
- **Access policy enforcement.** `PutBackupVaultAccessPolicy` stores a resource policy and
  `GetBackupVaultAccessPolicy` reports it, but no authorization path consults it: a policy
  denying a vault operation does not prevent that operation, even with IAM enforcement
  enabled. This matches the rest of Floci rather than being specific to Backup -- S3 bucket
  policies are the only resource policies any service enforces today. Stated here because a
  stored-and-ignored policy is otherwise indistinguishable from an enforced one.
- Restore jobs (`StartRestoreJob`, `DescribeRestoreJob`, `ListRestoreJobs`)
- Copy jobs (`StartCopyJob`, `DescribeCopyJob`, `ListCopyJobs`)
- Report plans (`CreateReportPlan`, `DescribeReportPlan`, etc.)
- Framework operations (`CreateFramework`, etc.)
- Legal holds
- Pagination tokens on list operations

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a backup vault
aws backup create-backup-vault \
  --backup-vault-name my-vault \
  --backup-vault-tags env=dev

# Describe the vault
aws backup describe-backup-vault \
  --backup-vault-name my-vault

# Create a backup plan
aws backup create-backup-plan \
  --backup-plan '{
    "BackupPlanName": "daily-backup",
    "Rules": [{
      "RuleName": "daily",
      "TargetBackupVaultName": "my-vault",
      "ScheduleExpression": "cron(0 12 * * ? *)",
      "StartWindowMinutes": 60,
      "CompletionWindowMinutes": 120
    }]
  }'

# Assign resources to the plan
aws backup create-backup-selection \
  --backup-plan-id <plan-id> \
  --backup-selection '{
    "SelectionName": "my-tables",
    "IamRoleArn": "arn:aws:iam::000000000000:role/backup-role",
    "Resources": ["arn:aws:dynamodb:us-east-1:000000000000:table/my-table"]
  }'

# Start an on-demand backup job
aws backup start-backup-job \
  --backup-vault-name my-vault \
  --resource-arn arn:aws:dynamodb:us-east-1:000000000000:table/my-table \
  --iam-role-arn arn:aws:iam::000000000000:role/backup-role

# Poll job status
aws backup describe-backup-job --backup-job-id <job-id>

# List recovery points
aws backup list-recovery-points-by-backup-vault \
  --backup-vault-name my-vault

# Tag a vault
aws backup tag-resource \
  --resource-arn arn:aws:backup:us-east-1:000000000000:backup-vault:my-vault \
  --tags team=platform

# List tags
aws backup list-tags \
  --resource-arn arn:aws:backup:us-east-1:000000000000:backup-vault:my-vault

# Untag a vault
aws backup untag-resource \
  --resource-arn arn:aws:backup:us-east-1:000000000000:backup-vault:my-vault \
  --tag-key-list team
```
