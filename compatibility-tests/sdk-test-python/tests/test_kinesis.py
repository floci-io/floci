"""Kinesis integration tests."""

import os

import pytest


# Disable CBOR — use JSON 1.1 as the emulator supports
os.environ["AWS_CBOR_DISABLE"] = "true"


class TestKinesisStream:
    """Test Kinesis stream operations."""

    def test_create_stream(self, kinesis_client, unique_name):
        """Test CreateStream creates a stream."""
        stream_name = f"pytest-stream-{unique_name}"

        try:
            kinesis_client.create_stream(StreamName=stream_name, ShardCount=1)
            # If no exception, test passes
        finally:
            kinesis_client.delete_stream(StreamName=stream_name)

    def test_list_streams(self, kinesis_client, unique_name):
        """Test ListStreams includes created stream."""
        stream_name = f"pytest-stream-{unique_name}"

        kinesis_client.create_stream(StreamName=stream_name, ShardCount=1)

        try:
            response = kinesis_client.list_streams()
            assert stream_name in response["StreamNames"]
        finally:
            kinesis_client.delete_stream(StreamName=stream_name)

    def test_describe_stream(self, kinesis_client, unique_name):
        """Test DescribeStream returns stream details."""
        stream_name = f"pytest-stream-{unique_name}"

        kinesis_client.create_stream(StreamName=stream_name, ShardCount=1)

        try:
            response = kinesis_client.describe_stream(StreamName=stream_name)
            assert response["StreamDescription"]["StreamName"] == stream_name
            assert response["StreamDescription"]["Shards"]
        finally:
            kinesis_client.delete_stream(StreamName=stream_name)

    def test_describe_stream_summary(self, kinesis_client, unique_name):
        """Test DescribeStreamSummary returns summary."""
        stream_name = f"pytest-stream-{unique_name}"

        kinesis_client.create_stream(StreamName=stream_name, ShardCount=1)

        try:
            response = kinesis_client.describe_stream_summary(StreamName=stream_name)
            assert response["StreamDescriptionSummary"]["StreamName"] == stream_name
        finally:
            kinesis_client.delete_stream(StreamName=stream_name)

    def test_delete_stream(self, kinesis_client, unique_name):
        """Test DeleteStream removes stream."""
        stream_name = f"pytest-stream-{unique_name}"

        kinesis_client.create_stream(StreamName=stream_name, ShardCount=1)
        kinesis_client.delete_stream(StreamName=stream_name)

        response = kinesis_client.list_streams()
        assert stream_name not in response.get("StreamNames", [])


class TestKinesisRecords:
    """Test Kinesis record operations."""

    def test_put_record(self, kinesis_client, unique_name):
        """Test PutRecord writes record to stream."""
        stream_name = f"pytest-stream-{unique_name}"
        data = b"hello kinesis pytest"

        kinesis_client.create_stream(StreamName=stream_name, ShardCount=1)

        try:
            response = kinesis_client.put_record(
                StreamName=stream_name, Data=data, PartitionKey="pk1"
            )
            assert response.get("SequenceNumber")
        finally:
            kinesis_client.delete_stream(StreamName=stream_name)

    def test_get_records(self, kinesis_client, unique_name):
        """Test GetRecords retrieves records from stream."""
        stream_name = f"pytest-stream-{unique_name}"
        data = b"hello kinesis pytest"

        kinesis_client.create_stream(StreamName=stream_name, ShardCount=1)

        try:
            # Put a record
            kinesis_client.put_record(
                StreamName=stream_name, Data=data, PartitionKey="pk1"
            )

            # Get shard iterator
            describe_response = kinesis_client.describe_stream(StreamName=stream_name)
            shard_id = describe_response["StreamDescription"]["Shards"][0]["ShardId"]

            iterator_response = kinesis_client.get_shard_iterator(
                StreamName=stream_name,
                ShardId=shard_id,
                ShardIteratorType="TRIM_HORIZON",
            )
            shard_iterator = iterator_response["ShardIterator"]

            # Get records
            response = kinesis_client.get_records(ShardIterator=shard_iterator)
            found = any(rec["Data"] == data for rec in response["Records"])
            assert found
        finally:
            kinesis_client.delete_stream(StreamName=stream_name)

    def test_put_records_batch(self, kinesis_client, unique_name):
        """Test PutRecords writes multiple records."""
        stream_name = f"pytest-stream-{unique_name}"

        kinesis_client.create_stream(StreamName=stream_name, ShardCount=1)

        try:
            response = kinesis_client.put_records(
                StreamName=stream_name,
                Records=[
                    {"Data": b"batch1", "PartitionKey": "pk1"},
                    {"Data": b"batch2", "PartitionKey": "pk2"},
                ],
            )
            assert response["FailedRecordCount"] == 0
            assert len(response["Records"]) == 2
        finally:
            kinesis_client.delete_stream(StreamName=stream_name)


class TestKinesisTags:
    """Test Kinesis tagging operations."""

    def test_add_tags_to_stream(self, kinesis_client, unique_name):
        """Test AddTagsToStream adds tags."""
        stream_name = f"pytest-stream-{unique_name}"

        kinesis_client.create_stream(StreamName=stream_name, ShardCount=1)

        try:
            kinesis_client.add_tags_to_stream(
                StreamName=stream_name, Tags={"project": "floci"}
            )

            response = kinesis_client.list_tags_for_stream(StreamName=stream_name)
            assert any(
                t["Key"] == "project" and t["Value"] == "floci" for t in response["Tags"]
            )
        finally:
            kinesis_client.delete_stream(StreamName=stream_name)
