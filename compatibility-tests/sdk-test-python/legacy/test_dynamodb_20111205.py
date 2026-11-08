"""Explicit legacy-client suite: Boto 2 and Boto 3 share DynamoDB table storage."""

from urllib.parse import urlsplit
import uuid

from boto.dynamodb.layer2 import Layer2
from boto.dynamodb.condition import BETWEEN
from boto.regioninfo import RegionInfo
from boto.exception import DynamoDBResponseError
import pytest


def legacy_client(aws_config, region=None, access_key=None):
    endpoint = urlsplit(aws_config["endpoint_url"])
    client = Layer2(
        aws_access_key_id=access_key or aws_config["aws_access_key_id"],
        aws_secret_access_key=aws_config["aws_secret_access_key"],
        port=endpoint.port,
        is_secure=endpoint.scheme == "https",
        region=RegionInfo(name=region or aws_config["region_name"], endpoint=endpoint.hostname),
    )
    # Boto 2 otherwise infers signing scope from the endpoint's DNS labels.
    client.layer1.auth_region_name = region or aws_config["region_name"]
    client.layer1.auth_service_name = "dynamodb"
    return client


@pytest.fixture
def metrics_table(dynamodb_client):
    name = "legacy-metrics-" + uuid.uuid4().hex
    dynamodb_client.create_table(
        TableName=name,
        KeySchema=[{"AttributeName": "id-type", "KeyType": "HASH"}, {"AttributeName": "range", "KeyType": "RANGE"}],
        AttributeDefinitions=[
            {"AttributeName": "id-type", "AttributeType": "S"},
            {"AttributeName": "range", "AttributeType": "N"},
        ],
        BillingMode="PAY_PER_REQUEST",
    )
    dynamodb_client.get_waiter("table_exists").wait(TableName=name)
    try:
        for partition in ("deleted-domain_q", "other-domain_q"):
            for index in range(4):
                dynamodb_client.put_item(
                    TableName=name,
                    Item={"id-type": {"S": partition}, "range": {"N": str(index)}, "payload": {"S": "keep"}},
                )
        yield name
    finally:
        dynamodb_client.delete_table(TableName=name)


def test_missing_table_raises_native_handled_error(aws_config):
    client = legacy_client(aws_config)
    with pytest.raises(DynamoDBResponseError) as error:
        client.get_table("missing-" + uuid.uuid4().hex)
    assert error.value.status == 400
    assert "ResourceNotFoundException" in error.value.body["__type"]


def test_paginated_cleanup_preserves_other_partitions(aws_config, dynamodb_client, metrics_table):
    client = legacy_client(aws_config)
    table = client.get_table(metrics_table)
    assert table.name == metrics_table
    assert table.schema.hash_key_name == "id-type"
    assert table.schema.range_key_name == "range"
    assert table.schema.range_key_type == "N"
    rows = table.query(
        hash_key="deleted-domain_q",
        range_key_condition=BETWEEN(0, 3),
        attributes_to_get=["id-type", "range"],
        consistent_read=True,
        scan_index_forward=False,
        request_limit=1,
    )
    items = list(rows)
    assert [int(item["range"]) for item in items] == [3, 2, 1, 0]
    assert all(set(item) == {"id-type", "range"} for item in items)
    assert rows.consumed_units > 0
    batch = client.new_batch_write_list()
    batch.add_batch(table, deletes=[(item["id-type"], item["range"]) for item in items])
    response = client.layer1.batch_write_item(batch.to_dict())
    assert response["UnprocessedItems"] == {}
    assert response["Responses"][metrics_table]["ConsumedCapacityUnits"] > 0
    assert list(table.query(hash_key="deleted-domain_q", consistent_read=True)) == []
    count = table.query(hash_key="other-domain_q", count=True)
    assert count.count == 4
    remaining = dynamodb_client.scan(TableName=metrics_table, ConsistentRead=True)["Items"]
    assert len(remaining) == 4
    assert {item["id-type"]["S"] for item in remaining} == {"other-domain_q"}
    assert dynamodb_client.describe_table(TableName=metrics_table)["Table"]["KeySchema"] == [
        {"AttributeName": "id-type", "KeyType": "HASH"}, {"AttributeName": "range", "KeyType": "RANGE"}
    ]


def test_region_and_account_scoping_and_bad_keys(aws_config, dynamodb_client, metrics_table):
    other_region = "eu-west-1" if aws_config["region_name"] != "eu-west-1" else "us-west-2"
    for client in (legacy_client(aws_config, region=other_region),
                   legacy_client(aws_config, access_key="999988887777")):
        with pytest.raises(DynamoDBResponseError) as error:
            client.get_table(metrics_table)
        assert "ResourceNotFoundException" in error.value.body["__type"]
    client = legacy_client(aws_config)
    with pytest.raises(DynamoDBResponseError) as error:
        client.layer1.batch_write_item({
            metrics_table: [{"DeleteRequest": {"Key": {"HashKeyElement": {"S": "deleted-domain_q"}}}}],
        })
    assert "ValidationException" in error.value.body["__type"]
    assert dynamodb_client.scan(TableName=metrics_table)["Count"] == 8
