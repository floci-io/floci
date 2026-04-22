"""EventBridge Pipes integration tests."""

import time

import pytest
from botocore.exceptions import ClientError

ACCOUNT_ID = "000000000000"
REGION = "us-east-1"
ROLE_ARN = f"arn:aws:iam::{ACCOUNT_ID}:role/pipe-role"


def sqs_arn(queue_name):
    return f"arn:aws:sqs:{REGION}:{ACCOUNT_ID}:{queue_name}"


class TestPipesCRUD:
    """Test Pipes create, describe, list, update, delete operations."""

    def test_create_pipe(self, pipes_client, sqs_client, unique_name):
        """Test CreatePipe creates a pipe in STOPPED state."""
        src_name = f"pipe-src-{unique_name}"
        tgt_name = f"pipe-tgt-{unique_name}"
        pipe_name = f"pipe-{unique_name}"

        sqs_client.create_queue(QueueName=src_name)
        sqs_client.create_queue(QueueName=tgt_name)
        try:
            response = pipes_client.create_pipe(
                Name=pipe_name,
                Source=sqs_arn(src_name),
                Target=sqs_arn(tgt_name),
                RoleArn=ROLE_ARN,
                DesiredState="STOPPED",
            )
            assert response["CurrentState"] == "STOPPED"
            assert pipe_name in response["Arn"]
        finally:
            pipes_client.delete_pipe(Name=pipe_name)
            sqs_client.delete_queue(QueueUrl=_queue_url(sqs_client, src_name))
            sqs_client.delete_queue(QueueUrl=_queue_url(sqs_client, tgt_name))

    def test_describe_pipe(self, pipes_client, sqs_client, unique_name):
        """Test DescribePipe returns pipe details."""
        src_name = f"pipe-src-{unique_name}"
        tgt_name = f"pipe-tgt-{unique_name}"
        pipe_name = f"pipe-{unique_name}"

        sqs_client.create_queue(QueueName=src_name)
        sqs_client.create_queue(QueueName=tgt_name)
        try:
            pipes_client.create_pipe(
                Name=pipe_name,
                Source=sqs_arn(src_name),
                Target=sqs_arn(tgt_name),
                RoleArn=ROLE_ARN,
                DesiredState="STOPPED",
            )
            response = pipes_client.describe_pipe(Name=pipe_name)
            assert response["Name"] == pipe_name
            assert response["Source"] == sqs_arn(src_name)
            assert response["Target"] == sqs_arn(tgt_name)
            assert response["CurrentState"] == "STOPPED"
        finally:
            pipes_client.delete_pipe(Name=pipe_name)
            sqs_client.delete_queue(QueueUrl=_queue_url(sqs_client, src_name))
            sqs_client.delete_queue(QueueUrl=_queue_url(sqs_client, tgt_name))

    def test_list_pipes(self, pipes_client, sqs_client, unique_name):
        """Test ListPipes returns created pipe."""
        src_name = f"pipe-src-{unique_name}"
        tgt_name = f"pipe-tgt-{unique_name}"
        pipe_name = f"pipe-{unique_name}"

        sqs_client.create_queue(QueueName=src_name)
        sqs_client.create_queue(QueueName=tgt_name)
        try:
            pipes_client.create_pipe(
                Name=pipe_name,
                Source=sqs_arn(src_name),
                Target=sqs_arn(tgt_name),
                RoleArn=ROLE_ARN,
                DesiredState="STOPPED",
            )
            response = pipes_client.list_pipes()
            names = [p["Name"] for p in response["Pipes"]]
            assert pipe_name in names
        finally:
            pipes_client.delete_pipe(Name=pipe_name)
            sqs_client.delete_queue(QueueUrl=_queue_url(sqs_client, src_name))
            sqs_client.delete_queue(QueueUrl=_queue_url(sqs_client, tgt_name))

    def test_update_pipe(self, pipes_client, sqs_client, unique_name):
        """Test UpdatePipe updates pipe description."""
        src_name = f"pipe-src-{unique_name}"
        tgt_name = f"pipe-tgt-{unique_name}"
        pipe_name = f"pipe-{unique_name}"

        sqs_client.create_queue(QueueName=src_name)
        sqs_client.create_queue(QueueName=tgt_name)
        try:
            pipes_client.create_pipe(
                Name=pipe_name,
                Source=sqs_arn(src_name),
                Target=sqs_arn(tgt_name),
                RoleArn=ROLE_ARN,
                DesiredState="STOPPED",
            )
            pipes_client.update_pipe(
                Name=pipe_name,
                RoleArn=ROLE_ARN,
                Description="updated via SDK",
                DesiredState="STOPPED",
            )
            response = pipes_client.describe_pipe(Name=pipe_name)
            assert response["Description"] == "updated via SDK"
        finally:
            pipes_client.delete_pipe(Name=pipe_name)
            sqs_client.delete_queue(QueueUrl=_queue_url(sqs_client, src_name))
            sqs_client.delete_queue(QueueUrl=_queue_url(sqs_client, tgt_name))

    def test_delete_pipe(self, pipes_client, sqs_client, unique_name):
        """Test DeletePipe removes the pipe."""
        src_name = f"pipe-src-{unique_name}"
        tgt_name = f"pipe-tgt-{unique_name}"
        pipe_name = f"pipe-{unique_name}"

        sqs_client.create_queue(QueueName=src_name)
        sqs_client.create_queue(QueueName=tgt_name)
        try:
            pipes_client.create_pipe(
                Name=pipe_name,
                Source=sqs_arn(src_name),
                Target=sqs_arn(tgt_name),
                RoleArn=ROLE_ARN,
                DesiredState="STOPPED",
            )
            pipes_client.delete_pipe(Name=pipe_name)

            with pytest.raises(ClientError) as exc_info:
                pipes_client.describe_pipe(Name=pipe_name)
            assert exc_info.value.response["Error"]["Code"] == "NotFoundException"
        finally:
            sqs_client.delete_queue(QueueUrl=_queue_url(sqs_client, src_name))
            sqs_client.delete_queue(QueueUrl=_queue_url(sqs_client, tgt_name))

    def test_describe_nonexistent_pipe(self, pipes_client):
        """Test DescribePipe for non-existent pipe returns NotFoundException."""
        with pytest.raises(ClientError) as exc_info:
            pipes_client.describe_pipe(Name="nonexistent-pipe")
        assert exc_info.value.response["Error"]["Code"] == "NotFoundException"


class TestPipesLifecycle:
    """Test Pipes start/stop operations."""

    def test_start_and_stop_pipe(self, pipes_client, sqs_client, unique_name):
        """Test StartPipe and StopPipe transitions."""
        src_name = f"pipe-src-{unique_name}"
        tgt_name = f"pipe-tgt-{unique_name}"
        pipe_name = f"pipe-{unique_name}"

        sqs_client.create_queue(QueueName=src_name)
        sqs_client.create_queue(QueueName=tgt_name)
        try:
            pipes_client.create_pipe(
                Name=pipe_name,
                Source=sqs_arn(src_name),
                Target=sqs_arn(tgt_name),
                RoleArn=ROLE_ARN,
                DesiredState="STOPPED",
            )

            response = pipes_client.start_pipe(Name=pipe_name)
            assert response["CurrentState"] == "RUNNING"

            response = pipes_client.stop_pipe(Name=pipe_name)
            assert response["CurrentState"] == "STOPPED"
        finally:
            pipes_client.delete_pipe(Name=pipe_name)
            sqs_client.delete_queue(QueueUrl=_queue_url(sqs_client, src_name))
            sqs_client.delete_queue(QueueUrl=_queue_url(sqs_client, tgt_name))


class TestPipesPolling:
    """Test Pipes source polling and target invocation."""

    def test_sqs_to_sqs_forwarding(self, pipes_client, sqs_client, unique_name):
        """Test that a RUNNING pipe forwards SQS messages to SQS target."""
        src_name = f"pipe-src-{unique_name}"
        tgt_name = f"pipe-tgt-{unique_name}"
        pipe_name = f"pipe-{unique_name}"

        src_resp = sqs_client.create_queue(QueueName=src_name)
        src_url = src_resp["QueueUrl"]
        tgt_resp = sqs_client.create_queue(QueueName=tgt_name)
        tgt_url = tgt_resp["QueueUrl"]
        try:
            pipes_client.create_pipe(
                Name=pipe_name,
                Source=sqs_arn(src_name),
                Target=sqs_arn(tgt_name),
                RoleArn=ROLE_ARN,
                DesiredState="RUNNING",
            )

            sqs_client.send_message(QueueUrl=src_url, MessageBody="hello from pipes")

            found = False
            for _ in range(15):
                time.sleep(1)
                resp = sqs_client.receive_message(
                    QueueUrl=tgt_url, MaxNumberOfMessages=1, WaitTimeSeconds=1
                )
                msgs = resp.get("Messages", [])
                if msgs and "hello from pipes" in msgs[0]["Body"]:
                    found = True
                    break

            assert found, "Target queue should receive forwarded message"
        finally:
            pipes_client.delete_pipe(Name=pipe_name)
            sqs_client.delete_queue(QueueUrl=src_url)
            sqs_client.delete_queue(QueueUrl=tgt_url)

    def test_stopped_pipe_does_not_forward(self, pipes_client, sqs_client, unique_name):
        """Test that a STOPPED pipe does not forward messages."""
        src_name = f"pipe-src-{unique_name}"
        tgt_name = f"pipe-tgt-{unique_name}"
        pipe_name = f"pipe-{unique_name}"

        src_resp = sqs_client.create_queue(QueueName=src_name)
        src_url = src_resp["QueueUrl"]
        tgt_resp = sqs_client.create_queue(QueueName=tgt_name)
        tgt_url = tgt_resp["QueueUrl"]
        try:
            pipes_client.create_pipe(
                Name=pipe_name,
                Source=sqs_arn(src_name),
                Target=sqs_arn(tgt_name),
                RoleArn=ROLE_ARN,
                DesiredState="STOPPED",
            )

            sqs_client.send_message(QueueUrl=src_url, MessageBody="should not forward")
            time.sleep(3)

            attrs = sqs_client.get_queue_attributes(
                QueueUrl=src_url, AttributeNames=["ApproximateNumberOfMessages"]
            )
            assert attrs["Attributes"]["ApproximateNumberOfMessages"] == "1"

            resp = sqs_client.receive_message(
                QueueUrl=tgt_url, MaxNumberOfMessages=1, WaitTimeSeconds=1
            )
            assert len(resp.get("Messages", [])) == 0
        finally:
            pipes_client.delete_pipe(Name=pipe_name)
            sqs_client.delete_queue(QueueUrl=src_url)
            sqs_client.delete_queue(QueueUrl=tgt_url)

    def test_source_queue_drained_after_forwarding(
        self, pipes_client, sqs_client, unique_name
    ):
        """Test that source queue messages are deleted after forwarding."""
        src_name = f"pipe-src-{unique_name}"
        tgt_name = f"pipe-tgt-{unique_name}"
        pipe_name = f"pipe-{unique_name}"

        src_resp = sqs_client.create_queue(QueueName=src_name)
        src_url = src_resp["QueueUrl"]
        tgt_resp = sqs_client.create_queue(QueueName=tgt_name)
        tgt_url = tgt_resp["QueueUrl"]
        try:
            pipes_client.create_pipe(
                Name=pipe_name,
                Source=sqs_arn(src_name),
                Target=sqs_arn(tgt_name),
                RoleArn=ROLE_ARN,
                DesiredState="RUNNING",
            )

            sqs_client.send_message(QueueUrl=src_url, MessageBody="drain test")

            drained = False
            for _ in range(15):
                time.sleep(1)
                attrs = sqs_client.get_queue_attributes(
                    QueueUrl=src_url, AttributeNames=["ApproximateNumberOfMessages"]
                )
                if attrs["Attributes"]["ApproximateNumberOfMessages"] == "0":
                    drained = True
                    break

            assert drained, "Source queue should be drained after pipe forwards messages"
        finally:
            pipes_client.delete_pipe(Name=pipe_name)
            sqs_client.delete_queue(QueueUrl=src_url)
            sqs_client.delete_queue(QueueUrl=tgt_url)


def _queue_url(sqs_client, queue_name):
    """Get queue URL by name, ignoring errors."""
    try:
        return sqs_client.get_queue_url(QueueName=queue_name)["QueueUrl"]
    except Exception:
        return ""
