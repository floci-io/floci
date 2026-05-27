# Neptune

**Protocol:** Query (XML) for management API + Gremlin / HTTP for data plane
**Management Endpoint:** `POST http://localhost:4566/`
**Data Endpoint:** `localhost:<proxy-port>` (TCP / WebSocket)

Floci manages real [Apache TinkerPop Gremlin Server](https://tinkerpop.apache.org/) Docker containers and proxies connections to them, providing an API-compatible Neptune emulation for local development and testing.

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
| `FLOCI_SERVICES_NEPTUNE_PROXY_MAX_PORT` | `8282` | Last host port in the Gremlin proxy range |
| `FLOCI_SERVICES_NEPTUNE_DEFAULT_IMAGE` | `tinkerpop/gremlin-server:3.7.3` | Gremlin Server Docker image |
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
