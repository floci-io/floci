package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbAccessPathIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String TABLE = "access-path-validation";
    private static final String SIBLING_TABLE = "access-path-validation-sibling";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createTable() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "TableName": "%s",
                  "AttributeDefinitions": [
                    {"AttributeName":"pk","AttributeType":"S"},
                    {"AttributeName":"sk","AttributeType":"S"},
                    {"AttributeName":"status","AttributeType":"S"},
                    {"AttributeName":"createdAt","AttributeType":"S"},
                    {"AttributeName":"alternate","AttributeType":"S"}
                  ],
                  "KeySchema": [
                    {"AttributeName":"pk","KeyType":"HASH"},
                    {"AttributeName":"sk","KeyType":"RANGE"}
                  ],
                  "GlobalSecondaryIndexes": [{
                    "IndexName":"status-index",
                    "KeySchema": [
                      {"AttributeName":"status","KeyType":"HASH"},
                      {"AttributeName":"createdAt","KeyType":"RANGE"}
                    ],
                    "Projection":{"ProjectionType":"INCLUDE","NonKeyAttributes":["summary"]}
                  }],
                  "LocalSecondaryIndexes": [{
                    "IndexName":"alternate-index",
                    "KeySchema": [
                      {"AttributeName":"pk","KeyType":"HASH"},
                      {"AttributeName":"alternate","KeyType":"RANGE"}
                    ],
                    "Projection":{"ProjectionType":"KEYS_ONLY"}
                  }],
                  "BillingMode":"PAY_PER_REQUEST"
                }
                """.formatted(TABLE))
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void queryAndScanRejectUnknownIndexOnEmptyTable() {
        request("DynamoDB_20120810.Scan", """
                {"TableName":"%s","IndexName":"missing-index"}
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("The table does not have the specified index: missing-index"));

        request("DynamoDB_20120810.Query", """
                {
                  "TableName":"%s",
                  "IndexName":"missing-index",
                  "KeyConditionExpression":"pk = :pk",
                  "ExpressionAttributeValues":{":pk":{"S":"p1"}}
                }
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("The table does not have the specified index: missing-index"));
    }

    @Test
    @Order(3)
    void queryRejectsNonKeyConditionOnEmptyTable() {
        request("DynamoDB_20120810.Query", """
                {
                  "TableName":"%s",
                  "KeyConditionExpression":"pk = :pk AND status = :status",
                  "ExpressionAttributeValues":{
                    ":pk":{"S":"p1"},
                    ":status":{"S":"open"}
                  }
                }
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("Query key condition not supported"));
    }

    @Test
    @Order(4)
    void tableAndLsiUseTheirSelectedKeySchemas() {
        request("DynamoDB_20120810.Query", """
                {
                  "TableName":"%s",
                  "KeyConditionExpression":"sk >= :sk AND pk = :pk",
                  "ExpressionAttributeValues":{
                    ":pk":{"S":"p1"},
                    ":sk":{"S":"a"}
                  }
                }
                """.formatted(TABLE))
            .statusCode(200)
            .body("Count", equalTo(0));

        request("DynamoDB_20120810.Query", """
                {
                  "TableName":"%s",
                  "IndexName":"alternate-index",
                  "KeyConditionExpression":"pk = :pk AND sk = :sk",
                  "ExpressionAttributeValues":{
                    ":pk":{"S":"p1"},
                    ":sk":{"S":"s1"}
                  }
                }
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(5)
    void queryRejectsKeyConditionValuesWithWrongSchemaTypes() {
        request("DynamoDB_20120810.Query", """
                {
                  "TableName":"%s",
                  "KeyConditionExpression":"pk = :pk AND sk > :sk",
                  "ExpressionAttributeValues":{
                    ":pk":{"S":"p1"},
                    ":sk":{"N":"1"}
                  }
                }
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("One or more parameter values were invalid: "
                    + "Condition parameter type does not match schema type"));

        request("DynamoDB_20120810.Query", """
                {
                  "TableName":"%s",
                  "KeyConditionExpression":"pk = :pk",
                  "ExpressionAttributeValues":{":pk":{"S":null}}
                }
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));

        request("DynamoDB_20120810.Query", """
                {
                  "TableName":"%s",
                  "KeyConditionExpression":"pk = :pk",
                  "ExpressionAttributeValues":{":pk":{"S":"p1","N":"1"}}
                }
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(6)
    void queryRejectsSelectedKeyInFilterExpression() {
        request("DynamoDB_20120810.Query", """
                {
                  "TableName":"%s",
                  "IndexName":"status-index",
                  "KeyConditionExpression":"#status = :status",
                  "FilterExpression":"createdAt > :cutoff",
                  "ExpressionAttributeNames":{"#status":"status"},
                  "ExpressionAttributeValues":{
                    ":status":{"S":"open"},
                    ":cutoff":{"S":"2026-01-01"}
                  }
                }
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("Filter Expression can only contain non-primary key attributes: "
                    + "Primary key attribute: createdAt"));
    }

    @Test
    @Order(7)
    void queryAndScanRejectNonProjectedGsiAttribute() {
        String query = """
                {
                  "TableName":"%s",
                  "IndexName":"status-index",
                  "KeyConditionExpression":"#status = :status",
                  "ExpressionAttributeNames":{"#status":"status"},
                  "ExpressionAttributeValues":{":status":{"S":"open"}},
                  "ProjectionExpression":"details"
                }
                """.formatted(TABLE);
        request("DynamoDB_20120810.Query", query)
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));

        request("DynamoDB_20120810.Scan", """
                {
                  "TableName":"%s",
                  "IndexName":"status-index",
                  "ProjectionExpression":"details"
                }
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(8)
    void acceptsProjectedGsiAttributesAndLsiTableFetch() {
        request("DynamoDB_20120810.Query", """
                {
                  "TableName":"%s",
                  "IndexName":"status-index",
                  "KeyConditionExpression":"#status = :status",
                  "ExpressionAttributeValues":{":status":{"S":"open"}},
                  "ProjectionExpression":"pk, #status, summary",
                  "ExpressionAttributeNames":{"#status":"status"}
                }
                """.formatted(TABLE))
            .statusCode(200)
            .body("Count", equalTo(0));

        request("DynamoDB_20120810.Query", """
                {
                  "TableName":"%s",
                  "IndexName":"alternate-index",
                  "KeyConditionExpression":"pk = :pk",
                  "ExpressionAttributeValues":{":pk":{"S":"p1"}},
                  "Select":"ALL_ATTRIBUTES"
                }
                """.formatted(TABLE))
            .statusCode(200)
            .body("Count", equalTo(0));

        request("DynamoDB_20120810.Scan", """
                {
                  "TableName":"%s",
                  "IndexName":"alternate-index",
                  "ProjectionExpression":"details"
                }
                """.formatted(TABLE))
            .statusCode(200)
            .body("Count", equalTo(0));
    }

    @Test
    @Order(9)
    void gsiStillRejectsConsistentReads() {
        request("DynamoDB_20120810.Query", """
                {
                  "TableName":"%s",
                  "IndexName":"status-index",
                  "ConsistentRead":true,
                  "KeyConditionExpression":"#status = :status",
                  "ExpressionAttributeNames":{"#status":"status"},
                  "ExpressionAttributeValues":{":status":{"S":"open"}}
                }
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("Consistent reads are not supported on global secondary indexes"));
    }

    @Test
    @Order(10)
    void scanGsiRejectsConsistentReads() {
        request("DynamoDB_20120810.Scan", """
                {
                  "TableName":"%s",
                  "IndexName":"status-index",
                  "ConsistentRead":true
                }
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("Consistent reads are not supported on global secondary indexes"));

        // Consistent reads stay legal on a local secondary index.
        request("DynamoDB_20120810.Scan", """
                {
                  "TableName":"%s",
                  "IndexName":"alternate-index",
                  "ConsistentRead":true
                }
                """.formatted(TABLE))
            .statusCode(200);
    }

    @Test
    @Order(9)
    void queryAndScanRejectWrongExclusiveStartKeyTypes() {
        request("DynamoDB_20120810.Query", """
                {
                  "TableName":"%s",
                  "KeyConditionExpression":"pk = :pk",
                  "ExpressionAttributeValues":{":pk":{"S":"p1"}},
                  "ExclusiveStartKey":{"pk":{"N":"1"},"sk":{"S":"s1"}}
                }
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("The provided starting key is invalid"));

        request("DynamoDB_20120810.Scan", """
                {
                  "TableName":"%s",
                  "ExclusiveStartKey":{"pk":{"S":"p1"},"sk":{"BOOL":true}}
                }
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("The provided starting key is invalid: "
                    + "The provided key element does not match the schema"));

        request("DynamoDB_20120810.Query", """
                {
                  "TableName":"%s",
                  "KeyConditionExpression":"pk = :pk",
                  "ExpressionAttributeValues":{":pk":{"S":"p1"}},
                  "ExclusiveStartKey":{"pk":{"S":null},"sk":{"S":"s1"}}
                }
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("The provided starting key is invalid"));

        request("DynamoDB_20120810.Scan", """
                {
                  "TableName":"%s",
                  "ExclusiveStartKey":{"pk":{"S":123},"sk":{"S":"s1"}}
                }
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(10)
    void queryRejectsMalformedNumericExclusiveStartKey() {
        String numericTable = TABLE + "-numeric";
        request("DynamoDB_20120810.CreateTable", """
                {
                  "TableName":"%s",
                  "AttributeDefinitions":[{"AttributeName":"pk","AttributeType":"N"}],
                  "KeySchema":[{"AttributeName":"pk","KeyType":"HASH"}],
                  "BillingMode":"PAY_PER_REQUEST"
                }
                """.formatted(numericTable))
            .statusCode(200);

        request("DynamoDB_20120810.Query", """
                {
                  "TableName":"%s",
                  "KeyConditionExpression":"pk = :pk",
                  "ExpressionAttributeValues":{":pk":{"N":"1"}},
                  "ExclusiveStartKey":{"pk":{"N":"not-a-number"}}
                }
                """.formatted(numericTable))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));

        request("DynamoDB_20120810.DeleteTable", """
                {"TableName":"%s"}
                """.formatted(numericTable))
            .statusCode(200);
    }

    @Test
    @Order(11)
    void queryRejectsWrongIndexExclusiveStartKeyType() {
        request("DynamoDB_20120810.Query", """
                {
                  "TableName":"%s",
                  "IndexName":"status-index",
                  "KeyConditionExpression":"#status = :status",
                  "ExpressionAttributeNames":{"#status":"status"},
                  "ExpressionAttributeValues":{":status":{"S":"open"}},
                  "ExclusiveStartKey":{
                    "pk":{"S":"p1"},
                    "sk":{"S":"s1"},
                    "status":{"S":"open"},
                    "createdAt":{"N":"1"}
                  }
                }
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("The provided starting key is invalid"));
    }

    @Test
    @Order(20)
    void executeStatementRejectsConsistentReadOnQualifiedGsi() {
        request("DynamoDB_20120810.ExecuteStatement", """
                {
                  "Statement":"SELECT * FROM \\"%s\\".\\"status-index\\"",
                  "ConsistentRead":true
                }
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("Strongly consistent read is not supported on Global Secondary Indexes"));
    }

    @Test
    @Order(21)
    void executeStatementReadsThroughQualifiedGsi() {
        request("DynamoDB_20120810.PutItem", """
                {
                  "TableName":"%s",
                  "Item":{"pk":{"S":"partiql-gsi"},"sk":{"S":"s1"},
                    "status":{"S":"partiql-status"},"createdAt":{"S":"2026-01-01"},
                    "alternate":{"S":"alt-1"},"summary":{"S":"first"}}
                }
                """.formatted(TABLE))
            .statusCode(200);
        request("DynamoDB_20120810.PutItem", """
                {
                  "TableName":"%s",
                  "Item":{"pk":{"S":"partiql-gsi"},"sk":{"S":"s2"},
                    "status":{"S":"partiql-status"},"createdAt":{"S":"2026-01-02"},
                    "alternate":{"S":"alt-2"},"summary":{"S":"second"}}
                }
                """.formatted(TABLE))
            .statusCode(200);
        // A row matching the base-table predicate but absent from the index:
        // it must stay invisible to the qualified read.
        request("DynamoDB_20120810.PutItem", """
                {
                  "TableName":"%s",
                  "Item":{"pk":{"S":"partiql-sparse"},"sk":{"S":"s2"},
                    "alternate":{"S":"alt-9"}}
                }
                """.formatted(TABLE))
            .statusCode(200);

        // Equality on the index partition key routes through an index query.
        // status-index projects INCLUDE summary, so non-projected attributes
        // stay out of the result (SELECT * reads the projection).
        request("DynamoDB_20120810.ExecuteStatement", """
                {
                  "Statement":"SELECT * FROM \\"%s\\".\\"status-index\\" WHERE status = 'partiql-status'"
                }
                """.formatted(TABLE))
            .statusCode(200)
            .body("Items.size()", equalTo(2))
            .body("Items[0].summary.S", equalTo("first"))
            .body("Items[0].alternate", equalTo(null));

        // Without equality on the index partition key, the statement performs
        // a full index scan and applies the remaining conditions as a filter.
        request("DynamoDB_20120810.ExecuteStatement", """
                {
                  "Statement":"SELECT * FROM \\"%s\\".\\"status-index\\" WHERE sk = 's2'"
                }
                """.formatted(TABLE))
            .statusCode(200)
            .body("Items.size()", equalTo(1))
            .body("Items[0].sk.S", equalTo("s2"));
    }

    @Test
    @Order(22)
    void executeStatementQualifiedLsiAcceptsConsistentReads() {
        request("DynamoDB_20120810.ExecuteStatement", """
                {
                  "Statement":"SELECT * FROM \\"%s\\".\\"alternate-index\\" WHERE pk = 'partiql-gsi'",
                  "ConsistentRead":true
                }
                """.formatted(TABLE))
            .statusCode(200)
            .body("Items.size()", equalTo(2))
            .body("Items[0].alternate.S", equalTo("alt-1"))
            .body("Items[0].summary", equalTo(null));

        // An unqualified statement stays legal with ConsistentRead, even when
        // its WHERE clause names a GSI attribute.
        request("DynamoDB_20120810.ExecuteStatement", """
                {
                  "Statement":"SELECT * FROM \\"%s\\" WHERE status = 'partiql-status'",
                  "ConsistentRead":true
                }
                """.formatted(TABLE))
            .statusCode(200)
            .body("Items.size()", equalTo(2));

        // An LSI read reaches the co-located base item, so an explicit column
        // outside the index projection still returns (characterised on real
        // AWS, eu-west-1, 2026-09-02). alternate-index is KEYS_ONLY.
        request("DynamoDB_20120810.ExecuteStatement", """
                {
                  "Statement":"SELECT summary FROM \\"%s\\".\\"alternate-index\\" WHERE pk = 'partiql-gsi'"
                }
                """.formatted(TABLE))
            .statusCode(200)
            .body("Items.size()", equalTo(2))
            .body("Items[0].summary.S", equalTo("first"));
    }

    @Test
    @Order(26)
    void executeStatementRejectsUnprojectedGsiColumnsInStatementOrder() {
        request("DynamoDB_20120810.ExecuteStatement", """
                {
                  "Statement":"SELECT zdrop, adrop FROM \\"%s\\".\\"status-index\\" WHERE status = 'partiql-status'"
                }
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo(
                    "One or more parameter values were invalid: Global secondary index status-index "
                            + "does not project [zdrop, adrop]"));
    }

    @Test
    @Order(23)
    void executeStatementRejectsUnknownQualifiedIndex() {
        request("DynamoDB_20120810.ExecuteStatement", """
                {
                  "Statement":"SELECT * FROM \\"%s\\".\\"no-such-index\\""
                }
                """.formatted(TABLE))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("The table does not have the specified index"));
    }

    @Test
    @Order(24)
    void executeStatementScanAndQueryBranchesPaginate() {
        request("DynamoDB_20120810.PutItem", """
                {
                  "TableName":"%s",
                  "Item":{"pk":{"S":"partiql-page-1"},"sk":{"S":"a"},
                    "status":{"S":"partiql-page"},"createdAt":{"S":"2026-02-01"},
                    "alternate":{"S":"page-1"},"summary":{"S":"one"}}
                }
                """.formatted(TABLE))
            .statusCode(200);
        request("DynamoDB_20120810.PutItem", """
                {
                  "TableName":"%s",
                  "Item":{"pk":{"S":"partiql-page-2"},"sk":{"S":"b"},
                    "status":{"S":"partiql-page"},"createdAt":{"S":"2026-02-02"},
                    "alternate":{"S":"page-2"},"summary":{"S":"two"}}
                }
                """.formatted(TABLE))
            .statusCode(200);

        // Filter scan (no partition-key equality): Limit caps scanned items, so
        // a page may come back empty while the cursor still advances. Page
        // until the cursor is exhausted and collect both matches.
        Set<String> scannedPks = new HashSet<>();
        String scanToken = null;
        int scanPages = 0;
        do {
            String pageBody = """
                    {"Statement":"SELECT * FROM \\"%s\\" WHERE status = 'partiql-page'",
                      "Limit":1%s}
                    """.formatted(TABLE, scanToken == null ? "" : ",\"NextToken\":\"" + scanToken + "\"");
            io.restassured.response.ValidatableResponse page =
                request("DynamoDB_20120810.ExecuteStatement", pageBody).statusCode(200);
            page.body("Items.size()", lessThanOrEqualTo(1));
            List<String> pagePks = page.extract().jsonPath().getList("Items.pk.S");
            if (pagePks != null) scannedPks.addAll(pagePks);
            scanToken = page.extract().jsonPath().getString("NextToken");
            scanPages++;
        } while (scanToken != null && scanPages < 10);

        org.junit.jupiter.api.Assertions.assertEquals(Set.of("partiql-page-1", "partiql-page-2"), scannedPks);

        // Query branch (partition-key equality): same pagination contract.
        String queryToken = request("DynamoDB_20120810.ExecuteStatement", """
                {
                  "Statement":"SELECT * FROM \\"%s\\" WHERE pk = 'partiql-gsi' AND begins_with(sk, 's')",
                  "Limit":1
                }
                """.formatted(TABLE))
            .statusCode(200)
            .body("Items.size()", equalTo(1))
            .extract().jsonPath().getString("NextToken");
        request("DynamoDB_20120810.ExecuteStatement", """
                {
                  "Statement":"SELECT * FROM \\"%s\\" WHERE pk = 'partiql-gsi' AND begins_with(sk, 's')",
                  "Limit":1,
                  "NextToken":"%s"
                }
                """.formatted(TABLE, queryToken))
            .statusCode(200)
            .body("Items.size()", equalTo(1));
    }

    @Test
    @Order(25)
    void batchExecuteStatementRejectsSelectsOutsideThePrimaryKey() {
        request("DynamoDB_20120810.BatchExecuteStatement", """
                {
                  "Statements":[
                    {"Statement":"SELECT * FROM \\"%s\\".\\"status-index\\" WHERE status = 'partiql-status'"},
                    {"Statement":"SELECT * FROM \\"%s\\" WHERE pk = 'partiql-page-1'"}
                  ]
                }
                """.formatted(TABLE, TABLE))
            .statusCode(200)
            .body("Responses[0].Error.Code", equalTo("ValidationError"))
            .body("Responses[0].Error.Message", equalTo(
                    "Select statements within BatchExecuteStatement must specify the primary key in the where clause."))
            .body("Responses[1].Error.Code", equalTo("ValidationError"))
            .body("Responses[1].Error.Message", equalTo(
                    "Select statements within BatchExecuteStatement must specify the primary key in the where clause."));
    }

    @Test
    @Order(27)
    void executeStatementRejectsForeignNextTokens() {
        // The NextToken is opaque and bound to the issuing statement text and
        // parameters (characterised on real AWS, eu-west-1, 2026-09-03):
        // replaying one against a different statement, parameter set or access
        // path is rejected, while a different Limit is accepted.
        String scanToken = request("DynamoDB_20120810.ExecuteStatement", """
                {"Statement":"SELECT * FROM \\"%s\\" WHERE status = 'partiql-page'","Limit":1}
                """.formatted(TABLE))
            .statusCode(200)
            .body("Items.size()", lessThanOrEqualTo(1))
            .extract().jsonPath().getString("NextToken");

        // Different statement over the same table.
        request("DynamoDB_20120810.ExecuteStatement", """
                {"Statement":"SELECT * FROM \\"%s\\"","Limit":1,"NextToken":"%s"}
                """.formatted(TABLE, scanToken))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("NextToken does not match request"));

        // Base-table token replayed on an index-qualified statement.
        request("DynamoDB_20120810.ExecuteStatement", """
                {"Statement":"SELECT * FROM \\"%s\\".\\"status-index\\" WHERE status = 'partiql-page'","Limit":1,"NextToken":"%s"}
                """.formatted(TABLE, scanToken))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("NextToken does not match request"));

        // Index-qualified token replayed on the base table.
        String gsiToken = request("DynamoDB_20120810.ExecuteStatement", """
                {"Statement":"SELECT * FROM \\"%s\\".\\"status-index\\" WHERE status = 'partiql-page'","Limit":1}
                """.formatted(TABLE))
            .statusCode(200)
            .extract().jsonPath().getString("NextToken");
        request("DynamoDB_20120810.ExecuteStatement", """
                {"Statement":"SELECT * FROM \\"%s\\" WHERE status = 'partiql-page'","Limit":1,"NextToken":"%s"}
                """.formatted(TABLE, gsiToken))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("NextToken does not match request"));

        // Same statement text with different parameters.
        String parameterToken = request("DynamoDB_20120810.ExecuteStatement", """
                {"Statement":"SELECT * FROM \\"%s\\" WHERE pk = ?","Limit":1,"Parameters":[{"S":"partiql-gsi"}]}
                """.formatted(TABLE))
            .statusCode(200)
            .extract().jsonPath().getString("NextToken");
        request("DynamoDB_20120810.ExecuteStatement", """
                {"Statement":"SELECT * FROM \\"%s\\" WHERE pk = ?","Limit":1,"Parameters":[{"S":"partiql-page-1"}],"NextToken":"%s"}
                """.formatted(TABLE, parameterToken))
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("NextToken does not match request"));

        // A different Limit is not part of the binding.
        request("DynamoDB_20120810.ExecuteStatement", """
                {"Statement":"SELECT * FROM \\"%s\\" WHERE pk = ?","Limit":2,"Parameters":[{"S":"partiql-gsi"}],"NextToken":"%s"}
                """.formatted(TABLE, parameterToken))
            .statusCode(200);
    }

    @Test
    @Order(28)
    void executeStatementRejectsNextTokenFromAnotherTable() {
        // Even with an identical key schema, a token minted against one table
        // is rejected on another (characterised on real AWS, eu-west-1,
        // 2026-09-03).
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "TableName":"%s",
                  "AttributeDefinitions":[
                    {"AttributeName":"pk","AttributeType":"S"},
                    {"AttributeName":"sk","AttributeType":"S"}
                  ],
                  "KeySchema":[
                    {"AttributeName":"pk","KeyType":"HASH"},
                    {"AttributeName":"sk","KeyType":"RANGE"}
                  ],
                  "BillingMode":"PAY_PER_REQUEST"
                }
                """.formatted(SIBLING_TABLE))
        .when()
            .post("/")
        .then()
            .statusCode(200);
        try {
            String token = request("DynamoDB_20120810.ExecuteStatement", """
                    {"Statement":"SELECT * FROM \\"%s\\" WHERE pk = 'partiql-gsi' AND begins_with(sk, 's')","Limit":1}
                    """.formatted(TABLE))
                .statusCode(200)
                .extract().jsonPath().getString("NextToken");
            request("DynamoDB_20120810.ExecuteStatement", """
                    {"Statement":"SELECT * FROM \\"%s\\"","Limit":1,"NextToken":"%s"}
                    """.formatted(SIBLING_TABLE, token))
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("NextToken does not match request"));
        } finally {
            given()
                .header("X-Amz-Target", "DynamoDB_20120810.DeleteTable")
                .contentType(CONTENT_TYPE)
                .body("{\"TableName\":\"" + SIBLING_TABLE + "\"}")
            .when()
                .post("/")
            .then()
                .statusCode(200);
        }
    }

    @Test
    @Order(99)
    void deleteTable() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DeleteTable")
            .contentType(CONTENT_TYPE)
            .body("{\"TableName\":\"" + TABLE + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static io.restassured.response.ValidatableResponse request(String target, String body) {
        return given()
                .header("X-Amz-Target", target)
                .contentType(CONTENT_TYPE)
                .body(body)
            .when()
                .post("/")
            .then();
    }
}
