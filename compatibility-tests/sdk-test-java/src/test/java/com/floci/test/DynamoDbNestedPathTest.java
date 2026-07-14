package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for issue #1756: DynamoDB UpdateItem ADD and DELETE on a nested map path with ExpressionAttributeNames.
 */
@DisplayName("DynamoDB UpdateItem ADD/DELETE on nested map path (#1756)")
class DynamoDbNestedPathTest {

    private static DynamoDbClient ddb;
    private static final String TABLE = "nested-path-repro-" + java.util.UUID.randomUUID().toString().substring(0, 8);

    private static final String TOP_LEVEL = "functionInvocationSummaries";
    private static final String ISA_KEY = "ISA_10136";

    @BeforeAll
    static void setup() {
        ddb = TestFixtures.dynamoDbClient();

        ddb.createTable(r -> r
                .tableName(TABLE)
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
                .attributeDefinitions(
                        AttributeDefinition.builder()
                                .attributeName("pk")
                                .attributeType(ScalarAttributeType.S)
                                .build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
        );
    }

    @AfterAll
    static void cleanup() {
        if (ddb != null) {
            try {
                ddb.deleteTable(DeleteTableRequest.builder().tableName(TABLE).build());
            } catch (Exception ignored) {
            }
            ddb.close();
        }
    }

    @Test
    @DisplayName("ADD on nested placeholder path increments nested counter")
    void addOnNestedPlaceholderPath() {
        String pk = "add";

        ddb.updateItem(
                UpdateItemRequest.builder()
                        .tableName(TABLE)
                        .key(Map.of("pk", AttributeValue.fromS(pk)))
                        .updateExpression("SET #fis = if_not_exists(#fis, :empty)")
                        .expressionAttributeNames(Map.of("#fis", TOP_LEVEL))
                        .expressionAttributeValues(Map.of(":empty", AttributeValue.builder().m(Map.of()).build()))
                        .build()
        );

        ddb.updateItem(
                UpdateItemRequest.builder()
                        .tableName(TABLE)
                        .key(Map.of("pk", AttributeValue.fromS(pk)))
                        .updateExpression("SET #fis.#isa = if_not_exists(#fis.#isa, :seed)")
                        .expressionAttributeNames(Map.of("#fis", TOP_LEVEL, "#isa", ISA_KEY))
                        .expressionAttributeValues(Map.of(
                                ":seed", AttributeValue.builder().m(Map.of("eventsAvailable", AttributeValue.fromN("0"))).build()))
                        .build()
        );

        ddb.updateItem(
                UpdateItemRequest.builder()
                        .tableName(TABLE)
                        .key(Map.of("pk", AttributeValue.fromS(pk)))
                        .updateExpression("ADD #fis.#isa.#ea :one")
                        .expressionAttributeNames(Map.of(
                                "#fis", TOP_LEVEL,
                                "#isa", ISA_KEY,
                                "#ea", "eventsAvailable"))
                        .expressionAttributeValues(Map.of(":one", AttributeValue.fromN("1")))
                        .build()
        );

        GetItemResponse resp = ddb.getItem(r -> r
                .tableName(TABLE)
                .key(Map.of("pk", AttributeValue.fromS(pk)))
                .consistentRead(true)
        );

        String counter = resp.item().get(TOP_LEVEL).m().get(ISA_KEY).m().get("eventsAvailable").n();
        assertThat(counter).isEqualTo("1");
        assertThat(resp.item()).doesNotContainKey("#fis.#isa.#ea");
    }

    @Test
    @DisplayName("DELETE on nested placeholder path removes elements from nested set")
    void deleteOnNestedPlaceholderPath() {
        String pk = "del";

        AttributeValue isaMap = AttributeValue.builder()
                .m(Map.of("tags", AttributeValue.builder().ss(List.of("a", "b", "c")).build()))
                .build();
        AttributeValue topLevelMap = AttributeValue.builder()
                .m(Map.of(ISA_KEY, isaMap))
                .build();

        ddb.putItem(
                PutItemRequest.builder()
                        .tableName(TABLE)
                        .item(Map.of("pk", AttributeValue.fromS(pk), TOP_LEVEL, topLevelMap))
                        .build()
        );

        ddb.updateItem(
                UpdateItemRequest.builder()
                        .tableName(TABLE)
                        .key(Map.of("pk", AttributeValue.fromS(pk)))
                        .updateExpression("DELETE #fis.#isa.#tags :val")
                        .expressionAttributeNames(Map.of(
                                "#fis", TOP_LEVEL,
                                "#isa", ISA_KEY,
                                "#tags", "tags"))
                        .expressionAttributeValues(Map.of(":val", AttributeValue.builder().ss(List.of("b")).build()))
                        .build()
        );

        GetItemResponse resp = ddb.getItem(
                r -> r
                        .tableName(TABLE)
                        .key(Map.of("pk", AttributeValue.fromS(pk)))
                        .consistentRead(true)
        );

        List<String> tags = resp.item().get(TOP_LEVEL).m().get(ISA_KEY).m().get("tags").ss();
        assertThat(tags).containsExactlyInAnyOrder("a", "c");
    }
}
