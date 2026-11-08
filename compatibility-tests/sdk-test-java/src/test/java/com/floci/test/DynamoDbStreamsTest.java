package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeStreamRequest;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsRequest;
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Record;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.Shard;
import software.amazon.awssdk.services.dynamodb.model.ShardIteratorType;
import software.amazon.awssdk.services.dynamodb.model.StreamSpecification;
import software.amazon.awssdk.services.dynamodb.model.StreamViewType;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads stream records through the SDK's DynamoDB Streams client, whose JSON unmarshaller matches the
 * Streams API's capitalised members exactly.
 */
@DisplayName("DynamoDB Streams")
class DynamoDbStreamsTest {

    private static final String TABLE_NAME = TestFixtures.uniqueName("sdk-stream");

    private static DynamoDbClient ddb;
    private static DynamoDbStreamsClient streams;
    private static String streamArn;

    @BeforeAll
    static void setup() {
        ddb = TestFixtures.dynamoDbClient();
        streams = TestFixtures.dynamoDbStreamsClient();
        streamArn = ddb.createTable(CreateTableRequest.builder()
                .tableName(TABLE_NAME)
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
                .attributeDefinitions(AttributeDefinition.builder()
                        .attributeName("pk").attributeType(ScalarAttributeType.S).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .streamSpecification(StreamSpecification.builder()
                        .streamEnabled(true).streamViewType(StreamViewType.NEW_AND_OLD_IMAGES).build())
                .build())
                .tableDescription().latestStreamArn();
        ddb.putItem(PutItemRequest.builder().tableName(TABLE_NAME)
                .item(Map.of("pk", AttributeValue.fromS("deleted")))
                .build());
        ddb.deleteItem(DeleteItemRequest.builder().tableName(TABLE_NAME)
                .key(Map.of("pk", AttributeValue.fromS("deleted")))
                .build());
    }

    @AfterAll
    static void cleanup() {
        if (ddb != null) {
            try {
                ddb.deleteTable(DeleteTableRequest.builder().tableName(TABLE_NAME).build());
            } catch (ResourceNotFoundException ignored) {
                // The table is gone already when setup failed before creating it.
            }
            ddb.close();
        }
        if (streams != null) {
            streams.close();
        }
    }

    @Test
    @DisplayName("GetRecords returns a DeleteItem removal with no identity")
    void deleteItemRemovalCarriesNoIdentity() {
        Optional<Record> removal = findRemovalOf("deleted");

        assertThat(removal).hasValueSatisfying(record -> assertThat(record.userIdentity()).isNull());
    }

    private static Optional<Record> findRemovalOf(String pk) {
        for (Shard shard : streams.describeStream(DescribeStreamRequest.builder().streamArn(streamArn).build())
                .streamDescription().shards()) {
            String iterator = streams.getShardIterator(GetShardIteratorRequest.builder()
                    .streamArn(streamArn)
                    .shardId(shard.shardId())
                    .shardIteratorType(ShardIteratorType.TRIM_HORIZON)
                    .build()).shardIterator();
            for (Record record : streams.getRecords(GetRecordsRequest.builder().shardIterator(iterator).build())
                    .records()) {
                if ("REMOVE".equals(record.eventNameAsString())
                        && pk.equals(record.dynamodb().keys().get("pk").s())) {
                    return Optional.of(record);
                }
            }
        }
        return Optional.empty();
    }
}
