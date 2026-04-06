"""S3 notification filter integration tests."""

import pytest


class TestS3Notifications:
    """Test S3 bucket notification configuration with filters."""

    @pytest.fixture(autouse=True)
    def setup_resources(self, s3_client, sqs_client, sns_client, unique_name):
        """Set up test resources for S3 notification tests."""
        self.s3 = s3_client
        self.sqs = sqs_client
        self.sns = sns_client

        self.account_id = "000000000000"
        self.queue_name = f"s3-notif-{unique_name}-queue"
        self.topic_name = f"s3-notif-{unique_name}-topic"
        self.bucket_name = f"s3-notif-{unique_name}-bucket"
        self.queue_arn = f"arn:aws:sqs:us-east-1:{self.account_id}:{self.queue_name}"

        # Create SQS queue
        sqs_client.create_queue(QueueName=self.queue_name)

        # Create SNS topic
        response = sns_client.create_topic(Name=self.topic_name)
        self.topic_arn = response["TopicArn"]

        # Create S3 bucket
        s3_client.create_bucket(Bucket=self.bucket_name)

        yield

        # Cleanup
        try:
            s3_client.delete_bucket(Bucket=self.bucket_name)
        except Exception:
            pass
        try:
            sns_client.delete_topic(TopicArn=self.topic_arn)
        except Exception:
            pass
        try:
            queue_url = sqs_client.get_queue_url(QueueName=self.queue_name)["QueueUrl"]
            sqs_client.delete_queue(QueueUrl=queue_url)
        except Exception:
            pass

    def test_put_bucket_notification_configuration_with_filters(self):
        """Test PutBucketNotificationConfiguration with prefix/suffix filters."""
        self.s3.put_bucket_notification_configuration(
            Bucket=self.bucket_name,
            NotificationConfiguration={
                "QueueConfigurations": [
                    {
                        "Id": "sqs-filtered",
                        "QueueArn": self.queue_arn,
                        "Events": ["s3:ObjectCreated:*"],
                        "Filter": {
                            "Key": {
                                "FilterRules": [
                                    {"Name": "prefix", "Value": "incoming/"},
                                    {"Name": "suffix", "Value": ".csv"},
                                ]
                            }
                        },
                    }
                ],
                "TopicConfigurations": [
                    {
                        "Id": "sns-filtered",
                        "TopicArn": self.topic_arn,
                        "Events": ["s3:ObjectRemoved:*"],
                        "Filter": {
                            "Key": {
                                "FilterRules": [
                                    {"Name": "prefix", "Value": ""},
                                    {"Name": "suffix", "Value": ".txt"},
                                ]
                            }
                        },
                    }
                ],
            },
        )

    def test_get_bucket_notification_configuration_sqs_filter_roundtrip(self):
        """Test SQS notification filter configuration round-trip."""
        self.s3.put_bucket_notification_configuration(
            Bucket=self.bucket_name,
            NotificationConfiguration={
                "QueueConfigurations": [
                    {
                        "Id": "sqs-filtered",
                        "QueueArn": self.queue_arn,
                        "Events": ["s3:ObjectCreated:*"],
                        "Filter": {
                            "Key": {
                                "FilterRules": [
                                    {"Name": "prefix", "Value": "incoming/"},
                                    {"Name": "suffix", "Value": ".csv"},
                                ]
                            }
                        },
                    }
                ],
            },
        )

        response = self.s3.get_bucket_notification_configuration(Bucket=self.bucket_name)

        queue_configs = response.get("QueueConfigurations", [])
        sqs_entry = next(
            (c for c in queue_configs if c.get("QueueArn") == self.queue_arn), None
        )
        assert sqs_entry is not None

        sqs_rules = sqs_entry.get("Filter", {}).get("Key", {}).get("FilterRules", [])
        assert len(sqs_rules) == 2

    def test_get_bucket_notification_configuration_sns_filter_roundtrip(self):
        """Test SNS notification filter configuration round-trip."""
        self.s3.put_bucket_notification_configuration(
            Bucket=self.bucket_name,
            NotificationConfiguration={
                "TopicConfigurations": [
                    {
                        "Id": "sns-filtered",
                        "TopicArn": self.topic_arn,
                        "Events": ["s3:ObjectRemoved:*"],
                        "Filter": {
                            "Key": {
                                "FilterRules": [
                                    {"Name": "prefix", "Value": ""},
                                    {"Name": "suffix", "Value": ".txt"},
                                ]
                            }
                        },
                    }
                ],
            },
        )

        response = self.s3.get_bucket_notification_configuration(Bucket=self.bucket_name)

        topic_configs = response.get("TopicConfigurations", [])
        sns_entry = next(
            (c for c in topic_configs if c.get("TopicArn") == self.topic_arn), None
        )
        assert sns_entry is not None

        sns_rules = sns_entry.get("Filter", {}).get("Key", {}).get("FilterRules", [])
        assert len(sns_rules) == 2
