# Floci

<p align="center">
  <img src="assets/logo.svg" alt="Floci" width="500" />
</p>

<p align="center"><em>Light, fluffy, and always free</em></p>

---

Floci is a fast, free, and open-source local AWS service emulator built for developers who need reliable AWS services in development and CI without cost, complexity, or vendor lock-in.

## Supported Services 

| Service | Protocol |
|---|---|
| SSM Parameter Store | JSON 1.1 |
| SQS | Query / JSON |
| SNS | Query / JSON |
| S3 | REST XML |
| DynamoDB + Streams | JSON 1.1 |
| Lambda | REST JSON |
| API Gateway v1 & v2 | REST JSON |
| Cognito | JSON 1.1 |
| KMS | JSON 1.1 |
| Kinesis | JSON 1.1 |
| Secrets Manager | JSON 1.1 |
| CloudFormation | Query |
| Step Functions | JSON 1.1 |
| IAM | Query |
| STS | Query |
| ElastiCache (Redis) | Query + RESP proxy |
| RDS (PostgreSQL / MySQL) | Query + wire proxy |
| EventBridge | JSON 1.1 |
| CloudWatch Logs & Metrics | JSON 1.1 / Query |

## Why Floci?

**No account required.** No auth tokens, no sign-ups, no telemetry. Pull the image and start building.

**No feature gates.** Every feature is available to everyone — no community-edition restrictions.

**No CI restrictions.** Run in your CI pipeline with zero limitations. No credits, no quotas, no paid tiers.

**Truly open source.** MIT licensed. Fork it, extend it, embed it. No "community edition" sunset coming.

## Quick Start

```yaml title="docker-compose.yml"
services:
  floci:
    image: hectorvent/floci:latest
    ports:
      - "4566:4566"
    volumes:
      # Local directory bind mount (default)
      - ./data:/app/data
      
      # OR named volume (optional):
      # - floci-data:/app/data

#volumes:
#  floci-data:
```

```bash
docker compose up -d
aws --endpoint-url http://localhost:4566 s3 mb s3://my-bucket
```

All 19+ AWS services are immediately available at `http://localhost:4566`.

[Get started →](getting-started/quick-start.md){ .md-button .md-button--primary }
[View services →](services/index.md){ .md-button }