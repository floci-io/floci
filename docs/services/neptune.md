# Neptune

**Protocol:** Query (XML) for management API + Gremlin / HTTP / Bolt for data plane
**Management Endpoint:** `POST http://localhost:4566/`
**Data Endpoint:** `localhost:<proxy-port>` (TCP / WebSocket / Bolt)

Floci manages real graph-database Docker containers and proxies connections to them, providing an API-compatible Neptune emulation for local development and testing.

## Backend engine (`db-type`)

Neptune supports multiple query languages. Floci backs each one with a different container and proxies the matching wire protocol, selected globally via `FLOCI_SERVICES_NEPTUNE_DB_TYPE` (mirroring LocalStack's `NEPTUNE_DB_TYPE`):

| `db-type` | Backend image | Query language | Wire protocol |
|-----------|---------------|----------------|---------------|
| `gremlin` _(default)_ | [Apache TinkerPop Gremlin Server](https://tinkerpop.apache.org/) | Gremlin | WebSocket |
| `neo4j` | [Neo4j](https://neo4j.com/) | openCypher | Bolt |

The proxy is a transparent byte relay, so the host-facing proxy port range is unchanged regardless of engine — only the protocol you connect with differs. Connect to a cluster's proxy port (from the `8182`–`8282` range, returned by `DescribeDBClusters`), not the backend's native port. The Neo4j backend runs with `NEO4J_AUTH=none`, matching Neptune's model of authenticating at the AWS edge (IAM) rather than at the graph protocol; connect your Bolt/openCypher driver with no auth.

## Supported Actions

| Action | Description |
|--------|-------------|
| `CreateDBCluster` | Create a Neptune cluster and start a Gremlin Server container |
| `DescribeDBClusters` | List clusters and their connection details |
| `DeleteDBCluster` | Stop and remove a cluster |
| `ModifyDBCluster` | Update cluster settings |
| `CreateDBInstance` | Add an instance to a cluster |
| `DescribeDBInstances` | List instances |
| `DeleteDBInstance` | Remove an instance from a cluster |
| `ModifyDBInstance` | Update instance settings |

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
