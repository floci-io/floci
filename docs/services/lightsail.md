# Amazon Lightsail

Floci exposes Amazon Lightsail through the AWS JSON 1.1 protocol:

- Endpoint: `POST /`
- Target prefix: `Lightsail_20161128.*`
- Signing name: `lightsail`

The local implementation keeps Lightsail state in Floci storage and is designed for SDK and AWS CLI compatibility on common development workflows.

## Supported local workflows

- Instances: create, get, list, start, stop, reboot, delete, state lookup, and public port management
- Disks: create, get, list, attach, detach, and delete
- Static IPs: allocate, get, list, attach, detach, and release
- Key pairs: create, import, get, list, delete, and download default key pair
- Discovery: regions, blueprints, bundles, active names, operations, and operations for resource
- Tags: tag and untag local Lightsail resources

Read-only list operations for cloud-side Lightsail resource families that are not locally implemented yet return empty AWS-shaped lists. Cloud-only provisioning workflows such as container services, distributions, managed databases, load balancers, buckets, and snapshots are recognized from the AWS Lightsail API model and return an explicit `UnsupportedOperation` error instead of a custom endpoint or non-AWS response shape.

## Examples

```bash
aws --endpoint-url http://localhost:4566 lightsail get-blueprints
```

```bash
aws --endpoint-url http://localhost:4566 lightsail create-instances \
  --instance-names web-a \
  --availability-zone us-east-1a \
  --blueprint-id ubuntu_22_04 \
  --bundle-id nano_3_0
```

```bash
aws --endpoint-url http://localhost:4566 lightsail allocate-static-ip \
  --static-ip-name web-ip

aws --endpoint-url http://localhost:4566 lightsail attach-static-ip \
  --static-ip-name web-ip \
  --instance-name web-a
```

## Persistence

Lightsail resources use `StorageFactory` and follow the global storage mode by default. Configure the service with:

```yaml
floci:
  services:
    lightsail:
      enabled: true
  storage:
    services:
      lightsail:
        flush-interval-ms: 5000
```
