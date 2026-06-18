# Floci

<p align="center">
  <img src="assets/floci.svg" alt="Floci" width="500" />
</p>

<p align="center"><em>Light, fluffy, and always free</em></p>

---

Floci is a fast, free, and open-source local AWS service emulator built for developers who need reliable AWS services in development and CI without cost, complexity, or vendor lock-in.

## Supported Services

Floci emulates 58 AWS services. See the [Services Overview](services/index.md) for per-service operation counts, endpoints, and full protocol details.

| Service | Protocol |
|---|---|
| SSM Parameter Store | JSON 1.1 |
| SQS | Query / JSON |
| SNS | Query / JSON |
| SES | Query |
| SES v2 | REST JSON |
| S3 | REST XML |
| DynamoDB + Streams | JSON 1.1 |
| Lambda | REST JSON |
| API Gateway v1 & v2 | REST JSON |
| AppSync | REST JSON |
| Cognito | JSON 1.1 |
| KMS | JSON 1.1 |
| Kinesis | JSON 1.1 |
| Secrets Manager | JSON 1.1 |
| CloudFormation | Query |
| Step Functions | JSON 1.1 |
| IAM | Query |
| STS | Query |
| ElastiCache (Redis / Valkey) | Query + RESP proxy |
| RDS (PostgreSQL / MySQL) | Query + wire proxy |
| RDS Data API | REST JSON |
| Neptune (graph DB / Gremlin) | Query + WebSocket proxy |
| DocumentDB (MongoDB-compatible) | Query + MongoDB wire |
| MSK (Kafka / Redpanda) | REST JSON + Kafka |
| Athena | JSON 1.1 |
| Glue Data Catalog + Schema Registry | JSON 1.1 |
| Data Firehose | JSON 1.1 |
| ECS | JSON 1.1 |
| EC2 | EC2 Query |
| ACM | JSON 1.1 |
| ECR | JSON 1.1 + OCI Distribution |
| Resource Groups Tagging API | JSON 1.1 |
| OpenSearch | REST JSON |
| EventBridge | JSON 1.1 |
| EventBridge Pipes | REST JSON |
| EventBridge Scheduler | REST JSON |
| CloudWatch Logs & Metrics | JSON 1.1 / Query |
| AppConfig + AppConfigData | REST JSON |
| Bedrock Runtime | REST JSON |
| EKS | REST JSON |
| ELB v2 | Query |
| Auto Scaling | Query |
| CodeBuild | JSON 1.1 |
| CodeDeploy | JSON 1.1 |
| AWS Backup | REST JSON |
| CloudFront | REST XML |
| Route53 | REST XML |
| Cloud Map | JSON 1.1 |
| AWS Config | JSON 1.1 |
| Textract | JSON 1.1 |
| Transcribe | JSON 1.1 |
| Pricing | JSON 1.1 |
| Cost Explorer | JSON 1.1 |
| Cost and Usage Reports | JSON 1.1 |
| BCM Data Exports | JSON 1.1 |
| Transfer Family | JSON 1.1 |

## Why Floci?

**No account required.** No auth tokens, no sign-ups, no telemetry. Pull the image and start building.

**No feature gates.** Every feature is available to everyone — no community-edition restrictions.

**No CI restrictions.** Run in your CI pipeline with zero limitations. No credits, no quotas, no paid tiers.

**Truly open source.** MIT licensed. Fork it, extend it, embed it. No "community edition" sunset coming.

## Quick Start

```yaml title="docker-compose.yml"
services:
  floci:
    image: floci/floci:latest
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

All 58 AWS services are immediately available at `http://localhost:4566`.

[Get started →](getting-started/quick-start.md){ .md-button .md-button--primary }
[View services →](services/index.md){ .md-button }
