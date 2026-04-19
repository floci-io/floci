# application.yml Reference

All settings can be provided as YAML (mounted as a config file or in `src/main/resources/application.yml`) or overridden via environment variables (`FLOCI_` prefix, dots/dashes replaced with underscores).

## URL configuration

Floci generates absolute URLs for certain response fields (SQS queue URLs, SNS
subscription endpoints, pre-signed S3 URLs). Two settings control the hostname
embedded in those URLs:

| Setting | Env variable | Default | Description |
|---|---|---|---|
| `floci.base-url` | `FLOCI_BASE_URL` | `http://localhost:4566` | Full base URL used to build response URLs. Change the scheme, host, and port together. |
| `floci.hostname` | `FLOCI_HOSTNAME` | _(none)_ | Override only the hostname in `base-url`. Useful in Docker Compose where `localhost` is unreachable from other containers. |

When `floci.hostname` is set it replaces just the host portion of `base-url`,
leaving the scheme and port unchanged. Setting `FLOCI_HOSTNAME: floci` is
equivalent to changing `base-url` from `http://localhost:4566` to
`http://floci:4566`.

**Example — Docker Compose multi-container setup:**

```yaml
environment:
  FLOCI_HOSTNAME: floci   # matches the compose service name
```

See [Docker Compose — Multi-container networking](./docker-compose.md#multi-container-networking) for a full example.

## Full Reference

The block below mirrors `src/main/resources/application.yml`, it's the effective set of keys Floci ships with. Some supported keys are omitted here (for example `floci.init-hooks.*`) but can still be provided via YAML or environment variables.

```yaml
floci:
  max-request-size: 512              # Max HTTP request body size in MB
  base-url: "http://localhost:4566"  # Used to build response URLs (SQS QueueUrl, SNS endpoints, etc.)
  # hostname: ""                     # When set, overrides the host in base-url for multi-container Docker
  default-region: us-east-1
  default-account-id: "000000000000"

  storage:
    mode: memory                      # memory | persistent | hybrid | wal
    persistent-path: ./data
    wal:
      compaction-interval-ms: 30000
    services:
      ssm:
        flush-interval-ms: 5000
      dynamodb:
        flush-interval-ms: 5000
      sns:
        flush-interval-ms: 5000
      lambda:
        flush-interval-ms: 5000
      cloudwatchlogs:
        flush-interval-ms: 5000
      cloudwatchmetrics:
        flush-interval-ms: 5000
      secretsmanager:
        flush-interval-ms: 5000
      acm:
        flush-interval-ms: 5000
      opensearch:
        flush-interval-ms: 5000

  auth:
    validate-signatures: false               # Set to true to enforce AWS SigV4 validation
    presign-secret: local-emulator-secret    # HMAC secret for S3 pre-signed URL verification

  services:
    ssm:
      enabled: true
      max-parameter-history: 5               # Max versions kept per parameter

    sqs:
      enabled: true
      default-visibility-timeout: 30         # Seconds
      max-message-size: 262144               # Bytes (256 KB)

    s3:
      enabled: true
      default-presign-expiry-seconds: 3600

    dynamodb:
      enabled: true

    sns:
      enabled: true

    lambda:
      enabled: true
      ephemeral: false                        # true = remove container after each invocation
      default-memory-mb: 128
      default-timeout-seconds: 3
      docker-host: unix:///var/run/docker.sock
      runtime-api-base-port: 9200             # Port range for Lambda Runtime API
      runtime-api-max-port: 9299
      code-path: ./data/lambda-code           # Where ZIP archives are stored
      poll-interval-ms: 1000
      container-idle-timeout-seconds: 300     # Remove idle containers after this
      region-concurrency-limit: 1000          # Concurrent executions ceiling per region
      unreserved-concurrency-min: 100         # Minimum unreserved capacity PutFunctionConcurrency must leave

    apigateway:
      enabled: true

    apigatewayv2:
      enabled: true

    iam:
      enabled: true
      enforcement-enabled: false        # Set to true to enforce IAM policies on all requests

    elasticache:
      enabled: true
      proxy-base-port: 6379
      proxy-max-port: 6399
      default-image: "valkey/valkey:8"

    rds:
      enabled: true
      proxy-base-port: 7001
      proxy-max-port: 7099
      default-postgres-image: "postgres:16-alpine"
      default-mysql-image: "mysql:8.0"
      default-mariadb-image: "mariadb:11"

    eventbridge:
      enabled: true

    scheduler:
      enabled: true

    cloudwatchlogs:
      enabled: true
      max-events-per-query: 10000

    cloudwatchmetrics:
      enabled: true

    secretsmanager:
      enabled: true
      default-recovery-window-days: 30

    kinesis:
      enabled: true

    kms:
      enabled: true

    cognito:
      enabled: true

    stepfunctions:
      enabled: true

    cloudformation:
      enabled: true

    acm:
      enabled: true
      validation-wait-seconds: 0              # Seconds before transitioning PENDING_VALIDATION → ISSUED

    ses:
      enabled: true

    opensearch:
      enabled: true
      mock: false                             # true = metadata only, no Docker (useful for CI)
      default-image: "opensearchproject/opensearch:2"
      proxy-base-port: 9400
      proxy-max-port: 9499
      keep-running-on-shutdown: false         # leave containers running after Floci stops
      # docker network is inherited from floci.services.docker-network

    ec2:
      enabled: true

    ecs:
      enabled: true
      mock: false                             # true = tasks go to RUNNING without Docker (useful for CI)

    appconfig:
      enabled: true

    appconfigdata:
      enabled: true

    ecr:
      enabled: true
      registry-image: "registry:2"
      registry-container-name: floci-ecr-registry
      registry-base-port: 5100
      registry-max-port: 5199
      data-path: ./data/ecr
      tls-enabled: false
      keep-running-on-shutdown: true
      uri-style: hostname                     # hostname | path
```

### Initialization hooks

`floci.init-hooks.*` is accepted as an override but is not declared in the shipped `application.yml`. See [Initialization Hooks](./initialization-hooks.md) for the full list of keys (`shell-executable`, `timeout-seconds`, `shutdown-grace-period-seconds`) and their defaults.

## Service Limits

All keys in this table are declared on `EmulatorConfig` and accept environment variable overrides via the `FLOCI_` prefix.

| Variable                                           | Default          | Description                                                   |
|----------------------------------------------------|------------------|---------------------------------------------------------------|
| `FLOCI_MAX_REQUEST_SIZE`                           | `512`            | Max HTTP request body size in MB                              |
| `FLOCI_DEFAULT_REGION`                             | `us-east-1`      | Default AWS region used in ARNs and response URLs             |
| `FLOCI_DEFAULT_AVAILABILITY_ZONE`                  | `us-east-1a`     | Default AZ reported by EC2, RDS, and other AZ-aware services  |
| `FLOCI_DEFAULT_ACCOUNT_ID`                         | `000000000000`   | Default AWS account ID used in ARNs                           |
| `FLOCI_ECR_BASE_URI`                               | `public.ecr.aws` | Base URI used when pulling container images (e.g. Lambda)     |
| `FLOCI_SERVICES_SSM_MAX_PARAMETER_HISTORY`         | `5`              | Max parameter versions kept                                   |
| `FLOCI_SERVICES_SQS_DEFAULT_VISIBILITY_TIMEOUT`    | `30`             | Default visibility timeout (seconds)                          |
| `FLOCI_SERVICES_SQS_MAX_MESSAGE_SIZE`              | `262144`         | Max message size (bytes)                                      |
| `FLOCI_SERVICES_S3_DEFAULT_PRESIGN_EXPIRY_SECONDS` | `3600`           | Pre-signed URL expiry                                         |
| `FLOCI_SERVICES_DOCKER_NETWORK`                    | *(unset)*        | Shared Docker network for Lambda, RDS, ElastiCache containers |
| `FLOCI_SERVICES_ECS_MOCK`                          | `false`          | Skip Docker; tasks go straight to RUNNING (useful for CI)     |
| `FLOCI_SERVICES_ECS_DOCKER_NETWORK`                | *(unset)*        | Docker network for ECS task containers                        |
| `FLOCI_SERVICES_ECS_DEFAULT_MEMORY_MB`             | `512`            | Default memory (MB) when task definition omits it             |
| `FLOCI_SERVICES_ECS_DEFAULT_CPU_UNITS`             | `256`            | Default CPU units when task definition omits it               |
| `FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED`           | `false`          | Enforce IAM identity-based policies on every request when `true` |
| `FLOCI_SERVICES_OPENSEARCH_MOCK`                   | `false`          | Skip Docker; domains appear active immediately (useful for CI)   |
| `FLOCI_SERVICES_OPENSEARCH_KEEP_RUNNING_ON_SHUTDOWN` | `false`        | Leave OpenSearch containers running after Floci stops            |

Per-queue SQS redrive policy (`maxReceiveCount`) is configured at queue creation time via `SetQueueAttributes` / `CreateQueue`, not as a global default.

`FLOCI_DEFAULT_AVAILABILITY_ZONE` and `FLOCI_ECR_BASE_URI` are declared in `EmulatorConfig` but not in the shipped `application.yml`, so they fall through to the `@WithDefault` values above when unset.

## Disabling Services

Set `enabled: false` for any service you don't need. Disabled services return a `ServiceUnavailableException` rather than silently ignoring calls.

```yaml
floci:
  services:
    cloudformation:
      enabled: false
    stepfunctions:
      enabled: false
```