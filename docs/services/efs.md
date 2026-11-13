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
| `UpdateFileSystemProtection` | - |
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

## CloudFormation

A template can create `AWS::EFS::FileSystem`, `AWS::EFS::MountTarget` and `AWS::EFS::AccessPoint`. They go through the same metadata control plane as the API, so a stack and a direct call see the same file systems.

| Type | `Ref` | `Fn::GetAtt` | Updates in place | Replaces |
| --- | --- | --- | --- | --- |
| `AWS::EFS::FileSystem` | `FileSystemId` | `Arn`, `FileSystemId` | `ThroughputMode`, `ProvisionedThroughputInMibps`, `BackupPolicy`, `LifecyclePolicies`, `FileSystemPolicy`, `FileSystemProtection`, `FileSystemTags` | `Encrypted`, `KmsKeyId`, `PerformanceMode`, `AvailabilityZoneName` |
| `AWS::EFS::MountTarget` | mount target id | `Id`, `IpAddress` | `SecurityGroups` | `FileSystemId`, `SubnetId`, `IpAddress`, `IpAddressType`, `Ipv6Address` |
| `AWS::EFS::AccessPoint` | `AccessPointId` | `AccessPointId`, `Arn` | `AccessPointTags` | `FileSystemId`, `ClientToken`, `PosixUser`, `RootDirectory` |

- **Replacement**: the new resource is created first and the displaced one is deleted once the stack update commits. A failed update deletes the new one and keeps the old.
- **Replacement that collides**: because the new resource exists before the old one is deleted, a replacement that keeps the old one's key is refused. A mount target is keyed by file system and subnet, so changing only `IpAddress`, `IpAddressType` or `Ipv6Address` fails with `Mount target already exists in this subnet`. An access point that keeps an explicit `ClientToken` fails the same way when `PosixUser` or `RootDirectory` changes. The update then rolls back and the old resource stays as it was. To change those properties, also change the subnet, or change the `ClientToken` or leave it out so a new one is generated.
- **Rollback**: a failed stack update puts back what it changed in place (throughput, backup and lifecycle settings, policy, protection, tags, security groups).
- **Dropped properties**: removing a property the previous template declared resets it to the AWS default. A setting made outside the stack is left alone, with one exception: `FileSystemTags` and `AccessPointTags` are driven to the template's set on every update, so a tag added outside the stack is removed.
- **Backup**: a file system that leaves `BackupPolicy` out is created with automatic backups off, or on when `AvailabilityZoneName` is set (One Zone). Dropping a declared `BackupPolicy` resets it to that same default.
- **Provisioned throughput**: `ThroughputMode: provisioned` requires `ProvisionedThroughputInMibps`, and `ProvisionedThroughputInMibps` is refused in any other mode.
- **Delete order**: a file system that still has mount targets fails with `FileSystemInUse`, as on AWS, so list the file system in the mount target's `FileSystemId` with `Ref` and let the stack delete the mount target first.
- **Not supported**: `ReplicationConfiguration` and `BypassPolicyLockoutSafetyCheck` are accepted and ignored.

## Limitations
- **Metadata Only**: Floci provides an EFS API simulation (the control plane) so you can provision file systems for your ECS tasks and Lambda functions. It does not provide an actual NFSv4.1 data plane endpoint. Reading or writing files via standard file-system mounts will fail.
- **Lifecycle Evaluation**: Lifecycle policies are saved but no background worker evaluates them to move data across storage classes. Transition fields will remain static.
- **Backup**: Backup policies are recorded, but Floci does not currently interface with the AWS Backup emulator to generate real snapshot resources.
