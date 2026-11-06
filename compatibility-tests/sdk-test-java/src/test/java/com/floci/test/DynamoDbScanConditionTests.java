package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("DynamoDB Scan Condition Filters")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbScanConditionTests {

    private static DynamoDbClient ddb;
    private static final String TABLE_NAME = "scan-condition-test";

    @BeforeAll
    static void setup() {
        ddb = TestFixtures.dynamoDbClient();

        ddb.createTable(CreateTableRequest.builder()
                .tableName(TABLE_NAME)
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
                .attributeDefinitions(AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build());

        for (int i = 1; i <= 5; i++) {
            ddb.putItem(PutItemRequest.builder().tableName(TABLE_NAME).item(Map.of(
                    "pk", AttributeValue.fromS("item-" + i),
                    "score", AttributeValue.fromN(String.valueOf(i * 10)),
                    "name", AttributeValue.fromS("name-" + i)
            )).build());
        }
    }

    @AfterAll
    static void cleanup() {
        if (ddb != null) {
            try {
                ddb.deleteTable(DeleteTableRequest.builder().tableName(TABLE_NAME).build());
            } catch (Exception ignored) {}
            ddb.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Scan with EQ filter")
    void scanFilterEq() {
        ScanResponse resp = ddb.scan(ScanRequest.builder().tableName(TABLE_NAME)
                .scanFilter(Map.of("score", Condition.builder()
                        .comparisonOperator(ComparisonOperator.EQ)
                        .attributeValueList(AttributeValue.fromN("30"))
                        .build()))
                .build());

        assertThat(resp.count()).isEqualTo(1);
    }

    @Test
    @Order(2)
    @DisplayName("Scan with GT filter")
    void scanFilterGt() {
        ScanResponse resp = ddb.scan(ScanRequest.builder().tableName(TABLE_NAME)
                .scanFilter(Map.of("score", Condition.builder()
                        .comparisonOperator(ComparisonOperator.GT)
                        .attributeValueList(AttributeValue.fromN("30"))
                        .build()))
                .build());

        assertThat(resp.count()).isEqualTo(2);
    }

    @Test
    @Order(3)
    @DisplayName("Scan with LE filter")
    void scanFilterLe() {
        ScanResponse resp = ddb.scan(ScanRequest.builder().tableName(TABLE_NAME)
                .scanFilter(Map.of("score", Condition.builder()
                        .comparisonOperator(ComparisonOperator.LE)
                        .attributeValueList(AttributeValue.fromN("30"))
                        .build()))
                .build());

        assertThat(resp.count()).isEqualTo(3);
    }

    @Test
    @Order(4)
    @DisplayName("Scan with BEGINS_WITH filter")
    void scanFilterBeginsWith() {
        ScanResponse resp = ddb.scan(ScanRequest.builder().tableName(TABLE_NAME)
                .scanFilter(Map.of("name", Condition.builder()
                        .comparisonOperator(ComparisonOperator.BEGINS_WITH)
                        .attributeValueList(AttributeValue.fromS("name-"))
                        .build()))
                .build());

        assertThat(resp.count()).isEqualTo(5);
    }

    @Test
    @Order(5)
    @DisplayName("Scan with BETWEEN filter")
    void scanFilterBetween() {
        ScanResponse resp = ddb.scan(ScanRequest.builder().tableName(TABLE_NAME)
                .scanFilter(Map.of("score", Condition.builder()
                        .comparisonOperator(ComparisonOperator.BETWEEN)
                        .attributeValueList(AttributeValue.fromN("20"), AttributeValue.fromN("40"))
                        .build()))
                .build());

        assertThat(resp.count()).isEqualTo(3);
    }

    @Test
    @Order(6)
    @DisplayName("Scan with multiple conditions (AND)")
    void scanFilterMultipleConditions() {
        ScanResponse resp = ddb.scan(ScanRequest.builder().tableName(TABLE_NAME)
                .scanFilter(Map.of(
                        "score", Condition.builder()
                                .comparisonOperator(ComparisonOperator.GE)
                                .attributeValueList(AttributeValue.fromN("30"))
                                .build(),
                        "name", Condition.builder()
                                .comparisonOperator(ComparisonOperator.EQ)
                                .attributeValueList(AttributeValue.fromS("name-3"))
                                .build()))
                .build());

        assertThat(resp.count()).isEqualTo(1);
    }
}
