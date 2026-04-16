# Ports Reference

## Port Overview

| Port / Range | Protocol | Purpose |
|---|---|---|
| `4566` | HTTP | All AWS API calls (every service) |
| `5100–5199` | HTTP | ECR Registry sidecar (`floci-ecr-registry`) — bound directly by that container, not the floci container |
| `6379–6399` | TCP | ElastiCache Redis proxy, one port per replication group |
| `7001–7099` | TCP | RDS proxy, one port per DB instance |
| `9200–9299` | HTTP | Lambda Runtime API (internal: consumed by spawned Lambda containers, not host-mapped) |
| `9400–9499` | HTTP | OpenSearch proxy, reserved for `opensearch.mode: real` (not yet available) |

## Port 4566 — AWS API

Every AWS SDK and CLI call goes to port `4566`. This includes all management-plane operations: creating queues, putting items, invoking lambdas, etc.

```bash
aws s3 ls --endpoint-url http://localhost:4566
aws sqs list-queues --endpoint-url http://localhost:4566
aws lambda list-functions --endpoint-url http://localhost:4566
```

## Ports 6379–6399 — ElastiCache

When you create an ElastiCache replication group, Floci starts a Valkey/Redis Docker container and creates a TCP proxy on the next available port in the `6379–6399` range.

Connect to a Redis cluster:

```bash
# First create the replication group
aws elasticache create-replication-group \
  --replication-group-id my-redis \
  --replication-group-description "dev cache" \
  --endpoint-url http://localhost:4566

# Then connect directly on the proxied port
redis-cli -h localhost -p 6379
```

The port assigned to a replication group is returned in the `PrimaryEndpoint.Port` field of the `DescribeReplicationGroups` response.

!!! note
    The proxy range starts at `6379` by default (matching the standard Redis port for the first cluster). Configure the range with `FLOCI_SERVICES_ELASTICACHE_PROXY_BASE_PORT` and `FLOCI_SERVICES_ELASTICACHE_PROXY_MAX_PORT`.

## Ports 7001–7099 — RDS

When you create an RDS DB instance, Floci starts a PostgreSQL or MySQL Docker container and creates a TCP proxy on the next available port in the `7001–7099` range.

```bash
# Create a PostgreSQL instance
aws rds create-db-instance \
  --db-instance-identifier mydb \
  --db-instance-class db.t3.micro \
  --engine postgres \
  --master-username admin \
  --master-user-password secret \
  --endpoint-url http://localhost:4566

# Connect using the proxied port (returned in DescribeDBInstances Endpoint.Port)
psql -h localhost -p 7001 -U admin
```

!!! note
    Configure the range with `FLOCI_SERVICES_RDS_PROXY_BASE_PORT` and `FLOCI_SERVICES_RDS_PROXY_MAX_PORT`.

## Ports 9200–9299, Lambda Runtime API (internal)

Floci binds a Lambda Runtime API port in the `9200–9299` range for each warm Lambda container to poll. These ports are consumed by containers Floci spawns itself on the shared Docker network, so they do not need to be mapped to the host. Configure the range with `FLOCI_SERVICES_LAMBDA_RUNTIME_API_BASE_PORT` and `FLOCI_SERVICES_LAMBDA_RUNTIME_API_MAX_PORT`.

## Ports 9400–9499, OpenSearch (reserved)

This range is reserved for OpenSearch `mode: real`, which will spin up an OpenSearch Docker container per domain and proxy data-plane traffic on the next available port in `9400–9499`. Real mode is not yet implemented: the default `mock` mode exposes only the management API on port `4566` and uses no proxy port. See [OpenSearch](../services/opensearch.md) for current status.

!!! note
 When real mode lands, configure the range with `FLOCI_SERVICES_OPENSEARCH_PROXY_BASE_PORT` and `FLOCI_SERVICES_OPENSEARCH_PROXY_MAX_PORT`.

## Ports 5100–5199 — ECR Registry

ECR is backed by a separate `registry:2` sidecar container (`floci-ecr-registry`) that Floci starts lazily on the first ECR API call. That container binds its own host port directly — **do not** add `5100-5199` to the floci service's `ports` in Docker Compose. Doing so pre-allocates those ports on the floci container and prevents the sidecar from binding them, causing the ECR registry to fail to start.

```
host:5100  ←──  floci-ecr-registry (registry:2 container, started by Floci)
                       ↑
                 EcrRegistryManager manages this container's lifecycle
```

`docker login localhost:5100` works because the sidecar has a direct host port binding. No docker-compose port mapping is needed.

!!! warning "Do not expose ECR port range on the floci service"
    Adding `- "5100-5199:5100-5199"` to the floci service ports will conflict with the ECR sidecar container and break `docker push` / `docker pull`.

## Exposing Ports in Docker Compose

When running Floci inside Docker, expose these ranges to the host so your application (or Redis/psql CLI) can connect. **ECR ports are excluded** — they are handled by the registry sidecar:

```yaml
services:
  floci:
    image: hectorvent/floci:latest
    ports:
      - "4566:4566"           # All AWS API calls
      - "6379-6399:6379-6399" # ElastiCache / Redis proxy ports
      - "7001-7099:7001-7099" # RDS / PostgreSQL + MySQL proxy ports
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
```

If your application runs inside the same Docker Compose network, it can reach Floci directly on container port `4566` — the host port mapping is only needed for tools running on the host (CLI, IDE plugins, etc.).