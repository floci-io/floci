package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Arrays;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * PartiQL edge cases, with every expectation captured from real DynamoDB (eu-west-2, 2026-09-17).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbPartiQLEdgeCaseIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String TABLE = "partiql-edge-cases";

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
                  "AttributeDefinitions": [
                    {"AttributeName":"pk","AttributeType":"S"},
                    {"AttributeName":"sk","AttributeType":"S"}
                  ],
                  "KeySchema": [
                    {"AttributeName":"pk","KeyType":"HASH"},
                    {"AttributeName":"sk","KeyType":"RANGE"}
                  ],
                  "BillingMode": "PAY_PER_REQUEST"
                }
                """.formatted(TABLE))
            .statusCode(200);
    }

    @Test
    @Order(2)
    void readsKeyEqualitiesInsideAGroupedAnd() {
        putItem("grouped", "\"flag\":{\"S\":\"x\"}");
        putItem("grouped-other", "\"flag\":{\"S\":\"x\"}");

        statement("UPDATE \"" + TABLE + "\" SET a=2 WHERE (pk='grouped' AND sk='1') AND flag='x'")
            .statusCode(200);
        getItem("grouped").body("Item.a.N", equalTo("2"));

        transaction("EXISTS(SELECT * FROM \"" + TABLE + "\" WHERE (pk='grouped' AND sk='1') AND nope IS MISSING)",
                "UPDATE \"" + TABLE + "\" SET b=1 WHERE pk='grouped-other' AND sk='1'")
            .statusCode(200);
        getItem("grouped-other").body("Item.b.N", equalTo("1"));

        statement("DELETE FROM \"" + TABLE + "\" WHERE (pk='grouped' AND sk='1') AND flag='x'")
            .statusCode(200);
        getItem("grouped").body("Item", nullValue());
    }

    @Test
    @Order(3)
    void subtractsWithoutSpacesAndRefusesTrailingTokens() {
        putItem("minus", "\"n\":{\"N\":\"5\"}");

        statement("UPDATE \"" + TABLE + "\" SET n=n-1 WHERE pk='minus' AND sk='1'").statusCode(200);
        getItem("minus").body("Item.n.N", equalTo("4"));

        statement("SELECT sk FROM \"" + TABLE + "\" WHERE pk='minus' AND n>-5;")
            .statusCode(200)
            .body("Items.size()", equalTo(1));
        statement("UPDATE \"" + TABLE + "\" SET t=1 WHERE pk='minus' AND sk='1' garbage")
            .statusCode(400)
            .body("message", equalTo(
                    "Statement wasn't well formed, can't be processed: Unexpected token after expression"));
        getItem("minus").body("Item.t", nullValue());
    }

    @Test
    @Order(4)
    void refusesANegativeListIndex() {
        putItem("index", "\"l\":{\"L\":[{\"S\":\"a\"},{\"S\":\"b\"}]}");
        String select = "SELECT l[-1] FROM \"" + TABLE + "\" WHERE pk='index' AND sk='1'";

        statement(select)
            .statusCode(400)
            .body("message", equalTo("List index is not within the allowable range; index: [-1] at 1:11:1"));
        statement("SELECT l[-0] FROM \"" + TABLE + "\" WHERE pk='index' AND sk='1'")
            .statusCode(200)
            .body("Items[0].'l[0]'.S", equalTo("a"));
    }

    @Test
    @Order(5)
    void refusesANonStringTypeInAttributeType() {
        statement("SELECT sk FROM \"" + TABLE + "\" WHERE pk='index' AND attribute_type(l, 1)")
            .statusCode(400)
            .body("message", equalTo("Incorrect operand type for operator or function;"
                    + " operator or function: attribute_type, operand type: N"));
        statement("SELECT sk FROM \"" + TABLE + "\" WHERE pk='index' AND attribute_type(l, true)")
            .statusCode(400)
            .body("message", equalTo("Incorrect operand type for operator or function;"
                    + " operator or function: attribute_type, operand type: BOOL"));
    }

    @Test
    @Order(6)
    void refusesAValueThatIsNotASetInSetAdd() {
        putItem("sets", "\"BandMembers\":{\"SS\":[\"member1\",\"member2\"]}");
        String prefix = "UPDATE \"" + TABLE + "\"\nSET BandMembers = set_add(";

        statement(prefix + "BandMembers, 'x')\nWHERE pk='sets' AND sk='1'")
            .statusCode(400)
            .body("message", equalTo("The second argument to SET_ADD must be a value with type SET at 2:27:11"));
        String quotedPrefix = "UPDATE \"" + TABLE + "\" SET \"BandMembers\" = set_delete(";
        statement(quotedPrefix + "\"BandMembers\", ['x']) WHERE pk='sets' AND sk='1'")
            .statusCode(400)
            .body("message", equalTo("The second argument to SET_DELETE must be a value with type SET at 1:"
                    + (quotedPrefix.length() + 1) + ":13"));
        getItem("sets").body("Item.BandMembers.SS", containsInAnyOrder("member1", "member2"));
    }

    private static ValidatableResponse getItem(String pk) {
        return request("DynamoDB_20120810.GetItem", """
                {"TableName":"%s","Key":{"pk":{"S":"%s"},"sk":{"S":"1"}},"ConsistentRead":true}
                """.formatted(TABLE, pk))
            .statusCode(200);
    }

    private static void putItem(String pk, String attributes) {
        request("DynamoDB_20120810.PutItem", """
                {"TableName":"%s","Item":{"pk":{"S":"%s"},"sk":{"S":"1"},%s}}
                """.formatted(TABLE, pk, attributes))
            .statusCode(200);
    }

    private static ValidatableResponse statement(String partiql) {
        return request("DynamoDB_20120810.ExecuteStatement", "{\"Statement\":" + json(partiql) + "}");
    }

    private static ValidatableResponse transaction(String... statements) {
        String members = Arrays.stream(statements)
                .map(s -> "{\"Statement\":" + json(s) + "}")
                .collect(Collectors.joining(","));
        return request("DynamoDB_20120810.ExecuteTransaction", "{\"TransactStatements\":[" + members + "]}");
    }

    private static String json(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
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
