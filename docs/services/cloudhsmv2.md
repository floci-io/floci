# CloudHSM v2

**Protocol:** JSON 1.1 (`X-Amz-Target: BaldrApiService.*`)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateCluster` | Creates a new AWS CloudHSM cluster. |
| `DescribeClusters` | Gets information about AWS CloudHSM clusters. |
| `DeleteCluster` | Deletes the specified AWS CloudHSM cluster. |
| `ModifyCluster` | Modifies AWS CloudHSM cluster. |
| `InitializeCluster` | Claims an AWS CloudHSM cluster. |
| `CreateHsm` | Creates a new hardware security module (HSM) in the specified AWS CloudHSM cluster. |
| `DeleteHsm` | Deletes the specified HSM. |
| `DescribeBackups` | Gets information about backups of AWS CloudHSM clusters. |
| `DeleteBackup` | Deletes a specified AWS CloudHSM backup. |
| `RestoreBackup` | Restores a specified AWS CloudHSM backup. |
| `ModifyBackupAttributes` | Modifies attributes for AWS CloudHSM backup. |
| `CopyBackupToRegion` | Copies a specified AWS CloudHSM cluster backup to a different region. |
| `PutResourcePolicy` | Creates or updates a resource policy for an AWS CloudHSM cluster or backup. |
| `GetResourcePolicy` | Retrieves the resource policy for an AWS CloudHSM cluster or backup. |
| `DeleteResourcePolicy` | Deletes a resource policy for an AWS CloudHSM cluster or backup. |
| `TagResource` | Adds or overwrites tags on the specified AWS CloudHSM cluster or backup. |
| `UntagResource` | Removes the specified tag or tags from the specified AWS CloudHSM cluster or backup. |
| `ListTags` | Gets a list of tags for the specified AWS CloudHSM cluster or backup. |
<!-- floci:actions:end -->
