# Services Overview

Floci emulates 59 AWS services on a single port (`4566`). All services use the real AWS wire protocol, your existing AWS CLI commands and SDK clients work without modification.

This page is the canonical reference for supported service and operation counts. Some services expose separate control-plane and data-plane rows below. Other docs (and the README) should link here rather than duplicating the table.

## Service Matrix

Operation counts are exact. For dispatch-table services (Query and JSON 1.1) each count reflects one case per AWS action in the handler. For REST-based services (S3, Lambda, API Gateway v1) the count reflects distinct AWS SDK operations, collapsing routes where one JAX-RS handler fans out via query-string or header markers (e.g. `PUT /{bucket}/{key}` → `PutObject`, `PutObjectTagging`, `PutObjectAcl`, etc.).

| Service | Endpoint | Protocol | Supported operations |
|---|---|---|---|
| [SSM](ssm.md) | `POST /` + `X-Amz-Target: AmazonSSM.*` / `AmazonSSMMessageDeliveryService.*` | JSON 1.1 | 22 |
| [SQS](sqs.md) | `POST /` with `Action=` param | Query / JSON | 20 |
| [SNS](sns.md) | `POST /` with `Action=` param | Query / JSON | 17 |
| [S3](s3.md) | `/{bucket}/{key}` | REST XML | 58 |
| [DynamoDB](dynamodb.md) | `POST /` + `X-Amz-Target: DynamoDB_20120810.*` | JSON 1.1 | 28 |
| [DynamoDB Streams](dynamodb.md#streams) | `POST /` + `X-Amz-Target: DynamoDBStreams_20120810.*` | JSON 1.1 | 4 |
| [Lambda](lambda.md) | `/2015-03-31/functions/...` | REST JSON | 30 |
| [API Gateway v1](api-gateway.md) | `/restapis/...` | REST JSON | 64 |
| [API Gateway v2](api-gateway.md#v2) | `/v2/apis/...` | REST JSON | 48 + data-plane |
| [IAM](iam.md) | `POST /` with `Action=` param | Query | 76 |
| [STS](sts.md) | `POST /` with `Action=` param | Query | 7 |
| [Cognito](cognito.md) | `POST /` + `X-Amz-Target: AWSCognitoIdentityProviderService.*` | JSON 1.1 | 43 |
| [KMS](kms.md) | `POST /` + `X-Amz-Target: TrentService.*` | JSON 1.1 | 34 |
| [Kinesis](kinesis.md) | `POST /` + `X-Amz-Target: Kinesis_20131202.*` | JSON 1.1 | 24 |
| [Secrets Manager](secrets-manager.md) | `POST /` + `X-Amz-Target: secretsmanager.*` | JSON 1.1 | 16 |
| [Step Functions](step-functions.md) | `POST /` + `X-Amz-Target: AmazonStatesService.*` | JSON 1.1 | 19 |
| [CloudFormation](cloudformation.md) | `POST /` with `Action=` param | Query | 19 |
| [EventBridge](eventbridge.md) | `POST /` + `X-Amz-Target: AmazonEventBridge.*` | JSON 1.1 | 16 |
| [EventBridge Scheduler](scheduler.md) | `/schedules/*`, `/schedule-groups/*`, `/tags/*` | REST JSON | 12 |
| [EventBridge Pipes](pipes.md) | `/v1/pipes/*` | REST JSON | 7 |
| [CloudWatch Logs](cloudwatch.md) | `POST /` + `X-Amz-Target: Logs.*` | JSON 1.1 | 17 |
| [CloudWatch Metrics](cloudwatch.md#metrics) | `POST /` with `Action=` or JSON 1.1 | Query / JSON | 11 |
| [ElastiCache](elasticache.md) | `POST /` with `Action=` param + TCP proxy | Query + RESP | 8 |
| [MemoryDB](memorydb.md) | `POST /` + `X-Amz-Target: AmazonMemoryDB.*` + TCP proxy | JSON 1.1 + RESP | 7 |
| [RDS](rds.md) | `POST /` with `Action=` param + TCP proxy | Query + wire | 14 |
| [RDS Data API](rds-data.md) | `/Execute`, `/BeginTransaction`, `/CommitTransaction`, `/RollbackTransaction` | REST JSON | 4 |
| [MSK](msk.md) | `/v1/clusters/...`, `/api/v2/clusters/...` + Redpanda broker | REST JSON + Kafka | 8 |
| [Athena](athena.md) | `POST /` + `X-Amz-Target: AmazonAthena.*` | JSON 1.1 | 4 |
| [Glue](glue.md) | `POST /` + `X-Amz-Target: AWSGlue.*` | JSON 1.1 | 38 |
| [Neptune](neptune.md) | `POST /` with `Action=` param + Gremlin TCP proxy | Query + WebSocket | 8 |
| [DocumentDB](docdb.md) | `POST /` with `Action=` param + MongoDB container | Query + MongoDB wire | 8 |
| [EMR](emr.md) | `POST /` + `X-Amz-Target: ElasticMapReduce.*` | JSON 1.1 | 24 |
| [Data Firehose](firehose.md) | `POST /` + `X-Amz-Target: Firehose_20150804.*` | JSON 1.1 | 6 |
| [ECS](ecs.md) | `POST /` + `X-Amz-Target: AmazonEC2ContainerServiceV20141113.*` | JSON 1.1 | 58 |
| [EC2](ec2.md) | `POST /` with `Action=` param | EC2 Query | 78 |
| [ACM](acm.md) | `POST /` + `X-Amz-Target: CertificateManager.*` | JSON 1.1 | 12 |
| [ECR](ecr.md) | `POST /` + `X-Amz-Target: AmazonEC2ContainerRegistry_V20150921.*` (control plane) and `/v2/...` (data plane via `registry:2`) | JSON 1.1 + OCI Distribution | 17 |
| [Resource Groups Tagging API](resource-groups-tagging.md) | `POST /` + `X-Amz-Target: ResourceGroupsTaggingAPI_20170126.*` | JSON 1.1 | 5 |
| [SES](ses.md) | `POST /` with `Action=` param | Query | 16 |
| [SES v2](ses.md#v2) | `/v2/email/*` | REST JSON | 10 |
| [OpenSearch](opensearch.md) | `/2021-01-01/opensearch/...` | REST JSON | 24 |
| [AppConfig](appconfig.md) | `/applications/...`, `/deploymentstrategies/...` | REST JSON | 16 |
| [AppConfigData](appconfig.md#data-plane) | `/configurationsessions`, `/configuration` | REST JSON | 2 |
| [AppSync](appsync.md) | `/v1/apis/...` | REST JSON | 33 |
| [Bedrock Runtime](bedrock-runtime.md) | `/model/{modelId}/converse`, `/model/{modelId}/invoke` | REST JSON | 2 (stub; streaming returns 501) |
| [EKS](eks.md) | `/clusters`, `/clusters/{name}`, `/tags/{resourceArn}` | REST JSON | 7 |
| [ELB v2](elb.md) | `POST /` with `Action=` param | Query | 34 |
| [WAF v2](wafv2.md) | `POST /` + `X-Amz-Target: AWSWAF_20190729.*` | JSON 1.1 | 35 |
| [Auto Scaling](autoscaling.md) | `POST /` with `Action=` param | Query | 33 |
| [CodeBuild](codebuild.md) | `POST /` + `X-Amz-Target: CodeBuild_20161006.*` | JSON 1.1 | 20 |
| [AWS Batch](batch.md) | `/v1/...` | REST JSON | 10 |
| [CodeDeploy](codedeploy.md) | `POST /` + `X-Amz-Target: CodeDeploy_20141006.*` | JSON 1.1 | 30 |
| [CodePipeline](codepipeline.md) | `POST /` + `X-Amz-Target: CodePipeline_20150709.*` | JSON 1.1 | 44 |
| [AWS Backup](backup.md) | `/backup-vaults/*`, `/backup/plans/*`, `/backup-jobs/*`, `/supported-resource-types` | REST JSON | 20 |
| [CloudFront](cloudfront.md) | `/2020-05-31/distribution/*`, `/2020-05-31/cache-policy/*`, `/2020-05-31/function/*` | REST XML | 50 |
| [Route53](route53.md) | `/2013-04-01/hostedzone/*`, `/2013-04-01/healthcheck/*`, `/2013-04-01/change/*` | REST XML | 17 |
| [Cloud Map](cloudmap.md) | `POST /` + `X-Amz-Target: Route53AutoNaming_v20170314.*` | JSON 1.1 | 22 |
| [AWS Config](config.md) | `POST /` + `X-Amz-Target: StarlingDoveService.*` | JSON 1.1 | 20 |
| [CloudTrail](cloudtrail.md) | `POST /` + `X-Amz-Target: com.amazonaws.cloudtrail.v20131101.CloudTrail_20131101.*` | JSON 1.1 | 8 |
| [Textract](textract.md) | `POST /` + `X-Amz-Target: Textract.*` | JSON 1.1 | 6 |
| [Transcribe](transcribe.md) | `POST /` + `X-Amz-Target: Transcribe.*` | JSON 1.1 | 8 |
| [Pricing](pricing.md) | `POST /` + `X-Amz-Target: AWSPriceListService.*` | JSON 1.1 | 5 |
| [Cost Explorer](ce.md) | `POST /` + `X-Amz-Target: AWSInsightsIndexService.*` | JSON 1.1 | 9 |
| [Cost and Usage Reports](cur.md) | `POST /` + `X-Amz-Target: AWSOrigamiServiceGatewayService.*` | JSON 1.1 | 6 |
| [BCM Data Exports](bcm-data-exports.md) | `POST /` + `X-Amz-Target: AWSBillingAndCostManagementDataExports.*` | JSON 1.1 | 7 |
| [Transfer Family](transfer.md) | `POST /` + `X-Amz-Target: TransferService.*` | JSON 1.1 | 17 |

**Lambda, ElastiCache, RDS, MSK, ECS, EKS, and OpenSearch** spin up real Docker containers and support IAM authentication and SigV4 request signing, the same auth flow as production AWS. **RDS Data API** executes SQL against the local RDS containers through AWS-compatible REST JSON routes.

**ECR** runs a shared `registry:2` container so the stock `docker` client can push and pull image bytes against repositories returned by the AWS-shaped control plane. **EKS** (real mode) starts a k3s container per cluster and exposes the Kubernetes API server on a host port. **OpenSearch** (real mode) starts an `opensearchproject/opensearch` container per domain and exposes the data-plane REST API on a host port. **DocumentDB** starts a real `mongo` container per cluster and returns its host and port as the cluster endpoint, so any MongoDB driver can connect against the MongoDB-compatible wire protocol.

## Common Setup

Before calling any service, configure your AWS client to point to Floci:

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
```

`AWS_ENDPOINT_URL` is the standard env var recognised by the AWS CLI v2 and AWS SDKs v2+, so no `--endpoint-url` flag is needed on each command.
