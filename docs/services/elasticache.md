# ElastiCache

**Protocol:** Query (XML) for management API + Redis RESP protocol for data plane
**Management Endpoint:** `POST http://localhost:4566/`
**Data Endpoint:** `localhost:<proxy-port>` (TCP)

Floci manages real Valkey/Redis Docker containers and proxies TCP connections to them. This means any Redis client works — including IAM authentication.

## Supported Management Actions

| Action | Description |
|---|---|
| `CreateReplicationGroup` | Start a new Redis/Valkey cluster |
| `DescribeReplicationGroups` | List clusters and their connection info |
| `DeleteReplicationGroup` | Stop and remove a cluster |
| `CreateUser` | Create an ElastiCache IAM user |
| `DescribeUsers` | List ElastiCache users |
| `ModifyUser` | Update user access strings |
| `DeleteUser` | Remove an ElastiCache user |
| `ValidateIamAuthToken` | Validate an IAM auth token (data-plane auth) |

## Configuration

```yaml
floci:
  services:
    elasticache:
      enabled: true
      proxy-base-port: 6379
      proxy-max-port: 6399
      default-image: "valkey/valkey:8"
```

### Docker Compose

ElastiCache requires the Docker socket and port range exposure. For private registry authentication and other Docker settings see [Docker Configuration](../configuration/docker.md).

```yaml
services:
  floci:
    image: hectorvent/floci:latest
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
