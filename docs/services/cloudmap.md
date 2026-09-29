# AWS Cloud Map

**Protocol:** JSON 1.1
**Header:** `X-Amz-Target: Route53AutoNaming_v20170314.<Action>`
**Endpoint prefix:** `servicediscovery`

Floci emulates the AWS Cloud Map (service discovery) control plane in-process:
namespaces, services, and registered instances are stored as account-aware
domain objects, and discovery queries resolve against that in-memory state.

Asynchronous operations (`CreateHttpNamespace`, `CreatePublicDnsNamespace`,
`CreatePrivateDnsNamespace`, `DeleteNamespace`, `RegisterInstance`,
`DeregisterInstance`) apply their effect synchronously and return an operation
id. The operation reaches `SUCCESS` immediately by default, so a follow-up
`GetOperation` call returns a completed operation without polling. No Route 53
hosted zones are created: public and private DNS namespaces are assigned a
synthetic `HostedZoneId` so SDK and CLI clients can exercise the full Cloud Map
control flow locally.

### DNS resolution

A DNS namespace is answerable, not just storable. Floci's embedded DNS server
resolves `<service>.<namespace>` to the `AWS_INSTANCE_IPV4` of the instances
registered under that service, one A record each, so a container that uses Floci
as its resolver reaches its peers by name. Instances registered directly through
`RegisterInstance` and instances an ECS service registers through its
`serviceRegistries` resolve the same way.

Which instances answer follows Route 53: the healthy ones while any instance is
healthy, and all of them when none is, so a service whose instances have all gone
unhealthy still resolves rather than disappearing from DNS. `MULTIVALUE` returns up to
eight instances; `WEIGHTED` returns one randomly selected instance, with equal weights.
Names inside a DNS namespace stay local when no instance has a usable address:
the resolver answers negatively instead of forwarding them to upstream DNS.
An absent service name receives NXDOMAIN; a service with registered instances
and only other record types receives NOERROR with no A answers. DNS service
names are unique without regard to case, as in AWS Cloud Map.

A and AAAA records use `AWS_INSTANCE_IPV4` and `AWS_INSTANCE_IPV6`, respectively,
and carry the TTL of the corresponding `DnsConfig.DnsRecords` entry. CNAME services
answer with `AWS_INSTANCE_CNAME`, including when queried for A or AAAA. SRV queries
return priority 1, weight 1, `AWS_INSTANCE_PORT`, and
`<InstanceId>.<service>.<namespace>`. The SRV target also answers A and/or AAAA
queries when those address attributes are registered, using the SRV TTL. The SRV
service name itself has no address answer. Floci uses a 60-second fallback for older stored services without a
usable A record TTL.
New `CreateService` requests reject TTL values outside AWS's 0 to 2147483647
range. Names owned by Floci's embedded DNS server also use 60 seconds.

SRV registrations must produce an encodable DNS target: ASCII labels of at most
63 bytes and a full name of at most 253 characters. Floci returns `InvalidInput`
before storing an unencodable target, rather than accepting a registration that
cannot resolve. The API's general 64-character instance identifier limit is not
a guarantee that an identifier can be used as a single DNS label; non-SRV
registrations are unaffected. This early validation is an emulator behavior,
not a verified claim about AWS's error timing. Older mixed A/CNAME configurations
still resolve their explicitly configured A records.

UDP replies respect the legacy 512-byte limit or the client's EDNS payload size,
capped at 4096 bytes. Oversized answers are truncated at whole-record boundaries
and carry the DNS truncation flag. The embedded server remains UDP-only; TCP
retry is not implemented.

Two limits are worth knowing. `HTTP` namespaces do not resolve, matching AWS,
where they are reachable only through `DiscoverInstances`. The embedded DNS server only runs
when Floci itself runs inside Docker, so name resolution is available to
containers, not to processes on the host.

## Supported Operations

| Operation | Notes |
|-----------|-------|
| `CreateHttpNamespace` | Creates an `HTTP` namespace; returns an operation id |
| `CreatePublicDnsNamespace` | Creates a `DNS_PUBLIC` namespace with a synthetic `HostedZoneId` |
| `CreatePrivateDnsNamespace` | Creates a `DNS_PRIVATE` namespace; requires `Vpc`, assigns a `HostedZoneId` |
| `GetNamespace` | Returns a namespace by `Id` |
| `ListNamespaces` | Lists namespaces in the current region |
| `DeleteNamespace` | Deletes an empty namespace; fails with `ResourceInUse` if it still has services |
| `CreateService` | Creates a service (optionally under a namespace), tracks revision and instance count |
| `GetService` | Returns a service by `Id` |
| `ListServices` | Lists services, optionally filtered by `NamespaceId` |
| `DeleteService` | Deletes a service with no instances; fails with `ResourceInUse` otherwise |
| `RegisterInstance` | Registers an instance under a service; requires `InstanceId` and `Attributes`, bumps service revision |
| `DeregisterInstance` | Removes a registered instance and bumps service revision |
| `GetInstance` | Returns a registered instance by `ServiceId` and `InstanceId` |
| `ListInstances` | Lists instances registered under a service |
| `GetInstancesHealthStatus` | Returns health status keyed by instance id, with optional `Instances` filter |
| `DiscoverInstances` | Resolves instances by `NamespaceName` + `ServiceName`, with `HealthStatus` and `QueryParameters` filtering |
| `DiscoverInstancesRevision` | Returns the current revision for a discovered service |
| `GetOperation` | Returns an operation by `Id` |
| `ListOperations` | Lists operations with optional `STATUS`, `TYPE`, `NAMESPACE_ID`, and `SERVICE_ID` filters |
| `TagResource` | Adds tags to a namespace or service ARN |
| `UntagResource` | Removes tag keys from a namespace or service ARN |
| `ListTagsForResource` | Lists tags for a namespace or service ARN |

`DiscoverInstances` supports the `HEALTHY`, `UNHEALTHY`, `HEALTHY_OR_ELSE_ALL`,
and `ALL` health filters, and matches instances whose attributes contain every
key/value pair supplied in `QueryParameters`.

## CloudFormation

Floci provisions these resource types:

- `AWS::ServiceDiscovery::HttpNamespace`
- `AWS::ServiceDiscovery::PrivateDnsNamespace`
- `AWS::ServiceDiscovery::PublicDnsNamespace`
- `AWS::ServiceDiscovery::Service`

`Ref` returns the namespace or service id. `Fn::GetAtt` exposes `Id` and `Arn` on all of them,
`HostedZoneId` on a DNS namespace, and `Name` on a service.

`Description`, `DnsConfig`, `HealthCheckConfig`, and `Tags` update in place and keep the id. Only
the TTLs in `DnsConfig` can change, as on AWS: an update that changes anything else in it, such as
the record types or `RoutingPolicy`, fails with `InvalidInput`. A change to a createOnly property
(`Name`, and `Vpc` on a private DNS namespace; `Name`, `NamespaceId`, `Type`, or
`HealthCheckCustomConfig` on a service) replaces the resource: the new one is created and the old
one is deleted once the stack update completes. As on AWS, a replacement that keeps the same name
fails, since the name is still taken.

`ServiceAttributes` and `Properties.DnsProperties.SOA` are accepted but ignored.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_CLOUDMAP_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_CLOUDMAP_OPERATION_COMPLETION_DELAY_SECONDS` | `0` | Delay before an async operation transitions from `PENDING` to `SUCCESS`; `0` completes immediately |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

aws servicediscovery create-http-namespace --name floci-demo

aws servicediscovery list-namespaces

aws servicediscovery create-service \
  --name backend \
  --namespace-id ns-xxxxxxxxxxxxxxxxxxxx

aws servicediscovery register-instance \
  --service-id srv-xxxxxxxxxxxxxxxxxxxx \
  --instance-id i-0123456789 \
  --attributes AWS_INSTANCE_IPV4=10.0.0.10

aws servicediscovery discover-instances \
  --namespace-name floci-demo \
  --service-name backend
```

```python
import boto3

client = boto3.client(
    "servicediscovery",
    endpoint_url="http://localhost:4566",
    region_name="us-east-1",
)

ns = client.create_http_namespace(Name="floci-demo")
client.get_operation(OperationId=ns["OperationId"])  # Status == "SUCCESS"

namespace_id = client.list_namespaces()["Namespaces"][0]["Id"]
service = client.create_service(Name="backend", NamespaceId=namespace_id)

client.register_instance(
    ServiceId=service["Service"]["Id"],
    InstanceId="i-0123456789",
    Attributes={"AWS_INSTANCE_IPV4": "10.0.0.10"},
)

found = client.discover_instances(NamespaceName="floci-demo", ServiceName="backend")
print(found["Instances"])
```

## Related Docs

- [Services overview](index.md)
- [Route53](route53.md)
- [AWS CLI & SDK setup](../getting-started/aws-setup.md)
- [Environment variables](../configuration/environment-variables.md)

## Out of Scope

- Route 53 hosted zone / record set creation.
- Route 53 health checks backing `HealthCheckConfig` (custom health status is stored, not actively probed).
- `AWS_INIT_HEALTH_STATUS` and `AWS_EC2_INSTANCE_ID` on `RegisterInstance`. Every instance registers `HEALTHY`, which is AWS's own initial status when `AWS_INIT_HEALTH_STATUS` is absent, so only a caller that explicitly asks for `UNHEALTHY` sees a difference.
- Cross-region namespace and service discovery. A DNS query carries no region, so a name resolves through whichever namespace copy holds instances.
