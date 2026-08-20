# ElastiCache

**Protocol:** Query (XML) for management API + Redis RESP protocol for data plane
**Management Endpoint:** `POST http://localhost:4566/`
**Data Endpoint:** `localhost:<proxy-port>` (TCP)

Floci manages real Valkey/Redis Docker containers and proxies TCP connections to them. This means any Redis client works — including IAM authentication.

## Supported Management Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `ValidateIamAuthToken` | Validate an IAM auth token (data-plane auth) |
| `CreateReplicationGroup` | Start a new Redis/Valkey cluster |
| `DescribeReplicationGroups` | List clusters and their connection info |
| `ModifyReplicationGroup` | - |
| `DeleteReplicationGroup` | Stop and remove a cluster |
| `CreateUser` | Create an ElastiCache IAM user |
| `DescribeUsers` | List ElastiCache users |
| `ModifyUser` | Update user access strings |
| `DeleteUser` | Remove an ElastiCache user |
| `CreateCacheCluster` | - |
| `DescribeCacheClusters` | - |
| `DeleteCacheCluster` | - |
| `CreateCacheSubnetGroup` | Create a cache subnet group |
| `DescribeCacheSubnetGroups` | List cache subnet groups |
| `ModifyCacheSubnetGroup` | Replace a group's description or subnets |
| `DeleteCacheSubnetGroup` | Delete a cache subnet group |
| `CreateCacheParameterGroup` | Create a cache parameter group |
| `DescribeCacheParameterGroups` | List parameter groups, including the AWS defaults |
| `ModifyCacheParameterGroup` | Set parameters on a group |
| `DescribeCacheParameters` | List the parameters set on a group |
| `DeleteCacheParameterGroup` | Delete a cache parameter group |
| `ListTagsForResource` | Tags on a parameter group ARN |
<!-- floci:actions:end -->

### Cache Subnet Groups

A subnet group's VPC and each subnet's availability zone are read from the subnets themselves, as
AWS reads them, so the subnets have to exist in the emulator's EC2 first. Subnets that are unknown,
or that span more than one VPC, are refused the way AWS refuses them.

### Cache Parameter Groups

The `default.*` groups AWS publishes are listed for every family it supports, and cannot be modified
or deleted — AWS refuses those by the identifier rule, since a name it accepts cannot contain a dot.

floci does not carry AWS's per-family catalogue of parameter names, which runs to dozens per family.
It therefore stores whatever parameters a caller sets and reports them with source `user`, rather
than rejecting names a partial catalogue happens to be missing, which would refuse configurations
AWS accepts. `DescribeCacheParameters` returns those parameters; a request for `system` or
`engine-default` parameters returns none, and listings are unpaged.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_ELASTICACHE_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_ELASTICACHE_PROXY_BASE_PORT` | `6379` | First host port in the ElastiCache proxy range |
| `FLOCI_SERVICES_ELASTICACHE_PROXY_MAX_PORT` | `6399` | Last host port in the ElastiCache proxy range |
| `FLOCI_SERVICES_ELASTICACHE_DEFAULT_IMAGE` | `valkey/valkey:8` | Docker image for Redis/Valkey containers |

### Docker Compose

ElastiCache requires the Docker socket and port range exposure. For private registry authentication and other Docker settings see [Docker Configuration](../configuration/docker.md).

```yaml
services:
  floci:
    image: floci/floci:latest
    ports:
      - "4566:4566"
      - "6379-6399:6379-6399"   # ElastiCache proxy ports
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    environment:
      FLOCI_SERVICES_DOCKER_NETWORK: my-project_default
```

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a replication group (starts a Valkey container)
aws elasticache create-replication-group \
  --replication-group-id my-cache \
  --replication-group-description "Dev cache" \
  --endpoint-url $AWS_ENDPOINT_URL

# Get the connection port
PORT=$(aws elasticache describe-replication-groups \
  --replication-group-id my-cache \
  --query 'ReplicationGroups[0].NodeGroups[0].PrimaryEndpoint.Port' \
  --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

# Connect with redis-cli
redis-cli -h localhost -p $PORT ping

# Use from your application
redis-cli -h localhost -p $PORT set mykey "hello"
redis-cli -h localhost -p $PORT get mykey

# Delete the cluster
aws elasticache delete-replication-group \
  --replication-group-id my-cache \
  --endpoint-url $AWS_ENDPOINT_URL
```

## IAM Authentication

Floci supports ElastiCache IAM auth token validation. Create a user with access strings and validate tokens the same way real ElastiCache RBAC works.

```bash
# Create an ElastiCache user
aws elasticache create-user \
  --user-id alice \
  --user-name alice \
  --engine redis \
  --access-string "on ~* +@all" \
  --no-no-password-required \
  --endpoint-url $AWS_ENDPOINT_URL
```
