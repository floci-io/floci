# RDS

**Protocol:** Query (XML) for management API + PostgreSQL / MySQL wire protocol for data plane
**Management Endpoint:** `POST http://localhost:4566/`
**Data Endpoint:** `localhost:<proxy-port>` (TCP)

Floci manages real PostgreSQL, MySQL, and MariaDB Docker containers and proxies TCP connections to them, including IAM authentication support.

RDS Data API (`rds-data`) is documented separately because it uses REST JSON routes instead of the RDS Query protocol. See [RDS Data API](rds-data.md).

## Supported Management Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateDBInstance` | Start a new database instance |
| `DescribeDBInstances` | List instances and their connection info |
| `DeleteDBInstance` | Stop and remove an instance |
| `ModifyDBInstance` | Update instance settings |
| `RebootDBInstance` | Restart a database instance |
| `DescribeOrderableDBInstanceOptions` | List deterministic instance class options |
| `CreateDBSubnetGroup` | Create a DB subnet group |
| `DescribeDBSubnetGroups` | List DB subnet groups |
| `ModifyDBSubnetGroup` | Update DB subnet group description and subnet list |
| `DeleteDBSubnetGroup` | Delete a DB subnet group |
| `CreateDBCluster` | Create an Aurora-compatible cluster |
| `DescribeDBClusters` | List clusters |
| `DeleteDBCluster` | Delete a cluster |
| `ModifyDBCluster` | Update cluster settings |
| `CreateDBParameterGroup` | Create a parameter group |
| `DescribeDBParameterGroups` | List parameter groups |
| `DeleteDBParameterGroup` | Delete a parameter group |
| `ModifyDBParameterGroup` | Update parameter group settings |
| `DescribeDBParameters` | List parameters in a group |
| `CreateDBClusterParameterGroup` | - |
| `DescribeDBClusterParameterGroups` | - |
| `DeleteDBClusterParameterGroup` | - |
| `ModifyDBClusterParameterGroup` | - |
| `DescribeDBClusterParameters` | - |
| `DescribeDBSnapshots` | - |
| `DescribeDBProxies` | - |
| `DescribeDBClusterSnapshots` | - |
| `AddTagsToResource` | Add tags to a DB resource |
| `ListTagsForResource` | List tags for a DB resource |
| `RemoveTagsFromResource` | Remove tags from a DB resource |
<!-- floci:actions:end -->

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_RDS_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_RDS_MOCK` | `false` | `true` = metadata only (no Docker container or auth proxy) |
| `FLOCI_SERVICES_RDS_PROXY_BASE_PORT` | `7000` | First host port in the RDS proxy range |
| `FLOCI_SERVICES_RDS_PROXY_MAX_PORT` | `7099` | Last host port in the RDS proxy range |
| `FLOCI_SERVICES_RDS_DEFAULT_POSTGRES_IMAGE` | `postgres:16-alpine` | Docker image for PostgreSQL instances |
| `FLOCI_SERVICES_RDS_DEFAULT_MYSQL_IMAGE` | `mysql:8.0` | Docker image for MySQL instances |
| `FLOCI_SERVICES_RDS_DEFAULT_MARIADB_IMAGE` | `mariadb:11` | Docker image for MariaDB instances |

### Docker Compose

RDS requires the Docker socket and port range exposure. For private registry authentication and other Docker settings see [Docker Configuration](../configuration/docker.md).

```yaml
services:
  floci:
    image: floci/floci:latest
    ports:
      - "4566:4566"
      - "7001-7099:7001-7099"   # RDS proxy ports
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    environment:
      FLOCI_SERVICES_DOCKER_NETWORK: my-project_default
      FLOCI_SERVICES_RDS_PROXY_BASE_PORT: "7001"
```

### Mock mode (CI / tests)

Set `FLOCI_SERVICES_RDS_MOCK=true` when you only need the management API shape: clusters and
instances are registered as `available` immediately, with no Docker container or auth proxy behind
them. Each resource still gets a unique endpoint port, but nothing listens on it.

```yaml
# docker-compose.yml — CI / test environment
services:
  floci:
    image: floci/floci:latest
    environment:
      FLOCI_SERVICES_RDS_MOCK: "true"
```

!!! note "Switching modes over persisted state"
    With a persistent storage mode, changing `FLOCI_SERVICES_RDS_MOCK` between restarts is
    best-effort, as with the other mock-capable services: resources created in real mode and
    deleted under mock leave their containers and volumes behind, and resources created in mock
    mode are restored with fresh, empty containers when loaded in real mode.

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a PostgreSQL instance
aws rds create-db-instance \
  --db-instance-identifier mypostgres \
  --db-instance-class db.t3.micro \
  --engine postgres \
  --master-username admin \
  --master-user-password secret123 \
  --allocated-storage 20 \
  --endpoint-url $AWS_ENDPOINT_URL

# Get connection details
aws rds describe-db-instances \
  --db-instance-identifier mypostgres \
  --query 'DBInstances[0].Endpoint' \
  --endpoint-url $AWS_ENDPOINT_URL

# Connect with psql (use the port returned above)
psql -h localhost -p 7001 -U admin

# Create a MySQL instance
aws rds create-db-instance \
  --db-instance-identifier mymysql \
  --db-instance-class db.t3.micro \
  --engine mysql \
  --master-username root \
  --master-user-password secret123 \
  --allocated-storage 20 \
  --endpoint-url $AWS_ENDPOINT_URL

# Connect with mysql client
mysql -h 127.0.0.1 -P 7002 -u root -psecret123
```

## Supported Engines

| Engine | Default image |
|---|---|
| `postgres` | `postgres:16-alpine` |
| `mysql` | `mysql:8.0` |
| `mariadb` | `mariadb:11` |

Override the image per-instance with the `--engine-version` flag or globally via environment variables.

## Persistence

Each DB instance and cluster gets its own named Docker volume (`floci-rds-{volumeId}`) created
automatically. No configuration is required.

| Scenario | Volume behavior |
|---|---|
| `memory` mode (default) | Volume is removed automatically when the instance is deleted |
| `persistent` / `hybrid` / `wal` | Volume is retained after delete — data survives for manual recovery |

```bash
# CI — ephemeral, volumes cleaned up on each delete
FLOCI_STORAGE_MODE=memory

# Local dev — retain DB data across Floci restarts
FLOCI_STORAGE_MODE=hybrid

# Local dev — also remove volumes immediately on delete
FLOCI_STORAGE_MODE=hybrid
FLOCI_STORAGE_PRUNE_VOLUMES_ON_DELETE=true
```

To use a host bind mount instead of a named volume (advanced), set an absolute path:

```bash
FLOCI_STORAGE_HOST_PERSISTENT_PATH=/absolute/host/path/data
```

!!! note "Docker Desktop on macOS"
    Named volumes work correctly on Docker Desktop for macOS. Bind mounts to paths inside the Floci container are not supported — use named volumes (the default).

## Authentication

The RDS auth proxy validates the master username and password at the proxy layer. All other database users are passed through directly to the backend engine — create them with standard SQL (`CREATE USER`) and connect as normal.

IAM database authentication is also supported. Set `--enable-iam-database-authentication` at instance creation time and use `aws rds generate-db-auth-token` to obtain a token.
