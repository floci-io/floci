package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Verifies DynamoDB item data is isolated between accounts, mirroring
 * {@link io.github.hectorvent.floci.core.common.AccountIsolationIntegrationTest}'s SQS coverage.
 * Two accounts can each create a table with the same name; their items must not collide.
 */
@QuarkusTest
class DynamoDbAccountIsolationIntegrationTest {

    private static final String DYNAMODB_CONTENT_TYPE = "application/x-amz-json-1.0";

    private static final String AUTH_ACCOUNT_1 =
            "AWS4-HMAC-SHA256 Credential=000000000001/20260215/us-east-1/dynamodb/aws4_request, SignedHeaders=host, Signature=abc";
    private static final String AUTH_ACCOUNT_2 =
            "AWS4-HMAC-SHA256 Credential=000000000002/20260215/us-east-1/dynamodb/aws4_request, SignedHeaders=host, Signature=abc";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void itemsInSameNamedTableAreIsolatedBetweenAccounts() {
        String tableName = "account-isolation-shared-table";

        createTable(AUTH_ACCOUNT_1, tableName);
        createTable(AUTH_ACCOUNT_2, tableName);

        // Account 1 writes an item into its own table.
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
            .header("Authorization", AUTH_ACCOUNT_1)
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "Item": {"pk": {"S": "item-from-account-1"}, "secret": {"S": "account-1-data"}}
                }
                """.formatted(tableName))
        .when().post("/")
        .then().statusCode(200);

        // Account 2's same-named table must NOT see account 1's item.
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
            .header("Authorization", AUTH_ACCOUNT_2)
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "Key": {"pk": {"S": "item-from-account-1"}}
                }
                """.formatted(tableName))
        .when().post("/")
        .then()
            .statusCode(200)
            .body("Item", equalTo(null));

        // Account 2 scanning its own table must be empty too (not just GetItem).
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.Scan")
            .header("Authorization", AUTH_ACCOUNT_2)
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "%s"}
                """.formatted(tableName))
        .when().post("/")
        .then()
            .statusCode(200)
            .body("Count", equalTo(0));
    }

    private static void createTable(String auth, String tableName) {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .header("Authorization", auth)
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                    "ProvisionedThroughput": {"ReadCapacityUnits": 5, "WriteCapacityUnits": 5}
                }
                """.formatted(tableName))
        .when().post("/")
        .then().statusCode(200);
    }
}
