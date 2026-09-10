# AWS DataSync

**Protocol:** JSON 1.1 (`X-Amz-Target: FmrsService.<Action>`)
**Endpoint:** `http://localhost:4566/` (SigV4 service `datasync`)

DataSync's Smithy service shape is `FmrsService`, so that is the target prefix the SDK and
the CLI send, not the service name. Floci emulates the management plane: agents, locations,
tasks and tagging. The task-execution data plane is not emulated.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateAgent` | Activate an agent; it reports `ONLINE` immediately |
| `DescribeAgent` | Read an agent's status, endpoint type and platform version |
| `UpdateAgent` | Rename an agent |
| `DeleteAgent` | Delete an agent |
| `ListAgents` | Page through agents, ordered by ARN |
| `CreateLocationAzureBlob` | Create an Azure Blob Storage location |
| `CreateLocationEfs` | Create an Amazon EFS location |
| `CreateLocationFsxLustre` | Create an FSx for Lustre location |
| `CreateLocationFsxOntap` | Create an FSx for NetApp ONTAP location |
| `CreateLocationFsxOpenZfs` | Create an FSx for OpenZFS location |
| `CreateLocationFsxWindows` | Create an FSx for Windows File Server location |
| `CreateLocationHdfs` | Create a Hadoop HDFS location |
| `CreateLocationNfs` | Create an NFS location |
| `CreateLocationObjectStorage` | Create a self-managed object storage location |
| `CreateLocationS3` | Create an Amazon S3 location |
| `CreateLocationSmb` | Create an SMB file server location |
| `DescribeLocationAzureBlob` | Read back an Azure Blob location's configuration |
| `DescribeLocationEfs` | Read back an EFS location's configuration |
| `DescribeLocationFsxLustre` | Read back an FSx for Lustre location's configuration |
| `DescribeLocationFsxOntap` | Read back an FSx for ONTAP location, including the derived `FsxFilesystemArn` |
| `DescribeLocationFsxOpenZfs` | Read back an FSx for OpenZFS location's configuration |
| `DescribeLocationFsxWindows` | Read back an FSx for Windows location's configuration |
| `DescribeLocationHdfs` | Read back an HDFS location's configuration |
| `DescribeLocationNfs` | Read back an NFS location's configuration |
| `DescribeLocationObjectStorage` | Read back an object storage location's configuration |
| `DescribeLocationS3` | Read back an S3 location's configuration |
| `DescribeLocationSmb` | Read back an SMB location's configuration |
| `UpdateLocationAzureBlob` | Change an Azure Blob location and rebuild its `LocationUri` |
| `UpdateLocationEfs` | Change an EFS location and rebuild its `LocationUri` |
| `UpdateLocationFsxLustre` | Change an FSx for Lustre location and rebuild its `LocationUri` |
| `UpdateLocationFsxOntap` | Change an FSx for ONTAP location and rebuild its `LocationUri` |
| `UpdateLocationFsxOpenZfs` | Change an FSx for OpenZFS location and rebuild its `LocationUri` |
| `UpdateLocationFsxWindows` | Change an FSx for Windows location and rebuild its `LocationUri` |
| `UpdateLocationHdfs` | Change an HDFS location and rebuild its `LocationUri` |
| `UpdateLocationNfs` | Change an NFS location and rebuild its `LocationUri` |
| `UpdateLocationObjectStorage` | Change an object storage location and rebuild its `LocationUri` |
| `UpdateLocationS3` | Change an S3 location and rebuild its `LocationUri` |
| `UpdateLocationSmb` | Change an SMB location and rebuild its `LocationUri` |
| `ListLocations` | Page through locations, optionally filtered by `LocationUri`, `LocationType` or `CreationTime` |
| `DeleteLocation` | Delete a location |
| `CreateTask` | Create a transfer task; it reports `AVAILABLE` immediately |
| `DescribeTask` | Read a task's status, locations, options and filters |
| `UpdateTask` | Change a task's name, options, filters, schedule or reporting |
| `DeleteTask` | Delete a task |
| `ListTasks` | Page through tasks, optionally filtered by `LocationId` or `CreationTime` |
| `TagResource` | Tag an agent, location or task by ARN |
| `UntagResource` | Remove tags from an agent, location or task by ARN |
| `ListTagsForResource` | List an agent's, location's or task's tags |
<!-- floci:actions:end -->

Agents report `ONLINE` and tasks report `AVAILABLE` on the first read after they are
created, so SDK and Terraform waiters, including the one behind `aws_datasync_task`,
complete on their first poll. Tags passed on any create are honoured and come back from
`ListTagsForResource`.

Each `DescribeLocation*` projects the create request that produced the location, so the
configuration you sent is the configuration you read back. `LocationUri` is derived from
the same request the way AWS documents it, per location type:

| Location type | `LocationUri` |
|---|---|
| S3 | `s3://<bucket>/<subdirectory>` |
| EFS | `efs://<region>.<file-system-id>/<subdirectory>` |
| FSx for Lustre | `fsxl://<region>.<file-system-id>/<subdirectory>` |
| FSx for OpenZFS | `fsxz://<region>.<file-system-id>/<subdirectory>` |
| FSx for Windows | `fsxw://<region>.<file-system-id>/<subdirectory>` |
| FSx for ONTAP | `fsxn://<region>.<file-system-id>.<svm-id>/<subdirectory>` |
| HDFS | `hdfs://<namenode-hostname>:<port>/<subdirectory>` |
| NFS | `nfs://<server-hostname>/<subdirectory>` |
| SMB | `smb://<server-hostname>/<subdirectory>` |
| Object storage | `object-storage://<server-hostname>/<bucket>/<subdirectory>` |
| Azure Blob | `azure-blob://<account-host>/<container>/<subdirectory>` |

DataSync's documented server-side defaults are applied on create and returned on describe:
`S3StorageClass` `STANDARD`, Azure Blob `BLOCK` and `HOT`, object storage `HTTPS` on port
443 (80 for `HTTP`), SMB `NTLM` authentication, NFS and SMB mount version `AUTOMATIC`, HDFS
block size 128 MiB with replication factor 3 and `PRIVACY` QOP, EFS `InTransitEncryption`
`NONE`, and the full `Options` block on a task. `DescribeLocationFsxOntap` derives
`FsxFilesystemArn` from the storage virtual machine ARN, as AWS does.

## Not emulated

The task-execution data plane (`StartTaskExecution`, `DescribeTaskExecution`,
`ListTaskExecutions`, `CancelTaskExecution` and `UpdateTaskExecution`) transfers real bytes
between real storage systems, which floci has no way to model. These operations return
`UnknownOperationException` rather than a stub success, so a caller fails fast instead of
waiting on a transfer that will never progress.

Credential members are accepted on create but never stored or returned: `Password`,
`SecretKey`, Azure `SasConfiguration`, `KerberosKeytab` and `KerberosKrb5Conf`, plus the
nested `Protocol.SMB.Password` on the FSx protocol block. AWS also omits them from every
describe response. Because no secret is created, `ManagedSecretConfig` is not returned;
`CmkSecretConfig` and `CustomSecretConfig` are echoed as sent.

Agent ARNs referenced by a location (`AgentArns`, `OnPremConfig.AgentArns`) are not checked
against the agents floci knows about, so a location can point at an agent that was
provisioned elsewhere. Location ARNs on `CreateTask` are checked, and an unknown one fails
with `InvalidRequestException`.

DataSync models only `InvalidRequestException` and `InternalException`, so a missing
resource, a required member that was not sent, and a describe aimed at the wrong location
type all return `InvalidRequestException` with HTTP 400. There is no
`ResourceNotFoundException` in this API.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_DATASYNC_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws datasync create-agent --activation-key AAAAA-1AAAA-BB1CC-DDDDD-EEEEE --agent-name my-agent

aws datasync create-location-s3 \
  --s3-bucket-arn arn:aws:s3:::my-bucket \
  --subdirectory /backups \
  --s3-config BucketAccessRoleArn=arn:aws:iam::000000000000:role/datasync

aws datasync create-location-nfs \
  --server-hostname nfs.example.com \
  --subdirectory /export/home \
  --on-prem-config AgentArns=arn:aws:datasync:us-east-1:000000000000:agent/agent-...

aws datasync create-task \
  --source-location-arn arn:aws:datasync:us-east-1:000000000000:location/loc-... \
  --destination-location-arn arn:aws:datasync:us-east-1:000000000000:location/loc-... \
  --name my-task

aws datasync describe-task --task-arn arn:aws:datasync:us-east-1:000000000000:task/task-...

aws datasync list-locations
```
