# RDS

**Protocol:** Query (XML) for management API + PostgreSQL / MySQL wire protocol for data plane
**Management Endpoint:** `POST http://localhost:4566/`
**Data Endpoint:** `localhost:<proxy-port>` (TCP)

Floci manages real PostgreSQL, MySQL, and MariaDB Docker containers and proxies TCP connections to them, including IAM authentication support.

## Supported Management Actions

| Action | Description |
|---|---|
| `CreateDBInstance` | Start a new database instance |
| `DescribeDBInstances` | List instances and their connection info |
| `DeleteDBInstance` | Stop and remove an instance |
| `ModifyDBInstance` | Update instance settings |
| `RebootDBInstance` | Restart a database instance |
| `CreateDBCluster` | Create an Aurora-compatible cluster |
| `DescribeDBClusters` | List clusters |
| `DeleteDBCluster` | Delete a cluster |
| `ModifyDBCluster` | Update cluster settings |
| `CreateDBParameterGroup` | Create a parameter group |
| `DescribeDBParameterGroups` | List parameter groups |
| `DeleteDBParameterGroup` | Delete a parameter group |
| `ModifyDBParameterGroup` | Update parameter group settings |
| `DescribeDBParameters` | List parameters in a group |

## Configuration

```yaml
floci:
  services:
    rds:
      enabled: true
      proxy-base-port: 7001
      proxy-max-port: 7099
      default-postgres-image: "postgres:16-alpine"
      default-mysql-image: "mysql:8.0"
      default-mariadb-image: "mariadb:11"
```

### Docker Compose

RDS requires the Docker socket and port range exposure. For private registry authentication and other Docker settings see [Docker Configuration](../configuration/docker.md).

```yaml
services:
  floci:
    image: hectorvent/floci:latest
    ports:
      - "4566:4566"
      - "7001-7099:7001-7099"   # RDS proxy ports
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    environment:
      FLOCI_SERVICES_DOCKER_NETWORK: my-project_default
      FLOCI_SERVICES_RDS_PROXY_BASE_PORT: "7001"
```

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
