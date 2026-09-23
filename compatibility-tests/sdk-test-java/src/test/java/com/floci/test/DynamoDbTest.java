package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateReplicationGroupMemberAction;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.CreateTableResponse;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteReplicationGroupMemberAction;
import software.amazon.awssdk.services.dynamodb.model.DeleteRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.DescribeTimeToLiveRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTimeToLiveResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.IndexStatus;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ListTablesResponse;
import software.amazon.awssdk.services.dynamodb.model.ListTagsOfResourceRequest;
import software.amazon.awssdk.services.dynamodb.model.ListTagsOfResourceResponse;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ReplicationGroupUpdate;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.SearchResultItem;
import software.amazon.awssdk.services.dynamodb.model.SearchVectorsRequest;
import software.amazon.awssdk.services.dynamodb.model.SearchVectorsResponse;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;
import software.amazon.awssdk.services.dynamodb.model.TagResourceRequest;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveStatus;
import software.amazon.awssdk.services.dynamodb.model.UntagResourceRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateReplicationGroupMemberAction;
import software.amazon.awssdk.services.dynamodb.model.UpdateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateTableResponse;
import software.amazon.awssdk.services.dynamodb.model.VectorAttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.VectorDistanceFunction;
import software.amazon.awssdk.services.dynamodb.model.VectorIndex;
import software.amazon.awssdk.services.dynamodb.model.VectorIndexDescription;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("DynamoDB")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbTest {

    private static DynamoDbClient ddb;
    private static final String TABLE_NAME = "sdk-test-table";
    private static final String VECTOR_TABLE_NAME = "sdk-test-vector-table";
    private static final String VECTOR_INDEX_NAME = "embedding-index";
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
            // Nothing to delete when searchVectors did not get as far as creating it.
            try {
                ddb.deleteTable(DeleteTableRequest.builder().tableName(VECTOR_TABLE_NAME).build());
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
    void updateTableReplicaLifecycle() {
        String replicaRegion = "us-west-2";

        UpdateTableResponse added = ddb.updateTable(UpdateTableRequest.builder()
                .tableName(TABLE_NAME)
                .replicaUpdates(ReplicationGroupUpdate.builder()
                        .create(CreateReplicationGroupMemberAction.builder().regionName(replicaRegion).build())
                        .build())
                .build());

        assertThat(added.tableDescription().replicas())
                .anyMatch(replica -> replicaRegion.equals(replica.regionName())
                        && "ACTIVE".equals(replica.replicaStatusAsString()));
        assertThat(ddb.describeTable(DescribeTableRequest.builder().tableName(TABLE_NAME).build())
                .table().replicas())
                .anyMatch(replica -> replicaRegion.equals(replica.regionName()));

        UpdateTableResponse updated = ddb.updateTable(UpdateTableRequest.builder()
                .tableName(TABLE_NAME)
                .replicaUpdates(ReplicationGroupUpdate.builder()
                        .update(UpdateReplicationGroupMemberAction.builder().regionName(replicaRegion).build())
                        .build())
                .build());

        assertThat(updated.tableDescription().replicas())
                .anyMatch(replica -> replicaRegion.equals(replica.regionName())
                        && "ACTIVE".equals(replica.replicaStatusAsString()));

        UpdateTableResponse removed = ddb.updateTable(UpdateTableRequest.builder()
                .tableName(TABLE_NAME)
                .replicaUpdates(ReplicationGroupUpdate.builder()
                        .delete(DeleteReplicationGroupMemberAction.builder().regionName(replicaRegion).build())
                        .build())
                .build());

        assertThat(removed.tableDescription().replicas())
                .noneMatch(replica -> replicaRegion.equals(replica.regionName()));
    }

    @Test
    @Order(13)
    void describeTimeToLive() {
        DescribeTimeToLiveResponse response = ddb.describeTimeToLive(
                DescribeTimeToLiveRequest.builder().tableName(TABLE_NAME).build());

        assertThat(response.timeToLiveDescription().timeToLiveStatus())
                .isEqualTo(TimeToLiveStatus.DISABLED);
    }

    @Test
    @Order(14)
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
    @Order(15)
    void listTagsOfResource() {
        Assumptions.assumeTrue(tableArn != null);

        ListTagsOfResourceResponse response = ddb.listTagsOfResource(
                ListTagsOfResourceRequest.builder().resourceArn(tableArn).build());

        assertThat(response.tags())
                .anyMatch(t -> "env".equals(t.key()) && "test".equals(t.value()))
                .anyMatch(t -> "team".equals(t.key()) && "backend".equals(t.value()));
    }

    @Test
    @Order(16)
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
    @Order(17)
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
    @Order(18)
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
    @Order(19)
    void deleteTable() {
        ddb.deleteTable(DeleteTableRequest.builder().tableName(TABLE_NAME).build());

        ListTablesResponse response = ddb.listTables();
        assertThat(response.tableNames()).doesNotContain(TABLE_NAME);
    }

    @Test
    @Order(20)
    void searchVectors() {
        ddb.createTable(CreateTableRequest.builder()
                .tableName(VECTOR_TABLE_NAME)
                .keySchema(KeySchemaElement.builder()
                        .attributeName("docId").keyType(KeyType.HASH).build())
                .attributeDefinitions(AttributeDefinition.builder()
                        .attributeName("docId").attributeType(ScalarAttributeType.S).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .vectorIndexes(VectorIndex.builder()
                        .indexName(VECTOR_INDEX_NAME)
                        .vectorAttribute(VectorAttributeDefinition.builder()
                                .attributeName("embedding").build())
                        .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                        .dimensions(3L)
                        .distanceFunction(VectorDistanceFunction.COSINE)
                        .build())
                .build());

        DescribeTableResponse described = ddb.describeTable(
                DescribeTableRequest.builder().tableName(VECTOR_TABLE_NAME).build());
        VectorIndexDescription index = described.table().vectorIndexes().get(0);
        assertThat(index.indexName()).isEqualTo(VECTOR_INDEX_NAME);
        assertThat(index.dimensions()).isEqualTo(3L);
        assertThat(index.distanceFunction()).isEqualTo(VectorDistanceFunction.COSINE);
        assertThat(index.indexStatus()).isEqualTo(IndexStatus.ACTIVE);
        assertThat(index.backfilling()).isNull();

        putVector("near", "1", "0", "0");
        putVector("mid", "0.6", "0.8", "0");
        putVector("far", "-1", "0", "0");

        SearchVectorsResponse ranked = ddb.searchVectors(SearchVectorsRequest.builder()
                .tableName(VECTOR_TABLE_NAME)
                .indexName(VECTOR_INDEX_NAME)
                .searchVector(number("1"), number("0"), number("0"))
                .topK(3)
                .build());

        assertThat(ranked.searchResults())
                .extracting(result -> result.item().get("docId").s())
                .containsExactly("near", "mid", "far");
        assertThat(ranked.searchResults())
                .extracting(SearchResultItem::score)
                .containsExactly(0.0, 0.3999999761581421, 2.0);
        assertThat(ranked.searchResults().get(0).item())
                .containsKey("title")
                .doesNotContainKey("embedding");

        SearchVectorsResponse projected = ddb.searchVectors(SearchVectorsRequest.builder()
                .tableName(VECTOR_TABLE_NAME)
                .indexName(VECTOR_INDEX_NAME)
                .searchVector(number("1"), number("0"), number("0"))
                .topK(1)
                .projectionExpression("docId, embedding")
                .build());

        Map<String, AttributeValue> item = projected.searchResults().get(0).item();
        assertThat(item).containsOnlyKeys("docId", "embedding");
        // The index serves its own 32 bit copy, so "1" comes back as "1.0".
        assertThat(item.get("embedding").l())
                .extracting(AttributeValue::n)
                .containsExactly("1.0", "0.0", "0.0");
    }

    private static void putVector(String docId, String x, String y, String z) {
        ddb.putItem(PutItemRequest.builder()
                .tableName(VECTOR_TABLE_NAME)
                .item(Map.of(
                        "docId", AttributeValue.builder().s(docId).build(),
                        "title", AttributeValue.builder().s("title " + docId).build(),
                        "embedding", AttributeValue.builder()
                                .l(number(x), number(y), number(z)).build()
                ))
                .build());
    }

    private static AttributeValue number(String value) {
        return AttributeValue.builder().n(value).build();
    }
}
