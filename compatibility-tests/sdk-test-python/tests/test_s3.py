"""S3 bucket and object integration tests."""

import base64
import hashlib
import random
import zlib

import pytest
from boto3.s3.transfer import TransferConfig
from botocore.exceptions import ClientError

MIB = 1024 * 1024


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


class TestS3MultipartChecksums:
    """Multipart uploads carry composite checksums, computed as on AWS.

    AWS combines the part-level checksums of a multipart upload into a single object-level value:
    the algorithm applied to the concatenated binary part checksums, Base64 encoded, followed by
    ``-<part count>``. HeadObject/GetObject return that suffixed value, GetObjectAttributes returns
    it without the suffix. SHA1/SHA256 uploads are always COMPOSITE, and so are CRC32 uploads
    unless FULL_OBJECT is requested on CreateMultipartUpload.
    """

    PART_SIZE = 5 * MIB
    TRANSFER_CONFIG = TransferConfig(multipart_threshold=5 * MIB, multipart_chunksize=5 * MIB)

    @staticmethod
    def _payload(size=12 * MIB):
        return random.Random(20260903).randbytes(size)

    @staticmethod
    def _parts(payload, part_size):
        return [payload[i:i + part_size] for i in range(0, len(payload), part_size)]

    @staticmethod
    def _sha256(data):
        return hashlib.sha256(data).digest()

    @staticmethod
    def _crc32(data):
        return (zlib.crc32(data) & 0xFFFFFFFF).to_bytes(4, "big")

    @classmethod
    def _composite(cls, digest, parts):
        combined = digest(b"".join(digest(part) for part in parts))
        return base64.b64encode(combined).decode() + f"-{len(parts)}"

    def _upload(self, s3_client, bucket, key, payload, tmp_path, **extra_args):
        source = tmp_path / key
        source.write_bytes(payload)
        s3_client.upload_file(
            str(source), bucket, key,
            ExtraArgs=extra_args or None,
            Config=self.TRANSFER_CONFIG,
        )

    def test_multipart_upload_sha256_reports_composite_checksum(self, s3_client, test_bucket, tmp_path):
        """Multipart upload with SHA256 returns the composite checksum, stable across reads."""
        key = "multipart-sha256.bin"
        payload = self._payload()
        parts = self._parts(payload, self.PART_SIZE)
        composite = self._composite(self._sha256, parts)
        assert composite.endswith("-3")

        self._upload(s3_client, test_bucket, key, payload, tmp_path, ChecksumAlgorithm="SHA256")

        head = s3_client.head_object(Bucket=test_bucket, Key=key, ChecksumMode="ENABLED")
        assert head["ChecksumSHA256"] == composite
        assert head["ChecksumType"] == "COMPOSITE"
        assert head["ETag"].endswith('-3"')

        # The same value on every read: a client comparing checksums must not see drift
        again = s3_client.head_object(Bucket=test_bucket, Key=key, ChecksumMode="ENABLED")
        assert again["ChecksumSHA256"] == composite

        attributes = s3_client.get_object_attributes(
            Bucket=test_bucket, Key=key, ObjectAttributes=["Checksum", "ObjectParts"]
        )
        assert attributes["Checksum"]["ChecksumSHA256"] == composite.rsplit("-", 1)[0]
        assert attributes["Checksum"]["ChecksumType"] == "COMPOSITE"
        assert attributes["ObjectParts"]["TotalPartsCount"] == 3
        assert [p["ChecksumSHA256"] for p in attributes["ObjectParts"]["Parts"]] == [
            base64.b64encode(self._sha256(part)).decode() for part in parts
        ]

        body = s3_client.get_object(Bucket=test_bucket, Key=key)["Body"].read()
        assert body == payload

    def test_multipart_upload_default_checksum_is_composite_crc32(self, s3_client, test_bucket, tmp_path):
        """Without an explicit algorithm the SDK declares CRC32, which AWS stores as COMPOSITE."""
        key = "multipart-default.bin"
        payload = self._payload()
        parts = self._parts(payload, self.PART_SIZE)

        self._upload(s3_client, test_bucket, key, payload, tmp_path)

        head = s3_client.head_object(Bucket=test_bucket, Key=key, ChecksumMode="ENABLED")
        assert head["ChecksumType"] == "COMPOSITE"
        assert head["ChecksumCRC32"] == self._composite(self._crc32, parts)

        attributes = s3_client.get_object_attributes(
            Bucket=test_bucket, Key=key, ObjectAttributes=["Checksum", "ObjectParts"]
        )
        assert attributes["Checksum"]["ChecksumCRC32"] == head["ChecksumCRC32"].rsplit("-", 1)[0]
        assert [p["ChecksumCRC32"] for p in attributes["ObjectParts"]["Parts"]] == [
            base64.b64encode(self._crc32(part)).decode() for part in parts
        ]

    def test_single_part_upload_keeps_full_object_checksum(self, s3_client, test_bucket, tmp_path):
        """A file below the multipart threshold is a plain PutObject: FULL_OBJECT, no suffix."""
        key = "single-part-sha256.bin"
        payload = self._payload(1 * MIB)

        self._upload(s3_client, test_bucket, key, payload, tmp_path, ChecksumAlgorithm="SHA256")

        head = s3_client.head_object(Bucket=test_bucket, Key=key, ChecksumMode="ENABLED")
        assert head["ChecksumSHA256"] == base64.b64encode(self._sha256(payload)).decode()
        assert head["ChecksumType"] == "FULL_OBJECT"
        assert "-" not in head["ETag"]

    def test_copy_of_multipart_object_is_a_single_full_object(self, s3_client, test_bucket, tmp_path):
        """CopyObject writes one object: FULL_OBJECT checksum, an MD5 ETag without part count, no ObjectParts."""
        key, copy_key = "multipart-copy-source.bin", "multipart-copy.bin"
        payload = self._payload()
        self._upload(s3_client, test_bucket, key, payload, tmp_path, ChecksumAlgorithm="SHA256")
        source = s3_client.head_object(Bucket=test_bucket, Key=key, ChecksumMode="ENABLED")
        assert source["ChecksumType"] == "COMPOSITE"

        result = s3_client.copy_object(
            Bucket=test_bucket, Key=copy_key, CopySource={"Bucket": test_bucket, "Key": key}
        )["CopyObjectResult"]
        full = base64.b64encode(self._sha256(payload)).decode()
        assert result["ChecksumSHA256"] == full
        assert result["ChecksumType"] == "FULL_OBJECT"
        assert result["ETag"] == f'"{hashlib.md5(payload).hexdigest()}"'
        assert result["ETag"] != source["ETag"]

        head = s3_client.head_object(Bucket=test_bucket, Key=copy_key, ChecksumMode="ENABLED")
        assert (head["ChecksumSHA256"], head["ChecksumType"], head["ETag"]) == (full, "FULL_OBJECT", result["ETag"])
        attributes = s3_client.get_object_attributes(
            Bucket=test_bucket, Key=copy_key, ObjectAttributes=["Checksum", "ObjectParts"]
        )
        assert attributes["Checksum"]["ChecksumType"] == "FULL_OBJECT"
        assert "ObjectParts" not in attributes

    def test_get_object_attributes_etag_has_no_quotes(self, s3_client, test_bucket, tmp_path):
        """S3 quotes the ETag everywhere except in GetObjectAttributes."""
        key = "etag-quotes.bin"
        self._upload(s3_client, test_bucket, key, self._payload(), tmp_path, ChecksumAlgorithm="SHA256")

        head_etag = s3_client.head_object(Bucket=test_bucket, Key=key)["ETag"]
        attributes = s3_client.get_object_attributes(Bucket=test_bucket, Key=key, ObjectAttributes=["ETag"])

        assert head_etag.startswith('"') and head_etag.endswith('-3"')
        assert attributes["ETag"] == head_etag.strip('"')

    def test_download_with_checksum_mode_works_for_composite_and_full_object(self, s3_client, test_bucket, tmp_path):
        """botocore validates a full-object checksum on GetObject and skips a composite one (the -N suffix)."""
        payload, small = self._payload(), self._payload(1 * MIB)
        self._upload(s3_client, test_bucket, "download-composite.bin", payload, tmp_path, ChecksumAlgorithm="SHA256")
        self._upload(s3_client, test_bucket, "download-full.bin", small, tmp_path, ChecksumAlgorithm="SHA256")

        composite = s3_client.get_object(Bucket=test_bucket, Key="download-composite.bin", ChecksumMode="ENABLED")
        assert composite["ChecksumSHA256"].endswith("-3")
        assert composite["ChecksumType"] == "COMPOSITE"
        assert composite["Body"].read() == payload

        full = s3_client.get_object(Bucket=test_bucket, Key="download-full.bin", ChecksumMode="ENABLED")
        assert full["ChecksumType"] == "FULL_OBJECT"
        assert full["Body"].read() == small
