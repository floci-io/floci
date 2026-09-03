package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("DynamoDB PartiQL")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbPartiQLTest {

    private static DynamoDbClient ddb;
    private static final String TABLE = "partiql-test-table";
    private static final String INDEX_TABLE = "partiql-index-table";

    @BeforeAll
    static void setup() {
        ddb = TestFixtures.dynamoDbClient();
        try {
            ddb.deleteTable(DeleteTableRequest.builder().tableName(TABLE).build());
        } catch (Exception ignored) {}
        ddb.createTable(CreateTableRequest.builder()
                .tableName(TABLE)
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build()
                )
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build()
                )
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build());

        try {
            ddb.deleteTable(DeleteTableRequest.builder().tableName(INDEX_TABLE).build());
        } catch (Exception ignored) {}
        ddb.createTable(CreateTableRequest.builder()
                .tableName(INDEX_TABLE)
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build()
                )
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("status").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("createdAt").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("alternate").attributeType(ScalarAttributeType.S).build()
                )
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                        .indexName("status-index")
                        .keySchema(
                                KeySchemaElement.builder().attributeName("status").keyType(KeyType.HASH).build(),
                                KeySchemaElement.builder().attributeName("createdAt").keyType(KeyType.RANGE).build()
                        )
                        .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                        .build())
                .localSecondaryIndexes(LocalSecondaryIndex.builder()
                        .indexName("alternate-index")
                        .keySchema(
                                KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                                KeySchemaElement.builder().attributeName("alternate").keyType(KeyType.RANGE).build()
                        )
                        .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                        .build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build());
        ddb.putItem(PutItemRequest.builder()
                .tableName(INDEX_TABLE)
                .item(Map.of(
                        "pk", AttributeValue.builder().s("idxpk").build(),
                        "sk", AttributeValue.builder().s("s1").build(),
                        "status", AttributeValue.builder().s("active").build(),
                        "createdAt", AttributeValue.builder().s("2026-01-01").build(),
                        "alternate", AttributeValue.builder().s("alt-1").build()))
                .build());
        ddb.putItem(PutItemRequest.builder()
                .tableName(INDEX_TABLE)
                .item(Map.of(
                        "pk", AttributeValue.builder().s("idxpk").build(),
                        "sk", AttributeValue.builder().s("s2").build(),
                        "status", AttributeValue.builder().s("active").build(),
                        "createdAt", AttributeValue.builder().s("2026-01-02").build(),
                        "alternate", AttributeValue.builder().s("alt-2").build()))
                .build());
    }

    @AfterAll
    static void cleanup() {
        if (ddb != null) {
            try {
                ddb.deleteTable(DeleteTableRequest.builder().tableName(TABLE).build());
            } catch (Exception ignored) {}
            try {
                ddb.deleteTable(DeleteTableRequest.builder().tableName(INDEX_TABLE).build());
            } catch (Exception ignored) {}
            ddb.close();
        }
    }

    @Test
    @Order(1)
    void insertViaPartiQL() {
        ExecuteStatementResponse resp = ddb.executeStatement(r -> r
                .statement("INSERT INTO \"" + TABLE + "\" VALUE {'pk': 'pk1', 'sk': 'sk1', 'name': 'Alice', 'age': 30}"));
        assertThat(resp.items()).isEmpty();
    }

    @Test
    @Order(2)
    void selectByPkOnly() {
        ExecuteStatementResponse resp = ddb.executeStatement(r -> r
                .statement("SELECT * FROM \"" + TABLE + "\" WHERE pk = 'pk1' AND sk = 'sk1'"));
        assertThat(resp.items()).hasSize(1);
        assertThat(resp.items().get(0).get("name").s()).isEqualTo("Alice");
    }

    @Test
    @Order(3)
    void selectWithProjection() {
        ExecuteStatementResponse resp = ddb.executeStatement(r -> r
                .statement("SELECT name FROM \"" + TABLE + "\" WHERE pk = 'pk1' AND sk = 'sk1'"));
        assertThat(resp.items()).hasSize(1);
        Map<String, AttributeValue> item = resp.items().get(0);
        assertThat(item).containsKey("name");
        assertThat(item).doesNotContainKey("age");
    }

    @Test
    @Order(4)
    void insertDuplicateThrows() {
        assertThatThrownBy(() -> ddb.executeStatement(r -> r
                .statement("INSERT INTO \"" + TABLE + "\" VALUE {'pk': 'pk1', 'sk': 'sk1', 'name': 'Bob'}")))
                .isInstanceOf(DynamoDbException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    @Order(5)
    void updateSetAttribute() {
        ddb.executeStatement(r -> r
                .statement("UPDATE \"" + TABLE + "\" SET name = 'Charlie' WHERE pk = 'pk1' AND sk = 'sk1'"));
        ExecuteStatementResponse verify = ddb.executeStatement(r -> r
                .statement("SELECT * FROM \"" + TABLE + "\" WHERE pk = 'pk1' AND sk = 'sk1'"));
        assertThat(verify.items().get(0).get("name").s()).isEqualTo("Charlie");
    }

    @Test
    @Order(6)
    void updateRemoveAttribute() {
        ddb.executeStatement(r -> r
                .statement("UPDATE \"" + TABLE + "\" REMOVE age WHERE pk = 'pk1' AND sk = 'sk1'"));
        ExecuteStatementResponse verify = ddb.executeStatement(r -> r
                .statement("SELECT * FROM \"" + TABLE + "\" WHERE pk = 'pk1' AND sk = 'sk1'"));
        assertThat(verify.items().get(0).containsKey("age")).isFalse();
    }

    @Test
    @Order(7)
    void selectWithPlaceholders() {
        ExecuteStatementResponse resp = ddb.executeStatement(r -> r
                .statement("SELECT * FROM \"" + TABLE + "\" WHERE pk = ? AND sk = ?")
                .parameters(
                        AttributeValue.fromS("pk1"),
                        AttributeValue.fromS("sk1")
                ));
        assertThat(resp.items()).hasSize(1);
    }

    @Test
    @Order(8)
    void insertMultipleItemsAndQueryRange() {
        for (int i = 2; i <= 5; i++) {
            final int idx = i;
            ddb.executeStatement(r -> r
                    .statement("INSERT INTO \"" + TABLE + "\" VALUE {'pk': 'pk2', 'sk': 'sk" + idx + "', 'val': " + idx + "}"));
        }
        ExecuteStatementResponse resp = ddb.executeStatement(r -> r
                .statement("SELECT * FROM \"" + TABLE + "\" WHERE pk = 'pk2'"));
        assertThat(resp.items()).hasSize(4);
    }

    @Test
    @Order(9)
    void selectWithBeginsWith() {
        ExecuteStatementResponse resp = ddb.executeStatement(r -> r
                .statement("SELECT * FROM \"" + TABLE + "\" WHERE pk = 'pk2' AND begins_with(sk, 'sk')"));
        assertThat(resp.items()).hasSize(4);
    }

    @Test
    @Order(10)
    void deleteItem() {
        ddb.executeStatement(r -> r
                .statement("DELETE FROM \"" + TABLE + "\" WHERE pk = 'pk1' AND sk = 'sk1'"));
        ExecuteStatementResponse verify = ddb.executeStatement(r -> r
                .statement("SELECT * FROM \"" + TABLE + "\" WHERE pk = 'pk1' AND sk = 'sk1'"));
        assertThat(verify.items()).isEmpty();
    }

    @Test
    @Order(11)
    void executeTransactionInsertAndDelete() {
        // Insert a fresh item, then delete it in a single transaction
        ddb.executeStatement(r -> r
                .statement("INSERT INTO \"" + TABLE + "\" VALUE {'pk': 'txpk', 'sk': 'txsk1', 'data': 'x'}"));
        ddb.executeStatement(r -> r
                .statement("INSERT INTO \"" + TABLE + "\" VALUE {'pk': 'txpk', 'sk': 'txsk2', 'data': 'y'}"));

        ExecuteTransactionResponse txResp = ddb.executeTransaction(r -> r
                .transactStatements(
                        ParameterizedStatement.builder()
                                .statement("DELETE FROM \"" + TABLE + "\" WHERE pk = 'txpk' AND sk = 'txsk1'")
                                .build(),
                        ParameterizedStatement.builder()
                                .statement("UPDATE \"" + TABLE + "\" SET data = 'z' WHERE pk = 'txpk' AND sk = 'txsk2'")
                                .build()
                ));
        assertThat(txResp.responses()).isEmpty();

        ExecuteStatementResponse verify = ddb.executeStatement(r -> r
                .statement("SELECT * FROM \"" + TABLE + "\" WHERE pk = 'txpk' AND sk = 'txsk2'"));
        assertThat(verify.items().get(0).get("data").s()).isEqualTo("z");
    }

    @Test
    @Order(12)
    void executeTransactionDuplicateInsertThrows() {
        ddb.executeStatement(r -> r
                .statement("INSERT INTO \"" + TABLE + "\" VALUE {'pk': 'conflict', 'sk': 'sk', 'v': 1}"));

        assertThatThrownBy(() -> ddb.executeTransaction(r -> r
                .transactStatements(
                        ParameterizedStatement.builder()
                                .statement("INSERT INTO \"" + TABLE + "\" VALUE {'pk': 'conflict', 'sk': 'sk', 'v': 2}")
                                .build()
                )))
                .isInstanceOf(TransactionCanceledException.class);
    }

    @Test
    @Order(13)
    void batchExecuteStatementMixedResults() {
        ddb.executeStatement(r -> r
                .statement("INSERT INTO \"" + TABLE + "\" VALUE {'pk': 'batchpk', 'sk': 'bsk1', 'hit': 1}"));

        BatchExecuteStatementResponse resp = ddb.batchExecuteStatement(r -> r
                .statements(
                        BatchStatementRequest.builder()
                                .statement("SELECT * FROM \"" + TABLE + "\" WHERE pk = 'batchpk' AND sk = 'bsk1'")
                                .build(),
                        BatchStatementRequest.builder()
                                .statement("SELECT * FROM \"" + TABLE + "\" WHERE pk = 'batchpk' AND sk = 'bsk-missing'")
                                .build()
                ));
        assertThat(resp.responses()).hasSize(2);
        assertThat(resp.responses().get(0).item()).containsKey("hit");
        assertThat(resp.responses().get(1).item()).isEmpty();
    }

    @Test
    @Order(14)
    void executeStatementOnGsiRejectsConsistentRead() {
        assertThatThrownBy(() -> ddb.executeStatement(r -> r
                .statement("SELECT * FROM \"" + INDEX_TABLE + "\".\"status-index\"")
                .consistentRead(true)))
                .isInstanceOf(DynamoDbException.class)
                .hasMessageContaining("Strongly consistent read is not supported on Global Secondary Indexes");
    }

    @Test
    @Order(15)
    void executeStatementReadsThroughGsi() {
        // Equality on the index partition key routes through an index query.
        ExecuteStatementResponse resp = ddb.executeStatement(r -> r
                .statement("SELECT * FROM \"" + INDEX_TABLE + "\".\"status-index\" WHERE status = 'active'"));
        assertThat(resp.items()).hasSize(2);

        // Without equality on the index partition key, the statement performs
        // a full index scan and applies the remaining conditions as a filter.
        ExecuteStatementResponse filtered = ddb.executeStatement(r -> r
                .statement("SELECT * FROM \"" + INDEX_TABLE + "\".\"status-index\" WHERE sk = 's2'"));
        assertThat(filtered.items()).hasSize(1);
        assertThat(filtered.items().get(0).get("sk").s()).isEqualTo("s2");
    }

    @Test
    @Order(16)
    void executeStatementOnLsiAcceptsConsistentRead() {
        ExecuteStatementResponse resp = ddb.executeStatement(r -> r
                .statement("SELECT * FROM \"" + INDEX_TABLE + "\".\"alternate-index\" WHERE pk = 'idxpk'")
                .consistentRead(true));
        assertThat(resp.items()).hasSize(2);
    }

    @Test
    @Order(17)
    void executeStatementOnUnknownIndexFailsWithoutIndexName() {
        // ExecuteStatement omits the index name from this message, unlike
        // Query/Scan (characterised on real AWS, eu-west-1, 2026-09-02).
        assertThatThrownBy(() -> ddb.executeStatement(r -> r
                .statement("SELECT * FROM \"" + INDEX_TABLE + "\".\"no-such-index\"")))
                .isInstanceOfSatisfying(DynamoDbException.class, e -> {
                    assertThat(e.awsErrorDetails().errorCode()).isEqualTo("ValidationException");
                    // ExecuteStatement omits the index name from this message,
                    // unlike Query/Scan (characterised on real AWS, eu-west-1,
                    // 2026-09-02).
                    assertThat(e.awsErrorDetails().errorMessage())
                            .isEqualTo("The table does not have the specified index");
                });
    }

    @Test
    @Order(18)
    void batchExecuteStatementRejectsIndexQualifiedSelectPerSlot() {
        BatchExecuteStatementResponse resp = ddb.batchExecuteStatement(r -> r
                .statements(BatchStatementRequest.builder()
                        .statement("SELECT * FROM \"" + INDEX_TABLE + "\".\"status-index\" WHERE status = 'active'")
                        .build()));
        assertThat(resp.responses()).hasSize(1);
        assertThat(resp.responses().get(0).error().codeAsString()).isEqualTo("ValidationError");
        assertThat(resp.responses().get(0).error().message()).isEqualTo(
                "Select statements within BatchExecuteStatement must specify the primary key in the where clause.");
    }

    @Test
    @Order(19)
    void executeStatementRejectsForeignNextToken() {
        ExecuteStatementResponse page = ddb.executeStatement(r -> r
                .statement("SELECT * FROM \"" + INDEX_TABLE + "\" WHERE pk = 'idxpk'")
                .limit(1));
        assertThat(page.nextToken()).isNotBlank();

        // Characterised on real AWS (eu-west-1, 2026-09-03): the token is bound
        // to the issuing statement, so replaying it on another statement or
        // access path is rejected even when the target key schema matches.
        assertThatThrownBy(() -> ddb.executeStatement(r -> r
                .statement("SELECT * FROM \"" + INDEX_TABLE + "\".\"status-index\" WHERE status = 'active'")
                .limit(1)
                .nextToken(page.nextToken())))
                .isInstanceOfSatisfying(DynamoDbException.class, e -> {
                    assertThat(e.awsErrorDetails().errorCode()).isEqualTo("ValidationException");
                    assertThat(e.awsErrorDetails().errorMessage())
                            .isEqualTo("NextToken does not match request");
                });
    }
}
