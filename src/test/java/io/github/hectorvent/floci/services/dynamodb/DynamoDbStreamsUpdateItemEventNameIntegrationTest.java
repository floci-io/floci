package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Record;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.Shard;
import software.amazon.awssdk.services.dynamodb.model.ShardIteratorType;
import software.amazon.awssdk.services.dynamodb.model.StreamViewType;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class DynamoDbStreamsUpdateItemEventNameIntegrationTest {

    private static final String SET_NAME = "SET #name = :name";
    private static final Map<String, String> NAME = Map.of("#name", "name");

    private DynamoDbClient ddb;
    private DynamoDbStreamsClient streams;
    private String tableName;
    private String streamArn;

    @BeforeEach
    void createTableWithStream() {
        ddb = AwsTestClients.create(DynamoDbClient.builder(), "test", Region.US_EAST_1);
        streams = AwsTestClients.create(DynamoDbStreamsClient.builder(), "test", Region.US_EAST_1);
        tableName = "StreamEventName-" + UUID.randomUUID();
        streamArn = ddb.createTable(b -> b.tableName(tableName)
                        .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
                        .attributeDefinitions(AttributeDefinition.builder()
                                .attributeName("pk").attributeType(ScalarAttributeType.S).build())
                        .billingMode(BillingMode.PAY_PER_REQUEST)
                        .streamSpecification(s -> s.streamEnabled(true)
                                .streamViewType(StreamViewType.NEW_AND_OLD_IMAGES)))
                .tableDescription().latestStreamArn();
    }

    @AfterEach
    void deleteTableAndCloseClients() {
        try (DynamoDbStreamsClient streamsClient = streams; DynamoDbClient ddbClient = ddb) {
            ddbClient.deleteTable(b -> b.tableName(tableName));
        }
    }

    @Test
    void updateItemThatCreatesTheItemIsAnInsertAndASecondUpdateIsAModify() {
        update("vehicle-1", "added");
        update("vehicle-1", "renamed");

        List<Record> records = recordsFor("vehicle-1");

        assertEquals(List.of("INSERT", "MODIFY"), records.stream().map(Record::eventNameAsString).toList());
        Record insert = records.get(0);
        assertFalse(insert.dynamodb().hasOldImage(), insert.toString());
        assertEquals("added", insert.dynamodb().newImage().get("name").s());
        Record modify = records.get(1);
        assertEquals("added", modify.dynamodb().oldImage().get("name").s());
        assertEquals("renamed", modify.dynamodb().newImage().get("name").s());
    }

    @Test
    void transactionalUpdateThatCreatesTheItemIsAnInsert() {
        ddb.transactWriteItems(b -> b.transactItems(TransactWriteItem.builder()
                .update(u -> u.tableName(tableName)
                        .key(key("vehicle-2"))
                        .updateExpression(SET_NAME)
                        .expressionAttributeNames(NAME)
                        .expressionAttributeValues(nameValue("added")))
                .build()));

        List<Record> records = recordsFor("vehicle-2");

        assertEquals(List.of("INSERT"), records.stream().map(Record::eventNameAsString).toList());
        assertFalse(records.get(0).dynamodb().hasOldImage(), records.get(0).toString());
    }

    private void update(String pk, String name) {
        ddb.updateItem(b -> b.tableName(tableName)
                .key(key(pk))
                .updateExpression(SET_NAME)
                .expressionAttributeNames(NAME)
                .expressionAttributeValues(nameValue(name)));
    }

    private static Map<String, AttributeValue> key(String pk) {
        return Map.of("pk", AttributeValue.fromS(pk));
    }

    private static Map<String, AttributeValue> nameValue(String name) {
        return Map.of(":name", AttributeValue.fromS(name));
    }

    private List<Record> recordsFor(String pk) {
        List<Record> matching = new ArrayList<>();
        for (Shard shard : streams.describeStream(b -> b.streamArn(streamArn)).streamDescription().shards()) {
            String iterator = streams.getShardIterator(b -> b.streamArn(streamArn)
                    .shardId(shard.shardId())
                    .shardIteratorType(ShardIteratorType.TRIM_HORIZON)).shardIterator();
            for (Record record : streams.getRecords(b -> b.shardIterator(iterator)).records()) {
                if (pk.equals(record.dynamodb().keys().get("pk").s())) {
                    matching.add(record);
                }
            }
        }
        return matching;
    }
}
