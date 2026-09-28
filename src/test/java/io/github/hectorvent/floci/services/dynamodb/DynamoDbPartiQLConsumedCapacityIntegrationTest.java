package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbPartiQLConsumedCapacityIntegrationTest {

    private static final String CT = "application/x-amz-json-1.0";
    private static final String TABLE = "PartiQLCapacityTable";
    private static int testPort;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createTable() {
        testPort = RestAssured.port;
        send("CreateTable", """
            {
                "TableName": "%s",
                "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                "AttributeDefinitions": [
                    {"AttributeName": "pk", "AttributeType": "S"},
                    {"AttributeName": "gsiPk", "AttributeType": "S"},
                    {"AttributeName": "gsi2Pk", "AttributeType": "S"}
                ],
                "GlobalSecondaryIndexes": [{
                    "IndexName": "gsi1",
                    "KeySchema": [{"AttributeName": "gsiPk", "KeyType": "HASH"}],
                    "Projection": {"ProjectionType": "ALL"}
                }, {
                    "IndexName": "gsi2",
                    "KeySchema": [{"AttributeName": "gsi2Pk", "KeyType": "HASH"}],
                    "Projection": {"ProjectionType": "KEYS_ONLY"}
                }],
                "BillingMode": "PAY_PER_REQUEST"
            }
            """.formatted(TABLE));
    }

    @Test
    @Order(2)
    void executeStatementReadsAreChargedLikeGetItemWithNoSplit() {
        send("PutItem", """
            {"TableName": "%s", "Item": {"pk": {"S": "cc-pq"}, "data": {"S": "x"}}}
            """.formatted(TABLE));
        send("ExecuteStatement", """
            {"Statement": "SELECT * FROM \\"%s\\" WHERE pk = 'cc-pq'", "ReturnConsumedCapacity": "TOTAL"}
            """.formatted(TABLE))
            .body("ConsumedCapacity.TableName", equalTo(TABLE))
            .body("ConsumedCapacity.CapacityUnits", equalTo(0.5f))
            .body("ConsumedCapacity.ReadCapacityUnits", nullValue())
            .body("ConsumedCapacity.Table", nullValue());
        send("ExecuteStatement", """
            {
                "Statement": "SELECT * FROM \\"%s\\" WHERE pk = 'cc-pq-missing'",
                "ConsistentRead": true,
                "ReturnConsumedCapacity": "INDEXES"
            }
            """.formatted(TABLE))
            .body("ConsumedCapacity.CapacityUnits", equalTo(1.0f))
            .body("ConsumedCapacity.Table.CapacityUnits", equalTo(1.0f));
    }

    @Test
    @Order(3)
    void executeStatementOnAGsiChargesOnlyTheIndex() {
        send("PutItem", """
            {"TableName": "%s", "Item": {"pk": {"S": "cc-pq-gsi"}, "gsiPk": {"S": "q1"}}}
            """.formatted(TABLE));
        send("ExecuteStatement", """
            {
                "Statement": "SELECT pk FROM \\"%s\\".\\"gsi1\\" WHERE gsiPk = 'q1'",
                "ReturnConsumedCapacity": "INDEXES"
            }
            """.formatted(TABLE))
            .body("ConsumedCapacity.CapacityUnits", equalTo(0.5f))
            .body("ConsumedCapacity.Table.CapacityUnits", equalTo(0.0f))
            .body("ConsumedCapacity.GlobalSecondaryIndexes.gsi1.CapacityUnits", equalTo(0.5f));
    }

    @Test
    @Order(4)
    void executeStatementWritesAreChargedLikeSingleItemWrites() {
        send("ExecuteStatement", """
            {
                "Statement": "INSERT INTO \\"%s\\" VALUE {'pk': 'cc-pq-ins', 'gsiPk': 'i1', 'gsi2Pk': 'i2'}",
                "ReturnConsumedCapacity": "INDEXES"
            }
            """.formatted(TABLE))
            .body("ConsumedCapacity.CapacityUnits", equalTo(3.0f))
            .body("ConsumedCapacity.WriteCapacityUnits", nullValue())
            .body("ConsumedCapacity.Table.CapacityUnits", equalTo(1.0f))
            .body("ConsumedCapacity.GlobalSecondaryIndexes.gsi1.CapacityUnits", equalTo(1.0f))
            .body("ConsumedCapacity.GlobalSecondaryIndexes.gsi2.CapacityUnits", equalTo(1.0f));
        send("ExecuteStatement", """
            {"Statement": "DELETE FROM \\"%s\\" WHERE pk = 'cc-pq-absent'", "ReturnConsumedCapacity": "TOTAL"}
            """.formatted(TABLE))
            .body("ConsumedCapacity.CapacityUnits", equalTo(1.0f));
    }

    @Test
    @Order(5)
    void executeStatementOnAKeysOnlyLsiChargesEachBaseTableFetch() {
        send("CreateTable", """
            {
                "TableName": "PartiQLCapacityLsiTable",
                "KeySchema": [
                    {"AttributeName": "pk", "KeyType": "HASH"},
                    {"AttributeName": "sk", "KeyType": "RANGE"}
                ],
                "AttributeDefinitions": [
                    {"AttributeName": "pk", "AttributeType": "S"},
                    {"AttributeName": "sk", "AttributeType": "S"},
                    {"AttributeName": "lsiSk", "AttributeType": "S"}
                ],
                "LocalSecondaryIndexes": [{
                    "IndexName": "lsi-keys",
                    "KeySchema": [
                        {"AttributeName": "pk", "KeyType": "HASH"},
                        {"AttributeName": "lsiSk", "KeyType": "RANGE"}
                    ],
                    "Projection": {"ProjectionType": "KEYS_ONLY"}
                }],
                "BillingMode": "PAY_PER_REQUEST"
            }
            """);
        for (int i = 1; i <= 3; i++) {
            send("PutItem", """
                {
                    "TableName": "PartiQLCapacityLsiTable",
                    "Item": {"pk": {"S": "p"}, "sk": {"S": "%1$d"}, "lsiSk": {"S": "l%1$d"}, "nonproj": {"S": "n"}}
                }
                """.formatted(i));
        }
        String fetch = """
            {
                "Statement": "SELECT nonproj FROM \\"PartiQLCapacityLsiTable\\".\\"lsi-keys\\" WHERE pk = 'p'",
                "ReturnConsumedCapacity": "INDEXES"%s
            }
            """;
        send("ExecuteStatement", fetch.formatted(""))
            .body("ConsumedCapacity.CapacityUnits", equalTo(2.0f))
            .body("ConsumedCapacity.Table.CapacityUnits", equalTo(1.5f))
            .body("ConsumedCapacity.LocalSecondaryIndexes.'lsi-keys'.CapacityUnits", equalTo(0.5f));
        send("ExecuteStatement", fetch.formatted(", \"Limit\": 1"))
            .body("ConsumedCapacity.Table.CapacityUnits", equalTo(0.5f));
        send("ExecuteStatement", """
            {
                "Statement": "SELECT lsiSk FROM \\"PartiQLCapacityLsiTable\\".\\"lsi-keys\\" WHERE pk = 'p'",
                "ReturnConsumedCapacity": "INDEXES"
            }
            """)
            .body("ConsumedCapacity.CapacityUnits", equalTo(0.5f))
            .body("ConsumedCapacity.Table.CapacityUnits", equalTo(0.0f));
        send("ExecuteStatement", """
            {
                "Statement": "SELECT nonproj FROM \\"PartiQLCapacityLsiTable\\".\\"lsi-keys\\"",
                "ReturnConsumedCapacity": "INDEXES"
            }
            """)
            .body("ConsumedCapacity.Table.CapacityUnits", equalTo(1.5f))
            .body("ConsumedCapacity.LocalSecondaryIndexes.'lsi-keys'.CapacityUnits", equalTo(0.5f));
        send("ExecuteStatement", """
            {
                "Statement": "SELECT lsiSk FROM \\"PartiQLCapacityLsiTable\\".\\"lsi-keys\\"",
                "ReturnConsumedCapacity": "INDEXES"
            }
            """)
            .body("ConsumedCapacity.Table.CapacityUnits", equalTo(0.0f));
        send("DeleteTable", """
            {"TableName": "PartiQLCapacityLsiTable"}
            """);
    }

    @Test
    @Order(6)
    void batchExecuteStatementChargesOnlyTheMembersThatSucceed() {
        send("BatchExecuteStatement", """
            {
                "Statements": [
                    {"Statement": "SELECT * FROM \\"%1$s\\" WHERE pk = 'cc-pq'", "ConsistentRead": true},
                    {"Statement": "SELECT * FROM \\"%1$s\\" WHERE pk = 'cc-pq-missing'"},
                    {"Statement": "SELECT * FROM \\"%1$s\\" WHERE data = 'x'"}
                ],
                "ReturnConsumedCapacity": "INDEXES"
            }
            """.formatted(TABLE))
            .body("Responses[2].Error.Code", equalTo("ValidationError"))
            .body("ConsumedCapacity", hasSize(1))
            .body("ConsumedCapacity[0].TableName", equalTo(TABLE))
            .body("ConsumedCapacity[0].CapacityUnits", equalTo(1.5f))
            .body("ConsumedCapacity[0].ReadCapacityUnits", nullValue())
            .body("ConsumedCapacity[0].Table.CapacityUnits", equalTo(1.5f));
        send("BatchExecuteStatement", """
            {
                "Statements": [{"Statement": "INSERT INTO \\"%s\\" VALUE {'pk': 'cc-pq'}"}],
                "ReturnConsumedCapacity": "TOTAL"
            }
            """.formatted(TABLE))
            .body("Responses[0].Error.Code", equalTo("DuplicateItem"))
            .body("ConsumedCapacity", nullValue());
    }

    @Test
    @Order(7)
    void statementsRejectAnInvalidReturnConsumedCapacityBeforeRunning() {
        String invalid = "1 validation error detected: Value 'INVALID' at 'returnConsumedCapacity' failed to "
                + "satisfy constraint: Member must satisfy enum value set: [INDEXES, TOTAL, NONE]";
        rejected("ExecuteStatement", """
            {
                "Statement": "INSERT INTO \\"%s\\" VALUE {'pk': 'cc-pq-rcc'}",
                "ReturnConsumedCapacity": "INVALID"
            }
            """.formatted(TABLE))
            .body("message", equalTo(invalid));
        rejected("BatchExecuteStatement", """
            {
                "Statements": [{"Statement": "INSERT INTO \\"%s\\" VALUE {'pk': 'cc-pq-rcc-batch'}"}],
                "ReturnConsumedCapacity": "INVALID"
            }
            """.formatted(TABLE))
            .body("message", equalTo(invalid));

        send("BatchExecuteStatement", """
            {
                "Statements": [
                    {"Statement": "SELECT * FROM \\"%1$s\\" WHERE pk = 'cc-pq-rcc'"},
                    {"Statement": "SELECT * FROM \\"%1$s\\" WHERE pk = 'cc-pq-rcc-batch'"}
                ]
            }
            """.formatted(TABLE))
            .body("Responses[0].Item", nullValue())
            .body("Responses[1].Item", nullValue());
    }

    private static ValidatableResponse rejected(String operation, String body) {
        return given()
                .header("X-Amz-Target", "DynamoDB_20120810." + operation)
                .contentType(CT)
                .body(body)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    private static ValidatableResponse send(String operation, String body) {
        return given()
                .header("X-Amz-Target", "DynamoDB_20120810." + operation)
                .contentType(CT)
                .body(body)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    @AfterAll
    static void cleanup() {
        given()
                .port(testPort)
                .header("X-Amz-Target", "DynamoDB_20120810.DeleteTable")
                .contentType(CT)
                .body("""
                    {"TableName": "%s"}
                    """.formatted(TABLE))
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }
}
