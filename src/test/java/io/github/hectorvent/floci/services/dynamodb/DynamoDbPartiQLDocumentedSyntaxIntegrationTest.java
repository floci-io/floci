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
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * The PartiQL forms the AWS developer guide shows, with every expectation captured from real
 * DynamoDB (eu-west-2, 2026-09-17).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbPartiQLDocumentedSyntaxIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String TABLE = "partiql-documented-syntax";

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
    void insertsMapsListsAndSets() {
        statement("INSERT INTO \"" + TABLE + "\" value {'pk':'dt', 'sk':'1', 'NumberType':1,"
                + " 'MapType' : {'entryname1': 'value', 'entryname2': 4},"
                + " 'ListType': [1,'stringval'],"
                + " 'NumberSetType':<<1,34,32,4.5>>,"
                + " 'StringSetType':<<'stringval','stringval2'>>}")
            .statusCode(200);

        getItem("dt")
            .body("Item.NumberType.N", equalTo("1"))
            .body("Item.MapType.M.entryname1.S", equalTo("value"))
            .body("Item.MapType.M.entryname2.N", equalTo("4"))
            .body("Item.ListType.L[0].N", equalTo("1"))
            .body("Item.ListType.L[1].S", equalTo("stringval"))
            .body("Item.NumberSetType.NS", containsInAnyOrder("1", "34", "32", "4.5"))
            .body("Item.StringSetType.SS", containsInAnyOrder("stringval", "stringval2"));
    }

    @Test
    @Order(3)
    void updatesWithListAppendAndSetAdd() {
        statement("UPDATE \"" + TABLE + "\""
                + " SET NumberType=NumberType + 100"
                + " SET MapType.NewMapEntry=[2020, 'stringvalue', 2.4]"
                + " SET ListType = LIST_APPEND(ListType, [4, <<'string1', 'string2'>>])"
                + " SET NumberSetType= SET_ADD(NumberSetType, <<345, 48.4>>)"
                + " SET StringSetType = SET_ADD(StringSetType, <<'stringsetvalue1', 'stringsetvalue2'>>)"
                + " WHERE pk='dt' AND sk='1'")
            .statusCode(200);

        getItem("dt")
            .body("Item.NumberType.N", equalTo("101"))
            .body("Item.MapType.M.NewMapEntry.L[0].N", equalTo("2020"))
            .body("Item.MapType.M.NewMapEntry.L[1].S", equalTo("stringvalue"))
            .body("Item.MapType.M.NewMapEntry.L[2].N", equalTo("2.4"))
            .body("Item.ListType.L.size()", equalTo(4))
            .body("Item.ListType.L[3].SS", containsInAnyOrder("string1", "string2"))
            .body("Item.NumberSetType.NS", containsInAnyOrder("1", "4.5", "32", "34", "48.4", "345"))
            .body("Item.StringSetType.SS",
                    containsInAnyOrder("stringsetvalue1", "stringsetvalue2", "stringval", "stringval2"));
    }

    @Test
    @Order(4)
    void updatesWithRemoveAndSetDelete() {
        statement("UPDATE \"" + TABLE + "\""
                + " SET NumberType=NumberType - 1"
                + " REMOVE ListType[1]"
                + " REMOVE MapType.NewMapEntry"
                + " SET NumberSetType = SET_DELETE( NumberSetType, <<345>>)"
                + " SET StringSetType = SET_DELETE( StringSetType, <<'stringsetvalue1'>>)"
                + " WHERE pk='dt' AND sk='1'")
            .statusCode(200);

        getItem("dt")
            .body("Item.NumberType.N", equalTo("100"))
            .body("Item.ListType.L.size()", equalTo(3))
            .body("Item.ListType.L[1].N", equalTo("4"))
            .body("Item.MapType.M.NewMapEntry", nullValue())
            .body("Item.NumberSetType.NS", containsInAnyOrder("1", "4.5", "32", "34", "48.4"))
            .body("Item.StringSetType.SS", containsInAnyOrder("stringsetvalue2", "stringval", "stringval2"));
    }

    @Test
    @Order(5)
    void readsExponentsAndDoubledQuotes() {
        statement("INSERT INTO \"" + TABLE + "\" VALUE {'pk':'num', 'sk':'1',"
                + " 'a':1E3, 'b':-1.5e-3, 'c':2.50, 'd':-0, 'q':'it''s here'}")
            .statusCode(200);

        getItem("num")
            .body("Item.a.N", equalTo("1000"))
            .body("Item.b.N", equalTo("-0.0015"))
            .body("Item.c.N", equalTo("2.5"))
            .body("Item.d.N", equalTo("0"))
            .body("Item.q.S", equalTo("it's here"));
        statement("SELECT sk FROM \"" + TABLE + "\" WHERE pk = 'num' AND q = 'it''s here' AND a = 1e3")
            .statusCode(200)
            .body("Items.size()", equalTo(1));
    }

    @Test
    @Order(6)
    void refusesABagDynamoDbCannotStore() {
        statement("INSERT INTO \"" + TABLE + "\" VALUE {'pk':'bag', 'sk':'1', 's':<<>>}")
            .statusCode(400)
            .body("message", equalTo("Empty bags are not supported"));
        statement("INSERT INTO \"" + TABLE + "\" VALUE {'pk':'bag', 'sk':'1', 's':<<1, 'a'>>}")
            .statusCode(400)
            .body("message", equalTo(
                    "Unsupported data type in Bag. DynamoDB only supports either numbers or strings in bags"));
        statement("INSERT INTO \"" + TABLE + "\" VALUE {'pk':'bag', 'sk':'1', 's':<<'a', 'a'>>}")
            .statusCode(400)
            .body("message", equalTo(
                    "One or more parameter values were invalid: Input collection [a, a] contains duplicates."));
    }

    @Test
    @Order(7)
    void appendsToListsAndChangesSetMembers() {
        putItem("sets", "\"AwardDetail\":{\"M\":{\"Grammys\":{\"L\":[{\"N\":\"2020\"},{\"N\":\"2018\"}]}}},"
                + "\"BandMembers\":{\"SS\":[\"member1\",\"member2\"]},\"Only\":{\"SS\":[\"only\"]}");

        update("sets", "SET AwardDetail.Grammys = list_append(AwardDetail.Grammys, [2016])");
        update("sets", "SET AwardDetail.Grammys = list_append([2010], AwardDetail.Grammys)");
        update("sets", "SET BandMembers = set_add(BandMembers, <<'newbandmember'>>)");
        update("sets", "SET BandMembers = set_delete(BandMembers, <<'member1'>>)");
        update("sets", "SET Fresh = set_add(Fresh, <<'m'>>)");
        update("sets", "SET Only = set_delete(Only, <<'only'>>)");

        getItem("sets")
            .body("Item.AwardDetail.M.Grammys.L.N", equalTo(List.of("2010", "2020", "2018", "2016")))
            .body("Item.'#n0.#n1'", nullValue())
            .body("Item.BandMembers.SS", containsInAnyOrder("member2", "newbandmember"))
            .body("Item.Fresh.SS", containsInAnyOrder("m"))
            .body("Item.Only", nullValue());
    }

    @Test
    @Order(8)
    void refusesMisusedUpdateFunctions() {
        String prefix = "UPDATE \"" + TABLE + "\" SET Other = ";
        statement(prefix + "set_add(BandMembers, <<'x'>>) WHERE pk='sets' AND sk='1'")
            .statusCode(400)
            .body("message", equalTo("The first argument to SET_ADD must equal the assignment value at 1:"
                    + (prefix.length() + 1) + ":7"));
        statement("UPDATE \"" + TABLE + "\" SET BandMembers = set_add(BandMembers, <<1>>) WHERE pk='sets' AND sk='1'")
            .statusCode(400)
            .body("message", equalTo("An operand in the update expression has an incorrect data type"));
        statement("UPDATE \"" + TABLE + "\" SET NoList = list_append(NoList, [1]) WHERE pk='sets' AND sk='1'")
            .statusCode(400)
            .body("message", equalTo("The provided expression refers to an attribute that does not exist in the item"));
    }

    @Test
    @Order(9)
    void filtersWithFunctionsAndNullChecks() {
        putItem("fn-1", "\"Address\":{\"S\":\"7834 24th Kirkland\"},\"tags\":{\"SS\":[\"a\",\"b\"]},"
                + "\"Total\":{\"N\":\"400\"},\"nul\":{\"NULL\":true},"
                + "\"Image\":{\"B\":\"" + Base64.getEncoder().encodeToString(new byte[400]) + "\"}");
        putItem("fn-2", "\"Total\":{\"N\":\"550\"}");

        assertKeys("pk IN ['fn-1', 'fn-2'] AND contains(\"Address\", 'Kirkland')", "fn-1");
        assertKeys("pk IN ['fn-1', 'fn-2'] AND contains(tags, 'a')", "fn-1");
        assertKeys("pk IN ['fn-1', 'fn-2'] AND attribute_type(\"Total\", 'N')", "fn-1", "fn-2");
        assertKeys("pk IN ['fn-1', 'fn-2'] AND size(\"Image\") > 300", "fn-1");
        assertKeys("pk IN ['fn-1', 'fn-2'] AND Total != 400", "fn-2");
        assertKeys("pk IN ['fn-1', 'fn-2'] AND nul IS NULL", "fn-1");
        assertKeys("pk IN ['fn-1', 'fn-2'] AND nul IS NOT NULL", "fn-2");
        assertKeys("pk IN ['fn-1', 'fn-2'] AND absent IS NULL");

        statement("SELECT pk FROM \"" + TABLE + "\" WHERE pk = 'fn-1' AND attribute_type(Total, 'X')")
            .statusCode(400)
            .body("message", equalTo(
                    "Invalid attribute type name found; type: X, valid types: {N,BS,L,B,NULL,M,S,SS,NS,BOOL}"));
    }

    @Test
    @Order(10)
    void ordersByTheKey() {
        for (String pk : List.of("o", "p")) {
            for (String sk : List.of("1", "2", "3")) {
                request("DynamoDB_20120810.PutItem", """
                        {"TableName":"%s","Item":{"pk":{"S":"%s"},"sk":{"S":"%s"}}}
                        """.formatted(TABLE, pk, sk))
                    .statusCode(200);
            }
        }

        assertOrder("pk = 'o' ORDER BY sk DESC", "o/3", "o/2", "o/1");
        assertOrder("pk = 'o' ORDER BY sk", "o/1", "o/2", "o/3");
        assertOrder("pk = 'o' ORDER BY pk ASC", "o/3", "o/2", "o/1");
        assertOrder("pk IN ['o', 'p'] ORDER BY pk DESC, sk ASC", "p/1", "p/2", "p/3", "o/1", "o/2", "o/3");

        String firstToken = request("DynamoDB_20120810.ExecuteStatement",
                orderedBody("pk IN ['o', 'p'] ORDER BY pk DESC", 2, null))
            .statusCode(200)
            .body("Items.sk.S", equalTo(List.of("3", "2")))
            .body("NextToken", notNullValue())
            .extract().path("NextToken");
        request("DynamoDB_20120810.ExecuteStatement", orderedBody("pk IN ['o', 'p'] ORDER BY pk DESC", 2, firstToken))
            .statusCode(200)
            .body("Items.pk.S", equalTo(List.of("p", "o")))
            .body("Items.sk.S", equalTo(List.of("1", "3")));
    }

    @Test
    @Order(11)
    void refusesAnOrderByItCannotServe() {
        String select = "SELECT sk FROM \"" + TABLE + "\" ";
        statement(select + "ORDER BY sk")
            .statusCode(400)
            .body("message", equalTo("Must have WHERE clause in the statement when using ORDER BY clause."));
        statement(select + "WHERE pk = 'o' ORDER BY Total DESC")
            .statusCode(400)
            .body("message", equalTo("Attribute Total in ORDER BY clause must be part of the primary key"));
        statement(select + "WHERE pk > 'a' ORDER BY sk")
            .statusCode(400)
            .body("message", equalTo("Must have at least one non-optional hash key condition in WHERE clause"
                    + " when using ORDER BY clause."));
        statement(select + "WHERE pk IN ['o', 'p'] ORDER BY sk DESC")
            .statusCode(400)
            .body("message", equalTo("Must have hash key in ORDER BY clause when more than one hash key condition"
                    + " specified in WHERE clause."));
    }

    @Test
    @Order(12)
    void checksAConditionWithExists() {
        putItem("x1", "\"free\":{\"S\":\"x\"}");
        putItem("x2", "\"free\":{\"S\":\"x\"}");
        putItem("x3", "\"Awards\":{\"N\":\"1\"}");

        transaction("EXISTS(SELECT * FROM \"" + TABLE + "\" WHERE pk = 'x1' AND sk = '1' AND Awards IS MISSING)",
                "UPDATE \"" + TABLE + "\" SET AwardsWon=1 WHERE pk = 'x2' AND sk = '1'")
            .statusCode(200);
        getItem("x2").body("Item.AwardsWon.N", equalTo("1"));

        transaction("EXISTS(SELECT * FROM \"" + TABLE + "\" WHERE pk = 'x3' AND sk = '1' AND Awards IS MISSING)",
                "UPDATE \"" + TABLE + "\" SET Touched=1 WHERE pk = 'x1' AND sk = '1'")
            .statusCode(400)
            .body("__type", equalTo("TransactionCanceledException"))
            .body("message", equalTo("Transaction cancelled, please refer cancellation reasons for specific reasons"
                    + " [ConditionalCheckFailed, None]"))
            .body("CancellationReasons[0].Code", equalTo("ConditionalCheckFailed"))
            .body("CancellationReasons[0].Message", equalTo("The conditional request failed"))
            .body("CancellationReasons[1].Code", equalTo("None"))
            .body("CancellationReasons[1]", not(hasKey("Message")));

        transaction("EXISTS(SELECT * FROM \"" + TABLE + "\" WHERE pk = 'x1' AND sk = '1')",
                "UPDATE \"" + TABLE + "\" SET Touched=1 WHERE pk = 'x2' AND sk = '1'")
            .statusCode(400)
            .body("message", equalTo("Validation failed in TransactStatements[0]:"
                    + " EXISTS() must contain a single item read with additional condition"));

        statement("EXISTS(SELECT * FROM \"" + TABLE + "\" WHERE pk = 'x1' AND sk = '1' AND Awards IS MISSING)")
            .statusCode(400)
            .body("message", equalTo("EXISTS can only be used in ExecuteTransaction write requests."));

        transaction("SELECT * FROM \"" + TABLE + "\" WHERE pk = 'x3' AND sk = '1'",
                "UPDATE \"" + TABLE + "\" SET Touched=1 WHERE pk = 'x3' AND sk = '1'")
            .statusCode(400)
            .body("message", equalTo(
                    "ExecuteTransaction API does not support both read and write operations in the same request."));
    }

    private void assertKeys(String where, String... pks) {
        statement("SELECT pk FROM \"" + TABLE + "\" WHERE " + where)
            .statusCode(200)
            .body("Items.pk.S", containsInAnyOrder((Object[]) pks));
    }

    private void assertOrder(String whereAndOrder, String... rows) {
        List<String> expected = List.of(rows);
        request("DynamoDB_20120810.ExecuteStatement", orderedBody(whereAndOrder, null, null))
            .statusCode(200)
            .body("Items.pk.S", equalTo(expected.stream().map(r -> r.split("/")[0]).toList()))
            .body("Items.sk.S", equalTo(expected.stream().map(r -> r.split("/")[1]).toList()));
    }

    private static String orderedBody(String whereAndOrder, Integer limit, String nextToken) {
        String statement = "SELECT pk, sk FROM \"" + TABLE + "\" WHERE " + whereAndOrder;
        return "{\"Statement\":" + json(statement)
                + (limit == null ? "" : ",\"Limit\":" + limit)
                + (nextToken == null ? "" : ",\"NextToken\":" + json(nextToken)) + "}";
    }

    private static void update(String pk, String action) {
        statement("UPDATE \"" + TABLE + "\" " + action + " WHERE pk='" + pk + "' AND sk='1'").statusCode(200);
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
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
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
