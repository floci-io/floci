// Package testutil provides shared test utilities and AWS client factories.
package testutil

import (
	"archive/zip"
	"bytes"
	"context"
	"os"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/acm"
	"github.com/aws/aws-sdk-go-v2/service/cloudwatch"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/iam"
	"github.com/aws/aws-sdk-go-v2/service/kinesis"
	"github.com/aws/aws-sdk-go-v2/service/kms"
	"github.com/aws/aws-sdk-go-v2/service/lambda"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/aws/aws-sdk-go-v2/service/secretsmanager"
	"github.com/aws/aws-sdk-go-v2/service/sns"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	"github.com/aws/aws-sdk-go-v2/service/ssm"
	"github.com/aws/aws-sdk-go-v2/service/sts"
)

// Endpoint returns the Floci endpoint from environment or default.
func Endpoint() string {
	if ep := os.Getenv("FLOCI_ENDPOINT"); ep != "" {
		return ep
	}
	return "http://localhost:4566"
}

// Config returns an AWS config configured for the Floci endpoint.
func Config() aws.Config {
	ctx := context.Background()
	cfg, err := config.LoadDefaultConfig(ctx,
		config.WithRegion("us-east-1"),
		config.WithCredentialsProvider(credentials.NewStaticCredentialsProvider("test", "test", "")),
		config.WithBaseEndpoint(Endpoint()),
	)
	if err != nil {
		panic("failed to create AWS config: " + err.Error())
	}
	return cfg
}

// SSMClient returns a new SSM client.
func SSMClient() *ssm.Client {
	return ssm.NewFromConfig(Config())
}

// SQSClient returns a new SQS client.
func SQSClient() *sqs.Client {
	return sqs.NewFromConfig(Config())
}

// SNSClient returns a new SNS client.
func SNSClient() *sns.Client {
	return sns.NewFromConfig(Config())
}

// S3Client returns a new S3 client with path-style addressing.
func S3Client() *s3.Client {
	return s3.NewFromConfig(Config(), func(o *s3.Options) {
		o.UsePathStyle = true
	})
}

// DynamoDBClient returns a new DynamoDB client.
func DynamoDBClient() *dynamodb.Client {
	return dynamodb.NewFromConfig(Config())
}

// LambdaClient returns a new Lambda client.
func LambdaClient() *lambda.Client {
	return lambda.NewFromConfig(Config())
}

// IAMClient returns a new IAM client.
func IAMClient() *iam.Client {
	return iam.NewFromConfig(Config())
}

// STSClient returns a new STS client.
func STSClient() *sts.Client {
	return sts.NewFromConfig(Config())
}

// SecretsManagerClient returns a new Secrets Manager client.
func SecretsManagerClient() *secretsmanager.Client {
	return secretsmanager.NewFromConfig(Config())
}

// KMSClient returns a new KMS client.
func KMSClient() *kms.Client {
	return kms.NewFromConfig(Config())
}

// KinesisClient returns a new Kinesis client.
func KinesisClient() *kinesis.Client {
	return kinesis.NewFromConfig(Config())
}

// CloudWatchClient returns a new CloudWatch client.
func CloudWatchClient() *cloudwatch.Client {
	return cloudwatch.NewFromConfig(Config())
}

// ACMClient returns a new ACM client.
func ACMClient() *acm.Client {
	return acm.NewFromConfig(Config())
}

// MinimalZip returns a minimal Lambda deployment zip with a Node.js handler.
func MinimalZip() []byte {
	code := `exports.handler = async (event) => {
  const name = (event && event.name) ? event.name : "World";
  return { statusCode: 200, body: JSON.stringify({ message: "Hello, " + name + "!" }) };
};`
	var buf bytes.Buffer
	w := zip.NewWriter(&buf)
	f, _ := w.Create("index.js")
	f.Write([]byte(code))
	w.Close()
	return buf.Bytes()
}
