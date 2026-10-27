"""Query/Scan Select defaults with IAM enforcement enabled."""

from contextlib import ExitStack
import json

import boto3
import pytest
from botocore.exceptions import ClientError


@pytest.mark.parametrize("action", ["query", "scan"])
@pytest.mark.parametrize("index", [False, True])
def test_select_defaults_require_projection(
    dynamodb_client, iam_client, sts_client, aws_config, unique_name, action, index
):
    table = f"select-{unique_name}"
    role_name = f"select-{unique_name}"
    with ExitStack() as cleanup:
        dynamodb_client.create_table(
            TableName=table,
            KeySchema=[{"AttributeName": "pk", "KeyType": "HASH"}],
            AttributeDefinitions=[
                {"AttributeName": "pk", "AttributeType": "S"},
                {"AttributeName": "group_key", "AttributeType": "S"},
            ],
            GlobalSecondaryIndexes=[{
                "IndexName": "by-group",
                "KeySchema": [{"AttributeName": "group_key", "KeyType": "HASH"}],
                "Projection": {"ProjectionType": "KEYS_ONLY"},
            }],
            BillingMode="PAY_PER_REQUEST",
        )
        cleanup.callback(dynamodb_client.delete_table, TableName=table)
        dynamodb_client.get_waiter("table_exists").wait(TableName=table)
        dynamodb_client.put_item(TableName=table, Item={
            "pk": {"S": "one"}, "group_key": {"S": "group"}, "extra": {"S": "visible"}
        })
        role = iam_client.create_role(
            RoleName=role_name,
            AssumeRolePolicyDocument=json.dumps({
                "Version": "2012-10-17", "Statement": [{
                    "Effect": "Allow", "Principal": {"AWS": "*"}, "Action": "sts:AssumeRole"
                }]
            }),
        )["Role"]
        cleanup.callback(iam_client.delete_role, RoleName=role_name)
        iam_client.put_role_policy(RoleName=role_name, PolicyName="read", PolicyDocument=json.dumps({
            "Version": "2012-10-17", "Statement": [{
                "Effect": "Allow", "Action": "dynamodb:*", "Resource": "*"
            }]
        }))
        cleanup.callback(iam_client.delete_role_policy, RoleName=role_name, PolicyName="read")

        def scoped_client(selection, operator):
            credentials = sts_client.assume_role(
                RoleArn=role["Arn"], RoleSessionName="select-test",
                Policy=json.dumps({"Version": "2012-10-17", "Statement": [{
                    "Effect": "Allow", "Action": ["dynamodb:Query", "dynamodb:Scan"],
                    "Resource": "*", "Condition": {operator: {"dynamodb:Select": selection}}
                }]}),
            )["Credentials"]
            client = boto3.client(
                "dynamodb", endpoint_url=aws_config["endpoint_url"],
                region_name=aws_config["region_name"],
                aws_access_key_id=credentials["AccessKeyId"],
                aws_secret_access_key=credentials["SecretAccessKey"],
                aws_session_token=credentials["SessionToken"],
            )
            cleanup.callback(client.close)
            return client

        request = {"TableName": table}
        if index:
            request["IndexName"] = "by-group"
        if action == "query":
            request.update(
                KeyConditionExpression="#key = :v",
                ExpressionAttributeNames={"#key": "group_key" if index else "pk"},
                ExpressionAttributeValues={":v": {"S": "group" if index else "one"}},
            )
        restricted = scoped_client("SPECIFIC_ATTRIBUTES", "StringEqualsIfExists")
        with pytest.raises(ClientError) as error:
            getattr(restricted, action)(**request)
        assert error.value.response["Error"]["Code"] == "AccessDeniedException"
        projected = getattr(restricted, action)(**request, ProjectionExpression="pk")
        assert projected["Items"] == [{"pk": {"S": "one"}}]
        allowed = scoped_client(
            "ALL_PROJECTED_ATTRIBUTES" if index else "ALL_ATTRIBUTES", "StringEquals"
        )
        result = getattr(allowed, action)(**request)
        expected = {"pk": {"S": "one"}, "group_key": {"S": "group"}}
        if not index:
            expected["extra"] = {"S": "visible"}
        assert result["Items"] == [expected]
