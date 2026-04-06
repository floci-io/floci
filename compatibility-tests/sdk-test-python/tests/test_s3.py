"""S3 bucket and object integration tests."""

import pytest
from botocore.exceptions import ClientError


class TestS3Bucket:
    """Test S3 bucket operations."""

    def test_create_bucket(self, s3_client, unique_name):
        """Test CreateBucket creates a bucket."""
        bucket_name = f"pytest-s3-{unique_name}"

        try:
            s3_client.create_bucket(Bucket=bucket_name)
            # Verify bucket exists
            s3_client.head_bucket(Bucket=bucket_name)
        finally:
            s3_client.delete_bucket(Bucket=bucket_name)

    def test_create_bucket_with_location_constraint(self, s3_client, unique_name):
        """Test CreateBucket with LocationConstraint (regression: issue #11)."""
        bucket_name = f"pytest-s3-eu-{unique_name}"

        try:
            s3_client.create_bucket(
                Bucket=bucket_name,
                CreateBucketConfiguration={"LocationConstraint": "eu-central-1"},
            )

            response = s3_client.get_bucket_location(Bucket=bucket_name)
            assert response.get("LocationConstraint") == "eu-central-1"
        finally:
            s3_client.delete_bucket(Bucket=bucket_name)

    def test_list_buckets(self, s3_client, unique_name):
        """Test ListBuckets returns created bucket."""
        bucket_name = f"pytest-s3-{unique_name}"

        s3_client.create_bucket(Bucket=bucket_name)
        try:
            response = s3_client.list_buckets()
            assert any(b["Name"] == bucket_name for b in response["Buckets"])
        finally:
            s3_client.delete_bucket(Bucket=bucket_name)

    def test_head_bucket(self, s3_client, test_bucket):
        """Test HeadBucket succeeds for existing bucket."""
        s3_client.head_bucket(Bucket=test_bucket)
        # If no exception, test passes

    def test_head_bucket_non_existent(self, s3_client):
        """Test HeadBucket returns 404 for non-existent bucket."""
        with pytest.raises(ClientError) as exc_info:
            s3_client.head_bucket(Bucket="non-existent-bucket-pytest-xyz")
        assert exc_info.value.response["ResponseMetadata"]["HTTPStatusCode"] == 404

    def test_get_bucket_location(self, s3_client, test_bucket):
        """Test GetBucketLocation returns location constraint."""
        response = s3_client.get_bucket_location(Bucket=test_bucket)
        assert "LocationConstraint" in response

    def test_delete_bucket(self, s3_client, unique_name):
        """Test DeleteBucket removes bucket."""
        bucket_name = f"pytest-s3-{unique_name}"

        s3_client.create_bucket(Bucket=bucket_name)
        s3_client.delete_bucket(Bucket=bucket_name)

        with pytest.raises(ClientError):
            s3_client.head_bucket(Bucket=bucket_name)


class TestS3Object:
    """Test S3 object operations."""

    def test_put_object(self, s3_client, test_bucket):
        """Test PutObject uploads object."""
        key = "test-file.txt"
        content = b"Hello from pytest!"

        s3_client.put_object(
            Bucket=test_bucket, Key=key, Body=content, ContentType="text/plain"
        )

        # Verify object exists
        response = s3_client.head_object(Bucket=test_bucket, Key=key)
        assert response["ContentLength"] == len(content)

    def test_list_objects(self, s3_client, test_bucket):
        """Test ListObjectsV2 returns uploaded objects."""
        key = "test-file.txt"
        s3_client.put_object(Bucket=test_bucket, Key=key, Body=b"content")

        response = s3_client.list_objects_v2(Bucket=test_bucket)
        assert any(o["Key"] == key for o in response.get("Contents", []))

    def test_get_object(self, s3_client, test_bucket):
        """Test GetObject retrieves correct content."""
        key = "test-file.txt"
        content = b"Hello from pytest!"
        s3_client.put_object(Bucket=test_bucket, Key=key, Body=content)

        response = s3_client.get_object(Bucket=test_bucket, Key=key)
        data = response["Body"].read()
        assert data == content

    def test_head_object(self, s3_client, test_bucket):
        """Test HeadObject returns metadata."""
        key = "test-file.txt"
        content = b"Hello from pytest!"
        s3_client.put_object(Bucket=test_bucket, Key=key, Body=content)

        response = s3_client.head_object(Bucket=test_bucket, Key=key)
        assert response["ContentLength"] == len(content)
        # LastModified should have second precision (microsecond == 0)
        assert response["LastModified"].microsecond == 0

    def test_delete_object(self, s3_client, test_bucket):
        """Test DeleteObject removes object."""
        key = "test-file.txt"
        s3_client.put_object(Bucket=test_bucket, Key=key, Body=b"content")

        s3_client.delete_object(Bucket=test_bucket, Key=key)

        response = s3_client.list_objects_v2(Bucket=test_bucket)
        assert not any(o["Key"] == key for o in response.get("Contents", []))

    def test_delete_objects_batch(self, s3_client, test_bucket):
        """Test DeleteObjects batch deletes multiple objects."""
        for i in range(1, 4):
            s3_client.put_object(
                Bucket=test_bucket, Key=f"batch-{i}.txt", Body=f"batch {i}".encode()
            )

        response = s3_client.delete_objects(
            Bucket=test_bucket,
            Delete={"Objects": [{"Key": f"batch-{i}.txt"} for i in range(1, 4)]},
        )
        assert len(response.get("Deleted", [])) == 3


class TestS3CopyObject:
    """Test S3 copy operations."""

    def test_copy_object_same_bucket(self, s3_client, test_bucket):
        """Test CopyObject within same bucket."""
        src_key = "src-file.txt"
        dst_key = "dst-file.txt"
        content = b"content to copy"

        s3_client.put_object(Bucket=test_bucket, Key=src_key, Body=content)

        response = s3_client.copy_object(
            CopySource={"Bucket": test_bucket, "Key": src_key},
            Bucket=test_bucket,
            Key=dst_key,
        )
        assert response.get("CopyObjectResult")

        # Verify copy
        get_response = s3_client.get_object(Bucket=test_bucket, Key=dst_key)
        assert get_response["Body"].read() == content

    def test_copy_object_cross_bucket(self, s3_client, test_bucket, unique_name):
        """Test CopyObject across buckets."""
        dest_bucket = f"pytest-s3-copy-{unique_name}"
        src_key = "src-file.txt"
        dst_key = "dst-file.txt"
        content = b"content to copy"

        s3_client.put_object(Bucket=test_bucket, Key=src_key, Body=content)
        s3_client.create_bucket(Bucket=dest_bucket)

        try:
            response = s3_client.copy_object(
                CopySource={"Bucket": test_bucket, "Key": src_key},
                Bucket=dest_bucket,
                Key=dst_key,
            )
            assert response.get("CopyObjectResult")

            # Verify copy
            get_response = s3_client.get_object(Bucket=dest_bucket, Key=dst_key)
            assert get_response["Body"].read() == content
        finally:
            s3_client.delete_object(Bucket=dest_bucket, Key=dst_key)
            s3_client.delete_bucket(Bucket=dest_bucket)

    def test_copy_object_non_ascii_key(self, s3_client, test_bucket):
        """Test CopyObject with non-ASCII characters in key."""
        src_key = "src/テスト画像.png"
        dst_key = "dst/テスト画像.png"
        content = b"non-ascii content"

        s3_client.put_object(Bucket=test_bucket, Key=src_key, Body=content)

        response = s3_client.copy_object(
            CopySource={"Bucket": test_bucket, "Key": src_key},
            Bucket=test_bucket,
            Key=dst_key,
        )
        assert response.get("CopyObjectResult")

        # Verify copy
        get_response = s3_client.get_object(Bucket=test_bucket, Key=dst_key)
        assert get_response["Body"].read() == content


class TestS3ObjectTagging:
    """Test S3 object tagging operations."""

    def test_put_object_tagging(self, s3_client, test_bucket):
        """Test PutObjectTagging adds tags to object."""
        key = "tagged-file.txt"
        s3_client.put_object(Bucket=test_bucket, Key=key, Body=b"content")

        s3_client.put_object_tagging(
            Bucket=test_bucket,
            Key=key,
            Tagging={
                "TagSet": [
                    {"Key": "env", "Value": "test"},
                    {"Key": "project", "Value": "floci"},
                ]
            },
        )
        # If no exception, test passes

    def test_get_object_tagging(self, s3_client, test_bucket):
        """Test GetObjectTagging returns tags."""
        key = "tagged-file.txt"
        s3_client.put_object(Bucket=test_bucket, Key=key, Body=b"content")
        s3_client.put_object_tagging(
            Bucket=test_bucket,
            Key=key,
            Tagging={
                "TagSet": [
                    {"Key": "env", "Value": "test"},
                    {"Key": "project", "Value": "floci"},
                ]
            },
        )

        response = s3_client.get_object_tagging(Bucket=test_bucket, Key=key)
        tags = {t["Key"]: t["Value"] for t in response["TagSet"]}
        assert tags.get("env") == "test"
        assert tags.get("project") == "floci"

    def test_delete_object_tagging(self, s3_client, test_bucket):
        """Test DeleteObjectTagging removes tags."""
        key = "tagged-file.txt"
        s3_client.put_object(Bucket=test_bucket, Key=key, Body=b"content")
        s3_client.put_object_tagging(
            Bucket=test_bucket,
            Key=key,
            Tagging={"TagSet": [{"Key": "env", "Value": "test"}]},
        )

        s3_client.delete_object_tagging(Bucket=test_bucket, Key=key)

        response = s3_client.get_object_tagging(Bucket=test_bucket, Key=key)
        assert len(response["TagSet"]) == 0


class TestS3BucketTagging:
    """Test S3 bucket tagging operations."""

    def test_put_bucket_tagging(self, s3_client, test_bucket):
        """Test PutBucketTagging adds tags to bucket."""
        s3_client.put_bucket_tagging(
            Bucket=test_bucket,
            Tagging={
                "TagSet": [
                    {"Key": "team", "Value": "backend"},
                    {"Key": "cost-center", "Value": "123"},
                ]
            },
        )
        # If no exception, test passes

    def test_get_bucket_tagging(self, s3_client, test_bucket):
        """Test GetBucketTagging returns tags."""
        s3_client.put_bucket_tagging(
            Bucket=test_bucket,
            Tagging={
                "TagSet": [
                    {"Key": "team", "Value": "backend"},
                    {"Key": "cost-center", "Value": "123"},
                ]
            },
        )

        response = s3_client.get_bucket_tagging(Bucket=test_bucket)
        tags = {t["Key"]: t["Value"] for t in response["TagSet"]}
        assert tags.get("team") == "backend"
        assert tags.get("cost-center") == "123"

    def test_delete_bucket_tagging(self, s3_client, test_bucket):
        """Test DeleteBucketTagging removes tags."""
        s3_client.put_bucket_tagging(
            Bucket=test_bucket,
            Tagging={"TagSet": [{"Key": "team", "Value": "backend"}]},
        )

        s3_client.delete_bucket_tagging(Bucket=test_bucket)

        # Either empty tags or NoSuchTagSet error
        try:
            response = s3_client.get_bucket_tagging(Bucket=test_bucket)
            assert len(response.get("TagSet", [])) == 0
        except ClientError:
            pass  # NoSuchTagSet is also acceptable


class TestS3LargeObject:
    """Test S3 large object operations."""

    def test_put_object_25mb(self, s3_client, test_bucket):
        """Test PutObject with 25 MB payload."""
        key = "large-object-25mb.bin"
        large_payload = b"\x00" * (25 * 1024 * 1024)

        s3_client.put_object(
            Bucket=test_bucket,
            Key=key,
            Body=large_payload,
            ContentType="application/octet-stream",
        )

        response = s3_client.head_object(Bucket=test_bucket, Key=key)
        assert response["ContentLength"] == 25 * 1024 * 1024
