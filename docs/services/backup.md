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
| `DeleteBackupVault` | `DELETE` | `/backup-vaults/{backupVaultName}` | Delete an empty backup vault |
| `ListBackupVaults` | `GET` | `/backup-vaults/` | List all backup vaults |

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

```yaml
floci:
  services:
    backup:
      job-completion-delay-seconds: 3   # default
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

## Configuration

| Property | Env var | Default | Description |
|---|---|---|---|
| `floci.services.backup.enabled` | `FLOCI_SERVICES_BACKUP_ENABLED` | `true` | Enable / disable the service |
| `floci.services.backup.job-completion-delay-seconds` | `FLOCI_SERVICES_BACKUP_JOB_COMPLETION_DELAY_SECONDS` | `3` | Seconds from job start until `COMPLETED` |

## Not Yet Supported

- Restore jobs (`StartRestoreJob`, `DescribeRestoreJob`, `ListRestoreJobs`)
- Backup vaults with notifications (`PutBackupVaultNotifications`, `GetBackupVaultNotifications`)
- Backup vaults with access policy (`PutBackupVaultAccessPolicy`, `GetBackupVaultAccessPolicy`)
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
