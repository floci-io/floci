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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbPartiQLIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String TABLE = "partiql-statements";

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
    }

    @Test
    @Order(2)
    void selectReportsADocumentPathUnderItsLeafName() {
        putItem("path-select", """
                "profile":{"M":{"sub":{"S":"deep"},"sib":{"S":"keep"}}},
                "tags":{"L":[{"S":"a"},{"S":"b"}]}
                """);

        statement("SELECT profile.sub, tags[1] FROM \"" + TABLE + "\" WHERE pk = 'path-select'")
            .statusCode(200)
            .body("Items.size()", equalTo(1))
            .body("Items[0].sub.S", equalTo("deep"))
            .body("Items[0].'tags[1]'.S", equalTo("b"))
            .body("Items[0].profile", nullValue());
    }

    @Test
    @Order(3)
    void updateWritesThroughADocumentPath() {
        putItem("path-update", "\"profile\":{\"M\":{\"sub\":{\"S\":\"old\"}}}");

        statement("UPDATE \"" + TABLE + "\" SET profile.sub = 'new' WHERE pk = 'path-update'")
            .statusCode(200);

        statement("SELECT profile.sub FROM \"" + TABLE + "\" WHERE pk = 'path-update'")
            .statusCode(200)
            .body("Items[0].sub.S", equalTo("new"));
    }

    @Test
    @Order(4)
    void whereTakesInNotAndTheMissingPredicate() {
        putItem("grammar-a", "\"kind\":{\"S\":\"alpha\"}");
        putItem("grammar-b", "\"kind\":{\"S\":\"beta\"}");

        statement("SELECT pk FROM \"" + TABLE + "\" WHERE pk IN ['grammar-a','grammar-b']"
                + " AND NOT begins_with(\"kind\", 'al')")
            .statusCode(200)
            .body("Items.size()", equalTo(1))
            .body("Items[0].pk.S", equalTo("grammar-b"));

        statement("SELECT pk FROM \"" + TABLE + "\" WHERE pk = 'grammar-a' AND \"kind\" IS NOT MISSING")
            .statusCode(200)
            .body("Items.size()", equalTo(1));

        statement("SELECT pk FROM \"" + TABLE + "\" WHERE pk = 'grammar-a' AND \"kind\" IS MISSING")
            .statusCode(200)
            .body("Items.size()", equalTo(0));
    }

    @Test
    @Order(5)
    void updateOnAKeyHoldingNoItemFailsTheConditionalCheck() {
        statement("UPDATE \"" + TABLE + "\" SET data = 'new' WHERE pk = 'write-ghost'")
            .statusCode(400)
            .body("__type", equalTo("ConditionalCheckFailedException"));

        request("DynamoDB_20120810.GetItem", """
                {"TableName":"%s","Key":{"pk":{"S":"write-ghost"}}}
                """.formatted(TABLE))
            .statusCode(200)
            .body("Item", nullValue());
    }

    @Test
    @Order(6)
    void aFalsePredicateBesideTheKeyLeavesTheItem() {
        putItem("write-pred", "\"name\":{\"S\":\"alpha\"},\"n\":{\"N\":\"5\"}");

        statement("UPDATE \"" + TABLE + "\" SET n = 9 WHERE pk = 'write-pred' AND \"name\" = 'beta'")
            .statusCode(400)
            .body("__type", equalTo("ConditionalCheckFailedException"));

        statement("DELETE FROM \"" + TABLE + "\" WHERE pk = 'write-pred' AND \"name\" = 'beta'")
            .statusCode(400)
            .body("__type", equalTo("ConditionalCheckFailedException"));

        request("DynamoDB_20120810.GetItem", """
                {"TableName":"%s","Key":{"pk":{"S":"write-pred"}}}
                """.formatted(TABLE))
            .statusCode(200)
            .body("Item.n.N", equalTo("5"));
    }

    @Test
    @Order(7)
    void deleteOnAKeyHoldingNoItemIsANoOpEvenWithAPredicate() {
        statement("DELETE FROM \"" + TABLE + "\" WHERE pk = 'write-absent' AND \"name\" = 'x'")
            .statusCode(200);
    }

    @Test
    @Order(8)
    void aWriteWithoutTheKeyEqualityIsRejected() {
        statement("DELETE FROM \"" + TABLE + "\" WHERE \"name\" = 'alpha'")
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo(
                    "Where clause does not contain a mandatory equality on all key attributes"));
    }

    @Test
    @Order(9)
    void updateReturningReportsTheRowOnTheRequestedSide() {
        putItem("ret-update", "\"data\":{\"S\":\"old\"},\"keep\":{\"S\":\"same\"}");

        statement("UPDATE \"" + TABLE + "\" SET data = 'new' WHERE pk = 'ret-update' RETURNING ALL OLD *")
            .statusCode(200)
            .body("Items.size()", equalTo(1))
            .body("Items[0].data.S", equalTo("old"))
            .body("Items[0].keep.S", equalTo("same"));

        statement("UPDATE \"" + TABLE + "\" SET data = 'newer' WHERE pk = 'ret-update'"
                + " RETURNING MODIFIED NEW *")
            .statusCode(200)
            .body("Items.size()", equalTo(1))
            .body("Items[0].data.S", equalTo("newer"))
            .body("Items[0].keep", nullValue())
            .body("Items[0].pk", nullValue());
    }

    @Test
    @Order(10)
    void anEmptyModifiedProjectionAnswersNoRow() {
        putItem("ret-removed", "\"data\":{\"S\":\"old\"}");

        statement("UPDATE \"" + TABLE + "\" REMOVE data WHERE pk = 'ret-removed'"
                + " RETURNING MODIFIED NEW *")
            .statusCode(200)
            .body("Items.size()", equalTo(0));
    }

    @Test
    @Order(11)
    void deleteTakesOnlyReturningAllOld() {
        putItem("ret-delete", "\"data\":{\"S\":\"gone\"}");

        statement("DELETE FROM \"" + TABLE + "\" WHERE pk = 'ret-delete' RETURNING MODIFIED OLD *")
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("Invalid returning clause: RETURNING MODIFIED OLD *."
                    + " Only RETURNING ALL OLD * is allowed in DELETE statements."));

        statement("DELETE FROM \"" + TABLE + "\" WHERE pk = 'ret-delete' RETURNING ALL OLD *")
            .statusCode(200)
            .body("Items.size()", equalTo(1))
            .body("Items[0].data.S", equalTo("gone"));

        statement("DELETE FROM \"" + TABLE + "\" WHERE pk = 'ret-delete' RETURNING ALL OLD *")
            .statusCode(200)
            .body("Items.size()", equalTo(0));
    }

    @Test
    @Order(12)
    void aTransactionMemberTakesNoReturningClause() {
        request("DynamoDB_20120810.ExecuteTransaction", """
                {"TransactStatements":[{"Statement":"UPDATE \\"%s\\" SET data = 'v' WHERE pk = 'ret-txn' RETURNING ALL NEW *"}]}
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("Validation failed in TransactStatements[0]:"
                    + " RETURNING clause is not supported in ExecuteTransaction."));
    }

    @Test
    @Order(13)
    void updateSetsFromAnAttributeAndALiteral() {
        putItem("arith", "\"n\":{\"N\":\"5\"}");

        statement("UPDATE \"" + TABLE + "\" SET n = n + 1 WHERE pk = 'arith' RETURNING MODIFIED NEW *")
            .statusCode(200)
            .body("Items[0].n.N", equalTo("6"));

        statement("UPDATE \"" + TABLE + "\" SET n = n - 2 WHERE pk = 'arith' RETURNING MODIFIED NEW *")
            .statusCode(200)
            .body("Items[0].n.N", equalTo("4"));
    }

    @Test
    @Order(14)
    void aReplayedTransactionTokenDoesNotApplyTwice() {
        putItem("txn-idem", "\"n\":{\"N\":\"0\"}");
        String body = """
                {"ClientRequestToken":"partiql-idem-token","TransactStatements":[
                  {"Statement":"UPDATE \\"%s\\" SET n = n + 1 WHERE pk = 'txn-idem'"}]}
                """.formatted(TABLE);

        request("DynamoDB_20120810.ExecuteTransaction", body).statusCode(200);
        request("DynamoDB_20120810.ExecuteTransaction", body).statusCode(200);

        request("DynamoDB_20120810.GetItem", """
                {"TableName":"%s","Key":{"pk":{"S":"txn-idem"}}}
                """.formatted(TABLE))
            .statusCode(200)
            .body("Item.n.N", equalTo("1"));
    }

    @Test
    @Order(15)
    void aWriteStatementRejectsAnIndexQualifier() {
        statement("INSERT INTO \"" + TABLE + "\".\"an-index\" VALUE {'pk': 'qualified'}")
            .statusCode(400)
            .body("message", equalTo("FROM clause may only contain a single table name"));

        statement("UPDATE \"" + TABLE + "\".\"an-index\" SET data = 'x' WHERE pk = 'qualified'")
            .statusCode(400)
            .body("message", equalTo("This operation is not supported on an index"));

        statement("DELETE FROM \"" + TABLE + "\".\"an-index\" WHERE pk = 'qualified'")
            .statusCode(400)
            .body("message", equalTo("This operation is not supported on an index"));

        request("DynamoDB_20120810.ExecuteTransaction", """
                {"TransactStatements":[{"Statement":"UPDATE \\"%s\\".\\"an-index\\" SET data = 'x' WHERE pk = 'qualified'"}]}
                """.formatted(TABLE))
            .statusCode(400)
            .body("message", equalTo("Validation failed in TransactStatements[0]:"
                    + " This operation is not supported on an index"));
    }

    @Test
    @Order(16)
    void theFromClauseTakesAtMostTwoNonEmptyComponents() {
        statement("SELECT * FROM \"\" WHERE pk = 'x'")
            .statusCode(400)
            .body("message", equalTo("Path component cannot be an empty string"));

        statement("SELECT * FROM \"" + TABLE + "\".\"\" WHERE pk = 'x'")
            .statusCode(400)
            .body("message", equalTo("Path component cannot be an empty string"));

        statement("SELECT * FROM \"" + TABLE + "\".\"an-index\".\"extra\" WHERE pk = 'x'")
            .statusCode(400)
            .body("message", equalTo("A path may contain at most 2 components in the FROM clause"));
    }

    @Test
    @Order(17)
    void aTransactionOfSelectsReadsTheItems() {
        putItem("txn-read", "\"data\":{\"S\":\"here\"}");

        request("DynamoDB_20120810.ExecuteTransaction", """
                {"TransactStatements":[{"Statement":"SELECT * FROM \\"%s\\" WHERE pk = 'txn-read'"}]}
                """.formatted(TABLE))
            .statusCode(200)
            .body("Responses.size()", equalTo(1))
            .body("Responses[0].Item.data.S", equalTo("here"));

        request("DynamoDB_20120810.ExecuteTransaction", """
                {"TransactStatements":[{"Statement":"SELECT * FROM \\"%s\\".\\"an-index\\" WHERE pk = 'txn-read'"}]}
                """.formatted(TABLE))
            .statusCode(400)
            .body("message", equalTo("Validation failed in TransactStatements[0]:"
                    + " Reads on indices are not supported within transactions."));
    }

    @Test
    @Order(18)
    void aBatchMemberCarriesTheShortErrorCode() {
        putItem("batch-dup", "\"val\":{\"S\":\"here\"}");

        request("DynamoDB_20120810.BatchExecuteStatement", """
                {"Statements":[
                  {"Statement":"SLECT * FROM \\"%1$s\\" WHERE pk = 'batch-dup'"},
                  {"Statement":"INSERT INTO \\"%1$s\\" VALUE {'pk': 'batch-dup'}"},
                  {"Statement":"UPDATE \\"%1$s\\" SET touched = 'yes' WHERE pk = 'batch-dup' AND val = 'wrong'"}]}
                """.formatted(TABLE))
            .statusCode(200)
            .body("Responses[0].Error.Code", equalTo("ValidationError"))
            .body("Responses[0].Error.Message", equalTo(
                    "Statement wasn't well formed, can't be processed: Expected data manipulation"))
            .body("Responses[0].TableName", nullValue())
            .body("Responses[1].Error.Code", equalTo("DuplicateItem"))
            .body("Responses[1].TableName", equalTo(TABLE))
            .body("Responses[2].Error.Code", equalTo("ConditionalCheckFailed"))
            .body("Responses[2].TableName", equalTo(TABLE));
    }

    private static void putItem(String pk, String attributes) {
        request("DynamoDB_20120810.PutItem", """
                {"TableName":"%s","Item":{"pk":{"S":"%s"},%s}}
                """.formatted(TABLE, pk, attributes))
            .statusCode(200);
    }

    private static ValidatableResponse statement(String partiql) {
        return request("DynamoDB_20120810.ExecuteStatement",
                "{\"Statement\":\"" + partiql.replace("\"", "\\\"") + "\"}");
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
