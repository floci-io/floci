# Services Overview

Floci emulates 34 AWS services on a single port (`4566`). All services use the real AWS wire protocol, your existing AWS CLI commands and SDK clients work without modification.

This page is the canonical reference for supported service counts and operation counts. Other docs (and the README) should link here rather than duplicating the table.

## Service Matrix

Operation counts are exact. For dispatch-table services (Query and JSON 1.1) each count reflects one case per AWS action in the handler. For REST-based services (S3, Lambda, API Gateway v1) the count reflects distinct AWS SDK operations, collapsing routes where one JAX-RS handler fans out via query-string or header markers (e.g. `PUT /{bucket}/{key}` → `PutObject`, `PutObjectTagging`, `PutObjectAcl`, etc.).

| Service | Endpoint | Protocol | Supported operations |
|---|---|---|---|
| [SSM](ssm.md) | `POST /` + `X-Amz-Target: AmazonSSM.*` | JSON 1.1 | 12 |
| [SQS](sqs.md) | `POST /` with `Action=` param | Query / JSON | 20 |
| [SNS](sns.md) | `POST /` with `Action=` param | Query / JSON | 17 |
| [S3](s3.md) | `/{bucket}/{key}` | REST XML | 58 |
| [DynamoDB](dynamodb.md) | `POST /` + `X-Amz-Target: DynamoDB_20120810.*` | JSON 1.1 | 20 |
| [DynamoDB Streams](dynamodb.md#streams) | `POST /` + `X-Amz-Target: DynamoDBStreams_20120810.*` | JSON 1.1 | 4 |
| [Lambda](lambda.md) | `/2015-03-31/functions/...` | REST JSON | 30 |
| [API Gateway v1](api-gateway.md) | `/restapis/...` | REST JSON | 62 |
| [API Gateway v2](api-gateway.md#v2) | `/v2/apis/...` | REST JSON | 21 |
| [IAM](iam.md) | `POST /` with `Action=` param | Query | 68 |
| [STS](sts.md) | `POST /` with `Action=` param | Query | 7 |
| [Cognito](cognito.md) | `POST /` + `X-Amz-Target: AWSCognitoIdentityProviderService.*` | JSON 1.1 | 40 |
| [KMS](kms.md) | `POST /` + `X-Amz-Target: TrentService.*` | JSON 1.1 | 23 |
| [Kinesis](kinesis.md) | `POST /` + `X-Amz-Target: Kinesis_20131202.*` | JSON 1.1 | 24 |
| [Secrets Manager](secrets-manager.md) | `POST /` + `X-Amz-Target: secretsmanager.*` | JSON 1.1 | 16 |
| [Step Functions](step-functions.md) | `POST /` + `X-Amz-Target: AmazonStatesService.*` | JSON 1.1 | 18 |
| [CloudFormation](cloudformation.md) | `POST /` with `Action=` param | Query | 18 |
| [EventBridge](eventbridge.md) | `POST /` + `X-Amz-Target: AmazonEventBridge.*` | JSON 1.1 | 16 |
| [EventBridge Scheduler](scheduler.md) | `/schedules/*`, `/schedule-groups/*` | REST JSON | 9 |
| [CloudWatch Logs](cloudwatch.md) | `POST /` + `X-Amz-Target: Logs.*` | JSON 1.1 | 17 |
| [CloudWatch Metrics](cloudwatch.md#metrics) | `POST /` with `Action=` or JSON 1.1 | Query / JSON | 11 |
| [ElastiCache](elasticache.md) | `POST /` with `Action=` param + TCP proxy | Query + RESP | 8 |
| [RDS](rds.md) | `POST /` with `Action=` param + TCP proxy | Query + wire | 14 |
| [ECS](ecs.md) | `POST /` + `X-Amz-Target: AmazonEC2ContainerServiceV20141113.*` | JSON 1.1 | 58 |
| [EC2](ec2.md) | `POST /` with `Action=` param | EC2 Query | 61 |
| [ACM](acm.md) | `POST /` + `X-Amz-Target: CertificateManager.*` | JSON 1.1 | 12 |
| [ECR](ecr.md) | `POST /` + `X-Amz-Target: AmazonEC2ContainerRegistry_V20150921.*` (control plane) and `/v2/...` (data plane via `registry:2`) | JSON 1.1 + OCI Distribution | 17 |
| [SES](ses.md) | `POST /` with `Action=` param | Query | 16 |
| [SES v2](ses.md#v2) | `/v2/email/*` | REST JSON | 9 |
| [OpenSearch](opensearch.md) | `/2021-01-01/opensearch/...` | REST JSON | 24 |
| [AppConfig](appconfig.md) | `/applications/...`, `/deploymentstrategies/...` | REST JSON | 16 |
| [AppConfigData](appconfig.md#data-plane) | `/configurationsessions`, `/configuration` | REST JSON | 2 |
| [Bedrock Runtime](bedrock-runtime.md) | `/model/{modelId}/converse`, `/model/{modelId}/invoke` | REST JSON | 2 (stub; streaming returns 501) |
| [EKS](eks.md) | `/clusters`, `/clusters/{name}`, `/tags/{resourceArn}` | REST JSON | 7 |

**Lambda, ElastiCache, RDS, MSK, ECS, EKS, and OpenSearch** spin up real Docker containers and support IAM authentication and SigV4 request signing, the same auth flow as production AWS.

**ECR** runs a shared `registry:2` container so the stock `docker` client can push and pull image bytes against repositories returned by the AWS-shaped control plane. **EKS** (real mode) starts a k3s container per cluster and exposes the Kubernetes API server on a host port. **OpenSearch** (real mode) starts an `opensearchproject/opensearch` container per domain and exposes the data-plane REST API on a host port.

## Common Setup

Before calling any service, configure your AWS client to point to Floci:

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
```

`AWS_ENDPOINT_URL` is the standard env var recognised by the AWS CLI v2 and AWS SDKs v2+, so no `--endpoint-url` flag is needed on each command.
