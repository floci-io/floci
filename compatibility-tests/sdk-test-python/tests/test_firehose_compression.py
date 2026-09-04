"""Firehose S3 delivery compression (issue #2328).

The delivered S3 object must be compressed the way the stream's CompressionFormat
says, with the key extension and Content-Encoding real AWS uses. In CI this suite
also runs against the amd64 native image, where the two Snappy formats depend on
a JNI library bundled per platform, so this test is the proof that the x86_64
library survived the native build.

Both Snappy variants are container formats around raw Snappy blocks: ``Snappy`` is
the xerial snappy-java stream format (not the official framing format) and
``HADOOP_SNAPPY`` is Hadoop's block framing. Neither has a Python reader without
native system libraries, so the framing is parsed here and only the raw blocks go
through cramjam.
"""

import gzip
import io
import logging
import struct
import time
import uuid
import zipfile

import boto3
import cramjam
import pytest
from botocore.exceptions import ClientError

logger = logging.getLogger(__name__)

RECORD = '{"seq":1}\n'
RECORD_COUNT = 5
EXPECTED_PAYLOAD = (RECORD * RECORD_COUNT).encode()
# The buffering interval has to elapse before delivery happens, so it is kept short.
# AWS accepts 0..900, so this is still a contract-faithful stream; against Floci the
# flush lands on the next tick of its background flusher.
BUFFERING_INTERVAL_SECONDS = 5
POLL_INTERVAL_SECONDS = 5
POLL_TIMEOUT_SECONDS = 240
ROLE_ARN = "arn:aws:iam::000000000000:role/firehose-role"

XERIAL_MAGIC = b"\x82SNAPPY\x00"


def _unzip(body):
    with zipfile.ZipFile(io.BytesIO(body)) as archive:
        names = archive.namelist()
        assert len(names) == 1, f"expected a single zip entry, got {names}"
        return archive.read(names[0])


def _raw_snappy(chunk):
    return bytes(cramjam.snappy.decompress_raw(chunk))


def _xerial_snappy(body):
    """snappy-java's SnappyOutputStream format.

    A 16 byte header (8 byte magic, 4 byte version, 4 byte compatible version), then
    chunks of a big-endian length and a raw Snappy block. A negative length marks an
    uncompressed chunk of that many bytes, which snappy-java emits when compression
    would not shrink a block.
    """
    assert body[:8] == XERIAL_MAGIC, f"not a snappy-java stream: {body[:8]!r}"
    out = bytearray()
    offset = 16
    while offset < len(body):
        (length,) = struct.unpack(">i", body[offset:offset + 4])
        offset += 4
        if length < 0:
            out += body[offset:offset - length]
            offset -= length
        else:
            out += _raw_snappy(body[offset:offset + length])
            offset += length
    return bytes(out)


def _hadoop_snappy(body):
    """Hadoop's block framing.

    Per block, a big-endian uncompressed block length followed by one or more chunks
    of a big-endian compressed length and a raw Snappy block. The leading length
    belongs to the first block, not the whole payload, so anything above the 32 KiB
    block size carries several blocks and must be looped over.
    """
    out = bytearray()
    offset = 0
    while offset < len(body):
        (block_length,) = struct.unpack(">i", body[offset:offset + 4])
        offset += 4
        produced = 0
        while produced < block_length:
            (chunk_length,) = struct.unpack(">i", body[offset:offset + 4])
            offset += 4
            plain = _raw_snappy(body[offset:offset + chunk_length])
            offset += chunk_length
            out += plain
            produced += len(plain)
        assert produced == block_length, f"block decoded to {produced} bytes, header said {block_length}"
    return bytes(out)


# (CompressionFormat as the API spells it, S3 key extension, Content-Encoding, decoder).
# HADOOP_SNAPPY shares the .snappy extension with Snappy and differs only in framing
# and Content-Encoding: the S3 object name documentation tables .hsnappy, but the
# service delivers .snappy (verified against real AWS in issue #2328).
FORMATS = [
    ("UNCOMPRESSED", "", None, bytes),
    ("GZIP", ".gz", "gzip", gzip.decompress),
    ("ZIP", ".zip", "zip", _unzip),
    ("Snappy", ".snappy", "snappy-java", _xerial_snappy),
    ("HADOOP_SNAPPY", ".snappy", "hadoop-snappy", _hadoop_snappy),
]


def _slug(compression_format):
    return compression_format.lower().replace("_", "-")


@pytest.fixture(scope="class")
def delivered(aws_config, client_config):
    """One bucket and one stream per format, all created and filled up front.

    A single buffering interval is then shared by every format instead of being
    waited out once per test.
    """
    firehose = boto3.client("firehose", config=client_config, **aws_config)
    s3 = boto3.client("s3", config=client_config, **aws_config)
    suffix = uuid.uuid4().hex[:8]
    bucket = f"firehose-compression-{suffix}"
    stream_prefix = f"sdk-compression-{suffix}"
    streams = {}

    s3.create_bucket(Bucket=bucket)
    for compression_format, _, _, _ in FORMATS:
        stream_name = f"{stream_prefix}-{_slug(compression_format)}"
        streams[compression_format] = stream_name
        firehose.create_delivery_stream(
            DeliveryStreamName=stream_name,
            DeliveryStreamType="DirectPut",
            ExtendedS3DestinationConfiguration={
                "RoleARN": ROLE_ARN,
                "BucketARN": f"arn:aws:s3:::{bucket}",
                "Prefix": f"{_slug(compression_format)}/",
                "CompressionFormat": compression_format,
                "BufferingHints": {
                    "IntervalInSeconds": BUFFERING_INTERVAL_SECONDS,
                    "SizeInMBs": 1,
                },
            },
        )
    for stream_name in streams.values():
        response = firehose.put_record_batch(
            DeliveryStreamName=stream_name,
            Records=[{"Data": RECORD.encode()} for _ in range(RECORD_COUNT)],
        )
        assert response["FailedPutCount"] == 0, response

    yield {"s3": s3, "bucket": bucket}

    for stream_name in streams.values():
        try:
            firehose.delete_delivery_stream(DeliveryStreamName=stream_name)
        except ClientError as e:
            logger.warning("delete_delivery_stream %s failed: %s", stream_name, e)
    try:
        paginator = s3.get_paginator("list_objects_v2")
        for page in paginator.paginate(Bucket=bucket):
            for obj in page.get("Contents", []):
                s3.delete_object(Bucket=bucket, Key=obj["Key"])
        s3.delete_bucket(Bucket=bucket)
    except ClientError as e:
        logger.warning("bucket cleanup %s failed: %s", bucket, e)


def _wait_for_delivered_key(s3, bucket, prefix):
    deadline = time.time() + POLL_TIMEOUT_SECONDS
    while time.time() < deadline:
        contents = s3.list_objects_v2(Bucket=bucket, Prefix=prefix).get("Contents", [])
        if contents:
            return contents[0]["Key"]
        time.sleep(POLL_INTERVAL_SECONDS)
    raise AssertionError(f"no object delivered under {prefix} within {POLL_TIMEOUT_SECONDS}s")


@pytest.mark.timeout(POLL_TIMEOUT_SECONDS + 60)
class TestFirehoseCompressionDelivery:

    @pytest.mark.parametrize(
        "compression_format,extension,content_encoding,decode",
        FORMATS,
        ids=[_slug(f[0]) for f in FORMATS],
    )
    def test_delivered_object_is_compressed_as_declared(
        self, delivered, compression_format, extension, content_encoding, decode
    ):
        s3 = delivered["s3"]
        bucket = delivered["bucket"]

        key = _wait_for_delivered_key(s3, bucket, f"{_slug(compression_format)}/")
        assert key.endswith(extension), f"{key} should end with {extension!r}"
        if not extension:
            assert not key.endswith((".gz", ".zip", ".snappy")), key

        response = s3.get_object(Bucket=bucket, Key=key)
        assert response["ContentType"] == "application/octet-stream"
        assert response.get("ContentEncoding") == content_encoding
        body = response["Body"].read()
        assert decode(body) == EXPECTED_PAYLOAD, "delivered body must decode to the records that were put"
