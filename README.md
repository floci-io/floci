<p align="center">
  <img src="floci_banner.svg" alt="Floci"/>
</p>

<p align="center">
  <a href="https://github.com/floci-io/floci/releases/latest"><img src="https://img.shields.io/github/v/release/floci-io/floci?label=latest%20release&color=blue" alt="Latest Release"></a>
  <a href="https://github.com/floci-io/floci/actions/workflows/release.yml"><img src="https://img.shields.io/github/actions/workflow/status/floci-io/floci/release.yml?label=build" alt="Build Status"></a>
  <a href="https://hub.docker.com/r/hectorvent/floci"><img src="https://img.shields.io/docker/pulls/hectorvent/floci?label=docker%20pulls" alt="Docker Pulls"></a>
  <a href="https://hub.docker.com/r/hectorvent/floci"><img src="https://img.shields.io/docker/image-size/hectorvent/floci/latest?label=image%20size" alt="Docker Image Size"></a>
  <a href="https://opensource.org/licenses/MIT"><img src="https://img.shields.io/badge/license-MIT-green" alt="License: MIT"></a>
  <a href="https://github.com/floci-io/floci/stargazers"><img src="https://img.shields.io/github/stars/floci-io/floci?style=flat" alt="GitHub Stars"></a>
  <a href="https://github.com/floci-io/floci/graphs/contributors"><img src="https://img.shields.io/github/contributors/floci-io/floci" alt="GitHub Contributors"></a>
  <a href="https://join.slack.com/t/floci/shared_invite/zt-3tjn02s3q-A00kEjJ1cZxsg_imTfy6Cw"><img src="https://img.shields.io/badge/Slack-Join%20the%20community-4A154B?logo=slack&logoColor=white" alt="Join Floci on Slack"></a>

</p>

<p align="center">
  <em>Named after <a href="https://en.wikipedia.org/wiki/Cirrocumulus_floccus">floccus</a> — the cloud formation that looks exactly like popcorn.</em>
</p>

<p align="center">
  A free, open-source local AWS emulator. No account. No feature gates. Just&nbsp;<code>docker compose up</code>.
</p>

<p align="center">
  Join the community on <a href="https://join.slack.com/t/floci/shared_invite/zt-3tjn02s3q-A00kEjJ1cZxsg_imTfy6Cw">Slack</a> to ask questions, share feedback, and discuss Floci with other contributors and users. You can also open any topic in <a href="https://github.com/orgs/floci-io/discussions">GitHub Discussions</a> — feature ideas, compatibility questions, design tradeoffs, wild proposals, or half-baked thoughts are all welcome. No idea is too small, too early, or too popcorn-fueled to start a good discussion.
</p>

---

> LocalStack's community edition [sunset in March 2026](https://blog.localstack.cloud/the-road-ahead-for-localstack/) — requiring auth tokens, and freezing security updates. Floci is the no-strings-attached alternative.

## Why Floci?

| | Floci | LocalStack Community |
|---|---|---|
| Auth token required | No | Yes (since March 2026) |
| Security updates | Yes | Frozen |
| Startup time | **~24 ms** | ~3.3 s |
| Idle memory | **~13 MiB** | ~143 MiB |
| Docker image size | **~90 MB** | ~1.0 GB |
| License | **MIT** | Restricted |
| API Gateway v2 / HTTP API | ✅ | ❌ |
| Cognito | ✅ | ❌ |
| ElastiCache (Redis + IAM auth) | ✅ | ❌ |
| RDS (PostgreSQL + MySQL + IAM auth) | ✅ | ❌ |
| S3 Object Lock (COMPLIANCE / GOVERNANCE) | ✅ | ⚠️ Partial |
| DynamoDB Streams | ✅ | ⚠️ Partial |
| IAM (users, roles, policies, groups) | ✅ | ⚠️ Partial |
| STS (all 7 operations) | ✅ | ⚠️ Partial |
| Kinesis (streams, shards, fan-out) | ✅ | ⚠️ Partial |
| KMS (sign, verify, re-encrypt) | ✅ | ⚠️ Partial |
| ECS (clusters, services, tasks) | ✅ | ❌ |
| EC2 (VPCs, instances, security groups) | ✅ | ⚠️ Partial |
| Native binary | ✅ ~40 MB | ❌ |

**Broad AWS coverage. 1,850+ automated compatibility tests. Free forever.**

## Architecture Overview

```mermaid
flowchart LR
    Client["☁️ AWS SDK / CLI"]

    subgraph Floci ["Floci — port 4566"]
        Router["HTTP Router\n(JAX-RS / Vert.x)"]

        subgraph Stateless ["Stateless Services"]
            A["SSM · SQS · SNS\nIAM · STS · KMS\nSecrets Manager · SES\nCognito · Kinesis · OpenSearch\nEventBridge · Scheduler · AppConfig\nCloudWatch · Step Functions\nCloudFormation · ACM\nAPI Gateway · EC2"]
        end

        subgraph Stateful ["Stateful Services"]
            B["S3 · DynamoDB\nDynamoDB Streams"]
        end

        subgraph Containers ["Container Services  🐳"]
            C["Lambda\nElastiCache\nRDS\nECS"]
        end

        Router --> Stateless
        Router --> Stateful
        Router --> Containers
        Stateless & Stateful --> Store[("StorageBackend\nmemory · hybrid\npersistent · wal")]
    end

    Docker["🐳 Docker Engine"]
    Client -->|"HTTP :4566\nAWS wire protocol"| Router
    Containers -->|"Docker API\n+ IAM / SigV4 auth"| Docker
```

## Supported Services

| Service | How it works | Notable features |
|---|---|---|
| **SSM Parameter Store** | In-process | Version history, labels, SecureString, tagging |
| **SQS** | In-process | Standard & FIFO, DLQ, visibility timeout, batch, tagging |
| **SNS** | In-process | Topics, subscriptions, SQS / Lambda / HTTP delivery, tagging |
| **S3** | In-process | Versioning, multipart upload, pre-signed URLs, Object Lock, event notifications |
| **DynamoDB** | In-process | GSI / LSI, Query, Scan, TTL, transactions, batch operations |
| **DynamoDB Streams** | In-process | Shard iterators, records, Lambda ESM trigger |
| **Lambda** | **Real Docker containers** | Warm pool, aliases, Function URLs, SQS / Kinesis / DDB Streams ESM |
| **API Gateway REST** | In-process | Resources, methods, stages, Lambda proxy, MOCK integrations, AWS integrations |
| **API Gateway v2 (HTTP)** | In-process | Routes, integrations, JWT authorizers, stages |
| **IAM** | In-process | Users, roles, groups, policies, instance profiles, access keys |
| **STS** | In-process | AssumeRole, WebIdentity, SAML, GetFederationToken, GetSessionToken |
| **Cognito** | In-process | User pools, app clients, auth flows, JWKS / OpenID well-known endpoints |
| **KMS** | In-process | Encrypt / decrypt, sign / verify, data keys, aliases |
| **Kinesis** | In-process | Streams, shards, enhanced fan-out, split / merge |
| **Secrets Manager** | In-process | Versioning, resource policies, tagging |
| **Step Functions** | In-process | ASL execution, task tokens, execution history |
| **CloudFormation** | In-process | Stacks, change sets, resource provisioning |
| **EventBridge** | In-process | Custom buses, rules, targets (SQS / SNS / Lambda) |
| **EventBridge Scheduler** | In-process | Schedule groups, schedules, flexible time windows, retry policies, dead-letter queues |
| **CloudWatch Logs** | In-process | Log groups, streams, ingestion, filtering |
| **CloudWatch Metrics** | In-process | Custom metrics, statistics, alarms |
| **ElastiCache** | **Real Docker containers** | Redis / Valkey, IAM auth, SigV4 validation |
| **RDS** | **Real Docker containers** | PostgreSQL & MySQL, IAM auth, JDBC-compatible |
| **ECS** | **Real Docker containers** | Clusters, task definitions, tasks, services, capacity providers, task sets |
| **EC2** | In-process | VPCs, subnets, security groups, instances, AMIs, key pairs, internet gateways, route tables, Elastic IPs, tags |
| **ACM** | In-process | Certificate issuance, validation lifecycle |
| **ECR** | In-process + **real OCI registry** | Repositories, image push / pull via stock `docker`, image-backed Lambda functions |
| **SES** | In-process | Send email / raw email, identity verification, DKIM attributes |
| **SES v2 (HTTP)** | In-process | REST JSON API, identities, DKIM, feedback attributes, account sending |
| **OpenSearch** | In-process | Domain CRUD, tags, versions, instance types, upgrade stubs |
| **AppConfig** | In-process | Applications, environments, profiles, hosted configuration versions, deployments |
| **AppConfigData** | In-process | Configuration sessions, dynamic configuration retrieval |

> **Lambda, ElastiCache, RDS, and ECS** spin up real Docker containers and support IAM authentication and SigV4 request signing — the same auth flow as production AWS. **ECR** runs a shared `registry:2` container so the stock `docker` client can push and pull image bytes against repositories returned by the AWS-shaped control plane.
>
> For per-service operation counts and endpoint protocols, see the [Services Overview](https://floci.io/floci/services/) in the documentation site.

## Quick Start

```yaml
# docker-compose.yml
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
docker compose up
```

All services are available at `http://localhost:4566`. Use any AWS region — credentials can be anything.

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

# Try it
aws s3 mb s3://my-bucket
aws sqs create-queue --queue-name my-queue
aws dynamodb list-tables
```

## SDK Integration

Point your existing AWS SDK at `http://localhost:4566` — no other changes needed.

```java
// Java (AWS SDK v2)
DynamoDbClient client = DynamoDbClient.builder()
    .endpointOverride(URI.create("http://localhost:4566"))
    .region(Region.US_EAST_1)
    .credentialsProvider(StaticCredentialsProvider.create(
        AwsBasicCredentials.create("test", "test")))
    .build();
```

```python
# Python (boto3)
import boto3
client = boto3.client("s3",
    endpoint_url="http://localhost:4566",
    region_name="us-east-1",
    aws_access_key_id="test",
    aws_secret_access_key="test")
```

```javascript
// Node.js (AWS SDK v3)
import { S3Client } from "@aws-sdk/client-s3";

const client = new S3Client({
    endpoint: "http://localhost:4566",
    region: "us-east-1",
    credentials: { accessKeyId: "test", secretAccessKey: "test" },
    forcePathStyle: true,
});
```

## Compatibility Testing

> For full compatibility validation against real SDK and client workflows, see the [compatibility-tests](./compatibility-tests/) directory.

This directory provides a dedicated compatibility test suite for Floci across multiple SDKs and tooling scenarios, and is the recommended starting point when verifying integration behavior end to end.

Available compatibility test modules:

| Module | Language / Tool | SDK / Client / Version | Tests |
|---|---|---|---:|
| `sdk-test-java` | Java 17 | AWS SDK for Java v2 | 889 |
| `sdk-test-node` | Node.js | AWS SDK for JavaScript v3 | 360 |
| `sdk-test-python` | Python 3 | boto3 | 264 |
| `sdk-test-go` | Go | AWS SDK for Go v2 | 120 |
| `sdk-test-awscli` | Bash | AWS CLI v2 | 138 |
| `sdk-test-rust` | Rust | AWS SDK for Rust | 69 |
| `compat-terraform` | Terraform | v1.10+ | 14 |
| `compat-opentofu` | OpenTofu | v1.9+ | 14 |
| `compat-cdk` | AWS CDK | v2+ | 5 |

## Image Tags

| Tag | Description |
|---|---|
| `latest` | Native image — sub-second startup **(recommended)** |
| `latest-jvm` | JVM image — broadest platform compatibility |
| `x.y.z` / `x.y.z-jvm` | Pinned releases |

## Configuration

All settings are overridable via environment variables (`FLOCI_` prefix).

| Variable | Default | Description                                                                         |
|---|---|-------------------------------------------------------------------------------------|
| `QUARKUS_HTTP_PORT` | `4566` | Port exposed by the Floci API                                                       |
| `FLOCI_DEFAULT_REGION` | `us-east-1` | Default AWS region                                                                  |
| `FLOCI_DEFAULT_ACCOUNT_ID` | `000000000000` | Default AWS account ID                                                              |
| `FLOCI_BASE_URL` | `http://localhost:4566` | Base URL used when Floci returns service URLs (e.g. SQS QueueUrl)                   |
| `FLOCI_HOSTNAME` | *(unset)* | Hostname to use in returned URLs when Floci runs inside Docker Compose              |
| `FLOCI_STORAGE_MODE` | `memory` | Controls how data is stored across runs: `memory` · `persistent` · `hybrid` · `wal` |
| `FLOCI_STORAGE_PERSISTENT_PATH` | `./data` | Directory used for persisted state                                                  |
| `FLOCI_ECR_BASE_URI` | `public.ecr.aws` | AWS ECR base URI used when pulling container images (e.g. Lambda)                   |

* Full reference: [configuration docs](https://floci.io/floci/configuration/application-yml/)
* Per-service storage overrides: [storage docs](https://floci.io/floci/configuration/storage/#per-service-storage-overrides)

**Multi-container Docker Compose:** When your application runs in a separate container from Floci, set `FLOCI_HOSTNAME` to the Floci service name so that returned URLs (e.g. SQS QueueUrl) resolve correctly:

```yaml
services:
  floci:
    image: hectorvent/floci:latest
    ports:
      - "4566:4566"
    environment:
      - FLOCI_HOSTNAME=floci  # URLs will use http://floci:4566/...
  my-app:
    environment:
      - AWS_ENDPOINT_URL=http://floci:4566
    depends_on:
      - floci
```

Without this, SQS returns `http://localhost:4566/...` in QueueUrl responses, which resolves to the wrong container.

## Star history

[![Star History Chart](https://api.star-history.com/svg?repos=floci-io/floci&type=Date)](https://star-history.com/#floci-io/floci&Date)

## Contributors

<a href="https://github.com/floci-io/floci/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=floci-io/floci" />
</a>

## License

MIT — use it however you want.
