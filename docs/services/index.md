# Services Overview

Floci emulates 21+ AWS services on a single port (`4566`). All services use the real AWS wire protocol — your existing AWS CLI commands and SDK clients work without modification.

## Service Matrix

| Service | Endpoint | Protocol | Supported operations |
|---|---|---|---|
| [SSM](ssm.md) | `POST /` + `X-Amz-Target: AmazonSSM.*` | JSON 1.1 | 12 |
| [SQS](sqs.md) | `POST /` with `Action=` param | Query / JSON | 20 |
| [SNS](sns.md) | `POST /` with `Action=` param | Query / JSON | 17 |
| [S3](s3.md) | `/{bucket}/{key}` | REST XML | 50+ |
| [DynamoDB](dynamodb.md) | `POST /` + `X-Amz-Target: DynamoDB_20120810.*` | JSON 1.1 | 19 |
| [DynamoDB Streams](dynamodb.md#streams) | `POST /` + `X-Amz-Target: DynamoDBStreams_20120810.*` | JSON 1.1 | 4 |
| [Lambda](lambda.md) | `/2015-03-31/functions/...` | REST JSON | 18 |
| [API Gateway v1](api-gateway.md) | `/restapis/...` | REST JSON | 40+ |
| [API Gateway v2](api-gateway.md#v2) | `/v2/apis/...` | REST JSON | 20 |
| [Cognito](cognito.md) | `POST /` + `X-Amz-Target: AWSCognitoIdentityProviderService.*` | JSON 1.1 | 24 |
| [KMS](kms.md) | `POST /` + `X-Amz-Target: TrentService.*` | JSON 1.1 | 18 |
| [Kinesis](kinesis.md) | `POST /` + `X-Amz-Target: Kinesis_20131202.*` | JSON 1.1 | 21 |
| [Secrets Manager](secrets-manager.md) | `POST /` + `X-Amz-Target: secretsmanager.*` | JSON 1.1 | 14 |
| [CloudFormation](cloudformation.md) | `POST /` with `Action=` param | Query | 20 |
| [Step Functions](step-functions.md) | `POST /` + `X-Amz-Target: AmazonStatesService.*` | JSON 1.1 | 12 |
| [IAM](iam.md) | `POST /` with `Action=` param | Query | 60+ |
| [STS](sts.md) | `POST /` with `Action=` param | Query | 7 |
| [ElastiCache](elasticache.md) | `POST /` with `Action=` param + TCP proxy | Query + RESP | 8 |
| [RDS](rds.md) | `POST /` with `Action=` param + TCP proxy | Query + wire | 13 |
| [EventBridge](eventbridge.md) | `POST /` + `X-Amz-Target: AmazonEventBridge.*` | JSON 1.1 | 14 |
| [CloudWatch Logs](cloudwatch.md) | `POST /` + `X-Amz-Target: Logs.*` | JSON 1.1 | 14 |
| [CloudWatch Metrics](cloudwatch.md#metrics) | `POST /` with `Action=` or JSON 1.1 | Query / JSON | 8 |
| [ACM](acm.md) | `POST /` + `X-Amz-Target: CertificateManager.*` | JSON 1.1 | 12 |

## Common Setup

Before calling any service, configure your AWS client to point to Floci:

```bash
export AWS_ENDPOINT=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
```