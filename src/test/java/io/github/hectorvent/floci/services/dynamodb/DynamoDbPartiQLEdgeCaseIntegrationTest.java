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

    @Test
    @Order(7)
    void refusesDuplicateMembersInABag() {
        putItem("bags", "\"s\":{\"SS\":[\"a\"]},\"n\":{\"NS\":[\"1\"]}");
        String update = "UPDATE \"" + TABLE + "\" SET ";
        String where = " WHERE pk='bags' AND sk='1'";

        statement(update + "s = set_add(s, <<'b','b'>>)" + where)
            .statusCode(400)
            .body("message", equalTo(
                    "One or more parameter values were invalid: Input collection [b, b] contains duplicates."));
        statement(update + "n = set_add(n, <<2, 2.0>>)" + where)
            .statusCode(400)
            .body("message", equalTo(
                    "One or more parameter values were invalid: Input collection [2, 2.0] contains duplicates! under root"));
        statement("SELECT sk FROM \"" + TABLE + "\"" + where + " AND s = <<'a','a'>>")
            .statusCode(400)
            .body("message", equalTo(
                    "One or more parameter values were invalid: Input collection [a, a] contains duplicates."));
        transaction("INSERT INTO \"" + TABLE + "\" VALUE {'pk':'bags-tx','sk':'1','x':[<<'a','a'>>]}")
            .statusCode(400)
            .body("message", equalTo("Validation failed in TransactStatements[0]:"
                    + " One or more parameter values were invalid: Input collection [a, a] contains duplicates."));
        getItem("bags-tx").body("Item", nullValue());

        statement(update + "n = <<1, 10>>" + where).statusCode(200);
        getItem("bags")
            .body("Item.s.SS", containsInAnyOrder("a"))
            .body("Item.n.NS", containsInAnyOrder("1", "10"));
    }

    @Test
    @Order(8)
    void refusesAnInvalidParameterBeforeReadingTheStatement() {
        String duplicates = "One or more parameter values were invalid: Input collection [a, a] contains duplicates.";
        String insert = "INSERT INTO \"" + TABLE + "\" VALUE {'pk':'params','sk':'1','x':?}";
        String update = "UPDATE \"" + TABLE + "\" SET y = ? WHERE pk='params' AND sk='1'";

        request("DynamoDB_20120810.ExecuteStatement",
                member("UPDATE \"" + TABLE + "\" SET WHERE", "[{\"SS\":[\"a\",\"a\"]}]"))
            .statusCode(400)
            .body("message", equalTo(duplicates));
        request("DynamoDB_20120810.ExecuteStatement", member(insert, "[{\"NULL\":false}]"))
            .statusCode(400)
            .body("message", equalTo(
                    "One or more parameter values were invalid: Null attribute value types must have the value of true"));
        request("DynamoDB_20120810.ExecuteTransaction", "{\"TransactStatements\":["
                + member(update, "[{\"N\":\"1\"}]") + "," + member(insert, "[{\"SS\":[\"a\",\"a\"]}]") + "]}")
            .statusCode(400)
            .body("message", equalTo(duplicates));
        request("DynamoDB_20120810.BatchExecuteStatement",
                "{\"Statements\":[" + member(update, "[{\"SS\":[]}]") + "]}")
            .statusCode(200)
            .body("Responses[0].Error.Code", equalTo("ValidationError"))
            .body("Responses[0].Error.Message", equalTo(
                    "One or more parameter values were invalid: An string set  may not be empty"));
        getItem("params").body("Item", nullValue());
    }

    @Test
    @Order(9)
    void refusesTwoReadsOfOneItemInATransaction() {
        putItem("reads", "\"a\":{\"S\":\"x\"}");
        String select = "SELECT * FROM \"" + TABLE + "\" WHERE pk='reads' AND sk='1'";

        transaction(select, "SELECT a FROM \"" + TABLE + "\" WHERE sk='1' AND pk='reads'")
            .statusCode(400)
            .body("message", equalTo("Transaction request cannot include multiple operations on one item"));
        transaction(select, select, "SELECT * FROM \"" + TABLE + "\" WHERE pk=1 AND sk='1'")
            .statusCode(400)
            .body("message", equalTo("Transaction cancelled, please refer cancellation reasons for specific reasons"
                    + " [None, None, ValidationError]"));
        transaction(select, "SELECT * FROM \"" + TABLE + "\" WHERE pk='reads' AND sk='2'")
            .statusCode(200)
            .body("Responses[0].Item.a.S", equalTo("x"));
    }

    @Test
    @Order(10)
    void readsOnlyByTheKeyInATransaction() {
        putItem("keyed", "\"flag\":{\"S\":\"right\"}");
        String select = "SELECT * FROM \"" + TABLE + "\" WHERE ";
        String refused = "Validation failed in TransactStatements[0]:"
                + " Select statements within ExecuteTransaction must specify the primary key in the where clause.";

        transaction(select + "pk='keyed' AND sk='1' AND flag='right'")
            .statusCode(400)
            .body("message", equalTo(refused));
        transaction(select + "pk='keyed' AND sk='1' AND pk='reads'")
            .statusCode(400)
            .body("message", equalTo(refused));
        transaction(select + "pk='keyed'")
            .statusCode(400)
            .body("message", equalTo(refused));
        transaction(select + "(sk='1') AND pk='keyed' AND pk='keyed'")
            .statusCode(200)
            .body("Responses[0].Item.flag.S", equalTo("right"));
    }

    @Test
    @Order(11)
    void refusesUnprojectedAttributesInsideGroupedConditionsOnAKeyedIndexRead() {
        request("DynamoDB_20120810.CreateTable", """
                {
                  "TableName": "partiql-edge-cases-index",
                  "AttributeDefinitions": [
                    {"AttributeName":"pk","AttributeType":"S"},
                    {"AttributeName":"gsiPk","AttributeType":"S"}
                  ],
                  "KeySchema": [{"AttributeName":"pk","KeyType":"HASH"}],
                  "GlobalSecondaryIndexes": [{
                    "IndexName": "gsi-inc",
                    "KeySchema": [{"AttributeName":"gsiPk","KeyType":"HASH"}],
                    "Projection": {"ProjectionType":"INCLUDE","NonKeyAttributes":["projattr"]}
                  }],
                  "BillingMode": "PAY_PER_REQUEST"
                }
                """)
            .statusCode(200);
        request("DynamoDB_20120810.PutItem", """
                {"TableName":"partiql-edge-cases-index",
                 "Item":{"pk":{"S":"p"},"gsiPk":{"S":"y"},"projattr":{"S":"proj1"},"nonproj":{"S":"np1"}}}
                """)
            .statusCode(200);
        String select = "SELECT pk FROM \"partiql-edge-cases-index\".\"gsi-inc\" WHERE gsiPk='y' AND ";
        String refused = "One or more parameter values were invalid: Secondary index gsi-inc"
                + " does not project one or more filter attributes: ";

        statement(select + "NOT (nonproj='np1' OR projattr='x')")
            .statusCode(400)
            .body("message", equalTo(refused + "[nonproj]"));
        statement(select + "b='1' AND (a='2' OR projattr='3')")
            .statusCode(400)
            .body("message", equalTo(refused + "[a, b]"));
        statement(select + "(zz='1' OR aa='2' OR mm='3')")
            .statusCode(400)
            .body("message", equalTo(refused + "[zz, aa, mm]"));
        statement(select + "(projattr='proj1' OR projattr='proj2')")
            .statusCode(200)
            .body("Items[0].pk.S", equalTo("p"));
    }

    @Test
    @Order(12)
    void skipsLineAndBlockComments() {
        putItem("comments", "\"n\":{\"N\":\"1\"},\"note\":{\"S\":\"a--b /*c*/\"}");
        String key = "pk='comments' AND sk='1'";

        statement("SELECT/**/* FROM \"" + TABLE + "\" -- the table\nWHERE pk/* key */='comments' AND sk='1' --")
            .statusCode(200)
            .body("Items[0].note.S", equalTo("a--b /*c*/"));
        statement("SELECT note FROM \"" + TABLE + "\" WHERE " + key + " AND note = 'a--b /*c*/'; -- done")
            .statusCode(200)
            .body("Items.size()", equalTo(1));
        statement("UPDATE \"" + TABLE + "\" SET n=n--1 WHERE " + key)
            .statusCode(400);
        statement("SELECT * FROM \"" + TABLE + "\" WHERE " + key + " /* open")
            .statusCode(400);
        getItem("comments").body("Item.n.N", equalTo("1"));
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

    private static String member(String partiql, String parameters) {
        return "{\"Statement\":" + json(partiql) + ",\"Parameters\":" + parameters + "}";
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
