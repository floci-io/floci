package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * ADD and DELETE operand types, with every expectation captured from real DynamoDB (eu-west-2, 2026-09-17).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbAddDeleteOperandIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String TABLE = "add-delete-operands";
    private static final String KEY = "{\"pk\":{\"S\":\"a\"}}";
    private static final String REFUSED = "Invalid UpdateExpression: Incorrect operand type for operator or function;"
            + " operator: %s, operand type: %s, typeSet: ALLOWED_FOR_ADD_OPERAND";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createTable() {
        request("DynamoDB_20120810.CreateTable", """
                {
                  "TableName": "%s",
                  "AttributeDefinitions": [{"AttributeName":"pk","AttributeType":"S"}],
                  "KeySchema": [{"AttributeName":"pk","KeyType":"HASH"}],
                  "BillingMode": "PAY_PER_REQUEST"
                }
                """.formatted(TABLE))
            .statusCode(200);
        request("DynamoDB_20120810.PutItem", """
                {"TableName":"%s","Item":{"pk":{"S":"a"},"n":{"N":"5"},"s":{"SS":["m"]}}}
                """.formatted(TABLE))
            .statusCode(200);
    }

    @Test
    @Order(2)
    void updateItemRefusesAnOperandBeforeReadingTheItem() {
        updateItem("\"UpdateExpression\":\"ADD n :v\",\"ExpressionAttributeValues\":{\":v\":{\"S\":\"x\"}}")
            .statusCode(400)
            .body("message", equalTo("1 validation error detected: " + REFUSED.formatted("ADD", "STRING")));
        updateItem("\"UpdateExpression\":\"SET t = :t ADD n :v\","
                + "\"ExpressionAttributeValues\":{\":t\":{\"S\":\"t\"},\":v\":{\"BOOL\":true}}")
            .statusCode(400)
            .body("message", equalTo("1 validation error detected: " + REFUSED.formatted("ADD", "BOOL")));
        updateItem("\"UpdateExpression\":\"DELETE nope :v\",\"ConditionExpression\":\"attribute_exists(nope)\","
                + "\"ExpressionAttributeValues\":{\":v\":{\"L\":[{\"S\":\"m\"}]}}")
            .statusCode(400)
            .body("message", equalTo("1 validation error detected: " + REFUSED.formatted("DELETE", "LIST")));
        updateItem("\"UpdateExpression\":\"DELETE s :v\",\"ExpressionAttributeValues\":{\":v\":{\"N\":\"1\"}}")
            .statusCode(400)
            .body("message", equalTo("1 validation error detected: " + REFUSED.formatted("DELETE", "NUMBER")));
        assertItemUnchanged();
    }

    @Test
    @Order(3)
    void transactWriteItemsRefusesAnOperandWithoutTheErrorCount() {
        request("DynamoDB_20120810.TransactWriteItems", """
                {"TransactItems":[{"Update":{"TableName":"%s","Key":%s,"UpdateExpression":"ADD n :v",
                  "ExpressionAttributeValues":{":v":{"S":"x"}}}}]}
                """.formatted(TABLE, KEY))
            .statusCode(400)
            .body("message", equalTo(REFUSED.formatted("ADD", "STRING")));
        assertItemUnchanged();
    }

    private static void assertItemUnchanged() {
        request("DynamoDB_20120810.GetItem", """
                {"TableName":"%s","Key":%s,"ConsistentRead":true}
                """.formatted(TABLE, KEY))
            .statusCode(200)
            .body("Item.n.N", equalTo("5"))
            .body("Item.s.SS", contains("m"))
            .body("Item.t", nullValue());
    }

    private static ValidatableResponse updateItem(String expression) {
        return request("DynamoDB_20120810.UpdateItem",
                "{\"TableName\":\"" + TABLE + "\",\"Key\":" + KEY + "," + expression + "}");
    }

    private static ValidatableResponse request(String target, String body) {
        return given()
                .header("X-Amz-Target", target)
                .contentType(CONTENT_TYPE)
                .body(body)
            .when()
                .post("/")
            .then();
    }
}
