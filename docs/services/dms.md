# AWS Database Migration Service (DMS)

**Protocol:** AWS JSON 1.1  
**Signing name:** `dms`

Floci emulates the replication subnet group lifecycle, which is what
`aws_dms_replication_subnet_group` needs to plan and apply. Subnets are resolved against
the emulated EC2 service, so the VPC and Availability Zones a group reports are the ones
those subnets actually have.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateReplicationSubnetGroup` | Creates a replication subnet group from existing EC2 subnets. |
| `DescribeReplicationSubnetGroups` | Lists replication subnet groups, optionally filtered by identifier. |
| `DeleteReplicationSubnetGroup` | Deletes the specified replication subnet group. |
| `ListTagsForResource` | Lists the tags on one or more DMS resource ARNs. |
| `AddTagsToResource` | Merges tags into the resource, overwriting by key. |
| `RemoveTagsFromResource` | Removes the named tag keys from the resource. |
<!-- floci:actions:end -->

## Behaviour

- The identifier is stored as a lowercase string, as AWS does, so a group created as
  `MyGroup` is described and deleted as `mygroup`.
- `ReplicationSubnetGroupIdentifier` must not be `default` and is limited to 255
  alphanumeric characters, periods, underscores, or hyphens.
- A group must cover at least two Availability Zones. Fewer returns
  `ReplicationSubnetGroupDoesNotCoverEnoughAZs`, as AWS does.
- Subnets must exist and must all belong to one VPC. Otherwise `InvalidSubnet`.
- `SubnetGroupStatus` is immediately `Complete`, every subnet reports `Active`,
  `SupportedNetworkTypes` is `["IPV4"]`, and `IsReadOnly` is always `false`: a read-only
  group is one DMS manages for a zero-ETL integration, which Floci does not emulate.
- `DescribeReplicationSubnetGroups` supports the `replication-subnet-group-id` filter.
  A filter naming a group that does not exist returns `ResourceNotFoundFault`, which is
  how Terraform detects a group deleted outside its state.
- `DescribeReplicationSubnetGroups` paginates on `MaxRecords` and `Marker`. `MaxRecords`
  defaults to 100 and must be between 20 and 100; a value outside that range is rejected
  with `InvalidParameterValueException` rather than clamped, as AWS does. Results are
  ordered by identifier and `Marker` is an opaque cursor that resumes after the last item
  of the page, so a page stays resumable across groups created or deleted between
  requests. `Marker` is returned
  only when a further page exists, never on the last one.
- `ReplicationSubnetGroupDescription` is required, must not be blank, and must contain only
  printable characters. A control character such as `0x01` returns
  `InvalidParameterValueException`.
- A member of the wrong JSON type returns `SerializationException`, as AWS does, rather than
  being coerced. That covers a `Tags` entry whose `Value` is an object, a filter whose
  `Values` is not a list, a non-string element in `ResourceArnList`, `SubnetIds` or
  `TagKeys`, and a non-string identifier. An absent member is still a parameter error,
  not a serialization one.
- Groups are scoped per account and Region and persist through `StorageFactory`.

### Tagging

`Tags` on `CreateReplicationSubnetGroup` are stored with the group, and the tagging trio
works against the group's ARN, which is
`arn:aws:dms:<region>:<account>:subgrp:<identifier>`. DMS does not return that ARN from
`DescribeReplicationSubnetGroups`, so Terraform builds it client side and Floci parses it
back the same way.

- `AddTagsToResource` merges by key, so re-tagging an existing key overwrites its value.
- `RemoveTagsFromResource` removes the named keys and ignores keys that are not present.
- `ListTagsForResource` accepts either `ResourceArn` or `ResourceArnList`. Each returned
  tag carries its `ResourceArn` only for the `ResourceArnList` form, which is the form
  AWS documents it on.
- Tag keys are 1 to 128 characters, values at most 256, and neither may start with `aws:`
  or `dms:`.
- An ARN that is unparseable, names a different DMS resource type, names another account
  or Region, or names a group that does not exist returns `ResourceNotFoundFault`. Tagging
  is not a cross-account or cross-Region operation on AWS and is not one here either.
- Deleting a group deletes its tags with it.

## Limitations

- **Only the subnet group lifecycle is implemented.** Replication instances, endpoints,
  and replication tasks return `UnknownOperationException`.
- **Tagging covers replication subnet groups only.** The tagging trio is implemented, but
  a `ResourceArn` naming a replication instance, endpoint, or task returns
  `ResourceNotFoundFault` because Floci holds no such resource.
- **No `ModifyReplicationSubnetGroup`.** A subnet change has to be a delete and recreate.

See the [AWS DMS API Reference](https://docs.aws.amazon.com/dms/latest/APIReference/Welcome.html).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_DMS_ENABLED` | `true` | Enable or disable DMS |
