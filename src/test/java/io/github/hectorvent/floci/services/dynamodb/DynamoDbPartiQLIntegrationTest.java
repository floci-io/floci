package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

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

    @Test
    @Order(19)
    void aFailedConditionReturnsTheItemWithAllOld() {
        putItem("rvocf-upd", "\"val\":{\"S\":\"here\"}");
        putItem("rvocf-del", "\"val\":{\"S\":\"here\"}");

        request("DynamoDB_20120810.ExecuteStatement", """
                {"Statement":"UPDATE \\"%s\\" SET touched = 'yes' WHERE pk = 'rvocf-upd' AND val = 'wrong'",
                 "ReturnValuesOnConditionCheckFailure":"ALL_OLD"}
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ConditionalCheckFailedException"))
            .body("Item.val.S", equalTo("here"));
        request("DynamoDB_20120810.ExecuteStatement", """
                {"Statement":"DELETE FROM \\"%s\\" WHERE pk = 'rvocf-del' AND val = 'wrong'",
                 "ReturnValuesOnConditionCheckFailure":"ALL_OLD"}
                """.formatted(TABLE))
            .statusCode(400)
            .body("Item.val.S", equalTo("here"));
        request("DynamoDB_20120810.ExecuteStatement", """
                {"Statement":"UPDATE \\"%s\\" SET touched = 'yes' WHERE pk = 'rvocf-upd' AND val = 'wrong'",
                 "ReturnValuesOnConditionCheckFailure":"NONE"}
                """.formatted(TABLE))
            .statusCode(400)
            .body("Item", nullValue());
        request("DynamoDB_20120810.ExecuteStatement", """
                {"Statement":"UPDATE \\"%s\\" SET touched = 'yes' WHERE pk = 'rvocf-ghost'",
                 "ReturnValuesOnConditionCheckFailure":"ALL_OLD"}
                """.formatted(TABLE))
            .statusCode(400)
            .body("Item", nullValue());
    }

    @Test
    @Order(20)
    void aFailedBatchMemberCarriesTheItemOnItsErrorWithAllOld() {
        putItem("rvocf-batch", "\"val\":{\"S\":\"here\"}");
        putItem("rvocf-batch-none", "\"val\":{\"S\":\"here\"}");

        request("DynamoDB_20120810.BatchExecuteStatement", """
                {"Statements":[
                  {"Statement":"UPDATE \\"%1$s\\" SET touched = 'yes' WHERE pk = 'rvocf-batch' AND val = 'wrong'",
                   "ReturnValuesOnConditionCheckFailure":"ALL_OLD"},
                  {"Statement":"DELETE FROM \\"%1$s\\" WHERE pk = 'rvocf-batch-none' AND val = 'wrong'"}]}
                """.formatted(TABLE))
            .statusCode(200)
            .body("Responses[0].Error.Code", equalTo("ConditionalCheckFailed"))
            .body("Responses[0].Error.Item.val.S", equalTo("here"))
            .body("Responses[0].Item", nullValue())
            .body("Responses[1].Error.Code", equalTo("ConditionalCheckFailed"))
            .body("Responses[1].Error.Item", nullValue());
    }

    @Test
    @Order(21)
    void aCancelledTransactionCarriesTheItemOfAFailedWriteWithAllOld() {
        putItem("rvocf-tx-upd", "\"val\":{\"S\":\"here\"}");
        putItem("rvocf-tx-del", "\"val\":{\"S\":\"here\"}");

        request("DynamoDB_20120810.ExecuteTransaction", """
                {"TransactStatements":[
                  {"Statement":"UPDATE \\"%1$s\\" SET touched = 'yes' WHERE pk = 'rvocf-tx-upd' AND val = 'wrong'",
                   "ReturnValuesOnConditionCheckFailure":"ALL_OLD"},
                  {"Statement":"DELETE FROM \\"%1$s\\" WHERE pk = 'rvocf-tx-del' AND val = 'wrong'",
                   "ReturnValuesOnConditionCheckFailure":"ALL_OLD"},
                  {"Statement":"EXISTS(SELECT * FROM \\"%1$s\\" WHERE pk = 'rvocf-upd' AND val = 'wrong')",
                   "ReturnValuesOnConditionCheckFailure":"ALL_OLD"}]}
                """.formatted(TABLE))
            .statusCode(400)
            .body("CancellationReasons[0].Code", equalTo("ConditionalCheckFailed"))
            .body("CancellationReasons[0].Item.val.S", equalTo("here"))
            .body("CancellationReasons[1].Item.val.S", equalTo("here"))
            .body("CancellationReasons[2].Code", equalTo("ConditionalCheckFailed"))
            .body("CancellationReasons[2].Item", nullValue());
    }

    @Test
    @Order(22)
    void isTestsTheTypeOfAnAttribute() {
        putItem("is-type", "\"flag\":{\"BOOL\":true},\"s\":{\"S\":\"str\"},\"n\":{\"N\":\"5\"},"
                + "\"l\":{\"L\":[]},\"m\":{\"M\":{}},\"ss\":{\"SS\":[\"a\"]}");

        statement("SELECT pk FROM \"" + TABLE + "\" WHERE pk = 'is-type' AND flag IS BOOL AND s IS STRING"
                + " AND n IS NUMERIC(5, 2) AND l IS LIST AND m IS STRUCT AND ss IS NOT STRING AND nope IS NOT LIST")
            .statusCode(200)
            .body("Items.size()", equalTo(1));
        statement("SELECT pk FROM \"" + TABLE + "\" WHERE pk = 'is-type' AND s IS BOOL")
            .statusCode(200)
            .body("Items.size()", equalTo(0));
        statement("UPDATE \"" + TABLE + "\" SET touched = 'yes' WHERE pk = 'is-type' AND n IS STRING")
            .statusCode(400)
            .body("__type", equalTo("ConditionalCheckFailedException"));
        statement("UPDATE \"" + TABLE + "\" SET touched = 'yes' WHERE pk = 'is-type' AND n IS NUMERIC")
            .statusCode(200);

        String unsupported = "SELECT pk FROM \"" + TABLE + "\" WHERE pk = 'is-type' AND n IS DECIMAL";
        statement(unsupported)
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("Unsupported data type for IS operator: DecimalType. Supported data types are "
                    + "[MISSING, NULL, BOOLEAN, BLOB, STRING, NUMERIC, STRUCT, TUPLE, LIST] at 1:"
                    + (unsupported.indexOf("n IS") + 1) + ":1"));
    }

    @Test
    @Order(23)
    void aStatementThatBreaksOffNamesWhatItFoundWhereATermShouldStart() {
        String notWellFormed = "Statement wasn't well formed, can't be processed: ";

        statement("SELECT * FROM \"" + TABLE + "-never-created\" WHERE")
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo(notWellFormed + "Unexpected term"));
        statement("DELETE FROM WHERE pk = 'x'")
            .statusCode(400)
            .body("message", equalTo(notWellFormed + "Expected identifier for simple path"));
        request("DynamoDB_20120810.ExecuteTransaction", """
                {"TransactStatements":[{"Statement":"SELECT * FROM \\"%s\\" WHERE pk = 'x' AND"}]}
                """.formatted(TABLE))
            .statusCode(400)
            .body("message", equalTo("Validation failed in TransactStatements[0]: " + notWellFormed
                    + "Missing right-hand side expression of infix operator"));
        request("DynamoDB_20120810.BatchExecuteStatement", """
                {"Statements":[{"Statement":"SELECT * FROM \\"%s\\" WHERE IS NULL"}]}
                """.formatted(TABLE))
            .statusCode(200)
            .body("Responses[0].Error.Code", equalTo("ValidationError"))
            .body("Responses[0].Error.Message", equalTo(notWellFormed + "Unexpected operator"));
    }

    @Test
    @Order(24)
    void aStatementOutsideOneTo8192CodePointsIsRefusedBeforeItIsParsed() {
        String select = "SELECT * FROM \"" + TABLE + "-never-created\" WHERE pk = '";
        String tooLong = select + "a".repeat(8192 - select.length()) + "'";
        String badCapacity = "Value 'BAD' at 'returnConsumedCapacity' failed to satisfy constraint: "
                + "Member must satisfy enum value set: [INDEXES, TOTAL, NONE]";

        statement(select + "\\ud83d\\ude00" + "a".repeat(8190 - select.length()) + "'")
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
        request("DynamoDB_20120810.ExecuteStatement",
                "{\"Statement\":\"" + tooLong.replace("\"", "\\\"") + "\",\"ReturnConsumedCapacity\":\"BAD\"}")
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("2 validation errors detected: Value '" + tooLong + "' at 'statement' "
                    + "failed to satisfy constraint: Member must have length less than or equal to 8192; "
                    + badCapacity));
        request("DynamoDB_20120810.ExecuteStatement", "{}")
            .statusCode(400)
            .body("message", equalTo("1 validation error detected: Value null at 'statement' "
                    + "failed to satisfy constraint: Member must not be null"));
        request("DynamoDB_20120810.ExecuteTransaction", """
                {"TransactStatements":[{"Statement":"SELECT"},{"Statement":""}],"ReturnConsumedCapacity":"BAD"}
                """)
            .statusCode(400)
            .body("message", equalTo("2 validation errors detected: Value '' at 'transactStatements.2.member.statement' "
                    + "failed to satisfy constraint: Member must have length greater than or equal to 1; "
                    + badCapacity));
        request("DynamoDB_20120810.BatchExecuteStatement", """
                {"Statements":[{"Statement":"SELECT"},{}],"ReturnConsumedCapacity":"BAD"}
                """)
            .statusCode(400)
            .body("message", equalTo("2 validation errors detected: " + badCapacity
                    + "; Value null at 'statements.2.member.statement' "
                    + "failed to satisfy constraint: Member must not be null"));
    }

    @Test
    @Order(25)
    void requestMembersOutsideTheirConstraintsAreListedInDynamoDbOrderUpToTen() {
        String atLeastOne = "' failed to satisfy constraint: Member must have length greater than or equal to 1";
        String returnValues = "' failed to satisfy constraint: Member must satisfy enum value set: [ALL_OLD, NONE]";
        String badCapacity = "Value 'BAD' at 'returnConsumedCapacity' failed to satisfy constraint: "
                + "Member must satisfy enum value set: [INDEXES, TOTAL, NONE]";

        request("DynamoDB_20120810.ExecuteStatement", """
                {"Statement":"","Parameters":[],"NextToken":"","Limit":0,"ReturnConsumedCapacity":"BAD",
                 "ReturnValuesOnConditionCheckFailure":"BAD"}
                """)
            .statusCode(400)
            .body("message", equalTo("6 validation errors detected: "
                    + "Value 'BAD' at 'returnValuesOnConditionCheckFailure" + returnValues
                    + "; Value '' at 'nextToken" + atLeastOne
                    + "; Value '0' at 'limit' failed to satisfy constraint: "
                    + "Member must have value greater than or equal to 1"
                    + "; Value '' at 'statement" + atLeastOne
                    + "; " + badCapacity
                    + "; Value '[]' at 'parameters" + atLeastOne));
        request("DynamoDB_20120810.ExecuteTransaction", """
                {"ClientRequestToken":"","ReturnConsumedCapacity":"BAD","TransactStatements":[
                 {"Statement":"SELECT","ReturnValuesOnConditionCheckFailure":"BAD","Parameters":[]}%s]}
                """.formatted(",{\"Statement\":\"\"}".repeat(9)))
            .statusCode(400)
            .body("message", equalTo("10 validation errors detected: "
                    + "Value '' at 'clientRequestToken" + atLeastOne
                    + "; Value 'BAD' at 'transactStatements.1.member.returnValuesOnConditionCheckFailure" + returnValues
                    + "; Value '[]' at 'transactStatements.1.member.parameters" + atLeastOne
                    + IntStream.rangeClosed(2, 8)
                            .mapToObj(n -> "; Value '' at 'transactStatements." + n + ".member.statement" + atLeastOne)
                            .collect(Collectors.joining())));
        request("DynamoDB_20120810.ExecuteTransaction", "{\"TransactStatements\":[]}")
            .statusCode(400)
            .body("message", equalTo("1 validation error detected: Value '[]' at 'transactStatements" + atLeastOne));
        request("DynamoDB_20120810.BatchExecuteStatement", "{\"ReturnConsumedCapacity\":\"BAD\"}")
            .statusCode(400)
            .body("message", equalTo("2 validation errors detected: " + badCapacity
                    + "; Value null at 'statements' failed to satisfy constraint: Member must not be null"));
    }

    @Test
    @Order(26)
    void aTableAliasIsRefusedBeforeTheTableIsRead() {
        String missing = TABLE + "-never-created";

        statement("SELECT * FROM \"" + missing + "\" AS t WHERE pk = 1e126")
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("Aliasing is not supported"));
        request("DynamoDB_20120810.ExecuteTransaction", """
                {"TransactStatements":[{"Statement":"SELECT * FROM \\"%1$s\\" WHERE pk = 'a'"},
                 {"Statement":"SELECT * FROM \\"%1$s\\" t WHERE pk = 'b'"}]}
                """.formatted(TABLE))
            .statusCode(400)
            .body("message", equalTo("Validation failed in TransactStatements[1]: Aliasing is not supported"));
        request("DynamoDB_20120810.BatchExecuteStatement", """
                {"Statements":[{"Statement":"UPDATE \\"%1$s\\" t SET a = 1 WHERE pk = 'a'"},
                 {"Statement":"INSERT INTO \\"%1$s\\" t VALUE {'pk': 'a'}"}]}
                """.formatted(missing))
            .statusCode(200)
            .body("Responses[0].Error.Message", equalTo("Aliasing is not supported"))
            .body("Responses[1].Error.Message",
                    equalTo("Statement wasn't well formed, can't be processed: Unexpected token after expression"));
    }

    @Test
    @Order(27)
    void aStatementThatIsOneTermIsRefusedByItsKind() {
        statement("x IS INT4")
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("Unsupported operation: IsType"));
        request("DynamoDB_20120810.ExecuteTransaction", """
                {"TransactStatements":[{"Statement":"?"}]}
                """)
            .statusCode(400)
            .body("message", equalTo("Validation failed in TransactStatements[0]: Unsupported operation: Parameter"));
        request("DynamoDB_20120810.BatchExecuteStatement", """
                {"Statements":[{"Statement":"x.y IS DOUBLE PRECISION"}]}
                """)
            .statusCode(200)
            .body("Responses[0].Error.Code", equalTo("ValidationError"))
            .body("Responses[0].Error.Message", equalTo("Unsupported operation: IsType"));
    }

    @Test
    @Order(28)
    void aStatementTheLexerCannotReadIsRefusedWithNothingAfterTheColon() {
        String notWellFormed = "Statement wasn't well formed, can't be processed: ";

        statement("SELECT * FROM \"" + TABLE + "\" WHERE pk = 'x' # note")
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo(notWellFormed));
        request("DynamoDB_20120810.ExecuteTransaction", """
                {"TransactStatements":[{"Statement":"UPDATE \\"%1$s\\" SET a = 1 WHERE pk = 'x'"},
                 {"Statement":"UPDATE \\"%1$s\\" SET a = 1 WHERE pk = 'y"}]}
                """.formatted(TABLE))
            .statusCode(400)
            .body("message", equalTo("Validation failed in TransactStatements[1]: " + notWellFormed));
        request("DynamoDB_20120810.BatchExecuteStatement", """
                {"Statements":[{"Statement":"SELECT * FROM \\"%s\\" WHERE pk = 'x' ~"}]}
                """.formatted(TABLE))
            .statusCode(200)
            .body("Responses[0].Error.Code", equalTo("ValidationError"))
            .body("Responses[0].Error.Message", equalTo(notWellFormed));
    }

    @ParameterizedTest
    @Order(29)
    @ValueSource(strings = {
            "SELECT * FROM \"%s\" WHERE pk = 'x'",
            "SELECT * FROM \"%s\".\"gsi\" WHERE pk = 'x'",
            "INSERT INTO \"%s\" VALUE {'pk': 'x'}",
            "UPDATE \"%s\" SET a = 1 WHERE pk = 'x'",
            "DELETE FROM \"%s\" WHERE pk = 'x'",
            "FROM \"%s\" WHERE pk = 'x' SET c = 1"})
    void aStatementOnAMissingTableIsRefusedWithoutTheTableName(String statement) {
        String partiql = statement.formatted(TABLE + "-never-created");

        statement(partiql)
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"))
            .body("message", equalTo("Requested resource not found"));
        request("DynamoDB_20120810.BatchExecuteStatement",
                "{\"Statements\":[{\"Statement\":\"" + partiql.replace("\"", "\\\"") + "\"}]}")
            .statusCode(200)
            .body("Responses[0].Error.Code", equalTo("ResourceNotFound"))
            .body("Responses[0].Error.Message", equalTo("Requested resource not found"));
    }

    @ParameterizedTest
    @Order(30)
    @CsvSource(delimiter = '|', textBlock = """
            [{"N":"abc"}]                                     | The parameter cannot be converted to a numeric value: abc
            [{"N":""}]                                        | The parameter cannot be converted to a numeric value
            [{"N":"1e2147483648"}]                            | The parameter cannot be converted to a numeric value: 1e2147483648
            [{"N":"0.1e2147483648"}]                          | The parameter cannot be converted to a numeric value: 0.1e2147483648
            [{"N":"1e-2147483648"}]                           | The parameter cannot be converted to a numeric value: 1e-2147483648
            [{"N":"1e2147483645"}]                            | Number overflow. Attempting to store a number with magnitude larger than supported range
            [{"N":"1e2147483647"}]                            | Number underflow. Attempting to store a number with magnitude smaller than supported range
            [{"N":"0e-131"}]                                  | Number underflow. Attempting to store a number with magnitude smaller than supported range
            [{"N":"1.23456789012345678901234567890123456789"}] | Attempting to store more than 38 significant digits in a Number
            [{"NS":["1","1.0"]}]                              | Input collection contains duplicates
            [{"N":"abc"},%1$s]                                | The parameter cannot be converted to a numeric value: abc
            [%1$s,{"N":"abc"}]                                | Nesting Levels have exceeded supported limits: Attributes in the item have nested levels beyond supported limit
            [{"M":{"a":{"N":"abc"},"z":%1$s}}]                | The parameter cannot be converted to a numeric value: abc
            [{"M":{"a":{"N":"abc"},"d":%1$s}}]                | Nesting Levels have exceeded supported limits: Attributes in the item have nested levels beyond supported limit
            [{"L":[{"N":"1e126"},{"N":"abc"}]}]               | Number overflow. Attempting to store a number with magnitude larger than supported range
            [{"NS":["1","1"]},{"N":"abc"}]                    | Input collection contains duplicates
            [{},{"N":"abc"}]                                  | Supplied AttributeValue is empty, must contain exactly one of the supported datatypes
            [{"S":"a","N":"1"},{"N":"abc"}]                   | Supplied AttributeValue has more than one datatypes set, must contain exactly one of the supported datatypes
            [{"S":"a","N":"abc"}]                             | The parameter cannot be converted to a numeric value: abc
            """)
    void aParameterIsRefusedAtTheFirstValueThatCannotBeRead(String parameters, String message) {
        String deep = "{\"M\":{\"n\":".repeat(40) + "{\"S\":\"leaf\"}" + "}}".repeat(40);

        request("DynamoDB_20120810.ExecuteStatement", """
                {"Statement":"SELECT * FROM \\"%s\\" WHERE pk = 'x' AND x = ? AND y = ?","Parameters":%s}
                """.formatted(TABLE, parameters.formatted(deep)))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo(message));
    }

    @Test
    @Order(31)
    void aParameterIsReadBeforeTheStatementIsParsed() {
        String unconvertible = "The parameter cannot be converted to a numeric value: abc";

        request("DynamoDB_20120810.ExecuteStatement", """
                {"Statement":"SELECT * FROM \\"%s\\" WHERE pk = ? #","Parameters":[{"N":"abc"}]}
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo(unconvertible));
        request("DynamoDB_20120810.ExecuteTransaction", """
                {"TransactStatements":[{"Statement":"UPDATE \\"%1$s\\" SET n = ? WHERE pk = 'x'","Parameters":[{"N":"abc"}]},
                 {"Statement":"UPDATE \\"%1$s\\" SET n = 1 WHERE pk = 'y' #"}]}
                """.formatted(TABLE))
            .statusCode(400)
            .body("message", equalTo(unconvertible));
        request("DynamoDB_20120810.BatchExecuteStatement", """
                {"Statements":[{"Statement":"SELECT * FROM \\"%s\\" WHERE pk = ? #","Parameters":[{"N":"abc"}]}]}
                """.formatted(TABLE))
            .statusCode(200)
            .body("Responses[0].Error.Code", equalTo("ValidationError"))
            .body("Responses[0].Error.Message", equalTo(unconvertible));
    }

    @Test
    @Order(32)
    void aBinaryParameterIsDecodedBeforeAnythingElse() {
        String update = "UPDATE \\\"" + TABLE + "\\\" SET n = ? WHERE pk = 'x'";

        request("DynamoDB_20120810.ExecuteStatement", """
                {"Statement":"","Parameters":[{"N":"abc"},{"B":"AQI"}]}
                """)
            .statusCode(400)
            .body("__type", equalTo("SerializationException"))
            .body("message", equalTo("Base64 encoded length is expected a multiple of 4 bytes but found: 3"));
        request("DynamoDB_20120810.ExecuteTransaction", """
                {"TransactStatements":[{"Statement":"%1$s","Parameters":[{"N":"abc"}]},
                 {"Statement":"%1$s","Parameters":[{"B":"AQI"}]}]}
                """.formatted(update))
            .statusCode(400)
            .body("__type", equalTo("SerializationException"));
        request("DynamoDB_20120810.BatchExecuteStatement", """
                {"Statements":[{"Statement":""},{"Statement":"%s","Parameters":[{"B":"AQI"}]}]}
                """.formatted(update))
            .statusCode(400)
            .body("__type", equalTo("SerializationException"));
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
