package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.contains;
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
        createAndSeed(table, """
            {"pk":{"S":"x"},"a":{"S":"old"},"b":{"N":"1"}}""");
    }

    private void createAndSeed(String table, String itemJson) {
        // Tolerate a table that already exists so the test is safe to re-run against a
        // persisted store: a fresh store returns 200, an existing table returns 400
        // (ResourceInUseException). PutItem then overwrites, so seeding stays deterministic.
        given().header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(CT).body("""
                {"TableName":"%s","KeySchema":[{"AttributeName":"pk","KeyType":"HASH"}],
                 "AttributeDefinitions":[{"AttributeName":"pk","AttributeType":"S"}],
                 "BillingMode":"PAY_PER_REQUEST"}
                """.formatted(table))
            .when().post("/").then().statusCode(anyOf(equalTo(200), equalTo(400)));
        post("PutItem", """
            {"TableName":"%s","Item":%s}
            """.formatted(table, itemJson));
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

    /**
     * ADD and DELETE have their own clause parsers, reached via a separate
     * {@code startsWith("ADD ")} / {@code startsWith("DELETE ")} dispatch and via
     * findNextClauseKeyword's literal-trailing-space keyword scan. Both broke on a
     * newline directly after the keyword, independently of the SET path above.
     */
    @Test
    void newlineAfterAddAndDeleteKeywordsIsAccepted() {
        createAndSeed("UpdWsAddDel", """
            {"pk":{"S":"x"},"n":{"N":"1"},"s":{"SS":["a","b","c"]}}""");
        given().header("X-Amz-Target", "DynamoDB_20120810.UpdateItem").contentType(CT).body("""
            {"TableName":"UpdWsAddDel","Key":{"pk":{"S":"x"}},
             "UpdateExpression":"ADD\\n#n :inc\\nDELETE\\n#s :del",
             "ReturnValues":"ALL_NEW",
             "ExpressionAttributeNames":{"#n":"n","#s":"s"},
             "ExpressionAttributeValues":{":inc":{"N":"5"},":del":{"SS":["b"]}}}
            """)
        .when().post("/").then()
            .statusCode(200)
            .body("Attributes.n.N", equalTo("6"))
            .body("Attributes.s.SS", contains("a", "c"));
    }

    /**
     * A function or arithmetic right-hand side split across lines: the value part is
     * delimited by the depth-aware comma/clause scan, so a newline inside
     * {@code list_append(...)} args or before a {@code +} operator must survive
     * normalization without changing how the operands are split.
     */
    @Test
    void multilineFunctionAndArithmeticRightHandSidesAreAccepted() {
        createAndSeed("UpdWsFunc", """
            {"pk":{"S":"x"},"l":{"L":[{"S":"one"}]},"c":{"N":"10"}}""");
        given().header("X-Amz-Target", "DynamoDB_20120810.UpdateItem").contentType(CT).body("""
            {"TableName":"UpdWsFunc","Key":{"pk":{"S":"x"}},
             "UpdateExpression":"SET\\n#l = list_append(#l,\\n:more),\\n#c = if_not_exists(#c, :zero)\\n+ :inc",
             "ReturnValues":"ALL_NEW",
             "ExpressionAttributeNames":{"#l":"l","#c":"c"},
             "ExpressionAttributeValues":{":more":{"L":[{"S":"two"}]},":zero":{"N":"0"},":inc":{"N":"5"}}}
            """)
        .when().post("/").then()
            .statusCode(200)
            .body("Attributes.l.L.size()", equalTo(2))
            .body("Attributes.l.L[0].S", equalTo("one"))
            .body("Attributes.l.L[1].S", equalTo("two"))
            .body("Attributes.c.N", equalTo("15"));
    }

    /**
     * More than two clauses chained by newlines. Each clause parser hands the unconsumed
     * remainder back to the dispatch loop, so the keyword scan runs repeatedly rather than
     * once. The newlines sit after each keyword: a newline only before a keyword already
     * parsed, since indexOfKeyword's boundary check accepts any whitespace, but the
     * keyword itself is matched with a literal trailing space.
     */
    @Test
    void newlineSeparatedChainOfThreeClausesIsAccepted() {
        createAndSeed("UpdWsChain", """
            {"pk":{"S":"x"},"a":{"S":"old"},"b":{"S":"gone"},"c":{"N":"1"}}""");
        given().header("X-Amz-Target", "DynamoDB_20120810.UpdateItem").contentType(CT).body("""
            {"TableName":"UpdWsChain","Key":{"pk":{"S":"x"}},
             "UpdateExpression":"SET\\n#a = :v\\nREMOVE\\n#b\\nADD\\n#c :w",
             "ReturnValues":"ALL_NEW",
             "ExpressionAttributeNames":{"#a":"a","#b":"b","#c":"c"},
             "ExpressionAttributeValues":{":v":{"S":"new"},":w":{"N":"5"}}}
            """)
        .when().post("/").then()
            .statusCode(200)
            .body("Attributes.a.S", equalTo("new"))
            .body("Attributes.b", nullValue())
            .body("Attributes.c.N", equalTo("6"));
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
