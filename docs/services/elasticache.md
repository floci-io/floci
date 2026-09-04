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
| `CreateReplicationGroup` | Start a new Redis/Valkey cluster; `AtRestEncryptionEnabled`, `KmsKeyId` (resolved to the key ARN), `SnapshotRetentionLimit`, `SnapshotWindow` and `Tags` are kept and returned, with the group `ARN` |
| `DescribeReplicationGroups` | List clusters and their connection info |
| `ModifyReplicationGroup` | Modify `SnapshotRetentionLimit` and `SnapshotWindow`, and the associated user groups |
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

### Cluster Mode

`CreateReplicationGroup` provisions a real sharded Valkey cluster when the request asks for one:
`NumNodeGroups` greater than 1, a `default.*.cluster.on` parameter group, a custom parameter group
with `cluster-enabled` set to `yes`, or `ClusterMode=enabled`.

Floci starts one `--cluster-enabled` container per node — `NumNodeGroups × (1 + ReplicasPerNodeGroup)`
in total — forms the cluster (config epochs, MEET, slot assignment, replica attachment), and fronts
each node with its own auth-proxy port from the proxy port range. Nodes announce Floci's configured
hostname as their preferred endpoint (`cluster-announce-hostname` with
`cluster-preferred-endpoint-type hostname`, plus `cluster-announce-client-ipv4`/
`cluster-announce-port`), so `CLUSTER SLOTS`, `CLUSTER SHARDS` and `MOVED`/`ASK` redirects hand
clients the same name the `ConfigurationEndpoint` reports, while the cluster bus keeps using the
container network. Any cluster-aware Redis/Valkey client works against the reported
`ConfigurationEndpoint`.

Because clients must resolve the announced name to follow redirects, a `FLOCI_HOSTNAME` that only
resolves inside Floci's Docker network (such as the Compose service name `floci`) breaks
cluster-aware clients connecting from outside it. Set
`FLOCI_SERVICES_ELASTICACHE_CLUSTER_ANNOUNCE_HOSTNAME` to a universally resolvable name in that
case — the shipped `docker-compose.yml` uses `localhost.floci.io`, which public DNS resolves to
`127.0.0.1` on the host (reaching the published proxy ports) while the Compose network alias and
Floci's embedded DNS resolve it to the Floci container from inside Docker. Cluster-mode groups
then announce that name and report it as their `ConfigurationEndpoint`.

With `persistent`, `hybrid` or `wal` storage, cluster-mode groups are re-provisioned from their
persisted topology on startup: containers are restarted, the cluster is re-formed (caches restart
empty, as on any Floci restart) and each node's proxy port is re-reserved. Ports are re-reserved
and groups marked `creating` before Floci reports ready; the container restarts and cluster
formation run in the background so a slow Docker daemon cannot delay readiness, and each group
flips to `available` once its data plane is back. A group whose data plane cannot be brought back
is reported with status `create-failed` instead of `available`, and its member clusters answer
`DescribeCacheClusters` with `restore-failed` (`CacheClusterStatus` has no `create-failed` value).

`DescribeReplicationGroups` reports the topology honestly: `ClusterEnabled`, one `NodeGroup` per
shard with its `Slots`, `NodeGroupMembers`, and `MemberClusters`. Each member also answers
`DescribeCacheClusters` (as on AWS), which is what terraform-provider-aws reads node type, engine
version and port from.

Cluster mode requires a Valkey 8.1+ image (the default `valkey/valkey:8` qualifies) for
`cluster-announce-client-ipv4` support. Each node consumes one port from the proxy range, so size
`FLOCI_SERVICES_ELASTICACHE_PROXY_BASE_PORT`/`_MAX_PORT` to the number of nodes you need.

```bash
aws elasticache create-replication-group \
  --replication-group-id my-sharded-cache \
  --replication-group-description "Sharded dev cache" \
  --engine valkey \
  --cache-parameter-group-name default.valkey8.cluster.on \
  --num-node-groups 2 \
  --replicas-per-node-group 1 \
  --endpoint-url $AWS_ENDPOINT_URL

aws elasticache describe-replication-groups \
  --replication-group-id my-sharded-cache \
  --query 'ReplicationGroups[0].ConfigurationEndpoint' \
  --endpoint-url $AWS_ENDPOINT_URL

redis-cli -c -h localhost -p <configuration-endpoint-port> set mykey "hello"
```

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

A replication group that names a parameter group is refused with `CacheParameterGroupNotFound` when
no such group exists, and a parameter group still referenced by a replication group cannot be
deleted: `DeleteCacheParameterGroup` answers `InvalidCacheParameterGroupState` until that
replication group is gone. The reference counts from the moment `CreateReplicationGroup` accepts
the name, not from when the group is stored, so a delete that lands while that create is still
provisioning its container is refused too.

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
