# Docker Compose

## Minimal Setup

For most services (SSM, SQS, SNS, S3, DynamoDB, Lambda, API Gateway, Cognito, KMS, Kinesis, Secrets Manager, CloudFormation, Step Functions, IAM, STS, EventBridge, Scheduler, CloudWatch) a single port is enough:

```yaml title="docker-compose.yml"
services:
  floci:
    image: hectorvent/floci:latest
    ports:
      - "4566:4566"
    volumes:
      - ./data:/app/data
      - ./init/start.d:/etc/floci/init/start.d:ro
      - ./init/stop.d:/etc/floci/init/stop.d:ro
```

## Full Setup (with ElastiCache and RDS)

ElastiCache and RDS work by proxying TCP connections to real Docker containers (Valkey/Redis, PostgreSQL, MySQL). Those containers' ports must be reachable from your host, so additional port ranges must be exposed:

```yaml title="docker-compose.yml"
services:
  floci:
    image: hectorvent/floci:latest
    ports:
      - "4566:4566"         # All AWS API calls
      - "6379-6399:6379-6399"  # ElastiCache / Redis proxy ports
      - "7001-7099:7001-7099"  # RDS / PostgreSQL + MySQL proxy ports
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock  # required for Lambda, ElastiCache, RDS
      - ./data:/app/data
    environment:
      FLOCI_SERVICES_DOCKER_NETWORK: my-project_default  # (1)
```

1. Set this to the Docker network name that your compose project creates (usually `<project-name>_default`). Floci uses it to attach spawned Lambda / ElastiCache / RDS containers to the same network.

!!! warning "Docker socket"
    Lambda, ElastiCache, and RDS require access to the Docker socket (`/var/run/docker.sock`) to spawn and manage containers. If you don't use these services, you can omit that volume.

## Multi-container networking

By default, Floci embeds `localhost` in response URLs — for example, SQS queue
URLs look like `http://localhost:4566/000000000000/my-queue`. This is fine when
your application runs on the same machine, but breaks inside Docker Compose:
other containers cannot reach `localhost` of the Floci container.

Set `FLOCI_HOSTNAME` to the Compose service name so that Floci uses that name
in every URL it generates:

```yaml title="docker-compose.yml"
services:
  floci:
    image: hectorvent/floci:latest
    ports:
      - "4566:4566"
    environment:
      FLOCI_HOSTNAME: floci   # (1)

  app:
    build: .
    environment:
      AWS_ENDPOINT_URL: http://floci:4566
    depends_on:
      - floci
```

1. Must match the Compose service name so other containers can resolve it.

With this setting Floci returns URLs like
`http://floci:4566/000000000000/my-queue` that other containers in the same
network can reach.

This affects any response field that embeds the endpoint hostname:

- SQS — `QueueUrl`
- SNS — `TopicArn` callback URLs and subscription `SubscriptionArn` endpoints
- Any pre-signed URL or callback that is generated from `floci.base-url`

!!! tip "CI pipelines"
    In GitHub Actions or GitLab CI where both your app and Floci run as
    `services`, set `FLOCI_HOSTNAME` to the service name (e.g. `floci`) and
    point your SDK at `http://floci:4566`.

## Initialization Hooks

Hook scripts can be mounted into the container to run custom setup and teardown logic:

```yaml
services:
  floci:
    image: hectorvent/floci:latest
    ports:
      - "4566:4566"
    volumes:
      - ./init/start.d:/etc/floci/init/start.d:ro
      - ./init/stop.d:/etc/floci/init/stop.d:ro
```

See [Initialization Hooks](./initialization-hooks.md) for execution behavior and configuration details.

## Persistence

By default Floci stores all data in memory — data is lost on restart. To persist data to disk, set the storage path and enable persistent mode:

```yaml
services:
  floci:
    image: hectorvent/floci:latest
    ports:
      - "4566:4566"
    volumes:
      - ./data:/app/data
    environment:
      FLOCI_STORAGE_MODE: persistent
      FLOCI_STORAGE_PERSISTENT_PATH: /app/data
```

### Using Named Volumes

Instead of bind-mounting a local directory, you can use Docker named volumes to keep your project directory clean:

```yaml
services:
  floci:
    image: hectorvent/floci:latest
    ports:
      - "4566:4566"
    volumes:
      - floci-data:/app/data
    environment:
      FLOCI_STORAGE_MODE: persistent
      FLOCI_STORAGE_PERSISTENT_PATH: /app/data

volumes:
  floci-data:
```

Named volumes are managed entirely by Docker and won't create files in your repository. This works with both the JVM and native images.

## Environment Variables Reference

All `application.yml` options can be overridden via environment variables using the `FLOCI_` prefix with underscores replacing dots and dashes:

| Environment variable | Default | Description |
|---|---|---|
| `FLOCI_HOSTNAME` | _(none)_ | Hostname embedded in response URLs (SQS, SNS, pre-signed). Set to the Compose service name in multi-container setups |
| `FLOCI_DEFAULT_REGION` | `us-east-1` | AWS region reported in ARNs |
| `FLOCI_DEFAULT_ACCOUNT_ID` | `000000000000` | AWS account ID used in ARNs |
| `FLOCI_STORAGE_MODE` | `memory` | Global storage mode (`memory`, `persistent`, `hybrid`, `wal`) |
| `FLOCI_STORAGE_PERSISTENT_PATH` | `./data` | Directory for persistent storage |
| `FLOCI_SERVICES_LAMBDA_DOCKER_HOST` | `unix:///var/run/docker.sock` | Docker host for Lambda containers |
| `FLOCI_SERVICES_LAMBDA_EPHEMERAL` | `false` | Remove Lambda containers after each invocation |
| `FLOCI_SERVICES_LAMBDA_DEFAULT_MEMORY_MB` | `128` | Default Lambda memory allocation |
| `FLOCI_SERVICES_LAMBDA_DEFAULT_TIMEOUT_SECONDS` | `3` | Default Lambda timeout |
| `FLOCI_SERVICES_LAMBDA_CODE_PATH` | `./data/lambda-code` | Where Lambda ZIPs are stored |
| `FLOCI_SERVICES_ELASTICACHE_PROXY_BASE_PORT` | `6379` | First ElastiCache proxy port |
| `FLOCI_SERVICES_ELASTICACHE_PROXY_MAX_PORT` | `6399` | Last ElastiCache proxy port |
| `FLOCI_SERVICES_ELASTICACHE_DEFAULT_IMAGE` | `valkey/valkey:8` | Default Redis/Valkey Docker image |
| `FLOCI_SERVICES_RDS_PROXY_BASE_PORT` | `7001` | First RDS proxy port |
| `FLOCI_SERVICES_RDS_PROXY_MAX_PORT` | `7099` | Last RDS proxy port |
| `FLOCI_SERVICES_RDS_DEFAULT_POSTGRES_IMAGE` | `postgres:16-alpine` | Default PostgreSQL image |
| `FLOCI_SERVICES_RDS_DEFAULT_MYSQL_IMAGE` | `mysql:8.0` | Default MySQL image |
| `FLOCI_SERVICES_RDS_DEFAULT_MARIADB_IMAGE` | `mariadb:11` | Default MariaDB image |
| `FLOCI_SERVICES_DOCKER_NETWORK` | _(none)_ | Docker network to attach spawned containers |
| `FLOCI_AUTH_VALIDATE_SIGNATURES` | `false` | Verify AWS request signatures |

## CI Pipeline Example

```yaml title=".github/workflows/test.yml"
services:
  floci:
    image: hectorvent/floci:latest
    ports:
      - "4566:4566"

steps:
  - name: Run tests
    env:
      AWS_ENDPOINT: http://localhost:4566
      AWS_DEFAULT_REGION: us-east-1
      AWS_ACCESS_KEY_ID: test
      AWS_SECRET_ACCESS_KEY: test
    run: mvn test
```
