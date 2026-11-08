"""Legacy conditions verified against AWS in us-east-1 on 2026-10-12."""

import json
import uuid
from pathlib import Path

import pytest
from botocore.exceptions import ClientError


OBSERVATIONS = json.loads(
    (Path(__file__).parent / "fixtures/dynamodb-legacy-expected-aws.json").read_text()
)


@pytest.fixture
def legacy_table(dynamodb_client):
    name = "legacy-expected-" + uuid.uuid4().hex[:12]
    dynamodb_client.create_table(
        TableName=name,
        KeySchema=[{"AttributeName": "pk", "KeyType": "HASH"}],
        AttributeDefinitions=[{"AttributeName": "pk", "AttributeType": "S"}],
        BillingMode="PAY_PER_REQUEST",
    )
    try:
        dynamodb_client.get_waiter("table_exists").wait(
            TableName=name, WaiterConfig={"Delay": 1, "MaxAttempts": 30}
        )
        yield name
    finally:
        dynamodb_client.delete_table(TableName=name)


def write(client, operation, table, seed, expected):
    request = {"TableName": table, "Expected": expected}
    if operation == "put_item":
        request["Item"] = {**seed, "marker": {"S": "after"}}
    else:
        request["Key"] = {"pk": seed["pk"]}
        if operation == "update_item":
            request["AttributeUpdates"] = {
                "marker": {"Action": "PUT", "Value": {"S": "after"}}
            }
    try:
        getattr(client, operation)(**request)
        return "success", None
    except ClientError as error:
        assert error.response["ResponseMetadata"]["HTTPStatusCode"] == 400
        return error.response["Error"]["Code"], error.response["Error"]["Message"]


@pytest.mark.parametrize("operation", ["put_item", "update_item", "delete_item"])
@pytest.mark.parametrize(
    "case", OBSERVATIONS["operand_cases"], ids=lambda case: case["label"]
)
def test_expected_operands_match_aws(dynamodb_client, legacy_table, operation, case):
    seed = {"pk": {"S": "row"}, **OBSERVATIONS["metadata"]["operand_seed"]}
    dynamodb_client.put_item(TableName=legacy_table, Item=seed)
    status, message = write(
        dynamodb_client, operation, legacy_table, seed, {"tested": case["condition"]}
    )
    stored = dynamodb_client.get_item(
        TableName=legacy_table, Key={"pk": seed["pk"]}, ConsistentRead=True
    ).get("Item")
    expected_stored = seed
    if case["status"] == "success":
        expected_stored = (
            None if operation == "delete_item" else {**seed, "marker": {"S": "after"}}
        )
    assert (status, message) == (case["status"], case["message"])
    assert stored == expected_stored


@pytest.mark.parametrize(
    "case", OBSERVATIONS["valid_pairs"], ids=lambda case: case["name"]
)
def test_value_and_attribute_value_list_match_aws(dynamodb_client, legacy_table, case):
    stored_items = []
    for operand_field in ["Value", "AttributeValueList"]:
        seed = {
            **case["seed"],
            "pk": {"S": operand_field},
            "marker": {"S": "before"},
        }
        dynamodb_client.put_item(TableName=legacy_table, Item=seed)
        expected = dict(case.get("unchanged", {}))
        for field, (operator, operand) in case["conditions"].items():
            expected[field] = {
                "ComparisonOperator": operator,
                operand_field: operand if operand_field == "Value" else [operand],
            }
        status, _ = write(
            dynamodb_client, case["operation"], legacy_table, seed, expected
        )
        stored = dynamodb_client.get_item(
            TableName=legacy_table, Key={"pk": seed["pk"]}, ConsistentRead=True
        )["Item"]
        assert status == case["aws_status"]
        assert stored == {
            **seed,
            "marker": {"S": "after" if status == "success" else "before"},
        }
        stored_items.append({k: v for k, v in stored.items() if k != "pk"})
    assert stored_items[0] == stored_items[1]


@pytest.mark.parametrize("expression_key", [False, True])
@pytest.mark.parametrize("filter_value", ["7", "8"])
def test_legacy_key_conditions_with_filter_expression(
    dynamodb_client, legacy_table, expression_key, filter_value
):
    item = {"pk": {"S": "domain"}, "diNumber": {"S": "7"}}
    dynamodb_client.put_item(TableName=legacy_table, Item=item)
    request = {
        "TableName": legacy_table,
        "ConsistentRead": True,
        "FilterExpression": "diNumber = :diNumber",
        "ExpressionAttributeValues": {":diNumber": {"S": filter_value}},
    }
    if expression_key:
        request["KeyConditionExpression"] = "pk = :domain"
        request["ExpressionAttributeValues"][":domain"] = {"S": "domain"}
    else:
        request["KeyConditions"] = {
            "pk": {
                "ComparisonOperator": "EQ",
                "AttributeValueList": [{"S": "domain"}],
            }
        }
    response = dynamodb_client.query(**request)
    expected_items = [item] if filter_value == "7" else []
    assert response["Items"] == expected_items
    assert response["Count"] == len(expected_items)
    assert response["ScannedCount"] == 1
