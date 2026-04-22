"""Shared fixtures for AWS service integration tests."""

import os
import uuid
import io
import zipfile

import logging

import boto3
import pytest
from botocore.config import Config
from botocore.exceptions import ClientError

logger = logging.getLogger(__name__)


@pytest.fixture(scope="session")
def endpoint_url():
    """Return the Floci endpoint URL."""
    return os.environ.get("FLOCI_ENDPOINT", "http://localhost:4566")


@pytest.fixture(scope="session")
def aws_config(endpoint_url):
    """Common AWS configuration from environment variables."""
    return {
        "endpoint_url": endpoint_url,
        "region_name": os.environ.get("AWS_DEFAULT_REGION", "us-east-1"),
        "aws_access_key_id": os.environ.get("AWS_ACCESS_KEY_ID", "test"),
        "aws_secret_access_key": os.environ.get("AWS_SECRET_ACCESS_KEY", "test"),
    }


@pytest.fixture(scope="session")
def client_config():
    """Botocore client configuration with retry settings."""
    return Config(retries={"max_attempts": 1})


# ============================================
# Service Client Fixtures
# ============================================


@pytest.fixture
def ssm_client(aws_config, client_config):
    """Create SSM client."""
    return boto3.client("ssm", config=client_config, **aws_config)


@pytest.fixture
def sqs_client(aws_config, client_config):
    """Create SQS client."""
    return boto3.client("sqs", config=client_config, **aws_config)


@pytest.fixture
def sns_client(aws_config, client_config):
    """Create SNS client."""
    return boto3.client("sns", config=client_config, **aws_config)


@pytest.fixture
def s3_client(aws_config, client_config):
    """Create S3 client."""
    return boto3.client("s3", config=client_config, **aws_config)


@pytest.fixture
def dynamodb_client(aws_config, client_config):
    """Create DynamoDB client."""
    return boto3.client("dynamodb", config=client_config, **aws_config)


@pytest.fixture
def lambda_client(aws_config, client_config):
    """Create Lambda client."""
    return boto3.client("lambda", config=client_config, **aws_config)


@pytest.fixture
def iam_client(aws_config, client_config):
    """Create IAM client."""
    return boto3.client("iam", config=client_config, **aws_config)


@pytest.fixture
def sts_client(aws_config, client_config):
    """Create STS client."""
    return boto3.client("sts", config=client_config, **aws_config)


@pytest.fixture
def secretsmanager_client(aws_config, client_config):
    """Create Secrets Manager client."""
    return boto3.client("secretsmanager", config=client_config, **aws_config)


@pytest.fixture
def kms_client(aws_config, client_config):
    """Create KMS client."""
    return boto3.client("kms", config=client_config, **aws_config)


@pytest.fixture
def kinesis_client(aws_config, client_config):
    """Create Kinesis client."""
    return boto3.client("kinesis", config=client_config, **aws_config)


@pytest.fixture
def cloudwatch_client(aws_config, client_config):
    """Create CloudWatch client."""
    return boto3.client("cloudwatch", config=client_config, **aws_config)


@pytest.fixture
def logs_client(aws_config, client_config):
    """Create CloudWatch Logs client."""
    return boto3.client("logs", config=client_config, **aws_config)


@pytest.fixture
def cognito_client(aws_config, client_config):
    """Create Cognito Identity Provider client."""
    return boto3.client("cognito-idp", config=client_config, **aws_config)


@pytest.fixture
def cloudformation_client(aws_config, client_config):
    """Create CloudFormation client."""
    return boto3.client("cloudformation", config=client_config, **aws_config)


@pytest.fixture
def acm_client(aws_config, client_config):
    """Create ACM client."""
    return boto3.client("acm", config=client_config, **aws_config)


@pytest.fixture
def ecr_client(aws_config, client_config):
    """Create ECR client."""
    return boto3.client("ecr", config=client_config, **aws_config)


@pytest.fixture
def pipes_client(aws_config, client_config):
    """Create EventBridge Pipes client."""
    return boto3.client("pipes", config=client_config, **aws_config)


# ============================================
# Utility Fixtures
# ============================================


@pytest.fixture
def unique_name():
    """Generate a unique name for test resources."""
    return f"pytest-{uuid.uuid4().hex[:8]}"


@pytest.fixture
def minimal_lambda_zip():
    """Create a minimal Node.js Lambda deployment package."""
    code = (
        "exports.handler = async (event) => {\n"
        "  console.log('[handler] event:', JSON.stringify(event));\n"
        "  const name = (event && event.name) ? event.name : 'World';\n"
        "  return { statusCode: 200, body: JSON.stringify({ message: `Hello, ${name}!`, input: event }) };\n"
        "};\n"
    )
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr("index.js", code)
    return buf.getvalue()


# ============================================
# Resource Fixtures with Cleanup
# ============================================


@pytest.fixture
def test_bucket(s3_client, unique_name):
    """Create and cleanup a test S3 bucket."""
    bucket_name = f"test-bucket-{unique_name}"
    s3_client.create_bucket(Bucket=bucket_name)

    yield bucket_name

    # Cleanup: empty and delete bucket
    try:
        paginator = s3_client.get_paginator("list_objects_v2")
        for page in paginator.paginate(Bucket=bucket_name):
            if "Contents" in page:
                for obj in page["Contents"]:
                    s3_client.delete_object(Bucket=bucket_name, Key=obj["Key"])
        s3_client.delete_bucket(Bucket=bucket_name)
    except ClientError as e:
        logger.warning("Failed to clean up S3 bucket %s: %s", bucket_name, e)


@pytest.fixture
def test_queue(sqs_client, unique_name):
    """Create and cleanup a test SQS queue."""
    queue_name = f"test-queue-{unique_name}"
    response = sqs_client.create_queue(QueueName=queue_name)
    queue_url = response["QueueUrl"]

    yield queue_url

    # Cleanup
    try:
        sqs_client.delete_queue(QueueUrl=queue_url)
    except ClientError as e:
        logger.warning("Failed to clean up SQS queue %s: %s", queue_url, e)


@pytest.fixture
def test_topic(sns_client, unique_name):
    """Create and cleanup a test SNS topic."""
    topic_name = f"test-topic-{unique_name}"
    response = sns_client.create_topic(Name=topic_name)
    topic_arn = response["TopicArn"]

    yield topic_arn

    # Cleanup
    try:
        sns_client.delete_topic(TopicArn=topic_arn)
    except ClientError as e:
        logger.warning("Failed to clean up SNS topic %s: %s", topic_arn, e)


@pytest.fixture
def test_table(dynamodb_client, unique_name):
    """Create and cleanup a test DynamoDB table."""
    table_name = f"test-table-{unique_name}"
    dynamodb_client.create_table(
        TableName=table_name,
        KeySchema=[{"AttributeName": "pk", "KeyType": "HASH"}],
        AttributeDefinitions=[{"AttributeName": "pk", "AttributeType": "S"}],
        BillingMode="PAY_PER_REQUEST",
    )

    # Wait for table to be active
    waiter = dynamodb_client.get_waiter("table_exists")
    waiter.wait(TableName=table_name, WaiterConfig={"Delay": 1, "MaxAttempts": 30})

    yield table_name

    # Cleanup
    try:
        dynamodb_client.delete_table(TableName=table_name)
    except ClientError as e:
        logger.warning("Failed to clean up DynamoDB table %s: %s", table_name, e)
