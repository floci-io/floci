package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.CreateTableResponse;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.DescribeTimeToLiveRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTimeToLiveResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ListTablesResponse;
import software.amazon.awssdk.services.dynamodb.model.ListTagsOfResourceRequest;
import software.amazon.awssdk.services.dynamodb.model.ListTagsOfResourceResponse;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;
import software.amazon.awssdk.services.dynamodb.model.TagResourceRequest;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveStatus;
import software.amazon.awssdk.services.dynamodb.model.UntagResourceRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateTableResponse;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("DynamoDB")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbTest {

    private static DynamoDbClient ddb;
    private static final String TABLE_NAME = "sdk-test-table";
    private static String tableArn;

    @BeforeAll
    static void setup() {
        ddb = TestFixtures.dynamoDbClient();
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
    void createTable() {
        CreateTableResponse response = ddb.createTable(CreateTableRequest.builder()
                .tableName(TABLE_NAME)
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build()
                )
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build()
                )
                .provisionedThroughput(ProvisionedThroughput.builder()
                        .readCapacityUnits(5L).writeCapacityUnits(5L).build())
                .build());

        tableArn = response.tableDescription().tableArn();
        assertThat(response.tableDescription().tableStatus()).isEqualTo(TableStatus.ACTIVE);
    }

    @Test
    @Order(2)
    void describeTable() {
        DescribeTableResponse response = ddb.describeTable(
                DescribeTableRequest.builder().tableName(TABLE_NAME).build());

        assertThat(response.table().tableName()).isEqualTo(TABLE_NAME);
    }

    @Test
    @Order(3)
    void listTables() {
        ListTablesResponse response = ddb.listTables();

        assertThat(response.tableNames()).contains(TABLE_NAME);
    }

    @Test
    @Order(4)
    void putItem() {
        for (int i = 1; i <= 3; i++) {
            ddb.putItem(PutItemRequest.builder()
                    .tableName(TABLE_NAME)
                    .item(Map.of(
                            "pk", AttributeValue.builder().s("user-1").build(),
                            "sk", AttributeValue.builder().s("item-" + i).build(),
                            "data", AttributeValue.builder().s("value-" + i).build()
                    ))
                    .build());
        }
        ddb.putItem(PutItemRequest.builder()
                .tableName(TABLE_NAME)
                .item(Map.of(
                        "pk", AttributeValue.builder().s("user-2").build(),
                        "sk", AttributeValue.builder().s("item-1").build(),
                        "data", AttributeValue.builder().s("other-value").build()
                ))
                .build());
    }

    @Test
    @Order(5)
    void getItem() {
        GetItemResponse response = ddb.getItem(GetItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(Map.of(
                        "pk", AttributeValue.builder().s("user-1").build(),
                        "sk", AttributeValue.builder().s("item-2").build()
                ))
                .build());

        assertThat(response.hasItem()).isTrue();
        assertThat(response.item().get("data").s()).isEqualTo("value-2");
    }

    @Test
    @Order(6)
    void updateItem() {
        UpdateItemResponse response = ddb.updateItem(UpdateItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(Map.of(
                        "pk", AttributeValue.builder().s("user-1").build(),
                        "sk", AttributeValue.builder().s("item-1").build()
                ))
                .updateExpression("SET #d = :newVal")
                .expressionAttributeNames(Map.of("#d", "data"))
                .expressionAttributeValues(Map.of(
                        ":newVal", AttributeValue.builder().s("updated-value").build()
                ))
                .returnValues(ReturnValue.ALL_NEW)
                .build());

        assertThat(response.attributes().get("data").s()).isEqualTo("updated-value");
    }

    @Test
    @Order(7)
    void query() {
        QueryResponse response = ddb.query(QueryRequest.builder()
                .tableName(TABLE_NAME)
                .keyConditionExpression("pk = :pk")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.builder().s("user-1").build()
                ))
                .build());

        assertThat(response.count()).isEqualTo(3);
    }

    @Test
    @Order(8)
    void scan() {
        ScanResponse response = ddb.scan(ScanRequest.builder()
                .tableName(TABLE_NAME).build());

        assertThat(response.count()).isEqualTo(4);
    }

    @Test
    @Order(9)
    void batchWriteItem() {
        ddb.batchWriteItem(BatchWriteItemRequest.builder()
                .requestItems(Map.of(TABLE_NAME, List.of(
                        WriteRequest.builder().putRequest(PutRequest.builder()
                                .item(Map.of(
                                        "pk", AttributeValue.builder().s("user-3").build(),
                                        "sk", AttributeValue.builder().s("item-1").build(),
                                        "data", AttributeValue.builder().s("batch-value-1").build()
                                )).build()).build(),
                        WriteRequest.builder().putRequest(PutRequest.builder()
                                .item(Map.of(
                                        "pk", AttributeValue.builder().s("user-3").build(),
                                        "sk", AttributeValue.builder().s("item-2").build(),
                                        "data", AttributeValue.builder().s("batch-value-2").build()
                                )).build()).build()
                )))
                .build());

        ScanResponse scanResponse = ddb.scan(ScanRequest.builder().tableName(TABLE_NAME).build());
        assertThat(scanResponse.count()).isEqualTo(6);
    }

    @Test
    @Order(10)
    void batchGetItem() {
        BatchGetItemResponse response = ddb.batchGetItem(BatchGetItemRequest.builder()
                .requestItems(Map.of(TABLE_NAME, KeysAndAttributes.builder()
                        .keys(List.of(
                                Map.of(
                                        "pk", AttributeValue.builder().s("user-1").build(),
                                        "sk", AttributeValue.builder().s("item-1").build()
                                ),
                                Map.of(
                                        "pk", AttributeValue.builder().s("user-3").build(),
                                        "sk", AttributeValue.builder().s("item-2").build()
                                )
                        ))
                        .build()))
                .build());

        assertThat(response.responses().get(TABLE_NAME)).hasSize(2);
    }

    @Test
    @Order(11)
    void updateTable() {
        UpdateTableResponse response = ddb.updateTable(UpdateTableRequest.builder()
                .tableName(TABLE_NAME)
                .provisionedThroughput(ProvisionedThroughput.builder()
                        .readCapacityUnits(10L).writeCapacityUnits(10L).build())
                .build());

        assertThat(response.tableDescription().provisionedThroughput().readCapacityUnits())
                .isEqualTo(10L);
    }

    @Test
    @Order(12)
    void describeTimeToLive() {
        DescribeTimeToLiveResponse response = ddb.describeTimeToLive(
                DescribeTimeToLiveRequest.builder().tableName(TABLE_NAME).build());

        assertThat(response.timeToLiveDescription().timeToLiveStatus())
                .isEqualTo(TimeToLiveStatus.DISABLED);
    }

    @Test
    @Order(13)
    void tagResource() {
        Assumptions.assumeTrue(tableArn != null);

        ddb.tagResource(TagResourceRequest.builder()
                .resourceArn(tableArn)
                .tags(
                        software.amazon.awssdk.services.dynamodb.model.Tag.builder().key("env").value("test").build(),
                        software.amazon.awssdk.services.dynamodb.model.Tag.builder().key("team").value("backend").build()
                )
                .build());
    }

    @Test
    @Order(14)
    void listTagsOfResource() {
        Assumptions.assumeTrue(tableArn != null);

        ListTagsOfResourceResponse response = ddb.listTagsOfResource(
                ListTagsOfResourceRequest.builder().resourceArn(tableArn).build());

        assertThat(response.tags())
                .anyMatch(t -> "env".equals(t.key()) && "test".equals(t.value()))
                .anyMatch(t -> "team".equals(t.key()) && "backend".equals(t.value()));
    }

    @Test
    @Order(15)
    void untagResource() {
        Assumptions.assumeTrue(tableArn != null);

        ddb.untagResource(UntagResourceRequest.builder()
                .resourceArn(tableArn).tagKeys("team").build());

        ListTagsOfResourceResponse response = ddb.listTagsOfResource(
                ListTagsOfResourceRequest.builder().resourceArn(tableArn).build());

        assertThat(response.tags())
                .anyMatch(t -> "env".equals(t.key()) && "test".equals(t.value()))
                .noneMatch(t -> "team".equals(t.key()));
    }

    @Test
    @Order(16)
    void batchWriteItemDelete() {
        ddb.batchWriteItem(BatchWriteItemRequest.builder()
                .requestItems(Map.of(TABLE_NAME, List.of(
                        WriteRequest.builder().deleteRequest(DeleteRequest.builder()
                                .key(Map.of(
                                        "pk", AttributeValue.builder().s("user-3").build(),
                                        "sk", AttributeValue.builder().s("item-1").build()
                                )).build()).build(),
                        WriteRequest.builder().deleteRequest(DeleteRequest.builder()
                                .key(Map.of(
                                        "pk", AttributeValue.builder().s("user-3").build(),
                                        "sk", AttributeValue.builder().s("item-2").build()
                                )).build()).build()
                )))
                .build());

        ScanResponse scanResponse = ddb.scan(ScanRequest.builder().tableName(TABLE_NAME).build());
        assertThat(scanResponse.count()).isEqualTo(4);
    }

    @Test
    @Order(17)
    void deleteItem() {
        ddb.deleteItem(DeleteItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(Map.of(
                        "pk", AttributeValue.builder().s("user-2").build(),
                        "sk", AttributeValue.builder().s("item-1").build()
                ))
                .build());
    }

    @Test
    @Order(18)
    void deleteTable() {
        ddb.deleteTable(DeleteTableRequest.builder().tableName(TABLE_NAME).build());

        ListTablesResponse response = ddb.listTables();
        assertThat(response.tableNames()).doesNotContain(TABLE_NAME);
    }
}
