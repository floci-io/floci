# AWS Resource Groups

**Protocol:** REST JSON, signed with `resource-groups`

**Endpoint:** `http://localhost:4566`

Floci supports service-configured EC2 capacity reservation pools. This is a separate service from the Resource Groups Tagging API.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateGroup` | Create a capacity reservation pool with stored configuration and tags |
| `GetGroup` | Read a group by name or ARN |
| `DeleteGroup` | Delete the group and membership, preserving its reservations |
| `GroupResources` | Add verified active reservations |
| `ListGroupResources` | Read stored membership with scoped pagination |
<!-- floci:actions:end -->

The standard REST routes are `POST /groups`, `/get-group`, `/delete-group`, `/group-resources`, and `/list-group-resources`. Get, delete and list also accept the deprecated `GroupName` selector. Supplying both `Group` and `GroupName` is rejected.

With IAM enforcement enabled, signed management operations are checked against their `resource-groups:` actions and the selected group ARN. Signed S3 requests to buckets with the same names retain S3 routing.

## Capacity pools

Create a group with this public AWS service configuration:

```json
[
  {"Type": "AWS::EC2::CapacityReservationPool"},
  {
    "Type": "AWS::ResourceGroups::Generic",
    "Parameters": [
      {"Name": "allowed-resource-types", "Values": ["AWS::EC2::CapacityReservation"]}
    ]
  }
]
```

Groups use the caller's account and region. Their ARNs use the region's AWS partition. Names, descriptions, configuration, creation tags and membership are stored through the normal Floci storage backend.

`GroupResources` accepts one to ten resource ARNs. Each successful member must be an actual active EC2 capacity reservation in the same account, region and partition. RAM-shared reservations are not accepted; reservations must be owned by the group account. Missing, foreign and inactive reservations appear in `Failed`; they are never inserted. Repeated adds preserve one membership. Unexpected EC2 lookup errors remain errors and do not partially change membership.

`ListGroupResources` returns both `Resources.Identifier` and the deprecated `ResourceIdentifiers` representation. It supports the capacity-reservation `resource-type` filter and pages of one to fifty entries. Tokens bind the group generation and filter to an ordered membership snapshot, so later additions do not change an existing traversal. Tokens expire after fifteen minutes without a successful scoped read; the instance retains at most 256 tokens and evicts the least recently used token at capacity. Tokens remain retryable until expiry, eviction, group deletion or emulator restart/reset. Invalid tokens return `BadRequestException`.

Deleting a group removes its membership and tokens. It does not cancel or delete the EC2 reservations.

## Scope

Only the five operations and capacity-pool configuration above are implemented. Dynamic tag/CloudFormation queries, other service configurations, independent tag-update operations and `UngroupResources` are not supported. Unsupported configurations are rejected. Membership does not allocate physical capacity. EC2 launches that target the group consume matching available member reservations; the launch preference controls On-Demand fallback. No asynchronous membership transition is simulated: accepted additions complete synchronously and `Pending` is empty.

Group state follows the configured Floci storage mode. Pagination tokens are in memory and are intentionally invalidated by restart/reset.

## Configuration

| Variable | Default | Description |
| --- | --- | --- |
| `FLOCI_SERVICES_RESOURCE_GROUPS_ENABLED` | `true` | Enable the Resource Groups service |
| `FLOCI_STORAGE_SERVICES_RESOURCE_GROUPS_MODE` | global storage mode | Storage mode for groups and membership |
| `FLOCI_STORAGE_SERVICES_RESOURCE_GROUPS_FLUSH_INTERVAL_MS` | `5000` | Storage flush interval |

The public wire contracts and capacity-pool example are documented in the AWS references for
[CreateGroup](https://docs.aws.amazon.com/ARG/latest/APIReference/API_CreateGroup.html),
[GroupResources](https://docs.aws.amazon.com/ARG/latest/APIReference/API_GroupResources.html), and
[ListGroupResources](https://docs.aws.amazon.com/ARG/latest/APIReference/API_ListGroupResources.html).

Requests use botocore's modeled field, tag-pattern and size constraints; unknown fields are ignored. In the supported service-configured capacity-pool mode, valid `Criticality`, `Owner` and `DisplayName` values are rejected as unsupported parameters, matching live AWS.
