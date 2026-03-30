# application.yml Reference

All settings can be provided as YAML (mounted as a config file or in `src/main/resources/application.yml`) or overridden via environment variables (`FLOCI_` prefix, dots/dashes replaced with underscores).

## Full Reference

```yaml
floci:
  base-url: "http://localhost:4566"  # Used to build callback URLs (e.g. SNS subscription endpoints)
  default-region: us-east-1
  default-account-id: "000000000000"
  max-request-size: 512              # Max HTTP request body size in MB (default 512 MB)

  auth:
    validate-signatures: false        # Set to true to enforce AWS request signing
    presign-secret: local-emulator-secret  # HMAC secret for S3 pre-signed URL verification

  init-hooks:
    shell-executable: /bin/bash
    timeout-seconds: 30
    shutdown-grace-period-seconds: 2

  storage:
    mode: memory                      # memory | persistent | hybrid | wal
    persistent-path: ./data
    wal:
      compaction-interval-ms: 30000
    services:
      ssm:
        mode: memory
        flush-interval-ms: 5000
      sqs:
        mode: memory
      s3:
        mode: memory
      dynamodb:
        mode: memory
        flush-interval-ms: 5000
      sns:
        mode: memory
        flush-interval-ms: 5000
      lambda:
        mode: memory
        flush-interval-ms: 5000
      cloudwatchlogs:
        mode: memory
        flush-interval-ms: 5000
      cloudwatchmetrics:
        mode: memory
        flush-interval-ms: 5000
      secretsmanager:
        mode: memory
        flush-interval-ms: 5000
      opensearch:
        flush-interval-ms: 5000   # inherits global storage mode

  services:
    ssm:
      enabled: true
      max-parameter-history: 5        # Max versions kept per parameter

    sqs:
      enabled: true
      default-visibility-timeout: 30  # Seconds
      max-message-size: 262144        # Bytes (256 KB)

    s3:
      enabled: true
      default-presign-expiry-seconds: 3600

    dynamodb:
      enabled: true

    sns:
      enabled: true

    lambda:
      enabled: true
      ephemeral: false                # true = remove container after each invocation
      default-memory-mb: 128
      default-timeout-seconds: 3
      docker-host: unix:///var/run/docker.sock
      runtime-api-base-port: 9200    # Port range for Lambda Runtime API
      runtime-api-max-port: 9299
      code-path: ./data/lambda-code  # Where ZIP archives are stored
      poll-interval-ms: 1000
      container-idle-timeout-seconds: 300  # Remove idle containers after this

    apigateway:
      enabled: true

    iam:
      enabled: true

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
      validation-wait-seconds: 0  # Seconds before transitioning PENDING_VALIDATION → ISSUED

    ses:
      enabled: true

    opensearch:
      enabled: true
      mode: mock                                    # mock | real
      default-image: "opensearchproject/opensearch:2"
      proxy-base-port: 9400
      proxy-max-port: 9499
```

## Service Limits

| Variable                                           | Default  | Description                                                   |
|----------------------------------------------------|----------|---------------------------------------------------------------|
| `FLOCI_SERVICES_SSM_MAX_PARAMETER_HISTORY`         | `5`      | Max parameter versions kept                                   |
| `FLOCI_SERVICES_SQS_DEFAULT_VISIBILITY_TIMEOUT`    | `30`     | Visibility timeout (seconds)                                  |
| `FLOCI_SERVICES_SQS_MAX_MESSAGE_SIZE`              | `262144` | Max message size (bytes)                                      |
| `FLOCI_SERVICES_SQS_MAX_RECEIVE_COUNT`             | `10`     | Max receive count                                             |
| `FLOCI_MAX_REQUEST_SIZE`                           | `512`    | Max HTTP request body size in MB                              |
| `FLOCI_SERVICES_S3_MAX_MULTIPART_PARTS`            | `10000`  | Max parts per upload                                          |
| `FLOCI_SERVICES_S3_DEFAULT_PRESIGN_EXPIRY_SECONDS` | `3600`   | Pre-signed URL expiry                                         |
| `FLOCI_SERVICES_DYNAMODB_MAX_ITEM_SIZE`            | `400000` | Max item size (bytes)                                         |
| `FLOCI_SERVICES_DOCKER_NETWORK`                    |          | Shared Docker network for Lambda, RDS, ElastiCache containers |

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