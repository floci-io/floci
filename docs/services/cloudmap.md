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
`GetOperation` call returns a completed operation without polling. No real DNS
records or Route 53 hosted zones are created — public and private DNS
namespaces are assigned a synthetic `HostedZoneId` so SDK and CLI clients can
exercise the full Cloud Map control flow locally.

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

- Real DNS resolution or Route 53 hosted zone / record set creation.
- Route 53 health checks backing `HealthCheckConfig` (custom health status is stored, not actively probed).
- Cross-region namespace and service discovery.
