package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.github.hectorvent.floci.testing.DynamoDbRequests.ddb;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * PartiQL's FROM-first form of UPDATE, {@code FROM "T" WHERE key SET ... REMOVE ...}, which
 * DynamoDB runs as the same update as {@code UPDATE "T" SET ... REMOVE ... WHERE key}.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbPartiQLFromFirstUpdateIntegrationTest {

    private static final String TABLE = "partiql-from-first-update";
    private static final String FROM = "FROM \"" + TABLE + "\" ";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void updatesTheItemTheWhereNames() {
        ddb("CreateTable", """
                {
                  "TableName": "%s",
                  "AttributeDefinitions": [{"AttributeName":"pk","AttributeType":"S"}],
                  "KeySchema": [{"AttributeName":"pk","KeyType":"HASH"}],
                  "BillingMode": "PAY_PER_REQUEST"
                }
                """.formatted(TABLE))
            .statusCode(200);
        seed("ff-set");

        ddb("ExecuteStatement", "{\"Statement\":" + quoted(FROM + "WHERE pk = ? SET c = ? REMOVE b RETURNING ALL NEW *")
                + ",\"Parameters\":[{\"S\":\"ff-set\"},{\"N\":\"7\"}]}")
            .statusCode(200)
            .body("Items[0].c.N", equalTo("7"))
            .body("Items[0].b", nullValue())
            .body("Items[0].a.N", equalTo("1"));
        statement("from \"" + TABLE + "\" where pk = 'ff-set' set d = 1").statusCode(200);
        getItem("ff-set").body("Item.d.N", equalTo("1")).body("Item.c.N", equalTo("7"));
    }

    @Test
    @Order(2)
    void refusesAStatementWithoutAWhereForItsMissingKey() {
        statement(FROM + "SET c = 1")
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("Where clause does not contain a mandatory equality on all key attributes"));
    }

    @Test
    @Order(3)
    void runsInsideATransactionAndABatch() {
        seed("ff-transaction");
        seed("ff-batch");

        ddb("ExecuteTransaction", "{\"TransactStatements\":[{\"Statement\":"
                + quoted(FROM + "WHERE pk = 'ff-transaction' SET c = 1") + "}]}")
            .statusCode(200);
        getItem("ff-transaction").body("Item.c.N", equalTo("1"));

        ddb("BatchExecuteStatement", "{\"Statements\":[{\"Statement\":"
                + quoted(FROM + "WHERE pk = 'ff-batch' SET c = 1") + "}]}")
            .statusCode(200)
            .body("Responses[0].Error", nullValue())
            .body("Responses[0].TableName", equalTo(TABLE));
        getItem("ff-batch").body("Item.c.N", equalTo("1"));
    }

    private static void seed(String pk) {
        ddb("PutItem", """
                {"TableName":"%s","Item":{"pk":{"S":"%s"},"a":{"N":"1"},"b":{"S":"bee"}}}
                """.formatted(TABLE, pk))
            .statusCode(200);
    }

    private static ValidatableResponse statement(String partiql) {
        return ddb("ExecuteStatement", "{\"Statement\":" + quoted(partiql) + "}");
    }

    private static String quoted(String text) {
        return JsonNodeFactory.instance.textNode(text).toString();
    }

    private static ValidatableResponse getItem(String pk) {
        return ddb("GetItem", """
                {"TableName":"%s","Key":{"pk":{"S":"%s"}},"ConsistentRead":true}
                """.formatted(TABLE, pk))
            .statusCode(200);
    }
}
