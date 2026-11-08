"""Whole-request BatchWriteItem limits through the AWS SDK."""

from contextlib import ExitStack

import pytest
from botocore.exceptions import ClientError


@pytest.mark.parametrize("count", [25, 26])
def test_aggregate_batch_write_limit(dynamodb_client, unique_name, count):
    names = [f"batch-first-{unique_name}", f"batch-second-{unique_name}"]
    with ExitStack() as cleanup:
        for name in names:
            dynamodb_client.create_table(
                TableName=name,
                KeySchema=[{"AttributeName": "pk", "KeyType": "HASH"}],
                AttributeDefinitions=[{"AttributeName": "pk", "AttributeType": "S"}],
                BillingMode="PAY_PER_REQUEST",
            )
            cleanup.callback(dynamodb_client.delete_table, TableName=name)
            dynamodb_client.get_waiter("table_exists").wait(TableName=name)
            dynamodb_client.put_item(TableName=name, Item={"pk": {"S": "delete"}})
        requests = {}
        for name, size in zip(names, [13, count - 13]):
            requests[name] = [{"DeleteRequest": {"Key": {"pk": {"S": "delete"}}}}]
            requests[name].extend(
                {"PutRequest": {"Item": {"pk": {"S": f"put-{index}"}}}}
                for index in range(size - 1)
            )
        if count == 26:
            with pytest.raises(ClientError) as error:
                dynamodb_client.batch_write_item(RequestItems=requests)
            assert error.value.response["Error"]["Code"] == "ValidationException"
            for name in names:
                assert dynamodb_client.scan(TableName=name, ConsistentRead=True)["Items"] == [
                    {"pk": {"S": "delete"}}
                ]
        else:
            assert not dynamodb_client.batch_write_item(RequestItems=requests)["UnprocessedItems"]
            for name, size in zip(names, [13, count - 13]):
                items = dynamodb_client.scan(TableName=name, ConsistentRead=True)["Items"]
                assert len(items) == size - 1
                assert {"pk": {"S": "delete"}} not in items
