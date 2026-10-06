# AWS Database Migration Service (DMS)

**Protocol:** AWS JSON 1.1  
**Signing name:** `dms`

Floci emulates the replication subnet group, endpoint, replication instance and
replication task lifecycles, which is what `aws_dms_replication_subnet_group`,
`aws_dms_endpoint`, `aws_dms_s3_endpoint`, `aws_dms_replication_instance` and
`aws_dms_replication_task` need to plan, apply and destroy. Subnets are resolved against
the emulated EC2 service, so the VPC and Availability Zones a group reports are the ones
those subnets actually have. No data is migrated: the resources are control-plane state.

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
| `CreateEndpoint` | Creates a source or target endpoint; secret members are accepted and never returned. |
| `DescribeEndpoints` | Lists endpoints, filtered by `endpoint-arn`, `endpoint-type`, `endpoint-id` or `engine-name`. |
| `ModifyEndpoint` | Updates an endpoint; settings structures merge unless `ExactSettings` is true. |
| `DeleteEndpoint` | Deletes an endpoint that no replication task uses. |
| `CreateReplicationInstance` | Creates a replication instance, `available` immediately. |
| `DescribeReplicationInstances` | Lists replication instances, filtered by `replication-instance-arn`, `replication-instance-id`, `replication-instance-class` or `engine-version`. |
| `ModifyReplicationInstance` | Updates a replication instance, applied immediately. |
| `DeleteReplicationInstance` | Deletes a replication instance that no replication task uses. |
| `CreateReplicationTask` | Creates a replication task between two existing endpoints on an existing instance, `ready` immediately. |
| `DescribeReplicationTasks` | Lists replication tasks, filtered by `replication-task-arn`, `replication-task-id`, `migration-type`, `endpoint-arn` or `replication-instance-arn`. |
| `ModifyReplicationTask` | Updates a replication task that is not running. |
| `DeleteReplicationTask` | Deletes a replication task that is not running. |
| `StartReplicationTask` | Moves a task to `running`. |
| `StopReplicationTask` | Moves a running task to `stopped`. |
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

### Endpoints, replication instances and replication tasks

- Each resource gets an ARN of the form `arn:aws:dms:<region>:<account>:<type>:<id>`, where
  `<type>` is `endpoint`, `rep` or `task` and `<id>` is 26 characters of the base32
  alphabet, or the request's `ResourceIdentifier` when one is given. Update and delete
  calls address the resource by that ARN.
- Identifiers must begin with a letter, contain only ASCII letters, digits and hyphens,
  and not end with a hyphen or contain two consecutive hyphens (63 characters for an
  instance, 255 otherwise). A replication instance identifier is stored lowercase, as AWS
  documents; endpoint and task identifiers keep their case. A duplicate identifier returns
  `ResourceAlreadyExistsFault`.
- A describe whose `Filters` match nothing returns `ResourceNotFoundFault`, which the
  Terraform provider reads as "gone". Without filters, no resources is an empty list.
  Filter values compare case-insensitively. Describes paginate on `MaxRecords` and
  `Marker` as subnet groups do.
- **Endpoints** are `active` from creation. `EndpointType` is accepted lowercase and returned
  uppercase (`SOURCE`, `TARGET`), as AWS does. `SslMode` defaults to `none`. Engine settings
  structures (`MySQLSettings`, `S3Settings` and the rest) are stored as sent and returned
  as stored, except that every member the DMS model types as a secret (`Password`,
  `SaslPassword`, `AuthPassword` and so on) is dropped, because DMS never returns them.
  `ModifyEndpoint` merges a settings structure into the stored one member by member, or
  replaces it when `ExactSettings` is true.
- **Replication instances** are `available` from creation and stay so through a modify. A
  named `ReplicationSubnetGroupIdentifier` must exist (otherwise `ResourceNotFoundFault`)
  and is returned as the full `ReplicationSubnetGroup` structure; without one the instance
  reports the `default` group. Defaults: `AllocatedStorage` 50, `EngineVersion` `3.5.4`,
  `PubliclyAccessible` true, `MultiAZ` false, `AutoMinorVersionUpgrade` true,
  `NetworkType` `IPV4`, `AvailabilityZone` `<region>a`. `ModifyReplicationInstance` applies
  every change at once whatever `ApplyImmediately` says.
- **Replication tasks** need existing source and target endpoints and an existing instance
  (otherwise `ResourceNotFoundFault`). `TableMappings` and `ReplicationTaskSettings` must be
  JSON objects and are returned exactly as sent. A task is `ready` from creation;
  `StartReplicationTask` makes it `running` and `StopReplicationTask` makes it `stopped`.
  Stopping a task that is not running returns `InvalidResourceStateFault` with "is
  currently not running", the message the Terraform provider treats as already stopped.
  Modifying or deleting a running task returns `InvalidResourceStateFault`.
- Deleting an endpoint or instance that a task references, or a subnet group that an
  instance uses, returns `InvalidResourceStateFault`. A delete returns the resource with
  status `deleting`, and it is gone from the next describe.

### Tagging

`Tags` on every create are stored with the resource, and the tagging trio works against
the resource's ARN. For a subnet group that is
`arn:aws:dms:<region>:<account>:subgrp:<identifier>`; DMS does not return that ARN from
`DescribeReplicationSubnetGroups`, so Terraform builds it client side and Floci parses it
back the same way. Endpoints, instances and tasks are tagged through the ARN their create
returned.

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
- Deleting a resource deletes its tags with it.

## Limitations

- **No migration runs.** A started task stays `running` until stopped; a full-load task
  never finishes on its own, and `ReplicationTaskStats`, `StopReason` and
  `LastFailureMessage` are not reported.
- **Values AWS fills in are not invented.** An endpoint does not report
  `EngineDisplayName`, `ExternalId` or the settings structure AWS derives from top-level
  connection fields, and a task without `ReplicationTaskSettings` reports none rather than
  the AWS defaults. Instances report no private or public IP addresses.
- **`CdcStartTime` is ignored.** `CreateReplicationTask`, `ModifyReplicationTask` and
  `StartReplicationTask` accept it, but it is not stored or returned. `CdcStartPosition` and
  `CdcStopPosition` are stored by create and modify, and ignored by `StartReplicationTask`.
- **Not implemented:** `TestConnection`, `DescribeConnections`, `MoveReplicationTask`,
  `DescribeEndpointTypes`, `DescribeOrderableReplicationInstances`, replication configs
  (`aws_dms_replication_config`, serverless DMS), event subscriptions, certificates and the
  `WithoutSettings` flag of `DescribeReplicationTasks`.
- **No `ModifyReplicationSubnetGroup`.** A subnet change has to be a delete and recreate.

See the [AWS DMS API Reference](https://docs.aws.amazon.com/dms/latest/APIReference/Welcome.html).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_DMS_ENABLED` | `true` | Enable or disable DMS |
