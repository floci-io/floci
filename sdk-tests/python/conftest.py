"""Shared fixtures for S3 integration tests."""

import os
import uuid

import boto3
import pytest


@pytest.fixture
def s3_client():
    """Create S3 client with environment-based configuration."""
    return boto3.client(
        "s3",
        endpoint_url=os.environ.get("FLOCI_ENDPOINT", "http://localhost:4566"),
        region_name=os.environ.get("AWS_DEFAULT_REGION", "us-east-1"),
        aws_access_key_id=os.environ.get("AWS_ACCESS_KEY_ID", "test"),
        aws_secret_access_key=os.environ.get("AWS_SECRET_ACCESS_KEY", "test"),
    )


@pytest.fixture
def test_bucket(s3_client):
    """Create and cleanup a unique test bucket."""
    bucket_name = f"test-bucket-{uuid.uuid4().hex[:8]}"
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
    except Exception as e:
        print(f"Cleanup error: {e}")
