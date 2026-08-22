# Elastic File System (EFS)

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/2015-02-01/...`

The EFS emulator provides a metadata control plane for file systems, mount targets, access points, and policies across the `2015-02-01` API. 

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateFileSystem` | - |
| `DescribeFileSystems` | - |
| `UpdateFileSystem` | - |
| `DeleteFileSystem` | - |
| `CreateTags` | - |
| `DescribeTags` | - |
| `DeleteTags` | - |
| `TagResource` | - |
| `UntagResource` | - |
| `ListTagsForResource` | - |
| `CreateMountTarget` | - |
| `DescribeMountTargets` | - |
| `DeleteMountTarget` | - |
| `DescribeMountTargetSecurityGroups` | - |
| `ModifyMountTargetSecurityGroups` | - |
| `CreateAccessPoint` | - |
| `DescribeAccessPoints` | - |
| `DeleteAccessPoint` | - |
| `PutFileSystemPolicy` | - |
| `DescribeFileSystemPolicy` | - |
| `DeleteFileSystemPolicy` | - |
| `PutBackupPolicy` | - |
| `DescribeBackupPolicy` | - |
| `PutLifecycleConfiguration` | - |
| `DescribeLifecycleConfiguration` | - |
<!-- floci:actions:end -->

## Limitations
- **Metadata Only**: Floci provides an EFS API simulation (the control plane) so you can provision file systems for your ECS tasks and Lambda functions. It does not provide an actual NFSv4.1 data plane endpoint. Reading or writing files via standard file-system mounts will fail.
- **Lifecycle Evaluation**: Lifecycle policies are saved but no background worker evaluates them to move data across storage classes. Transition fields will remain static.
- **Backup**: Backup policies are recorded, but Floci does not currently interface with the AWS Backup emulator to generate real snapshot resources.
