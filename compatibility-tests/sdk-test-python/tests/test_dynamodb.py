"""DynamoDB table and item integration tests."""

import pytest


class TestDynamoDBTable:
    """Test DynamoDB table operations."""

    def test_create_table(self, dynamodb_client, unique_name):
        """Test CreateTable creates a table."""
        table_name = f"pytest-ddb-{unique_name}"

        try:
            response = dynamodb_client.create_table(
                TableName=table_name,
                KeySchema=[
                    {"AttributeName": "pk", "KeyType": "HASH"},
                    {"AttributeName": "sk", "KeyType": "RANGE"},
                ],
                AttributeDefinitions=[
                    {"AttributeName": "pk", "AttributeType": "S"},
                    {"AttributeName": "sk", "AttributeType": "S"},
                ],
                BillingMode="PAY_PER_REQUEST",
            )
            assert response["TableDescription"]["TableStatus"] == "ACTIVE"
        finally:
            dynamodb_client.delete_table(TableName=table_name)

    def test_describe_table(self, dynamodb_client, test_table):
        """Test DescribeTable returns table info."""
        response = dynamodb_client.describe_table(TableName=test_table)
        assert response["Table"]["TableName"] == test_table

    def test_list_tables(self, dynamodb_client, test_table):
        """Test ListTables includes created table."""
        response = dynamodb_client.list_tables()
        assert test_table in response["TableNames"]

    def test_update_table(self, dynamodb_client, unique_name):
        """Test UpdateTable modifies provisioned throughput."""
        table_name = f"pytest-ddb-{unique_name}"

        dynamodb_client.create_table(
            TableName=table_name,
            KeySchema=[{"AttributeName": "pk", "KeyType": "HASH"}],
            AttributeDefinitions=[{"AttributeName": "pk", "AttributeType": "S"}],
            ProvisionedThroughput={"ReadCapacityUnits": 5, "WriteCapacityUnits": 5},
        )

        try:
            response = dynamodb_client.update_table(
                TableName=table_name,
                ProvisionedThroughput={"ReadCapacityUnits": 10, "WriteCapacityUnits": 10},
            )
            assert (
                response["TableDescription"]["ProvisionedThroughput"]["ReadCapacityUnits"]
                == 10
            )
        finally:
            dynamodb_client.delete_table(TableName=table_name)

    def test_describe_time_to_live(self, dynamodb_client, test_table):
        """Test DescribeTimeToLive returns TTL status."""
        response = dynamodb_client.describe_time_to_live(TableName=test_table)
        assert response["TimeToLiveDescription"]["TimeToLiveStatus"] == "DISABLED"

    def test_delete_table(self, dynamodb_client, unique_name):
        """Test DeleteTable removes table."""
        table_name = f"pytest-ddb-{unique_name}"

        dynamodb_client.create_table(
            TableName=table_name,
            KeySchema=[{"AttributeName": "pk", "KeyType": "HASH"}],
            AttributeDefinitions=[{"AttributeName": "pk", "AttributeType": "S"}],
            BillingMode="PAY_PER_REQUEST",
        )

        dynamodb_client.delete_table(TableName=table_name)

        response = dynamodb_client.list_tables()
        assert table_name not in response["TableNames"]


class TestDynamoDBItem:
    """Test DynamoDB item operations."""

    def test_put_item(self, dynamodb_client, test_table):
        """Test PutItem adds item to table."""
        dynamodb_client.put_item(
            TableName=test_table,
            Item={"pk": {"S": "user-1"}, "data": {"S": "value-1"}},
        )

        response = dynamodb_client.get_item(
            TableName=test_table, Key={"pk": {"S": "user-1"}}
        )
        assert response.get("Item", {}).get("data", {}).get("S") == "value-1"

    def test_get_item(self, dynamodb_client, test_table):
        """Test GetItem retrieves item."""
        dynamodb_client.put_item(
            TableName=test_table,
            Item={"pk": {"S": "user-1"}, "data": {"S": "test-value"}},
        )

        response = dynamodb_client.get_item(
            TableName=test_table, Key={"pk": {"S": "user-1"}}
        )
        assert response.get("Item", {}).get("data", {}).get("S") == "test-value"

    def test_update_item(self, dynamodb_client, test_table):
        """Test UpdateItem modifies item."""
        dynamodb_client.put_item(
            TableName=test_table,
            Item={"pk": {"S": "user-1"}, "data": {"S": "original"}},
        )

        response = dynamodb_client.update_item(
            TableName=test_table,
            Key={"pk": {"S": "user-1"}},
            UpdateExpression="SET #d = :newVal",
            ExpressionAttributeNames={"#d": "data"},
            ExpressionAttributeValues={":newVal": {"S": "updated"}},
            ReturnValues="ALL_NEW",
        )
        assert response["Attributes"]["data"]["S"] == "updated"

    def test_delete_item(self, dynamodb_client, test_table):
        """Test DeleteItem removes item."""
        dynamodb_client.put_item(
            TableName=test_table,
            Item={"pk": {"S": "to-delete"}, "data": {"S": "value"}},
        )

        dynamodb_client.delete_item(
            TableName=test_table, Key={"pk": {"S": "to-delete"}}
        )

        response = dynamodb_client.get_item(
            TableName=test_table, Key={"pk": {"S": "to-delete"}}
        )
        assert "Item" not in response


class TestDynamoDBQuery:
    """Test DynamoDB query and scan operations."""

    def test_query(self, dynamodb_client, unique_name):
        """Test Query returns matching items."""
        table_name = f"pytest-ddb-{unique_name}"

        dynamodb_client.create_table(
            TableName=table_name,
            KeySchema=[
                {"AttributeName": "pk", "KeyType": "HASH"},
                {"AttributeName": "sk", "KeyType": "RANGE"},
            ],
            AttributeDefinitions=[
                {"AttributeName": "pk", "AttributeType": "S"},
                {"AttributeName": "sk", "AttributeType": "S"},
            ],
            BillingMode="PAY_PER_REQUEST",
        )

        try:
            for i in range(1, 4):
                dynamodb_client.put_item(
                    TableName=table_name,
                    Item={
                        "pk": {"S": "user-1"},
                        "sk": {"S": f"item-{i}"},
                        "data": {"S": f"value-{i}"},
                    },
                )

            response = dynamodb_client.query(
                TableName=table_name,
                KeyConditionExpression="pk = :pk",
                ExpressionAttributeValues={":pk": {"S": "user-1"}},
            )
            assert response["Count"] == 3
        finally:
            dynamodb_client.delete_table(TableName=table_name)

    def test_scan(self, dynamodb_client, test_table):
        """Test Scan returns all items."""
        for i in range(1, 4):
            dynamodb_client.put_item(
                TableName=test_table,
                Item={"pk": {"S": f"user-{i}"}, "data": {"S": f"value-{i}"}},
            )

        response = dynamodb_client.scan(TableName=test_table)
        assert response["Count"] == 3


class TestDynamoDBBatch:
    """Test DynamoDB batch operations."""

    def test_batch_write_item_put(self, dynamodb_client, test_table):
        """Test BatchWriteItem puts multiple items."""
        dynamodb_client.batch_write_item(
            RequestItems={
                test_table: [
                    {
                        "PutRequest": {
                            "Item": {
                                "pk": {"S": "batch-1"},
                                "data": {"S": "value-1"},
                            }
                        }
                    },
                    {
                        "PutRequest": {
                            "Item": {
                                "pk": {"S": "batch-2"},
                                "data": {"S": "value-2"},
                            }
                        }
                    },
                ]
            }
        )

        response = dynamodb_client.scan(TableName=test_table)
        assert response["Count"] == 2

    def test_batch_write_item_delete(self, dynamodb_client, test_table):
        """Test BatchWriteItem deletes multiple items."""
        # Setup items
        dynamodb_client.batch_write_item(
            RequestItems={
                test_table: [
                    {"PutRequest": {"Item": {"pk": {"S": "del-1"}, "data": {"S": "v1"}}}},
                    {"PutRequest": {"Item": {"pk": {"S": "del-2"}, "data": {"S": "v2"}}}},
                ]
            }
        )

        # Delete items
        dynamodb_client.batch_write_item(
            RequestItems={
                test_table: [
                    {"DeleteRequest": {"Key": {"pk": {"S": "del-1"}}}},
                    {"DeleteRequest": {"Key": {"pk": {"S": "del-2"}}}},
                ]
            }
        )

        response = dynamodb_client.scan(TableName=test_table)
        assert response["Count"] == 0

    def test_batch_get_item(self, dynamodb_client, test_table):
        """Test BatchGetItem retrieves multiple items."""
        dynamodb_client.batch_write_item(
            RequestItems={
                test_table: [
                    {"PutRequest": {"Item": {"pk": {"S": "get-1"}, "data": {"S": "v1"}}}},
                    {"PutRequest": {"Item": {"pk": {"S": "get-2"}, "data": {"S": "v2"}}}},
                ]
            }
        )

        response = dynamodb_client.batch_get_item(
            RequestItems={
                test_table: {
                    "Keys": [
                        {"pk": {"S": "get-1"}},
                        {"pk": {"S": "get-2"}},
                    ]
                }
            }
        )
        items = response["Responses"].get(test_table, [])
        assert len(items) == 2


class TestDynamoDBTagging:
    """Test DynamoDB table tagging operations."""

    def test_tag_resource(self, dynamodb_client, unique_name):
        """Test TagResource adds tags to table."""
        table_name = f"pytest-ddb-{unique_name}"

        response = dynamodb_client.create_table(
            TableName=table_name,
            KeySchema=[{"AttributeName": "pk", "KeyType": "HASH"}],
            AttributeDefinitions=[{"AttributeName": "pk", "AttributeType": "S"}],
            BillingMode="PAY_PER_REQUEST",
        )
        table_arn = response["TableDescription"]["TableArn"]

        try:
            dynamodb_client.tag_resource(
                ResourceArn=table_arn,
                Tags=[
                    {"Key": "env", "Value": "test"},
                    {"Key": "team", "Value": "backend"},
                ],
            )
            # If no exception, test passes
        finally:
            dynamodb_client.delete_table(TableName=table_name)

    def test_list_tags_of_resource(self, dynamodb_client, unique_name):
        """Test ListTagsOfResource returns tags."""
        table_name = f"pytest-ddb-{unique_name}"

        response = dynamodb_client.create_table(
            TableName=table_name,
            KeySchema=[{"AttributeName": "pk", "KeyType": "HASH"}],
            AttributeDefinitions=[{"AttributeName": "pk", "AttributeType": "S"}],
            BillingMode="PAY_PER_REQUEST",
        )
        table_arn = response["TableDescription"]["TableArn"]

        dynamodb_client.tag_resource(
            ResourceArn=table_arn,
            Tags=[
                {"Key": "env", "Value": "test"},
                {"Key": "team", "Value": "backend"},
            ],
        )

        try:
            response = dynamodb_client.list_tags_of_resource(ResourceArn=table_arn)
            tags = {t["Key"]: t["Value"] for t in response["Tags"]}
            assert tags.get("env") == "test"
            assert tags.get("team") == "backend"
        finally:
            dynamodb_client.delete_table(TableName=table_name)

    def test_untag_resource(self, dynamodb_client, unique_name):
        """Test UntagResource removes tags."""
        table_name = f"pytest-ddb-{unique_name}"

        response = dynamodb_client.create_table(
            TableName=table_name,
            KeySchema=[{"AttributeName": "pk", "KeyType": "HASH"}],
            AttributeDefinitions=[{"AttributeName": "pk", "AttributeType": "S"}],
            BillingMode="PAY_PER_REQUEST",
        )
        table_arn = response["TableDescription"]["TableArn"]

        dynamodb_client.tag_resource(
            ResourceArn=table_arn,
            Tags=[
                {"Key": "env", "Value": "test"},
                {"Key": "team", "Value": "backend"},
            ],
        )

        try:
            dynamodb_client.untag_resource(ResourceArn=table_arn, TagKeys=["team"])

            response = dynamodb_client.list_tags_of_resource(ResourceArn=table_arn)
            tags = {t["Key"]: t["Value"] for t in response["Tags"]}
            assert tags.get("env") == "test"
            assert "team" not in tags
        finally:
            dynamodb_client.delete_table(TableName=table_name)


class TestDynamoDBGSI:
    """Test DynamoDB Global Secondary Index and Local Secondary Index operations."""

    def test_create_table_with_gsi_and_lsi(self, dynamodb_client, unique_name):
        """Test CreateTable with GSI and LSI."""
        table_name = f"pytest-gsi-{unique_name}"

        try:
            dynamodb_client.create_table(
                TableName=table_name,
                KeySchema=[
                    {"AttributeName": "pk", "KeyType": "HASH"},
                    {"AttributeName": "sk", "KeyType": "RANGE"},
                ],
                AttributeDefinitions=[
                    {"AttributeName": "pk", "AttributeType": "S"},
                    {"AttributeName": "sk", "AttributeType": "S"},
                    {"AttributeName": "gsiPk", "AttributeType": "S"},
                    {"AttributeName": "lsiSk", "AttributeType": "S"},
                ],
                GlobalSecondaryIndexes=[
                    {
                        "IndexName": "gsi-1",
                        "KeySchema": [
                            {"AttributeName": "gsiPk", "KeyType": "HASH"},
                            {"AttributeName": "sk", "KeyType": "RANGE"},
                        ],
                        "Projection": {"ProjectionType": "ALL"},
                        "ProvisionedThroughput": {
                            "ReadCapacityUnits": 5,
                            "WriteCapacityUnits": 5,
                        },
                    }
                ],
                LocalSecondaryIndexes=[
                    {
                        "IndexName": "lsi-1",
                        "KeySchema": [
                            {"AttributeName": "pk", "KeyType": "HASH"},
                            {"AttributeName": "lsiSk", "KeyType": "RANGE"},
                        ],
                        "Projection": {"ProjectionType": "KEYS_ONLY"},
                    }
                ],
                ProvisionedThroughput={"ReadCapacityUnits": 5, "WriteCapacityUnits": 5},
            )

            # Verify indexes exist
            desc = dynamodb_client.describe_table(TableName=table_name)["Table"]
            gsis = desc.get("GlobalSecondaryIndexes", [])
            lsis = desc.get("LocalSecondaryIndexes", [])

            assert len(gsis) == 1
            assert gsis[0]["IndexName"] == "gsi-1"
            assert gsis[0]["Projection"]["ProjectionType"] == "ALL"

            assert len(lsis) == 1
            assert lsis[0]["IndexName"] == "lsi-1"
            assert lsis[0]["Projection"]["ProjectionType"] == "KEYS_ONLY"
        finally:
            dynamodb_client.delete_table(TableName=table_name)

    def test_query_gsi_sparse_index(self, dynamodb_client, unique_name):
        """Test querying GSI excludes items without GSI key (sparse index)."""
        table_name = f"pytest-gsi-{unique_name}"

        dynamodb_client.create_table(
            TableName=table_name,
            KeySchema=[
                {"AttributeName": "pk", "KeyType": "HASH"},
                {"AttributeName": "sk", "KeyType": "RANGE"},
            ],
            AttributeDefinitions=[
                {"AttributeName": "pk", "AttributeType": "S"},
                {"AttributeName": "sk", "AttributeType": "S"},
                {"AttributeName": "gsiPk", "AttributeType": "S"},
            ],
            GlobalSecondaryIndexes=[
                {
                    "IndexName": "gsi-1",
                    "KeySchema": [
                        {"AttributeName": "gsiPk", "KeyType": "HASH"},
                        {"AttributeName": "sk", "KeyType": "RANGE"},
                    ],
                    "Projection": {"ProjectionType": "ALL"},
                    "ProvisionedThroughput": {
                        "ReadCapacityUnits": 5,
                        "WriteCapacityUnits": 5,
                    },
                }
            ],
            ProvisionedThroughput={"ReadCapacityUnits": 5, "WriteCapacityUnits": 5},
        )

        try:
            # Put 2 items with gsiPk
            dynamodb_client.put_item(
                TableName=table_name,
                Item={
                    "pk": {"S": "item-1"},
                    "sk": {"S": "rev-1"},
                    "gsiPk": {"S": "group-A"},
                },
            )
            dynamodb_client.put_item(
                TableName=table_name,
                Item={
                    "pk": {"S": "item-2"},
                    "sk": {"S": "rev-1"},
                    "gsiPk": {"S": "group-A"},
                },
            )
            # Put 1 item without gsiPk (sparse)
            dynamodb_client.put_item(
                TableName=table_name,
                Item={"pk": {"S": "item-3"}, "sk": {"S": "rev-1"}, "data": {"S": "no-gsi"}},
            )

            # Query GSI - should return only the 2 items with gsiPk
            resp = dynamodb_client.query(
                TableName=table_name,
                IndexName="gsi-1",
                KeyConditionExpression="gsiPk = :gpk",
                ExpressionAttributeValues={":gpk": {"S": "group-A"}},
            )

            assert resp["Count"] == 2
            pks = {item["pk"]["S"] for item in resp["Items"]}
            assert "item-3" not in pks
        finally:
            dynamodb_client.delete_table(TableName=table_name)

    def test_query_lsi(self, dynamodb_client, unique_name):
        """Test querying Local Secondary Index."""
        table_name = f"pytest-lsi-{unique_name}"

        dynamodb_client.create_table(
            TableName=table_name,
            KeySchema=[
                {"AttributeName": "pk", "KeyType": "HASH"},
                {"AttributeName": "sk", "KeyType": "RANGE"},
            ],
            AttributeDefinitions=[
                {"AttributeName": "pk", "AttributeType": "S"},
                {"AttributeName": "sk", "AttributeType": "S"},
                {"AttributeName": "lsiSk", "AttributeType": "S"},
            ],
            LocalSecondaryIndexes=[
                {
                    "IndexName": "lsi-1",
                    "KeySchema": [
                        {"AttributeName": "pk", "KeyType": "HASH"},
                        {"AttributeName": "lsiSk", "KeyType": "RANGE"},
                    ],
                    "Projection": {"ProjectionType": "KEYS_ONLY"},
                }
            ],
            ProvisionedThroughput={"ReadCapacityUnits": 5, "WriteCapacityUnits": 5},
        )

        try:
            dynamodb_client.put_item(
                TableName=table_name,
                Item={
                    "pk": {"S": "item-1"},
                    "sk": {"S": "rev-1"},
                    "lsiSk": {"S": "2024-01-01"},
                },
            )
            dynamodb_client.put_item(
                TableName=table_name,
                Item={
                    "pk": {"S": "item-1"},
                    "sk": {"S": "rev-2"},
                    "lsiSk": {"S": "2024-01-02"},
                },
            )

            # Query LSI with range condition
            resp = dynamodb_client.query(
                TableName=table_name,
                IndexName="lsi-1",
                KeyConditionExpression="pk = :pk AND lsiSk > :d",
                ExpressionAttributeValues={
                    ":pk": {"S": "item-1"},
                    ":d": {"S": "2024-01-00"},
                },
            )

            assert resp["Count"] == 2
        finally:
            dynamodb_client.delete_table(TableName=table_name)
