"""SQS queue integration tests."""

import json
import time

import pytest


class TestSQSQueue:
    """Test SQS queue operations."""

    def test_create_queue(self, sqs_client, unique_name):
        """Test CreateQueue creates a queue with correct URL."""
        queue_name = f"pytest-sdk-{unique_name}"

        try:
            response = sqs_client.create_queue(QueueName=queue_name)
            queue_url = response["QueueUrl"]
            assert queue_name in queue_url
        finally:
            sqs_client.delete_queue(QueueUrl=response["QueueUrl"])

    def test_get_queue_url(self, sqs_client, unique_name):
        """Test GetQueueUrl returns correct URL."""
        queue_name = f"pytest-sdk-{unique_name}"

        response = sqs_client.create_queue(QueueName=queue_name)
        queue_url = response["QueueUrl"]
        try:
            response = sqs_client.get_queue_url(QueueName=queue_name)
            assert response["QueueUrl"] == queue_url
        finally:
            sqs_client.delete_queue(QueueUrl=queue_url)

    def test_list_queues(self, sqs_client, unique_name):
        """Test ListQueues returns created queue."""
        queue_name = f"pytest-sdk-{unique_name}"

        response = sqs_client.create_queue(QueueName=queue_name)
        queue_url = response["QueueUrl"]
        try:
            response = sqs_client.list_queues(QueueNamePrefix=queue_name)
            assert any(queue_name in u for u in response.get("QueueUrls", []))
        finally:
            sqs_client.delete_queue(QueueUrl=queue_url)


class TestSQSMessages:
    """Test SQS message operations."""

    def test_send_message(self, sqs_client, test_queue):
        """Test SendMessage sends message with MessageId."""
        response = sqs_client.send_message(
            QueueUrl=test_queue, MessageBody="Hello from pytest!"
        )
        assert response.get("MessageId")

    def test_receive_message(self, sqs_client, test_queue):
        """Test ReceiveMessage receives sent message."""
        sqs_client.send_message(QueueUrl=test_queue, MessageBody="Hello from pytest!")

        response = sqs_client.receive_message(
            QueueUrl=test_queue, MaxNumberOfMessages=1
        )
        msgs = response.get("Messages", [])
        assert len(msgs) == 1
        assert msgs[0]["Body"] == "Hello from pytest!"

        # Cleanup
        sqs_client.delete_message(
            QueueUrl=test_queue, ReceiptHandle=msgs[0]["ReceiptHandle"]
        )

    def test_delete_message(self, sqs_client, test_queue):
        """Test DeleteMessage removes message from queue."""
        sqs_client.send_message(QueueUrl=test_queue, MessageBody="To delete")

        response = sqs_client.receive_message(
            QueueUrl=test_queue, MaxNumberOfMessages=1
        )
        receipt = response["Messages"][0]["ReceiptHandle"]
        sqs_client.delete_message(QueueUrl=test_queue, ReceiptHandle=receipt)

        # Queue should be empty
        response = sqs_client.receive_message(
            QueueUrl=test_queue, MaxNumberOfMessages=1
        )
        assert len(response.get("Messages", [])) == 0

    def test_send_message_batch(self, sqs_client, test_queue):
        """Test SendMessageBatch sends multiple messages."""
        response = sqs_client.send_message_batch(
            QueueUrl=test_queue,
            Entries=[
                {"Id": "m1", "MessageBody": "Batch 1"},
                {"Id": "m2", "MessageBody": "Batch 2"},
                {"Id": "m3", "MessageBody": "Batch 3"},
            ],
        )
        assert len(response["Successful"]) == 3
        assert len(response.get("Failed", [])) == 0

    def test_delete_message_batch(self, sqs_client, test_queue):
        """Test DeleteMessageBatch deletes multiple messages."""
        sqs_client.send_message_batch(
            QueueUrl=test_queue,
            Entries=[
                {"Id": "m1", "MessageBody": "Batch 1"},
                {"Id": "m2", "MessageBody": "Batch 2"},
                {"Id": "m3", "MessageBody": "Batch 3"},
            ],
        )

        response = sqs_client.receive_message(
            QueueUrl=test_queue, MaxNumberOfMessages=3
        )
        msgs = response.get("Messages", [])
        entries = [
            {"Id": f"d{i}", "ReceiptHandle": m["ReceiptHandle"]}
            for i, m in enumerate(msgs)
        ]

        response = sqs_client.delete_message_batch(QueueUrl=test_queue, Entries=entries)
        assert len(response["Successful"]) == 3

    def test_message_attributes(self, sqs_client, test_queue):
        """Test message with custom attributes."""
        sqs_client.send_message(
            QueueUrl=test_queue,
            MessageBody="msg-attrs",
            MessageAttributes={"myattr": {"DataType": "String", "StringValue": "myval"}},
        )

        response = sqs_client.receive_message(
            QueueUrl=test_queue,
            MaxNumberOfMessages=1,
            MessageAttributeNames=["All"],
        )
        msgs = response.get("Messages", [])
        assert len(msgs) == 1
        assert (
            msgs[0].get("MessageAttributes", {}).get("myattr", {}).get("StringValue")
            == "myval"
        )

        # Cleanup
        sqs_client.delete_message(
            QueueUrl=test_queue, ReceiptHandle=msgs[0]["ReceiptHandle"]
        )


class TestSQSAttributes:
    """Test SQS queue attribute operations."""

    def test_set_queue_attributes(self, sqs_client, test_queue):
        """Test SetQueueAttributes modifies queue attributes."""
        sqs_client.set_queue_attributes(
            QueueUrl=test_queue, Attributes={"VisibilityTimeout": "60"}
        )

        response = sqs_client.get_queue_attributes(
            QueueUrl=test_queue, AttributeNames=["VisibilityTimeout"]
        )
        assert response["Attributes"].get("VisibilityTimeout") == "60"


class TestSQSTags:
    """Test SQS queue tagging operations."""

    def test_tag_queue(self, sqs_client, test_queue):
        """Test TagQueue adds tags to queue."""
        sqs_client.tag_queue(
            QueueUrl=test_queue, Tags={"env": "test", "team": "backend"}
        )
        # If no exception, test passes

    def test_list_queue_tags(self, sqs_client, test_queue):
        """Test ListQueueTags returns queue tags."""
        sqs_client.tag_queue(
            QueueUrl=test_queue, Tags={"env": "test", "team": "backend"}
        )

        response = sqs_client.list_queue_tags(QueueUrl=test_queue)
        tags = response.get("Tags", {})
        assert tags.get("env") == "test"
        assert tags.get("team") == "backend"

    def test_untag_queue(self, sqs_client, test_queue):
        """Test UntagQueue removes tags from queue."""
        sqs_client.tag_queue(
            QueueUrl=test_queue, Tags={"env": "test", "team": "backend"}
        )
        sqs_client.untag_queue(QueueUrl=test_queue, TagKeys=["team"])

        response = sqs_client.list_queue_tags(QueueUrl=test_queue)
        tags = response.get("Tags", {})
        assert tags.get("env") == "test"
        assert "team" not in tags


class TestSQSLongPolling:
    """Test SQS long polling behavior."""

    def test_long_polling(self, sqs_client, test_queue):
        """Test long polling waits for messages."""
        start = time.time()
        sqs_client.receive_message(
            QueueUrl=test_queue, MaxNumberOfMessages=1, WaitTimeSeconds=2
        )
        elapsed = time.time() - start
        assert elapsed >= 1.8


class TestSQSDeadLetterQueue:
    """Test SQS dead letter queue operations."""

    def test_dlq_routing(self, sqs_client, unique_name):
        """Test messages are moved to DLQ after maxReceiveCount."""
        # Create main queue and DLQ
        main_queue_name = f"pytest-sdk-{unique_name}-main"
        dlq_name = f"pytest-sdk-{unique_name}-dlq"

        dlq_response = sqs_client.create_queue(QueueName=dlq_name)
        dlq_url = dlq_response["QueueUrl"]
        dlq_arn = sqs_client.get_queue_attributes(
            QueueUrl=dlq_url, AttributeNames=["QueueArn"]
        )["Attributes"]["QueueArn"]

        main_response = sqs_client.create_queue(QueueName=main_queue_name)
        main_url = main_response["QueueUrl"]

        try:
            # Set redrive policy
            redrive = json.dumps(
                {"maxReceiveCount": "2", "deadLetterTargetArn": dlq_arn}
            )
            sqs_client.set_queue_attributes(
                QueueUrl=main_url, Attributes={"RedrivePolicy": redrive}
            )

            # Send message and receive twice (to exceed maxReceiveCount)
            sqs_client.send_message(QueueUrl=main_url, MessageBody="dlq-test")

            m1 = sqs_client.receive_message(QueueUrl=main_url, MaxNumberOfMessages=1)[
                "Messages"
            ][0]
            sqs_client.change_message_visibility(
                QueueUrl=main_url, ReceiptHandle=m1["ReceiptHandle"], VisibilityTimeout=0
            )

            m2 = sqs_client.receive_message(QueueUrl=main_url, MaxNumberOfMessages=1)[
                "Messages"
            ][0]
            sqs_client.change_message_visibility(
                QueueUrl=main_url, ReceiptHandle=m2["ReceiptHandle"], VisibilityTimeout=0
            )

            # Main queue should be empty now
            r3 = sqs_client.receive_message(QueueUrl=main_url, MaxNumberOfMessages=1)
            assert len(r3.get("Messages", [])) == 0

            # Message should be in DLQ
            dlq_recv = sqs_client.receive_message(
                QueueUrl=dlq_url, MaxNumberOfMessages=1
            )
            dlq_msgs = dlq_recv.get("Messages", [])
            assert len(dlq_msgs) == 1
            assert dlq_msgs[0]["Body"] == "dlq-test"

        finally:
            sqs_client.delete_queue(QueueUrl=main_url)
            sqs_client.delete_queue(QueueUrl=dlq_url)

    def test_list_dead_letter_source_queues(self, sqs_client, unique_name):
        """Test ListDeadLetterSourceQueues returns source queues."""
        main_queue_name = f"pytest-sdk-{unique_name}-main"
        dlq_name = f"pytest-sdk-{unique_name}-dlq"

        dlq_response = sqs_client.create_queue(QueueName=dlq_name)
        dlq_url = dlq_response["QueueUrl"]
        dlq_arn = sqs_client.get_queue_attributes(
            QueueUrl=dlq_url, AttributeNames=["QueueArn"]
        )["Attributes"]["QueueArn"]

        main_response = sqs_client.create_queue(QueueName=main_queue_name)
        main_url = main_response["QueueUrl"]

        try:
            redrive = json.dumps(
                {"maxReceiveCount": "2", "deadLetterTargetArn": dlq_arn}
            )
            sqs_client.set_queue_attributes(
                QueueUrl=main_url, Attributes={"RedrivePolicy": redrive}
            )

            response = sqs_client.list_dead_letter_source_queues(QueueUrl=dlq_url)
            assert any(main_url == u for u in response.get("queueUrls", []))

        finally:
            sqs_client.delete_queue(QueueUrl=main_url)
            sqs_client.delete_queue(QueueUrl=dlq_url)
