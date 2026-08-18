# Redshift

**Protocol:** Query (form-encoded POST, XML response)
**Endpoint:** `http://localhost:4566/`

Floci emulates the Amazon Redshift control plane: provisioned clusters, cluster subnet
groups, cluster parameter groups and the Redshift tag operations. There is no Redshift
compute — no SQL is executed and nothing listens on the reported cluster endpoint. The
service exists so infrastructure tooling can create, read, tag and destroy Redshift
resources without reaching AWS.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateCluster` | Creates a provisioned cluster; returns `available` immediately |
| `DescribeClusters` | Returns clusters, optionally filtered by identifier or tag |
| `ModifyCluster` | Applies configuration changes, including a rename via `NewClusterIdentifier` |
| `DeleteCluster` | Removes the cluster; a later describe returns `ClusterNotFound` |
| `RebootCluster` | Returns the cluster; the reboot completes within the call |
| `CreateClusterSubnetGroup` | Creates a subnet group; the VPC is resolved from the named EC2 subnets |
| `DescribeClusterSubnetGroups` | Returns subnet groups, optionally filtered by name or tag |
| `ModifyClusterSubnetGroup` | Replaces the subnet list and description, preserving tags |
| `DeleteClusterSubnetGroup` | Removes the subnet group unless a cluster still references it |
| `CreateClusterParameterGroup` | Creates a parameter group in a parameter group family |
| `DescribeClusterParameterGroups` | Returns parameter groups, including the engine default |
| `ModifyClusterParameterGroup` | Records user parameter values on the group |
| `DeleteClusterParameterGroup` | Removes the parameter group unless it is the default or in use |
| `DescribeClusterParameters` | Returns engine defaults overlaid with user values, filterable by `Source` |
| `CreateTags` | Adds tags to a cluster, subnet group or parameter group ARN |
| `DeleteTags` | Removes tags by key from a resource ARN |
| `DescribeTags` | Returns tags across the region, filterable by ARN, resource type, key or value |
<!-- floci:actions:end -->

## Cluster state

A cluster reports `ClusterStatus` `available` and `ClusterAvailabilityStatus` `Available`
from the `CreateCluster` response onwards. Floci provisions nothing, so a modelled
`creating` phase would never end: the SDK's `ClusterAvailable` waiter and the Terraform
provider both poll `Clusters[].ClusterStatus` and would spin until the apply deadline.
`ModifyCluster` and `RebootCluster` behave the same way — the change is complete when the
call returns. `DeleteCluster` reports `deleting` in its own response and removes the
cluster, so the next `DescribeClusters` raises `ClusterNotFound`, which is what delete
waiters treat as success.

`Endpoint.Address` follows the AWS shape
(`{cluster-id}.{suffix}.{region}.redshift.amazonaws.com`) and `Endpoint.Port` defaults to
`5439`. Nothing is listening on it.

`ClusterNodes` reports node roles only — `SHARED` for a single-node cluster, one `LEADER`
plus the compute nodes otherwise. Per-node IP addresses are omitted rather than
fabricated.

## Subnet groups and the VPC

`CreateClusterSubnetGroup` and `ModifyClusterSubnetGroup` resolve every subnet id through
Floci's EC2 service. An unknown subnet raises `InvalidSubnet`; subnets spanning more than
one VPC raise `InvalidVPCNetworkStateFault`. The group's `VpcId` and each subnet's
availability zone come from the resolved EC2 subnets, and a cluster created with
`ClusterSubnetGroupName` inherits that `VpcId` and the group's first availability zone. A
cluster created without a subnet group falls back to the region's default VPC.

## Parameter groups

Every region has `default.redshift-1.0`, materialized on first access and not deletable.
`DescribeClusterParameters` returns the `redshift-1.0` engine defaults overlaid with the
values set by `ModifyClusterParameterGroup`; user-set parameters carry `Source` `user`, so
`Source=user` returns exactly the values that were written.

## Tags

`CreateTags`, `DeleteTags` and `DescribeTags` accept the Redshift ARN forms
`arn:aws:redshift:{region}:{account}:cluster:{name}`, `…:subnetgroup:{name}` and
`…:parametergroup:{name}`. Tags passed on any of the three create operations are echoed
back by the matching describe. An ARN with no backing resource raises
`ResourceNotFoundFault`.

## Not implemented

These families return `UnknownOperationException` rather than a stub success:

- Snapshots (`CreateClusterSnapshot`, `DescribeClusterSnapshots`, `RestoreFromClusterSnapshot`,
  snapshot copy and snapshot schedules) — a snapshot's value is the data it captures, and
  Floci stores no Redshift data to capture.
- Resize and pause/resume (`ResizeCluster`, `DescribeResize`, `PauseCluster`, `ResumeCluster`) —
  these exist to model a long-running transition, which is exactly what this emulator
  deliberately does not do.
- Datashares, scheduled actions, usage limits, event subscriptions, HSM and IAM
  credential issuance (`GetClusterCredentials`) — each depends on Redshift internals or a
  live data plane that Floci does not have.
- Redshift Serverless and the Redshift Data API — separate AWS services with their own
  endpoints, not part of this Query-protocol surface.

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws redshift create-cluster-subnet-group \
  --cluster-subnet-group-name analytics-subnets \
  --description "Analytics subnets" \
  --subnet-ids subnet-default-a subnet-default-b

aws redshift create-cluster \
  --cluster-identifier analytics \
  --node-type ra3.xlplus \
  --master-username flociadmin \
  --master-user-password 'Floci-Secret-1' \
  --db-name analytics \
  --cluster-subnet-group-name analytics-subnets \
  --tags Key=Project,Value=Floci

aws redshift describe-clusters --cluster-identifier analytics
```
