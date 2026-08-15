# AWS Global Accelerator

**Protocol:** JSON 1.1 (`X-Amz-Target: GlobalAccelerator_V20180706.<Action>`)
**Endpoint:** `http://localhost:4566/`
**Credential scope:** `globalaccelerator`

## Supported Actions

| Action | Description |
|---|---|
| `CreateAccelerator` | Create a standard accelerator; returns `DEPLOYED` immediately |
| `DescribeAccelerator` | Get an accelerator including its static IP sets and DNS name |
| `UpdateAccelerator` | Rename, enable/disable, or change the IP address type |
| `DeleteAccelerator` | Delete a disabled accelerator with no listeners |
| `ListAccelerators` | List accelerators in the calling account |
| `DescribeAcceleratorAttributes` | Get the accelerator's flow-log attributes |
| `UpdateAcceleratorAttributes` | Set the accelerator's flow-log attributes |
| `CreateListener` | Create a TCP or UDP listener on an accelerator |
| `DescribeListener` | Get a listener |
| `UpdateListener` | Change port ranges, protocol, or client affinity |
| `DeleteListener` | Delete a listener with no endpoint groups |
| `ListListeners` | List an accelerator's listeners |
| `CreateEndpointGroup` | Create a listener's endpoint group for one Region |
| `DescribeEndpointGroup` | Get an endpoint group and its endpoint health |
| `UpdateEndpointGroup` | Replace endpoints or change health-check settings |
| `DeleteEndpointGroup` | Delete an endpoint group |
| `ListEndpointGroups` | List a listener's endpoint groups |
| `AddEndpoints` | Add endpoints to an endpoint group |
| `RemoveEndpoints` | Remove endpoints from an endpoint group |
| `TagResource` | Tag an accelerator, listener, or endpoint group |
| `UntagResource` | Remove tags from an accelerator, listener, or endpoint group |
| `ListTagsForResource` | List tags on an accelerator, listener, or endpoint group |

## ARNs

Global Accelerator is a global service, so its ARNs carry an **empty region segment**, and
child ARNs extend the parent:

```
arn:aws:globalaccelerator::000000000000:accelerator/{acceleratorId}
arn:aws:globalaccelerator::000000000000:accelerator/{acceleratorId}/listener/{listenerId}
arn:aws:globalaccelerator::000000000000:accelerator/{acceleratorId}/listener/{listenerId}/endpoint-group/{groupId}
```

`ListListeners` and `ListEndpointGroups` are scoped by that ARN prefix, which is why a
listener created under one accelerator is never visible under another.

## State and Health

`Accelerator.Status` is `DEPLOYED` and every `EndpointDescription.HealthState` is `HEALTHY`
from the first read, so SDK and Terraform waiters complete on their first poll. No state
transition is modelled.

Each accelerator is assigned two static IPv4 addresses from the ranges Global Accelerator
advertises, exposed in `IpSets`, plus a `DnsName` of the documented form
`a{16 hex}.awsglobalaccelerator.com`. A `DUAL_STACK` accelerator additionally carries an IPv6
`IpSet` and a `DualStackDnsName`. Passing `IpAddresses` on `CreateAccelerator` (the BYOIP
case) pins those addresses instead. The addresses and DNS names resolve nowhere — Global
Accelerator's anycast data plane is not emulated.

## Constraints

- **DeleteAccelerator** returns `AcceleratorNotDisabledException` while `Enabled` is true, and
  `AssociatedListenerFoundException` while the accelerator still has listeners. Terraform
  disables the accelerator before deleting it, which is the documented order.
- **DeleteListener** returns `AssociatedEndpointGroupFoundException` while the listener still
  has endpoint groups.
- **CreateEndpointGroup** returns `EndpointGroupAlreadyExistsException` when the listener
  already has an endpoint group in that Region — a listener has at most one group per Region.
- **CreateListener** / **UpdateListener** return `InvalidPortRangeException` for a port range
  outside 1–65535 or with `FromPort` greater than `ToPort`.

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
- Idempotency: `IdempotencyToken` is accepted but not deduplicated, so repeating a create
  produces a second resource
- Pagination tokens on list operations

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
