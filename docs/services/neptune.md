# Neptune

**Protocol:** Query (XML) for management API + Gremlin / HTTP / Bolt for data plane
**Management Endpoint:** `POST http://localhost:4566/`
**Data Endpoint:** `localhost:<proxy-port>` (TCP / WebSocket / Bolt)

Floci manages real graph-database Docker containers and proxies connections to them, providing an API-compatible Neptune emulation for local development and testing.

As on AWS, the list form of `DescribeDBClusters` / `DescribeDBInstances` on the `rds`, `docdb` and `neptune` endpoints returns Neptune records together with RDS and DocumentDB ones; the `engine` filter (`neptune`) narrows it.

## Backend engine (`db-type`)

Neptune supports multiple query languages. Floci backs each one with a different container and proxies the matching wire protocol, selected globally via `FLOCI_SERVICES_NEPTUNE_DB_TYPE` (mirroring LocalStack's `NEPTUNE_DB_TYPE`):

| `db-type` | Backend image | Query language | Wire protocol |
|-----------|---------------|----------------|---------------|
| `gremlin` _(default)_ | [Apache TinkerPop Gremlin Server](https://tinkerpop.apache.org/) | Gremlin | WebSocket |
| `neo4j` | [Neo4j](https://neo4j.com/) | openCypher | Bolt |

The proxy is a transparent byte relay, so the host-facing proxy port range is unchanged regardless of engine — only the protocol you connect with differs. Connect to a cluster's proxy port (from the `8182`–`8282` range, returned by `DescribeDBClusters`), not the backend's native port. The Neo4j backend runs with `NEO4J_AUTH=none`, matching Neptune's model of authenticating at the AWS edge (IAM) rather than at the graph protocol; connect your Bolt/openCypher driver with no auth.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateDBCluster` | Create a Neptune cluster (tags, placement, backup, protection and log-export settings included) and start a graph database container |
| `DescribeDBClusters` | List clusters and their connection details |
| `DeleteDBCluster` | Stop and remove a cluster |
| `ModifyDBCluster` | Update cluster settings, including deletion protection, backup retention, log exports and security groups |
| `AddRoleToDBCluster` | Associate an IAM role with a cluster (listed under `AssociatedRoles`) |
| `RemoveRoleFromDBCluster` | Disassociate an IAM role from a cluster |
| `DescribeGlobalClusters` | Always an empty list; global clusters are not modelled |
| `CreateDBInstance` | Add an instance to a cluster |
| `DescribeDBInstances` | List instances |
| `DeleteDBInstance` | Remove an instance from a cluster |
| `ModifyDBInstance` | Update instance settings |
| `ListTagsForResource` | List the tags on a cluster or instance by ARN |
| `AddTagsToResource` | Add or overwrite tags on a cluster or instance |
| `RemoveTagsFromResource` | Remove tags from a cluster or instance by key |
<!-- floci:actions:end -->

## Terraform and the `aws_neptune_cluster` resource

`DescribeDBClusters` returns every field the Terraform AWS provider's `aws_neptune_cluster` resource reads back, with the values the request set and the AWS defaults otherwise, so a `terraform plan` straight after `terraform apply` reports no changes:

- `StorageEncrypted` defaults to `false`, as on AWS; pass `StorageEncrypted=true` (and optionally `KmsKeyId`) to encrypt.
- `AvailabilityZones` echoes the requested zones and otherwise contains the emulator's default zone.
- `BackupRetentionPeriod` (default 1), `PreferredBackupWindow`, `PreferredMaintenanceWindow`, `DBSubnetGroup` (default `default`), `DBClusterParameterGroup` (default `default.neptune<major>.<minor>` for the engine version), `DeletionProtection`, `CopyTagsToSnapshot`, `StorageType`, `EnabledCloudwatchLogsExports`, `VpcSecurityGroups`, `ServerlessV2ScalingConfiguration` and `AssociatedRoles` are all stored and read back.
- Deleting a cluster with `DeletionProtection` enabled fails with `InvalidParameterCombination`, as on AWS.
- Tags given on create are readable through `ListTagsForResource`, and the provider's tag updates go through `AddTagsToResource` and `RemoveTagsFromResource`.
- `DescribeGlobalClusters` answers with an empty list, which is what the provider's read needs when the cluster is not part of a global cluster.

### The `aws_neptune_cluster_instance` resource

`DescribeDBInstances` reads back the fields the provider's `aws_neptune_cluster_instance` resource manages, so an instance plan is clean after apply too:

- `AutoMinorVersionUpgrade` (default `true`), `PromotionTier` (default `1`), `PubliclyAccessible` (default `false`), `AvailabilityZone`, `DBParameterGroups` (default `default.neptune<major>.<minor>`), `DBSubnetGroup` (default `default`), `PreferredBackupWindow` and `PreferredMaintenanceWindow` are stored on create, updated by `ModifyDBInstance` and read back.
- `StorageEncrypted`, `KmsKeyId` and `StorageType` are the cluster's values.
- The first instance created in a cluster is the writer in `DBClusterMembers`; later instances are readers, and deleting the writer promotes the next member.
- Instance tags are readable through `ListTagsForResource` with the instance ARN.

An instance answers on its cluster's port. Terraform's instance `port` attribute defaults to `8182`, so set it to the cluster's port when the cluster does not use `8182`.

### Port

A requested `Port` is honoured when it is free and inside the proxy port range (`8182`-`8282` by default); otherwise the cluster gets the next free port in the range and `DescribeDBClusters` reports that port. Terraform's `port` attribute defaults to `8182`, so the first cluster is a clean match. For a second cluster set `port` explicitly (for example `port = 8183`) so the plan stays clean, because two clusters cannot share one host port.

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `FLOCI_SERVICES_NEPTUNE_ENABLED` | `true` | Enable or disable Neptune |
| `FLOCI_SERVICES_NEPTUNE_PROXY_BASE_PORT` | `8182` | First host port in the Gremlin proxy range |
| `FLOCI_SERVICES_NEPTUNE_PROXY_MAX_PORT` | `8282` | Last host port in the proxy range |
| `FLOCI_SERVICES_NEPTUNE_DB_TYPE` | `gremlin` | Backend engine: `gremlin` (Gremlin/WebSocket) or `neo4j` (openCypher/Bolt) |
| `FLOCI_SERVICES_NEPTUNE_DEFAULT_IMAGE` | `tinkerpop/gremlin-server:3.7.3` | Image used when `db-type=gremlin` |
| `FLOCI_SERVICES_NEPTUNE_DEFAULT_NEO4J_IMAGE` | `neo4j:5-community` | Image used when `db-type=neo4j` |
| `FLOCI_SERVICES_NEPTUNE_DOCKER_NETWORK` | _(host default)_ | Docker network for container connectivity |

### Graph data

When Neptune's records are kept, with `FLOCI_STORAGE_MODE` (or `FLOCI_STORAGE_SERVICES_NEPTUNE_MODE`)
set to `persistent`, `hybrid` or `wal`, each cluster keeps its graph in a Docker volume of its own,
`floci-aws-neptune-<volume-id>` (`floci-aws-<namespace>-neptune-<volume-id>` when
`FLOCI_DOCKER_RESOURCE_NAMESPACE` is set), so the graph outlives the cluster's container. When
`FLOCI_STORAGE_HOST_PERSISTENT_PATH` is an absolute path, the graph goes in
`<host-path>/[<namespace>/]neptune/<volume-id>` on the Docker host instead.

- **`neo4j`:** the volume is Neo4j's data directory, `/data`.
- **`gremlin`:** the volume is the image's `/opt/gremlin-server/data`, and the server's TinkerGraph
  saves the graph there, to `floci-graph.kryo`, when the server stops, and loads it from there when
  it starts. The graph is written only when the server stops, which Floci gives a container 30
  seconds to do before Docker kills it, so a container that is killed rather than stopped loses
  what changed since it started. Under the host path the server runs as
  root, since the directory there belongs to whoever created it rather than to the image's
  `gremlin` user. Floci writes the graph settings into the image's
  `/opt/gremlin-server/conf/tinkergraph-empty.properties`, so an image configured in place of the
  default needs the same layout; one laid out otherwise keeps its graph in memory, and Floci logs a
  warning.

Deleting the cluster keeps its volume, for you to remove with `docker volume rm`, unless
`FLOCI_STORAGE_PRUNE_VOLUMES_ON_DELETE=true`; a directory under the host path is always kept. A
create that fails leaves no volume behind. A cluster created again under a deleted one's identifier
starts on a new, empty volume. In `memory` mode a cluster's graph lasts only as long as its
container.

### Restarts

When Neptune's records are kept, Floci's shutdown stops each cluster's proxy and container and
keeps its volume, and the next start brings them back. Each cluster's port is held for it at once,
so a cluster created meanwhile is never handed it. While its container starts again in the
background, the cluster reports `maintenance` and its instances `starting`. The new container runs
in the engine the cluster's graph was kept in, on its volume, so the graph is there. Its proxy then
listens on the same port again, so clients reconnect to the endpoint they had, and the cluster and
its instances report `available`. With no Docker daemon reachable they come back `available` with
no container, as a cluster created then does. A cluster whose container or proxy does not start is
left with none and its instances `failed`, and is tried again at the next start. A
`DeleteDBCluster` during the restore leaves the cluster `deleting` until its container has started,
and removes it then. `CreateDBInstance` is refused with `InvalidDBClusterStateFault` for a cluster
that is not `available`.

A cluster whose stored port another cluster already holds moves to a free one. A cluster created by
a Floci that did not record its engine comes back in the configured `db-type`, and one whose graph
was not kept in a volume gets a new, empty one.

### Docker Compose

Neptune requires the Docker socket and the Gremlin proxy port range to be exposed. The first cluster claims `PROXY_BASE_PORT`; each additional cluster increments the port.

```yaml
services:
  floci:
    image: floci/floci:latest
    ports:
      - "4566:4566"
      - "8182-8282:8182-8282"   # Neptune Gremlin proxy ports
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    environment:
      FLOCI_SERVICES_DOCKER_NETWORK: my-project_default
```

For private registry authentication and other Docker settings see [Docker Configuration](../configuration/docker.md).

## Examples

### Management API (AWS CLI)

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

# Create a Neptune cluster
aws neptune create-db-cluster \
  --db-cluster-identifier my-neptune \
  --engine neptune

# Get cluster details and Gremlin endpoint port
aws neptune describe-db-clusters \
  --db-cluster-identifier my-neptune \
  --query 'DBClusters[0].{Endpoint:Endpoint,Port:Port}'

# Create an instance in the cluster
aws neptune create-db-instance \
  --db-instance-identifier my-neptune-instance \
  --db-cluster-identifier my-neptune \
  --db-instance-class db.r5.large \
  --engine neptune

# Delete instance and cluster
aws neptune delete-db-instance \
  --db-instance-identifier my-neptune-instance
aws neptune delete-db-cluster \
  --db-cluster-identifier my-neptune \
  --skip-final-snapshot
```

### Graph data plane (Python + gremlin-python)

```python
from gremlin_python.driver import client, serializer

# Use the port returned by DescribeDBClusters
gremlin = client.Client(
    "ws://localhost:8182/gremlin",
    "g",
    message_serializer=serializer.GraphSONSerializersV2d0(),
)

# Add a vertex
gremlin.submit("g.addV('person').property('name', 'Alice')").all().result()

# Query vertices
result = gremlin.submit("g.V().valueMap(true)").all().result()
print(result)

gremlin.close()
```

### Graph data plane — openCypher (Python + neo4j driver)

Start Floci with `FLOCI_SERVICES_NEPTUNE_DB_TYPE=neo4j`, then connect with any Bolt
driver and run openCypher:

```python
from neo4j import GraphDatabase

# Use the port returned by DescribeDBClusters; no auth (NEO4J_AUTH=none)
driver = GraphDatabase.driver("bolt://localhost:8182", auth=None)

with driver.session() as session:
    session.run("CREATE (:Person {name: 'Alice'})")
    count = session.run("MATCH (p:Person) RETURN count(p) AS c").single()["c"]
    print(count)

driver.close()
```

### Management API (Python / boto3)

```python
import boto3

neptune = boto3.client(
    "neptune",
    endpoint_url="http://localhost:4566",
    region_name="us-east-1",
)

cluster = neptune.create_db_cluster(
    DBClusterIdentifier="my-neptune",
    Engine="neptune",
)
print(cluster["DBCluster"]["Endpoint"])
```

## Out of Scope

- IAM database authentication for Gremlin connections.
- Neptune Analytics (vector search, graph analytics).
- Neptune Serverless auto-pause/resume.
- Snapshot and restore operations.
