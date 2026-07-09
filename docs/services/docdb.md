# DocumentDB

**Protocol:** Query (XML) for the management API
**Management Endpoint:** `POST http://localhost:4566/` with `Action=` param
**Data Endpoint:** the `Endpoint` and `Port` returned by `DescribeDBClusters` (MongoDB wire protocol)

Floci emulates Amazon DocumentDB by managing real [MongoDB](https://www.mongodb.com/) Docker containers behind an RDS-shaped control plane. DocumentDB is MongoDB-compatible, so the cluster endpoint returned by `DescribeDBClusters` speaks the MongoDB wire protocol and works with any standard MongoDB driver.

> **Always read the host and port from `DescribeDBClusters`** rather than assuming a fixed port. MongoDB listens on `27017` *inside* the container, but the port you connect to depends on how Floci runs:
>
> - **Real mode, Floci on the host** (default): the container's `27017` is published on a **dynamically assigned host port**. `DescribeDBClusters.Port` returns that mapped port.
> - **Real mode, Floci itself in a container** (shared Docker network): the endpoint is the container host on `27017`.
> - **Mock mode** (`FLOCI_SERVICES_DOCDB_MOCK=true`): no container is started; the cluster reports `localhost:27017`.

The management API shares the RDS Query endpoint (`POST /` with an `Action=` parameter). Requests are routed to DocumentDB when `Engine=docdb` is supplied, or when the referenced cluster/instance is a known DocumentDB resource.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateDBCluster` | Create a DocumentDB cluster and start a MongoDB container |
| `DescribeDBClusters` | List clusters and their connection details |
| `DescribeDBClusterSnapshots` | - |
| `DeleteDBCluster` | Stop and remove a cluster (must have no instances) |
| `ModifyDBCluster` | Update engine version or IAM auth setting |
| `CreateDBInstance` | Add an instance to a cluster |
| `DescribeDBInstances` | List instances |
| `DeleteDBInstance` | Remove an instance from a cluster |
| `ModifyDBInstance` | Update instance class or IAM auth setting |
<!-- floci:actions:end -->

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `FLOCI_SERVICES_DOCDB_ENABLED` | `true` | Enable or disable DocumentDB |
| `FLOCI_SERVICES_DOCDB_MOCK` | `false` | Mock mode: skip the container and return a placeholder endpoint |
| `FLOCI_SERVICES_DOCDB_DEFAULT_IMAGE` | `mongo:7.0` | MongoDB Docker image |
| `FLOCI_SERVICES_DOCDB_DOCKER_NETWORK` | _(host default)_ | Docker network for container connectivity |

Mock mode is useful for control-plane tests that do not need a live database; the cluster reports `localhost:27017` and no container is started.

### Docker Compose

DocumentDB needs the Docker socket so it can launch MongoDB containers. Each cluster's container is published on a dynamically assigned host port, returned by `DescribeDBClusters`.

```yaml
services:
  floci:
    image: floci/floci:latest
    ports:
      - "4566:4566"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    environment:
      FLOCI_SERVICES_DOCDB_DOCKER_NETWORK: my-project_default
```

For private registry authentication and other Docker settings see [Docker Configuration](../configuration/docker.md).

## Examples

### Management API (AWS CLI)

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

# Create a DocumentDB cluster (starts a MongoDB container)
aws docdb create-db-cluster \
  --db-cluster-identifier my-docdb \
  --engine docdb \
  --master-username admin \
  --master-user-password secret99

# Get the cluster endpoint and port
aws docdb describe-db-clusters \
  --db-cluster-identifier my-docdb \
  --query 'DBClusters[0].{Endpoint:Endpoint,Port:Port}'

# Add an instance to the cluster
aws docdb create-db-instance \
  --db-instance-identifier my-docdb-instance \
  --db-cluster-identifier my-docdb \
  --db-instance-class db.r5.large \
  --engine docdb

# Delete instance and cluster
aws docdb delete-db-instance \
  --db-instance-identifier my-docdb-instance
aws docdb delete-db-cluster \
  --db-cluster-identifier my-docdb \
  --skip-final-snapshot
```

### Data plane (Python + pymongo)

```python
from pymongo import MongoClient

# Read the host and port from DescribeDBClusters — the port is dynamic
# in real mode and is NOT guaranteed to be 27017.
host, port = "localhost", 32768  # e.g. DBClusters[0].Endpoint / .Port
client = MongoClient(f"mongodb://admin:secret99@{host}:{port}/")

db = client["app"]
db["people"].insert_one({"name": "Alice"})

for doc in db["people"].find():
    print(doc)

client.close()
```

### Management API (Python / boto3)

```python
import boto3

docdb = boto3.client(
    "docdb",
    endpoint_url="http://localhost:4566",
    region_name="us-east-1",
)

cluster = docdb.create_db_cluster(
    DBClusterIdentifier="my-docdb",
    Engine="docdb",
    MasterUsername="admin",
    MasterUserPassword="secret99",
)
print(cluster["DBCluster"]["Endpoint"])
```

## Out of Scope

- IAM database authentication for MongoDB connections (the flag is stored and echoed back, but connections are not SigV4-proxied).
- TLS / `--tls` enforced connections.
- Snapshot creation and restore (`DescribeDBClusterSnapshots` returns an empty stub result; snapshots are not modeled).
- Global clusters, replicas, and read-scaling beyond a single MongoDB container per cluster.
- Parameter groups, subnet groups, and maintenance windows.
