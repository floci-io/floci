# MemoryDB

**Protocol:** JSON 1.1 (`X-Amz-Target: AmazonMemoryDB.*`) for management API + Redis RESP protocol for data plane
**Management Endpoint:** `POST http://localhost:4566/`
**Data Endpoint:** `localhost:<proxy-port>` (TCP)

Floci manages real Valkey/Redis Docker containers and proxies TCP connections to them, so any Redis client works — including IAM-style authentication. MemoryDB is Redis wire-compatible, so it reuses Floci's ElastiCache RESP proxy and SigV4 validator.

## Mock Mode

Set `FLOCI_SERVICES_MEMORYDB_MOCK=true` to manage clusters as control-plane-only resources — no Redis container is started. The management API (`CreateCluster`, `DescribeClusters`, tagging, etc.) behaves normally and returns a `ClusterEndpoint` of `<hostname>:6379` (defaults to `localhost:6379`, controlled by `FLOCI_HOSTNAME`), but there is no live data plane to connect to. This is intended for Infrastructure-as-Code tools like Terraform and OpenTofu running in environments without a Docker socket, where you only need the AWS resources to exist and return consistent attributes.

## Supported Management Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateCluster` | Start a new MemoryDB (Redis/Valkey) cluster |
| `DescribeClusters` | List clusters and their connection info |
| `UpdateCluster` | Update mutable cluster attributes (e.g. description) |
| `DeleteCluster` | Stop and remove a cluster |
| `CreateUser` | - |
| `DescribeUsers` | - |
| `DeleteUser` | - |
| `CreateACL` | - |
| `DescribeACLs` | - |
| `DeleteACL` | - |
| `ListTags` | List tags for a cluster |
| `TagResource` | Add tags to a cluster |
| `UntagResource` | Remove tags from a cluster |
<!-- floci:actions:end -->

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_MEMORYDB_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_MEMORYDB_MOCK` | `false` | Track clusters without starting a real Redis container. Useful for IaC tools (Terraform/OpenTofu) when no Docker socket is available |
| `FLOCI_SERVICES_MEMORYDB_PROXY_BASE_PORT` | `6400` | First host port in the MemoryDB proxy range |
| `FLOCI_SERVICES_MEMORYDB_PROXY_MAX_PORT` | `6419` | Last host port in the MemoryDB proxy range |
| `FLOCI_SERVICES_MEMORYDB_DEFAULT_IMAGE` | `valkey/valkey:8` | Docker image for Redis/Valkey containers |

### Docker Compose

MemoryDB requires the Docker socket and port range exposure. For private registry authentication and other Docker settings see [Docker Configuration](../configuration/docker.md).

```yaml
services:
  floci:
    image: floci/floci:latest
    ports:
      - "4566:4566"
      - "6400-6419:6400-6419"   # MemoryDB proxy ports
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    environment:
      FLOCI_SERVICES_DOCKER_NETWORK: my-project_default
```

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a cluster (starts a Valkey container)
aws memorydb create-cluster \
  --cluster-name my-memdb \
  --node-type db.t4g.small \
  --acl-name open-access \
  --endpoint-url $AWS_ENDPOINT_URL

# Get the connection address and port
aws memorydb describe-clusters \
  --cluster-name my-memdb \
  --query 'Clusters[0].ClusterEndpoint' \
  --endpoint-url $AWS_ENDPOINT_URL

# Connect with redis-cli (use the Port from ClusterEndpoint)
redis-cli -h localhost -p 6400 ping

# Delete the cluster
aws memorydb delete-cluster \
  --cluster-name my-memdb \
  --endpoint-url $AWS_ENDPOINT_URL
```
