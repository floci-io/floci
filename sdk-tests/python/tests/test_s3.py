"""S3 integration tests using boto3."""

import uuid


def test_create_bucket(s3_client):
    """Test creating an S3 bucket."""
    bucket_name = f"test-bucket-{uuid.uuid4().hex[:8]}"

    try:
        response = s3_client.create_bucket(Bucket=bucket_name)
        assert response["ResponseMetadata"]["HTTPStatusCode"] == 200
    finally:
        # Cleanup
        s3_client.delete_bucket(Bucket=bucket_name)


def test_list_buckets(s3_client, test_bucket):
    """Test listing S3 buckets."""
    response = s3_client.list_buckets()

    assert "Buckets" in response
    bucket_names = [b["Name"] for b in response["Buckets"]]
    assert test_bucket in bucket_names


def test_put_object(s3_client, test_bucket):
    """Test uploading an object to S3."""
    object_key = f"test-object-{uuid.uuid4().hex[:8]}.txt"
    object_body = b"Hello, floci!"

    response = s3_client.put_object(
        Bucket=test_bucket,
        Key=object_key,
        Body=object_body,
    )

    assert response["ResponseMetadata"]["HTTPStatusCode"] == 200


def test_get_object(s3_client, test_bucket):
    """Test downloading an object from S3."""
    object_key = f"test-object-{uuid.uuid4().hex[:8]}.txt"
    object_body = b"Hello, floci!"

    # Put object first
    s3_client.put_object(
        Bucket=test_bucket,
        Key=object_key,
        Body=object_body,
    )

    # Get object
    response = s3_client.get_object(Bucket=test_bucket, Key=object_key)
    retrieved_body = response["Body"].read()

    assert retrieved_body == object_body


def test_list_objects(s3_client, test_bucket):
    """Test listing objects in an S3 bucket."""
    object_key = f"test-object-{uuid.uuid4().hex[:8]}.txt"

    # Put object first
    s3_client.put_object(
        Bucket=test_bucket,
        Key=object_key,
        Body=b"test content",
    )

    # List objects
    response = s3_client.list_objects_v2(Bucket=test_bucket)

    assert "Contents" in response
    object_keys = [obj["Key"] for obj in response["Contents"]]
    assert object_key in object_keys


def test_delete_object(s3_client, test_bucket):
    """Test deleting an object from S3."""
    object_key = f"test-object-{uuid.uuid4().hex[:8]}.txt"

    # Put object first
    s3_client.put_object(
        Bucket=test_bucket,
        Key=object_key,
        Body=b"test content",
    )

    # Delete object
    response = s3_client.delete_object(Bucket=test_bucket, Key=object_key)
    assert response["ResponseMetadata"]["HTTPStatusCode"] == 204

    # Verify object is deleted
    list_response = s3_client.list_objects_v2(Bucket=test_bucket)
    if "Contents" in list_response:
        object_keys = [obj["Key"] for obj in list_response["Contents"]]
        assert object_key not in object_keys


def test_delete_bucket(s3_client):
    """Test deleting an S3 bucket."""
    bucket_name = f"test-bucket-{uuid.uuid4().hex[:8]}"

    # Create bucket
    s3_client.create_bucket(Bucket=bucket_name)

    # Delete bucket
    response = s3_client.delete_bucket(Bucket=bucket_name)
    assert response["ResponseMetadata"]["HTTPStatusCode"] == 204

    # Verify bucket is deleted
    list_response = s3_client.list_buckets()
    bucket_names = [b["Name"] for b in list_response["Buckets"]]
    assert bucket_name not in bucket_names
