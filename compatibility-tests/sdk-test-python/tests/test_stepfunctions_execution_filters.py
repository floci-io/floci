"""Step Functions execution filters through boto3 and real workflow transitions."""
import json

import boto3
import pytest
from botocore.exceptions import ClientError


def test_execution_filters_follow_current_status(aws_config, client_config, iam_client, unique_name):
    client = boto3.client("stepfunctions", config=client_config, **aws_config)
    role_name = "sfn-filter-" + unique_name
    role = iam_client.create_role(
        RoleName=role_name,
        AssumeRolePolicyDocument=json.dumps({
            "Version": "2012-10-17",
            "Statement": [{
                "Effect": "Allow",
                "Principal": {"Service": "states.amazonaws.com"},
                "Action": "sts:AssumeRole",
            }],
        }),
    )["Role"]["Arn"]
    machine = running = None
    try:
        machine = client.create_state_machine(
            name="sfn-filter-" + unique_name,
            roleArn=role,
            definition=json.dumps({
                "StartAt": "Wait",
                "States": {
                    "Wait": {"Type": "Wait", "Seconds": 300, "End": True},
                },
            }),
        )["stateMachineArn"]
        running = client.start_execution(stateMachineArn=machine)["executionArn"]

        def listed(status=None):
            parameters = {"stateMachineArn": machine}
            if status is not None:
                parameters["statusFilter"] = status
            rows = client.list_executions(**parameters)["executions"]
            if status is not None:
                assert all(row["status"] == status for row in rows)
            return {row["executionArn"] for row in rows}

        assert listed("RUNNING") == {running}
        assert listed("SUCCEEDED") == set()
        assert listed("FAILED") == set()
        assert listed() == {running}
        client.stop_execution(executionArn=running)
        assert listed("RUNNING") == set()
        assert listed("ABORTED") == {running}
        for invalid_status in ("", "succeeded", "UNKNOWN"):
            with pytest.raises(ClientError) as error:
                client.list_executions(stateMachineArn=machine, statusFilter=invalid_status)
            response = error.value.response
            assert response["ResponseMetadata"]["HTTPStatusCode"] == 400
            assert response["Error"]["Code"] == "ValidationException"
            assert response["Error"]["Message"] == (
                f"1 validation error detected: Value '{invalid_status}' at 'statusFilter' "
                "failed to satisfy constraint: Member must satisfy enum value set: "
                "[SUCCEEDED, TIMED_OUT, PENDING_REDRIVE, ABORTED, FAILED, RUNNING]"
            )
    finally:
        if running and client.describe_execution(executionArn=running)["status"] == "RUNNING":
            client.stop_execution(executionArn=running)
        if machine:
            client.delete_state_machine(stateMachineArn=machine)
        iam_client.delete_role(RoleName=role_name)
        client.close()
