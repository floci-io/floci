# AWS Global Accelerator

**Protocol:** JSON 1.1 (`X-Amz-Target: GlobalAccelerator_V20180706.<Action>`)
**Endpoint:** `http://localhost:4566/` (SigV4 service `globalaccelerator`)

Floci emulates the Global Accelerator management plane: standard accelerators, listeners,
endpoint groups, accelerator attributes and resource tags. The anycast data plane is not
emulated, so the static IP addresses and DNS names Floci hands out route no traffic.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateAccelerator` | Create a standard accelerator; it reports `DEPLOYED` immediately |
| `DescribeAccelerator` | Read an accelerator with its static IP sets and DNS name |
| `UpdateAccelerator` | Rename an accelerator, enable or disable it, or change its IP address type |
| `DeleteAccelerator` | Delete a disabled accelerator that has no listeners |
| `ListAccelerators` | List every accelerator in the calling account |
| `DescribeAcceleratorAttributes` | Read an accelerator's flow log attributes |
| `UpdateAcceleratorAttributes` | Set an accelerator's flow log attributes |
| `CreateListener` | Create a TCP or UDP listener on an accelerator |
| `DescribeListener` | Read a listener |
| `UpdateListener` | Change a listener's port ranges, protocol or client affinity |
| `DeleteListener` | Delete a listener that has no endpoint groups |
| `ListListeners` | List an accelerator's listeners |
| `CreateEndpointGroup` | Create one Region's endpoint group on a listener |
| `DescribeEndpointGroup` | Read an endpoint group and its endpoint health |
| `UpdateEndpointGroup` | Replace an endpoint group's endpoints or change its health check settings |
| `DeleteEndpointGroup` | Delete an endpoint group |
| `ListEndpointGroups` | List a listener's endpoint groups |
| `AddEndpoints` | Add endpoints to an endpoint group |
| `RemoveEndpoints` | Remove endpoints from an endpoint group |
| `TagResource` | Tag an accelerator, listener or endpoint group |
| `UntagResource` | Remove tags from an accelerator, listener or endpoint group |
| `ListTagsForResource` | List the tags on an accelerator, listener or endpoint group |
<!-- floci:actions:end -->

## ARNs and Regions

Global Accelerator is a global service, so its ARNs carry an empty region segment and child
ARNs extend the parent:

```
arn:aws:globalaccelerator::000000000000:accelerator/{acceleratorId}
arn:aws:globalaccelerator::000000000000:accelerator/{acceleratorId}/listener/{listenerId}
arn:aws:globalaccelerator::000000000000:accelerator/{acceleratorId}/listener/{listenerId}/endpoint-group/{groupId}
```

`ListListeners` and `ListEndpointGroups` are scoped by that ARN prefix, which is why a
listener created under one accelerator is never visible under another.

The AWS SDK homes the service in `us-west-2` and signs there. Nothing in Floci is keyed by
the signing region, so a caller that signs under a different Region sees the same
accelerators. Only the account partitions the state. `EndpointGroupRegion` is stored as
given and is unrelated to the signing region.

## State and Health

`Accelerator.Status` is `DEPLOYED` and every `EndpointDescription.HealthState` is `HEALTHY`
from the first read, so SDK and Terraform waiters complete on their first poll. No state
transition is modelled.

Each accelerator is assigned two static IPv4 addresses from the ranges Global Accelerator
advertises, exposed in `IpSets`, plus a `DnsName` of the documented form
`a{16 hex}.awsglobalaccelerator.com`. A `DUAL_STACK` accelerator additionally carries an IPv6
`IpSet` and a `DualStackDnsName`. Passing `IpAddresses` on `CreateAccelerator` (the BYOIP
case) pins those addresses instead.

## Constraints

- **DeleteAccelerator** returns `AcceleratorNotDisabledException` while `Enabled` is true, and
  `AssociatedListenerFoundException` while the accelerator still has listeners. Terraform
  disables the accelerator before deleting it, which is the documented order.
- **DeleteListener** returns `AssociatedEndpointGroupFoundException` while the listener still
  has endpoint groups.
- **CreateEndpointGroup** returns `EndpointGroupAlreadyExistsException` when the listener
  already has an endpoint group in that Region. A listener has at most one group per Region.
- **CreateListener** and **UpdateListener** return `InvalidPortRangeException` for a port
  range outside 1 to 65535 or with `FromPort` greater than `ToPort`.
- **UpdateAcceleratorAttributes** returns `InvalidArgumentException` when the update would
  leave `FlowLogsEnabled` true with no `FlowLogsS3Bucket`.
- Enum and range members are validated against the API model: `IpAddressType`, `Protocol`,
  `ClientAffinity`, `HealthCheckProtocol`, `TrafficDialPercentage` (0 to 100),
  `HealthCheckIntervalSeconds` (10 to 30), `ThresholdCount` (1 to 10) and `Weight` (0 to 255).
  A value outside its range returns `InvalidArgumentException`.

## Defaults

| Field | Default |
|---|---|
| `Accelerator.Enabled` | `true` |
| `Accelerator.IpAddressType` | `IPV4` |
| `Listener.ClientAffinity` | `NONE` |
| `EndpointGroup.TrafficDialPercentage` | `100.0` |
| `EndpointGroup.HealthCheckPort` | the listener's first `FromPort` |
| `EndpointGroup.HealthCheckProtocol` | `TCP` |
| `EndpointGroup.HealthCheckPath` | `/` |
| `EndpointGroup.HealthCheckIntervalSeconds` | `30` |
| `EndpointGroup.ThresholdCount` | `3` |
| `EndpointConfiguration.Weight` | `128` |
| `EndpointConfiguration.ClientIPPreservationEnabled` | `true` for an Application Load Balancer endpoint, `false` otherwise |

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_GLOBALACCELERATOR_ENABLED` | `true` | Enable or disable the service |

## Not Yet Supported

These operations return `UnknownOperationException` rather than a stub success:

- Custom routing accelerators (`CreateCustomRoutingAccelerator`, `CreateCustomRoutingListener`,
  `CreateCustomRoutingEndpointGroup`, `AddCustomRoutingEndpoints`, `AllowCustomRoutingTraffic`,
  `ListCustomRoutingPortMappings`, and the rest of that family)
- BYOIP address pools (`ProvisionByoipCidr`, `AdvertiseByoipCidr`, `DeprovisionByoipCidr`,
  `WithdrawByoipCidr`, `ListByoipCidrs`). `CreateAccelerator` still honours an `IpAddresses`
  list, so a BYOIP address can be pinned without provisioning a pool.
- Cross-account attachments (`CreateCrossAccountAttachment` and friends)

Two more gaps are worth calling out:

- `IdempotencyToken` is accepted but not deduplicated, so repeating a create produces a
  second resource.
- The list operations ignore `MaxResults` and return every resource in one page with no
  `NextToken`. A paginator terminates after the first call.
- `AddEndpoints` returns the endpoint group's whole endpoint list rather than only the
  endpoints the call added.

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws globalaccelerator create-accelerator --name edge-accelerator

aws globalaccelerator create-listener \
  --accelerator-arn arn:aws:globalaccelerator::000000000000:accelerator/... \
  --protocol TCP \
  --port-ranges FromPort=80,ToPort=80

aws globalaccelerator create-endpoint-group \
  --listener-arn arn:aws:globalaccelerator::000000000000:accelerator/.../listener/... \
  --endpoint-group-region us-west-2 \
  --endpoint-configurations EndpointId=i-0123456789abcdef0,Weight=128

aws globalaccelerator list-tags-for-resource \
  --resource-arn arn:aws:globalaccelerator::000000000000:accelerator/...
```
