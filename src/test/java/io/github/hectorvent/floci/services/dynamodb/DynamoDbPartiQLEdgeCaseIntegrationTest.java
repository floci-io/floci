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
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * PartiQL edge cases, with every expectation captured from real DynamoDB (eu-west-2, 2026-09-17).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbPartiQLEdgeCaseIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String TABLE = "partiql-edge-cases";
    private static final String QUOTED_TABLE = "\"" + TABLE + "\"";

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

        statement("UPDATE " + QUOTED_TABLE + " SET a=2 WHERE (pk='grouped' AND sk='1') AND flag='x'")
            .statusCode(200);
        getItem("grouped").body("Item.a.N", equalTo("2"));

        transaction("EXISTS(SELECT * FROM " + QUOTED_TABLE + " WHERE (pk='grouped' AND sk='1') AND nope IS MISSING)",
                "UPDATE " + QUOTED_TABLE + " SET b=1 WHERE pk='grouped-other' AND sk='1'")
            .statusCode(200);
        getItem("grouped-other").body("Item.b.N", equalTo("1"));

        statement("DELETE FROM " + QUOTED_TABLE + " WHERE (pk='grouped' AND sk='1') AND flag='x'")
            .statusCode(200);
        getItem("grouped").body("Item", nullValue());
    }

    @Test
    @Order(3)
    void subtractsWithoutSpacesAndRefusesTrailingTokens() {
        putItem("minus", "\"n\":{\"N\":\"5\"}");

        statement("UPDATE " + QUOTED_TABLE + " SET n=n-1 WHERE pk='minus' AND sk='1'").statusCode(200);
        getItem("minus").body("Item.n.N", equalTo("4"));

        statement("SELECT sk FROM " + QUOTED_TABLE + " WHERE pk='minus' AND n>-5;")
            .statusCode(200)
            .body("Items.size()", equalTo(1));
        statement("UPDATE " + QUOTED_TABLE + " SET t=1 WHERE pk='minus' AND sk='1' garbage")
            .statusCode(400)
            .body("message", equalTo(
                    "Statement wasn't well formed, can't be processed: Unexpected token after expression"));
        getItem("minus").body("Item.t", nullValue());
    }

    @Test
    @Order(4)
    void refusesANegativeListIndex() {
        putItem("index", "\"l\":{\"L\":[{\"S\":\"a\"},{\"S\":\"b\"}]}");
        String select = "SELECT l[-1] FROM " + QUOTED_TABLE + " WHERE pk='index' AND sk='1'";

        statement(select)
            .statusCode(400)
            .body("message", equalTo("List index is not within the allowable range; index: [-1] at 1:11:1"));
        statement("SELECT l[-0] FROM " + QUOTED_TABLE + " WHERE pk='index' AND sk='1'")
            .statusCode(200)
            .body("Items[0].'l[0]'.S", equalTo("a"));
    }

    @Test
    @Order(15)
    void refusesAListIndexAboveTheIntegerRange() {
        statement("SELECT l[2147483648] FROM " + QUOTED_TABLE + " WHERE pk='index' AND sk='1'")
            .statusCode(400)
            .body("message", equalTo("List index is not within the allowable range; index: [2147483648] at 1:10:10"));
        statement("UPDATE " + QUOTED_TABLE + " REMOVE l[2147483648] WHERE pk='index' AND sk='1'")
            .statusCode(400);
        statement("SELECT l[2147483647] FROM " + QUOTED_TABLE + " WHERE pk='index' AND sk='1'")
            .statusCode(200);
        getItem("index").body("Item.l.L.size()", equalTo(2));
    }

    @Test
    @Order(5)
    void refusesANonStringTypeInAttributeType() {
        statement("SELECT sk FROM " + QUOTED_TABLE + " WHERE pk='index' AND attribute_type(l, 1)")
            .statusCode(400)
            .body("message", equalTo("Incorrect operand type for operator or function;"
                    + " operator or function: attribute_type, operand type: N"));
        statement("SELECT sk FROM " + QUOTED_TABLE + " WHERE pk='index' AND attribute_type(l, true)")
            .statusCode(400)
            .body("message", equalTo("Incorrect operand type for operator or function;"
                    + " operator or function: attribute_type, operand type: BOOL"));
    }

    @Test
    @Order(6)
    void refusesAValueThatIsNotASetInSetAdd() {
        putItem("sets", "\"BandMembers\":{\"SS\":[\"member1\",\"member2\"]}");
        String prefix = "UPDATE " + QUOTED_TABLE + "\nSET BandMembers = set_add(";

        statement(prefix + "BandMembers, 'x')\nWHERE pk='sets' AND sk='1'")
            .statusCode(400)
            .body("message", equalTo("The second argument to SET_ADD must be a value with type SET at 2:27:11"));
        String quotedPrefix = "UPDATE " + QUOTED_TABLE + " SET \"BandMembers\" = set_delete(";
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
        String update = "UPDATE " + QUOTED_TABLE + " SET ";
        String where = " WHERE pk='bags' AND sk='1'";

        statement(update + "s = set_add(s, <<'b','b'>>)" + where)
            .statusCode(400)
            .body("message", equalTo(
                    "One or more parameter values were invalid: Input collection [b, b] contains duplicates."));
        statement(update + "n = set_add(n, <<2, 2.0>>)" + where)
            .statusCode(400)
            .body("message", equalTo(
                    "One or more parameter values were invalid: Input collection [2, 2.0] contains duplicates! under root"));
        statement("SELECT sk FROM " + QUOTED_TABLE + where + " AND s = <<'a','a'>>")
            .statusCode(400)
            .body("message", equalTo(
                    "One or more parameter values were invalid: Input collection [a, a] contains duplicates."));
        transaction("INSERT INTO " + QUOTED_TABLE + " VALUE {'pk':'bags-tx','sk':'1','x':[<<'a','a'>>]}")
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
        String insert = "INSERT INTO " + QUOTED_TABLE + " VALUE {'pk':'params','sk':'1','x':?}";
        String update = "UPDATE " + QUOTED_TABLE + " SET y = ? WHERE pk='params' AND sk='1'";

        statement("UPDATE " + QUOTED_TABLE + " SET WHERE", "[{\"SS\":[\"a\",\"a\"]}]")
            .statusCode(400)
            .body("message", equalTo(duplicates));
        statement(insert, "[{\"NULL\":false}]")
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
        String select = "SELECT * FROM " + QUOTED_TABLE + " WHERE pk='reads' AND sk='1'";

        transaction(select, "SELECT a FROM " + QUOTED_TABLE + " WHERE sk='1' AND pk='reads'")
            .statusCode(400)
            .body("message", equalTo("Transaction request cannot include multiple operations on one item"));
        transaction(select, select, "SELECT * FROM " + QUOTED_TABLE + " WHERE pk=1 AND sk='1'")
            .statusCode(400)
            .body("message", equalTo("Transaction cancelled, please refer cancellation reasons for specific reasons"
                    + " [None, None, ValidationError]"));
        transaction(select, "SELECT * FROM " + QUOTED_TABLE + " WHERE pk='reads' AND sk='2'")
            .statusCode(200)
            .body("Responses[0].Item.a.S", equalTo("x"));
    }

    @Test
    @Order(10)
    void readsOnlyByTheKeyInATransaction() {
        putItem("keyed", "\"flag\":{\"S\":\"right\"}");
        String select = "SELECT * FROM " + QUOTED_TABLE + " WHERE ";
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

        statement("SELECT/**/* FROM " + QUOTED_TABLE + " -- the table\nWHERE pk/* key */='comments' AND sk='1' --")
            .statusCode(200)
            .body("Items[0].note.S", equalTo("a--b /*c*/"));
        statement("SELECT note FROM " + QUOTED_TABLE + " WHERE " + key + " AND note = 'a--b /*c*/'; -- done")
            .statusCode(200)
            .body("Items.size()", equalTo(1));
        statement("UPDATE " + QUOTED_TABLE + " SET n=n--1 WHERE " + key)
            .statusCode(400);
        statement("SELECT * FROM " + QUOTED_TABLE + " WHERE " + key + " /* open")
            .statusCode(400);
        getItem("comments").body("Item.n.N", equalTo("1"));
    }

    @Test
    @Order(13)
    void appliesASignToANumberLiteral() {
        putItem("signs", "\"n\":{\"N\":\"1\"}");
        String select = "SELECT sk FROM " + QUOTED_TABLE + " WHERE pk='signs' AND sk='1' AND ";

        statement(select + "n = - - 1").statusCode(200).body("Items.size()", equalTo(1));
        statement(select + "n > -/* c */-1").statusCode(200).body("Items.size()", equalTo(0));
        statement(select + "n BETWEEN - 2 AND +5 AND n IN [- 1, 1]").statusCode(200).body("Items.size()", equalTo(1));
        statement(select + "n > -?").statusCode(400);
        statement(select + "n > -n").statusCode(400);

        statement("UPDATE " + QUOTED_TABLE + " SET n = n - - 2, l = [- 1] WHERE pk='signs' AND sk='1'").statusCode(200);
        getItem("signs")
            .body("Item.n.N", equalTo("3"))
            .body("Item.l.L[0].N", equalTo("-1"));
    }

    @Test
    @Order(14)
    void refusesAParameterInsideACollectionLiteral() {
        String prefix = "UPDATE " + QUOTED_TABLE + " SET x = ";

        statement(prefix + "[?] WHERE pk='literals' AND sk='1'", "[{\"S\":\"v\"}]")
            .statusCode(400)
            .body("message", equalTo("Unsupported data type: Parameter under key root[0] at 1:"
                    + (prefix.length() + 2) + ":1"));
        String insert = "INSERT INTO " + QUOTED_TABLE + " VALUE {'pk':'literals','sk':'1','m':{'k':?}}";
        statement(insert, "[{\"S\":\"v\"}]")
            .statusCode(400)
            .body("message", equalTo("Unsupported data type: Parameter under key root.k at 1:"
                    + (insert.indexOf('?') + 1) + ":1"));
        getItem("literals").body("Item", nullValue());

        statement("INSERT INTO " + QUOTED_TABLE + " VALUE {'pk':?,'sk':'1','n':1}", "[{\"S\":\"literals\"}]")
            .statusCode(200);
        statement("SELECT sk FROM " + QUOTED_TABLE + " WHERE pk='literals' AND n IN [?, 2]", "[{\"N\":\"1\"}]")
            .statusCode(200)
            .body("Items.size()", equalTo(1));
    }

    @Test
    @Order(16)
    void refusesALiteralNested32LevelsDeep() {
        String update = "UPDATE " + QUOTED_TABLE + " SET d = ";
        String where = " WHERE pk='nested' AND sk='1'";

        statement(update + "{'a':".repeat(32) + "1" + "}".repeat(32) + where)
            .statusCode(400)
            .body("message", equalTo("Nesting Levels have exceeded supported limits under root" + ".a".repeat(32)));
        statement(update + "{'a':" + "[".repeat(31) + "1" + "]".repeat(31) + "}" + where)
            .statusCode(400)
            .body("message", equalTo("Nesting Levels have exceeded supported limits under root.a" + "[0]".repeat(31)));
        transaction(update + "[".repeat(32) + "1" + "]".repeat(32) + where)
            .statusCode(400)
            .body("message", equalTo("Validation failed in TransactStatements[0]:"
                    + " Nesting Levels have exceeded supported limits under root" + "[0]".repeat(32)));

        statement("INSERT INTO " + QUOTED_TABLE + " VALUE {'pk':'nested','sk':'1','d':"
                + "{'a':".repeat(31) + "1" + "}".repeat(31) + "}")
            .statusCode(200);
        getItem("nested").body("Item.d.M.a.M.a.M", notNullValue());
    }

    @Test
    @Order(17)
    void refusesANonNumberLiteralInArithmeticBeforeTheCondition() {
        putItem("arithmetic", "\"n\":{\"N\":\"1\"}");
        String update = "UPDATE " + QUOTED_TABLE + " SET n = ";
        String failingCondition = " WHERE pk='arithmetic' AND sk='1' AND nope='x'";

        statement(update + "'a' + 1" + failingCondition)
            .statusCode(400)
            .body("message", equalTo("Incorrect operand type for operator or function; operator or function: +, operand type: S"));
        statement(update + "n - null" + failingCondition)
            .statusCode(400)
            .body("message", equalTo("Incorrect operand type for operator or function; operator or function: -, operand type: NULL"));
        transaction(update + "n + [1] WHERE pk='arithmetic' AND sk='1'")
            .statusCode(400)
            .body("message", equalTo("Validation failed in TransactStatements[0]:"
                    + " Incorrect operand type for operator or function; operator or function: +, operand type: L"));
        getItem("arithmetic").body("Item.n.N", equalTo("1"));
    }

    @Test
    @Order(18)
    void readsAttributeExistsAndAttributeNotExists() {
        putItem("exists", "\"m\":{\"M\":{\"x\":{\"S\":\"y\"}}},\"l\":{\"L\":[{\"S\":\"a\"}]}");
        String select = "SELECT sk FROM " + QUOTED_TABLE + " WHERE pk='exists' AND sk='1' AND ";

        statement(select + "attribute_exists(m.x) AND ATTRIBUTE_NOT_EXISTS (nope) AND attribute_exists(l[0])")
            .statusCode(200)
            .body("Items.size()", equalTo(1));
        statement(select + "(attribute_exists(nope) OR NOT attribute_exists(\"m\"))")
            .statusCode(200)
            .body("Items.size()", equalTo(0));
        statement(select + "attribute_exists('m')").statusCode(400);

        statement("UPDATE " + QUOTED_TABLE + " SET t=1 WHERE pk='exists' AND sk='1' AND attribute_exists(nope)")
            .statusCode(400)
            .body("__type", equalTo("ConditionalCheckFailedException"));
        getItem("exists").body("Item.t", nullValue());
    }

    @Test
    @Order(19)
    void comparesAConditionWithABoolean() {
        putItem("booleans", "\"n\":{\"N\":\"1\"},\"flag\":{\"S\":\"right\"},\"yes\":{\"BOOL\":true}");
        String select = "SELECT sk FROM " + QUOTED_TABLE + " WHERE pk='booleans' AND sk='1' AND ";

        for (String holds : List.of("attribute_exists(nope) = false", "true = attribute_exists(n)",
                "attribute_exists(n) <> 1", "contains(nope, 'z') = false", "(n BETWEEN 0 AND 5) = true",
                "(n = 5 OR flag = 'x') = false", "attribute_exists(nope) IN [true, false]",
                "attribute_exists(n) = attribute_exists(flag)", "attribute_exists(n) = yes")) {
            statement(select + holds).statusCode(200).body("Items.size()", equalTo(1));
        }
        for (String fails : List.of("attribute_exists(n) = 1", "NOT attribute_exists(n) = true",
                "(attribute_exists(n) = true) = false", "attribute_exists(n) = flag")) {
            statement(select + fails).statusCode(200).body("Items.size()", equalTo(0));
        }
        statement(select + "attribute_exists(n) > false")
            .statusCode(400)
            .body("message", equalTo("Incorrect operand type for operator or function; operator or function: >, operand type: BOOL"));
    }

    @Test
    @Order(20)
    void readsSortKeyConditionsThatAQueryKeyCannotHold() {
        putItem("ranges", "\"flag\":{\"S\":\"one\"}");
        putItem("ranges", "2", "\"flag\":{\"S\":\"two\"}");
        String select = "SELECT sk FROM " + QUOTED_TABLE + " WHERE pk='ranges' AND ";

        for (String both : List.of("(sk='1' OR sk='2')", "sk IN ['1','2']", "(sk < '1' OR sk > '1' OR sk='1')",
                "attribute_exists(pk)")) {
            statement(select + both).statusCode(200).body("Items.sk.S", containsInAnyOrder("1", "2"));
        }
        for (String second : List.of("NOT sk='1'", "sk <> '1'", "sk > '0' AND sk <> '1'")) {
            statement(select + second).statusCode(200).body("Items.sk.S", containsInAnyOrder("2"));
        }
        statement(select + "sk='1' AND sk > '0'").statusCode(200).body("Items.sk.S", containsInAnyOrder("1"));
    }

    @Test
    @Order(21)
    void refusesOverlappingKeyConditionsAndMismatchedKeyTypes() {
        String select = "SELECT sk FROM " + QUOTED_TABLE + " WHERE ";
        String overlapping = "Overlapping conditions with range keys are not supported in where clause";

        for (String where : List.of("pk IN ['ranges','ranges']", "pk='ranges' OR pk='ranges'",
                "pk='ranges' AND (sk > '0' OR sk < '5')", "pk='ranges' AND (sk='1' OR flag='two')",
                "(pk='ranges' AND flag='one') OR (pk='ranges' AND flag='two')")) {
            statement(select + where).statusCode(400).body("message", equalTo(overlapping));
        }
        transaction(select + "pk IN ['ranges','ranges'] AND sk='1'")
            .statusCode(400)
            .body("message", equalTo("Validation failed in TransactStatements[0]: " + overlapping));
        statement(select + "pk='ranges' OR sk='1'").statusCode(200);

        statement(select + "pk = 1 OR pk = 'ranges'")
            .statusCode(400)
            .body("message", equalTo("Key attribute's data type should match its data type in table's schema: Key pk"));
        statement(select + "pk = 'ranges' AND sk IN ['1', 2]")
            .statusCode(400)
            .body("message", equalTo("Key attribute's data type should match its data type in table's schema: Key sk"));
        statement(select + "pk > 1").statusCode(200).body("Items.size()", equalTo(0));
    }

    @Test
    @Order(22)
    void checksProjectionOnEachKeyedBranchOfAnIndexRead() {
        String select = "SELECT pk FROM \"partiql-edge-cases-index\".\"gsi-inc\" WHERE ";
        String refused = "One or more parameter values were invalid: Secondary index gsi-inc"
                + " does not project one or more filter attributes: ";

        statement(select + "(gsiPk='y' OR gsiPk='z') AND zz='1'")
            .statusCode(400)
            .body("message", equalTo(refused + "[zz]"));
        statement(select + "(gsiPk='b' AND u1='1') OR (gsiPk='q' AND u2='1')")
            .statusCode(400)
            .body("message", equalTo(refused + "[u2]"));
        statement(select + "(gsiPk='q' AND u1='1') OR (gsiPk='b' AND u2='1')")
            .statusCode(400)
            .body("message", equalTo(refused + "[u1]"));
        for (String accepted : List.of("(gsiPk='y' AND projattr='1') OR (gsiPk='z' AND aa='1')",
                "gsiPk > 'a' AND zz='1'", "gsiPk='y' AND (gsiPk='z' OR zz='1')", "(gsiPk='y' AND zz='1') OR aa='1'")) {
            statement(select + accepted).statusCode(200);
        }
        statement(select + "(gsiPk='y' AND zz='1') OR (gsiPk='y' AND aa='1')")
            .statusCode(400)
            .body("message", equalTo("Overlapping conditions with range keys are not supported in where clause"));
    }

    @Test
    @Order(23)
    void readsByAOneValueInAndRefusesAnythingBesideTheKeyInTransactionAndBatchReads() {
        String select = "SELECT sk FROM " + QUOTED_TABLE + " WHERE ";
        String batchRefused = "Select statements within BatchExecuteStatement must specify the primary key in the where clause.";

        transaction(select + "pk IN ['ranges'] AND sk IN ['1']")
            .statusCode(200)
            .body("Responses[0].Item.sk.S", equalTo("1"));
        transaction(select + "(pk IN ['ranges']) AND pk='ranges' AND sk='2'")
            .statusCode(200)
            .body("Responses[0].Item.sk.S", equalTo("2"));
        transaction(select + "pk IN ['other'] AND pk='ranges' AND sk='1'")
            .statusCode(400)
            .body("message", equalTo("Validation failed in TransactStatements[0]:"
                    + " Select statements within ExecuteTransaction must specify the primary key in the where clause."));
        transaction("EXISTS(" + select + "pk IN ['ranges'] AND sk='1' AND flag='one')",
                "UPDATE " + QUOTED_TABLE + " SET b=1 WHERE pk='ranges' AND sk='2'")
            .statusCode(400)
            .body("message", equalTo("Validation failed in TransactStatements[0]:"
                    + " EXISTS() must contain a single item read with additional condition"));

        request("DynamoDB_20120810.BatchExecuteStatement", "{\"Statements\":["
                + "{\"Statement\":" + json(select + "pk IN ['ranges'] AND sk='1'") + "},"
                + "{\"Statement\":" + json(select + "pk='ranges' AND sk='1' AND flag='one'") + "},"
                + "{\"Statement\":" + json(select + "pk='ranges' AND sk='1' AND pk='other'") + "}]}")
            .statusCode(200)
            .body("Responses[0].Item.sk.S", equalTo("1"))
            .body("Responses[1].Error.Message", equalTo(batchRefused))
            .body("Responses[2].Error.Message", equalTo(batchRefused));
    }

    @Test
    @Order(24)
    void refusesTwoValuesForOneKeyOnAWriteAndReadsTheFirstInExists() {
        putItem("conflict", "\"flag\":{\"S\":\"right\"}");
        putItem("other", "\"flag\":{\"S\":\"right\"}");
        String multiple = "Multiple conditions on same key pk. Only single item Update/Insert/Delete are supported";

        statement("UPDATE " + QUOTED_TABLE + " SET a=1 WHERE pk='conflict' AND sk='1' AND pk='other'")
            .statusCode(400)
            .body("message", equalTo(multiple));
        statement("DELETE FROM " + QUOTED_TABLE + " WHERE pk='conflict' AND sk='1' AND pk='other'")
            .statusCode(400)
            .body("message", equalTo(multiple));
        statement("UPDATE " + QUOTED_TABLE + " SET a=2 WHERE pk='conflict' AND sk='1' AND pk='conflict'")
            .statusCode(200);
        getItem("conflict").body("Item.a.N", equalTo("2"));

        transaction("EXISTS(SELECT * FROM " + QUOTED_TABLE + " WHERE pk='conflict' AND sk='1' AND pk='other' AND flag='right')",
                "UPDATE " + QUOTED_TABLE + " SET b=1 WHERE pk='other' AND sk='1'")
            .statusCode(400)
            .body("CancellationReasons[0].Code", equalTo("ConditionalCheckFailed"))
            .body("CancellationReasons[1].Code", equalTo("None"));
    }

    @Test
    @Order(25)
    void cancelsATransactionThatNamesAMissingTable() {
        putItem("missing", "\"flag\":{\"S\":\"right\"}");
        String missingUpdate = "UPDATE \"partiql-edge-cases-missing\" SET a=1 WHERE pk='x' AND sk='1'";
        String select = "SELECT * FROM " + QUOTED_TABLE + " WHERE pk='missing' AND sk='1'";

        transaction(missingUpdate + " RETURNING ALL NEW *", "DELETE FROM \"partiql-edge-cases-missing\" WHERE pk='x' AND sk='1'")
            .statusCode(400)
            .body("__type", equalTo("TransactionCanceledException"))
            .body("CancellationReasons.Code", contains("ResourceNotFound", "ResourceNotFound"))
            .body("CancellationReasons[0].Message", equalTo("Requested resource not found"));
        transaction(select, "SELECT * FROM \"partiql-edge-cases-missing\".\"idx\" WHERE pk='x' AND sk='1'")
            .statusCode(400)
            .body("CancellationReasons.Code", contains("None", "ResourceNotFound"))
            .body("CancellationReasons[0].Message", nullValue());

        transaction(missingUpdate, "UPDATE " + QUOTED_TABLE + " SET a=1 WHERE pk='missing' AND sk='1' RETURNING ALL NEW *")
            .statusCode(400)
            .body("message", equalTo("Validation failed in TransactStatements[1]: RETURNING clause is not supported in ExecuteTransaction."));
        transaction("SELECT * FROM \"partiql-edge-cases-missing\" WHERE pk='x' AND sk='1'", "SELECT * FROM " + QUOTED_TABLE + ".\"idx\" WHERE pk='x' AND sk='1'")
            .statusCode(400)
            .body("message", equalTo("Validation failed in TransactStatements[1]: Reads on indices are not supported within transactions."));
        getItem("missing").body("Item.a", nullValue());
    }

    @Test
    @Order(26)
    void cancelsATransactionWhoseWriteDoesNotNameOneItem() {
        putItem("keys", "\"flag\":{\"S\":\"right\"}");
        String update = "UPDATE " + QUOTED_TABLE + " SET a=1 WHERE ";
        String mismatch = "The provided key element does not match the schema";

        transaction(update + "pk='keys'", update + "pk IN ['keys'] AND sk='1'", update + "pk='keys' AND sk='1' AND pk='other'",
                update + "pk='keys' AND sk=1", update + "pk='keys' AND sk='1'")
            .statusCode(400)
            .body("CancellationReasons.Code", contains("ValidationError", "ValidationError", "ValidationError",
                    "ValidationError", "None"))
            .body("CancellationReasons[0].Message", equalTo(mismatch))
            .body("CancellationReasons[3].Message", equalTo(mismatch));
        transaction("INSERT INTO " + QUOTED_TABLE + " VALUE {'pk':'keys-insert'}", "DELETE FROM " + QUOTED_TABLE + " WHERE sk='1'")
            .statusCode(400)
            .body("CancellationReasons[0].Message", equalTo("One or more parameter values were invalid: Missing the key sk in the item"))
            .body("CancellationReasons[1].Message", equalTo(mismatch));
        transaction("SELECT * FROM " + QUOTED_TABLE + " WHERE pk='keys' AND sk=1")
            .statusCode(400)
            .body("CancellationReasons[0].Message", equalTo(mismatch));
        getItem("keys").body("Item.a", nullValue());
    }

    @Test
    @Order(27)
    void refusesAWriteThatLeavesTheItemTooDeep() {
        putItem("deep-path", "\"m\":{\"M\":{}}");
        String update = "UPDATE " + QUOTED_TABLE + " SET m.deep = ? WHERE pk='deep-path' AND sk='1'";
        String parameters = "[" + "{\"M\":{\"a\":".repeat(31) + "{\"S\":\"x\"}" + "}}".repeat(31) + "]";
        String tooDeep = "Nesting Levels have exceeded supported limits";

        statement(update, parameters)
            .statusCode(400)
            .body("message", equalTo(tooDeep));
        request("DynamoDB_20120810.ExecuteTransaction", "{\"TransactStatements\":[" + member(update, parameters) + "]}")
            .statusCode(400)
            .body("CancellationReasons[0].Code", equalTo("ValidationError"))
            .body("CancellationReasons[0].Message", equalTo(tooDeep));
        request("DynamoDB_20120810.BatchExecuteStatement", "{\"Statements\":[" + member(update, parameters) + "]}")
            .statusCode(200)
            .body("Responses[0].Error.Message", equalTo(tooDeep))
            .body("Responses[0].TableName", equalTo(TABLE));
        getItem("deep-path").body("Item.m.M.deep", nullValue());
    }

    @Test
    @Order(28)
    void namesAPathColumnFromItsLastNamedComponent() {
        putItem("columns", "\"m\":{\"M\":{\"k\":{\"L\":[{\"S\":\"c\"}]}}}");

        statement("SELECT m.k[0] FROM " + QUOTED_TABLE + " WHERE pk='columns' AND sk='1'")
            .statusCode(200)
            .body("Items[0].'k[0]'.S", equalTo("c"));
    }

    @Test
    @Order(29)
    void refusesTooManyInOperandsAndTooManyDecomposedReads() {
        String select = "SELECT sk FROM " + QUOTED_TABLE + " WHERE ";
        String tooManyReads = "Too many decomposed read operations for a given query.";
        String tooManyOperands = "The IN operator is provided with too many operands; number of operands: 101";

        for (String where : List.of("pk IN [" + inValues(51) + "]",
                "pk='ranges' AND sk IN [" + inValues(51) + "]",
                "pk IN [" + inValues(26) + "] AND sk IN ['1','2']")) {
            statement(select + where).statusCode(400).body("message", equalTo(tooManyReads));
        }
        for (String where : List.of("pk IN [" + inValues(50) + "] AND flag IN [" + inValues(100) + "]",
                "pk IN [" + inValues(51) + "] OR flag='one'")) {
            statement(select + where).statusCode(200);
        }
        request("DynamoDB_20120810.BatchExecuteStatement",
                "{\"Statements\":[{\"Statement\":" + json(select + "pk IN [" + inValues(51) + "] AND sk='1'") + "}]}")
            .statusCode(200)
            .body("Responses[0].Error.Message", equalTo(tooManyReads));

        statement(select + "pk='ranges' AND flag IN [" + inValues(101) + "]")
            .statusCode(400)
            .body("message", equalTo(tooManyOperands));
        statement("UPDATE " + QUOTED_TABLE + " SET t=1 WHERE pk='ranges' AND sk='1' AND flag IN [" + inValues(101) + "]")
            .statusCode(400)
            .body("message", equalTo(tooManyOperands));
        transaction("SELECT * FROM " + QUOTED_TABLE + " WHERE pk IN [" + inValues(101) + "] AND sk='1'")
            .statusCode(400)
            .body("message", equalTo("Validation failed in TransactStatements[0]: " + tooManyOperands));
        getItem("ranges").body("Item.t", nullValue());
    }

    @Test
    @Order(30)
    void filtersAnUnkeyedIndexReadOnTheIndexView() {
        request("DynamoDB_20120810.CreateTable", """
                {
                  "TableName": "partiql-edge-cases-index-view",
                  "AttributeDefinitions": [
                    {"AttributeName":"pk","AttributeType":"S"},
                    {"AttributeName":"sk","AttributeType":"S"},
                    {"AttributeName":"gsiPk","AttributeType":"S"},
                    {"AttributeName":"lsiSk","AttributeType":"S"}
                  ],
                  "KeySchema": [
                    {"AttributeName":"pk","KeyType":"HASH"},
                    {"AttributeName":"sk","KeyType":"RANGE"}
                  ],
                  "GlobalSecondaryIndexes": [{
                    "IndexName": "gsi-inc",
                    "KeySchema": [{"AttributeName":"gsiPk","KeyType":"HASH"}],
                    "Projection": {"ProjectionType":"INCLUDE","NonKeyAttributes":["projattr"]}
                  }],
                  "LocalSecondaryIndexes": [{
                    "IndexName": "lsi-keys",
                    "KeySchema": [
                      {"AttributeName":"pk","KeyType":"HASH"},
                      {"AttributeName":"lsiSk","KeyType":"RANGE"}
                    ],
                    "Projection": {"ProjectionType":"KEYS_ONLY"}
                  }],
                  "BillingMode": "PAY_PER_REQUEST"
                }
                """)
            .statusCode(200);
        for (String item : List.of(
                "\"sk\":{\"S\":\"s1\"},\"gsiPk\":{\"S\":\"y\"},\"lsiSk\":{\"S\":\"k1\"},\"nonproj\":{\"S\":\"np1\"}",
                "\"sk\":{\"S\":\"s2\"},\"gsiPk\":{\"S\":\"y2\"},\"lsiSk\":{\"S\":\"k2\"},\"nonproj\":{\"S\":\"np2\"}")) {
            request("DynamoDB_20120810.PutItem",
                    "{\"TableName\":\"partiql-edge-cases-index-view\",\"Item\":{\"pk\":{\"S\":\"p\"}," + item + "}}")
                .statusCode(200);
        }
        String gsi = "SELECT sk FROM \"partiql-edge-cases-index-view\".\"gsi-inc\" WHERE ";
        String lsi = "SELECT sk FROM \"partiql-edge-cases-index-view\".\"lsi-keys\" WHERE ";

        statement(gsi + "nonproj='np1'").statusCode(200).body("Items.size()", equalTo(0));
        statement(lsi + "nonproj='np1'").statusCode(200).body("Items.size()", equalTo(0));
        statement(gsi + "NOT nonproj='np1'").statusCode(200).body("Items.sk.S", containsInAnyOrder("s1", "s2"));
        statement(gsi + "(gsiPk='y' AND nonproj='np1') OR (gsiPk='y2')")
            .statusCode(200)
            .body("Items.sk.S", containsInAnyOrder("s2"));
        statement("SELECT sk FROM \"partiql-edge-cases-index-view\" WHERE nonproj='np1'")
            .statusCode(200)
            .body("Items.sk.S", containsInAnyOrder("s1"));
    }

    private static ValidatableResponse getItem(String pk) {
        return request("DynamoDB_20120810.GetItem", """
                {"TableName":"%s","Key":{"pk":{"S":"%s"},"sk":{"S":"1"}},"ConsistentRead":true}
                """.formatted(TABLE, pk))
            .statusCode(200);
    }

    private static void putItem(String pk, String attributes) {
        putItem(pk, "1", attributes);
    }

    private static void putItem(String pk, String sk, String attributes) {
        request("DynamoDB_20120810.PutItem", """
                {"TableName":"%s","Item":{"pk":{"S":"%s"},"sk":{"S":"%s"},%s}}
                """.formatted(TABLE, pk, sk, attributes))
            .statusCode(200);
    }

    private static ValidatableResponse statement(String partiql) {
        return request("DynamoDB_20120810.ExecuteStatement", "{\"Statement\":" + json(partiql) + "}");
    }

    private static ValidatableResponse statement(String partiql, String parameters) {
        return request("DynamoDB_20120810.ExecuteStatement", member(partiql, parameters));
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

    private static String inValues(int count) {
        return IntStream.range(0, count).mapToObj(i -> "'v" + i + "'").collect(Collectors.joining(","));
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
