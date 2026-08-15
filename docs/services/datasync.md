# AWS DataSync

**Protocol:** JSON 1.1 (`X-Amz-Target: FmrsService.<Action>`)
**Endpoint:** `http://localhost:4566/` (SigV4 service `datasync`)

## Supported Actions

| Action | Description |
|---|---|
| `CreateAgent` | Activate an agent; it is `ONLINE` immediately |
| `DescribeAgent` | Get an agent's status, endpoint type and platform |
| `ListAgents` | List agents |
| `UpdateAgent` | Rename an agent |
| `DeleteAgent` | Delete an agent |
| `CreateLocationAzureBlob` | Create an Azure Blob Storage location |
| `CreateLocationEfs` | Create an Amazon EFS location |
| `CreateLocationFsxLustre` | Create an FSx for Lustre location |
| `CreateLocationFsxOntap` | Create an FSx for NetApp ONTAP location |
| `CreateLocationFsxOpenZfs` | Create an FSx for OpenZFS location |
| `CreateLocationFsxWindows` | Create an FSx for Windows File Server location |
| `CreateLocationHdfs` | Create a Hadoop HDFS location |
| `CreateLocationNfs` | Create an NFS location |
| `CreateLocationObjectStorage` | Create an object storage location |
| `CreateLocationS3` | Create an Amazon S3 location |
| `CreateLocationSmb` | Create an SMB location |
| `DescribeLocation*` | Read back the configuration its `CreateLocation*` received, one per location type |
| `UpdateLocation*` | Change a location's configuration, one per location type |
| `ListLocations` | List locations, optionally filtered by `LocationUri`, `LocationType` or `CreationTime` |
| `DeleteLocation` | Delete a location |
| `CreateTask` | Create a transfer task; it is `AVAILABLE` immediately |
| `DescribeTask` | Get a task's status, locations, options and filters |
| `ListTasks` | List tasks, optionally filtered by `LocationId` or `CreationTime` |
| `UpdateTask` | Change a task's name, options, filters, schedule or reporting |
| `DeleteTask` | Delete a task |
| `TagResource` | Tag an agent, location or task |
| `UntagResource` | Remove tags from an agent, location or task |
| `ListTagsForResource` | List an agent's, location's or task's tags |

Agents report `ONLINE` and tasks report `AVAILABLE` on the first read after they are
created, so SDK and Terraform waiters — including the one behind `aws_datasync_task` —
complete on their first poll. Tags passed on any create are honoured and come back from
`ListTagsForResource`.

Each `DescribeLocation*` projects the create request that produced the location, so the
configuration you sent is the configuration you read back. `LocationUri` is derived from
the request the way AWS documents it, per location type:

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
`S3StorageClass` `STANDARD`, Azure Blob `BLOCK`/`HOT`, object storage `HTTPS` on port 443
(80 for `HTTP`), SMB `NTLM` authentication, NFS and SMB mount version `AUTOMATIC`, HDFS
block size 128 MiB with replication factor 3 and `PRIVACY` QOP, EFS `InTransitEncryption`
`NONE`, and the full `Options` block on a task. `DescribeLocationFsxOntap` derives
`FsxFilesystemArn` from the storage virtual machine ARN, as AWS does.

## Not emulated

The task-execution data plane — `StartTaskExecution`, `DescribeTaskExecution`,
`ListTaskExecutions`, `CancelTaskExecution` and `UpdateTaskExecution` — transfers real
bytes between real storage systems, which floci has no way to model. These operations
return `UnknownOperationException` rather than a stub success, so a caller fails fast
instead of waiting on a transfer that will never progress.

Credential members are accepted on create but never stored or returned: `Password`,
`SecretKey`, Azure `SasConfiguration`, `KerberosKeytab` and `KerberosKrb5Conf`, plus the
nested `Protocol.SMB.Password` on the FSx protocol block. AWS also omits them from every
describe response. Because no secret is created, `ManagedSecretConfig` is not returned;
`CmkSecretConfig` and `CustomSecretConfig` are echoed as sent.

Agent ARNs referenced by a location (`AgentArns`, `OnPremConfig.AgentArns`) are not
checked against the agents floci knows about, so a location can point at an agent that was
provisioned elsewhere. Location ARNs on `CreateTask` *are* checked, and an unknown one
fails with `InvalidRequestException`.

DataSync models only `InvalidRequestException` and `InternalException`, so a missing
resource, a required member that was not sent, and a describe aimed at the wrong location
type all return `InvalidRequestException` with HTTP 400 — there is no
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
