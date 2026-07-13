package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Regression tests for issue #1640: UpdateExpression must be tokenized
 * whitespace-insensitively, so newlines, tabs and multiple spaces between clauses are
 * valid (real AWS accepts them). Previously a newline after a clause keyword was
 * rejected with 'Syntax error; token: "SET"'.
 */
@QuarkusTest
class DynamoDbUpdateExpressionWhitespaceTest {

    private static final String CT = "application/x-amz-json-1.0";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private void post(String target, String body) {
        given().header("X-Amz-Target", "DynamoDB_20120810." + target)
            .contentType(CT).body(body)
            .when().post("/").then().statusCode(200);
    }

    private void createAndSeed(String table) {
        post("CreateTable", """
            {"TableName":"%s","KeySchema":[{"AttributeName":"pk","KeyType":"HASH"}],
             "AttributeDefinitions":[{"AttributeName":"pk","AttributeType":"S"}],
             "BillingMode":"PAY_PER_REQUEST"}
            """.formatted(table));
        post("PutItem", """
            {"TableName":"%s","Item":{"pk":{"S":"x"},"a":{"S":"old"},"b":{"N":"1"}}}
            """.formatted(table));
    }

    @Test
    void newlineAfterSetKeywordIsAccepted() {
        createAndSeed("UpdWsSet");
        given().header("X-Amz-Target", "DynamoDB_20120810.UpdateItem").contentType(CT).body("""
            {"TableName":"UpdWsSet","Key":{"pk":{"S":"x"}},
             "UpdateExpression":"SET\\n  #a = :a,\\n  #b = :b",
             "ReturnValues":"ALL_NEW",
             "ExpressionAttributeNames":{"#a":"a","#b":"b"},
             "ExpressionAttributeValues":{":a":{"S":"new"},":b":{"N":"2"}}}
            """)
        .when().post("/").then()
            .statusCode(200)
            .body("Attributes.a.S", equalTo("new"))
            .body("Attributes.b.N", equalTo("2"));
    }

    @Test
    void tabsMultipleSpacesAndNewlineBetweenClausesAreAccepted() {
        createAndSeed("UpdWsMix");
        given().header("X-Amz-Target", "DynamoDB_20120810.UpdateItem").contentType(CT).body("""
            {"TableName":"UpdWsMix","Key":{"pk":{"S":"x"}},
             "UpdateExpression":"SET\\t#a   =   :a\\nREMOVE #b",
             "ReturnValues":"ALL_NEW",
             "ExpressionAttributeNames":{"#a":"a","#b":"b"},
             "ExpressionAttributeValues":{":a":{"S":"multi"}}}
            """)
        .when().post("/").then()
            .statusCode(200)
            .body("Attributes.a.S", equalTo("multi"))
            .body("Attributes.b", nullValue());
    }

    @Test
    void invalidLeadingKeywordIsStillRejected() {
        createAndSeed("UpdWsBad");
        given().header("X-Amz-Target", "DynamoDB_20120810.UpdateItem").contentType(CT).body("""
            {"TableName":"UpdWsBad","Key":{"pk":{"S":"x"}},
             "UpdateExpression":"FOO #a = :a",
             "ExpressionAttributeNames":{"#a":"a"},
             "ExpressionAttributeValues":{":a":{"S":"z"}}}
            """)
        .when().post("/").then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }
}
