"""SNS topic integration tests."""

import time

import pytest


class TestSNSTopic:
    """Test SNS topic operations."""

    def test_create_topic(self, sns_client, unique_name):
        """Test CreateTopic creates a topic with correct ARN."""
        topic_name = f"pytest-sns-{unique_name}"

        try:
            response = sns_client.create_topic(Name=topic_name)
            topic_arn = response["TopicArn"]
            assert topic_name in topic_arn
        finally:
            sns_client.delete_topic(TopicArn=response["TopicArn"])

    def test_list_topics(self, sns_client, unique_name):
        """Test ListTopics returns created topic."""
        topic_name = f"pytest-sns-{unique_name}"

        response = sns_client.create_topic(Name=topic_name)
        topic_arn = response["TopicArn"]
        try:
            response = sns_client.list_topics()
            assert any(t["TopicArn"] == topic_arn for t in response["Topics"])
        finally:
            sns_client.delete_topic(TopicArn=topic_arn)

    def test_get_topic_attributes(self, sns_client, unique_name):
        """Test GetTopicAttributes returns topic details."""
        topic_name = f"pytest-sns-{unique_name}"

        response = sns_client.create_topic(Name=topic_name)
        topic_arn = response["TopicArn"]
        try:
            response = sns_client.get_topic_attributes(TopicArn=topic_arn)
            assert "TopicArn" in response["Attributes"]
        finally:
            sns_client.delete_topic(TopicArn=topic_arn)

    def test_delete_topic(self, sns_client, unique_name):
        """Test DeleteTopic removes topic."""
        topic_name = f"pytest-sns-{unique_name}"

        response = sns_client.create_topic(Name=topic_name)
        topic_arn = response["TopicArn"]
        sns_client.delete_topic(TopicArn=topic_arn)

        # Topic should not be in list
        response = sns_client.list_topics()
        assert not any(t["TopicArn"] == topic_arn for t in response.get("Topics", []))


class TestSNSSubscription:
    """Test SNS subscription operations."""

    def test_subscribe_sqs(self, sns_client, sqs_client, unique_name):
        """Test Subscribe with SQS endpoint."""
        topic_name = f"pytest-sns-{unique_name}"
        queue_name = f"pytest-sns-queue-{unique_name}"

        topic_response = sns_client.create_topic(Name=topic_name)
        topic_arn = topic_response["TopicArn"]

        queue_response = sqs_client.create_queue(QueueName=queue_name)
        queue_url = queue_response["QueueUrl"]
        queue_arn = sqs_client.get_queue_attributes(
            QueueUrl=queue_url, AttributeNames=["QueueArn"]
        )["Attributes"]["QueueArn"]

        try:
            response = sns_client.subscribe(
                TopicArn=topic_arn, Protocol="sqs", Endpoint=queue_arn
            )
            sub_arn = response["SubscriptionArn"]
            assert sub_arn
        finally:
            sns_client.delete_topic(TopicArn=topic_arn)
            sqs_client.delete_queue(QueueUrl=queue_url)

    def test_list_subscriptions_by_topic(self, sns_client, sqs_client, unique_name):
        """Test ListSubscriptionsByTopic returns subscriptions."""
        topic_name = f"pytest-sns-{unique_name}"
        queue_name = f"pytest-sns-queue-{unique_name}"

        topic_response = sns_client.create_topic(Name=topic_name)
        topic_arn = topic_response["TopicArn"]

        queue_response = sqs_client.create_queue(QueueName=queue_name)
        queue_url = queue_response["QueueUrl"]
        queue_arn = sqs_client.get_queue_attributes(
            QueueUrl=queue_url, AttributeNames=["QueueArn"]
        )["Attributes"]["QueueArn"]

        sub_response = sns_client.subscribe(
            TopicArn=topic_arn, Protocol="sqs", Endpoint=queue_arn
        )
        sub_arn = sub_response["SubscriptionArn"]

        try:
            response = sns_client.list_subscriptions_by_topic(TopicArn=topic_arn)
            assert any(
                s["SubscriptionArn"] == sub_arn for s in response["Subscriptions"]
            )
        finally:
            sns_client.unsubscribe(SubscriptionArn=sub_arn)
            sns_client.delete_topic(TopicArn=topic_arn)
            sqs_client.delete_queue(QueueUrl=queue_url)

    def test_unsubscribe(self, sns_client, sqs_client, unique_name):
        """Test Unsubscribe removes subscription."""
        topic_name = f"pytest-sns-{unique_name}"
        queue_name = f"pytest-sns-queue-{unique_name}"

        topic_response = sns_client.create_topic(Name=topic_name)
        topic_arn = topic_response["TopicArn"]

        queue_response = sqs_client.create_queue(QueueName=queue_name)
        queue_url = queue_response["QueueUrl"]
        queue_arn = sqs_client.get_queue_attributes(
            QueueUrl=queue_url, AttributeNames=["QueueArn"]
        )["Attributes"]["QueueArn"]

        sub_response = sns_client.subscribe(
            TopicArn=topic_arn, Protocol="sqs", Endpoint=queue_arn
        )
        sub_arn = sub_response["SubscriptionArn"]

        try:
            sns_client.unsubscribe(SubscriptionArn=sub_arn)
            # Unsubscribe should succeed without exception
        finally:
            sns_client.delete_topic(TopicArn=topic_arn)
            sqs_client.delete_queue(QueueUrl=queue_url)


class TestSNSPublish:
    """Test SNS publish operations."""

    def test_publish(self, sns_client, unique_name):
        """Test Publish sends message and returns MessageId."""
        topic_name = f"pytest-sns-{unique_name}"

        topic_response = sns_client.create_topic(Name=topic_name)
        topic_arn = topic_response["TopicArn"]

        try:
            response = sns_client.publish(
                TopicArn=topic_arn,
                Message="hello from pytest sns",
                Subject="test",
            )
            assert response.get("MessageId")
        finally:
            sns_client.delete_topic(TopicArn=topic_arn)

    def test_publish_sqs_delivery(self, sns_client, sqs_client, unique_name):
        """Test message is delivered to SQS subscription."""
        topic_name = f"pytest-sns-{unique_name}"
        queue_name = f"pytest-sns-queue-{unique_name}"

        topic_response = sns_client.create_topic(Name=topic_name)
        topic_arn = topic_response["TopicArn"]

        queue_response = sqs_client.create_queue(QueueName=queue_name)
        queue_url = queue_response["QueueUrl"]
        queue_arn = sqs_client.get_queue_attributes(
            QueueUrl=queue_url, AttributeNames=["QueueArn"]
        )["Attributes"]["QueueArn"]

        sub_response = sns_client.subscribe(
            TopicArn=topic_arn, Protocol="sqs", Endpoint=queue_arn
        )
        sub_arn = sub_response["SubscriptionArn"]

        try:
            sns_client.publish(
                TopicArn=topic_arn,
                Message="hello from pytest sns",
                Subject="test",
            )

            time.sleep(0.5)
            response = sqs_client.receive_message(
                QueueUrl=queue_url, MaxNumberOfMessages=1, WaitTimeSeconds=2
            )
            msgs = response.get("Messages", [])
            assert len(msgs) > 0
            assert "hello from pytest sns" in msgs[0]["Body"]

        finally:
            sns_client.unsubscribe(SubscriptionArn=sub_arn)
            sns_client.delete_topic(TopicArn=topic_arn)
            sqs_client.delete_queue(QueueUrl=queue_url)

    def test_publish_with_message_attributes(self, sns_client, sqs_client, unique_name):
        """Test publish with message attributes delivered to SQS."""
        topic_name = f"pytest-sns-{unique_name}"
        queue_name = f"pytest-sns-queue-{unique_name}"

        topic_response = sns_client.create_topic(Name=topic_name)
        topic_arn = topic_response["TopicArn"]

        queue_response = sqs_client.create_queue(QueueName=queue_name)
        queue_url = queue_response["QueueUrl"]
        queue_arn = sqs_client.get_queue_attributes(
            QueueUrl=queue_url, AttributeNames=["QueueArn"]
        )["Attributes"]["QueueArn"]

        sub_response = sns_client.subscribe(
            TopicArn=topic_arn, Protocol="sqs", Endpoint=queue_arn
        )
        sub_arn = sub_response["SubscriptionArn"]

        try:
            sns_client.publish(
                TopicArn=topic_arn,
                Message="msg with attrs",
                MessageAttributes={
                    "my-attr": {"DataType": "String", "StringValue": "my-value"}
                },
            )

            time.sleep(0.5)
            response = sqs_client.receive_message(
                QueueUrl=queue_url, MaxNumberOfMessages=1, WaitTimeSeconds=2
            )
            msgs = response.get("Messages", [])
            assert len(msgs) > 0
            assert "my-value" in msgs[0]["Body"]

        finally:
            sns_client.unsubscribe(SubscriptionArn=sub_arn)
            sns_client.delete_topic(TopicArn=topic_arn)
            sqs_client.delete_queue(QueueUrl=queue_url)
