package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.github.hectorvent.floci.testing.DynamoDbRequests.ddb;
import static org.hamcrest.Matchers.contains;

/**
 * A set comes back in DynamoDB's order however it was written: strings by code point,
 * numbers by value, binaries by unsigned byte.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbSetOrderIntegrationTest {

    private static final String TABLE = "set-member-order";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void putItemStoresEverySetInDynamoDbOrder() {
        ddb("CreateTable", """
                {
                  "TableName": "%s",
                  "AttributeDefinitions": [{"AttributeName":"pk","AttributeType":"S"}],
                  "KeySchema": [{"AttributeName":"pk","KeyType":"HASH"}],
                  "BillingMode": "PAY_PER_REQUEST"
                }
                """.formatted(TABLE))
            .statusCode(200);
        ddb("PutItem", """
                {"TableName":"%s","Item":{"pk":{"S":"a"},
                  "ss":{"SS":["b","a","B","\\uff5e","\\ud83d\\ude00"]},
                  "ns":{"NS":["10","9","1e2","-1"]},
                  "bs":{"BS":["/w==","gA==","fw==","AQ==","AP8="]},
                  "m":{"M":{"deep":{"SS":["b","a",""]}}},
                  "l":{"L":[{"NS":["2","1"]}]}}}
                """.formatted(TABLE))
            .statusCode(200);
        getItem("a")
            .body("Item.ss.SS", contains("B", "a", "b", "～", "😀"))
            .body("Item.ns.NS", contains("-1", "9", "10", "100"))
            .body("Item.bs.BS", contains("AP8=", "AQ==", "fw==", "gA==", "/w=="))
            .body("Item.m.M.deep.SS", contains("", "a", "b"))
            .body("Item.l.L[0].NS", contains("1", "2"));
    }

    @Test
    @Order(2)
    void updateExpressionsKeepTheOrder() {
        ddb("UpdateItem", """
                {"TableName":"%s","Key":{"pk":{"S":"a"}},"ReturnValues":"ALL_NEW",
                 "UpdateExpression":"ADD ss :s, ns :n SET s2 = :v",
                 "ExpressionAttributeValues":{":s":{"SS":["c",""]},":n":{"NS":["5","-3"]},":v":{"SS":["z","y"]}}}
                """.formatted(TABLE))
            .statusCode(200)
            .body("Attributes.ss.SS", contains("", "B", "a", "b", "c", "～", "😀"))
            .body("Attributes.ns.NS", contains("-3", "-1", "5", "9", "10", "100"))
            .body("Attributes.s2.SS", contains("y", "z"));
        ddb("UpdateItem", """
                {"TableName":"%s","Key":{"pk":{"S":"a"}},"ReturnValues":"UPDATED_NEW",
                 "AttributeUpdates":{"s2":{"Action":"ADD","Value":{"SS":["x"]}}}}
                """.formatted(TABLE))
            .statusCode(200)
            .body("Attributes.s2.SS", contains("x", "y", "z"));
        getItem("a")
            .body("Item.ss.SS", contains("", "B", "a", "b", "c", "～", "😀"))
            .body("Item.s2.SS", contains("x", "y", "z"));
    }

    @Test
    @Order(3)
    void batchAndTransactWritesKeepTheOrder() {
        ddb("BatchWriteItem", """
                {"RequestItems":{"%s":[{"PutRequest":{"Item":{"pk":{"S":"batch"},"ss":{"SS":["b","a"]}}}}]}}
                """.formatted(TABLE))
            .statusCode(200);
        ddb("TransactWriteItems", """
                {"TransactItems":[
                  {"Put":{"TableName":"%1$s","Item":{"pk":{"S":"tx"},"ns":{"NS":["2","1"]}}}},
                  {"Update":{"TableName":"%1$s","Key":{"pk":{"S":"tx2"}},"UpdateExpression":"ADD ss :v",
                    "ExpressionAttributeValues":{":v":{"SS":["d","c"]}}}}]}
                """.formatted(TABLE))
            .statusCode(200);
        getItem("batch").body("Item.ss.SS", contains("a", "b"));
        getItem("tx").body("Item.ns.NS", contains("1", "2"));
        getItem("tx2").body("Item.ss.SS", contains("c", "d"));
    }

    private static ValidatableResponse getItem(String pk) {
        return ddb("GetItem", """
                {"TableName":"%s","Key":{"pk":{"S":"%s"}},"ConsistentRead":true}
                """.formatted(TABLE, pk))
            .statusCode(200);
    }
}
