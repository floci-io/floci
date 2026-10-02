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
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Reads time to live deletions through the SDK's DynamoDB Streams client, whose JSON unmarshaller
 * matches the Streams API's capitalised {@code Identity} members exactly.
 */
@DisplayName("DynamoDB Streams time to live")
class DynamoDbStreamsTtlTest {

    // Floci sweeps expired items every 60 seconds, the first sweep 60 seconds after it starts.
    private static final Duration SWEEP_WAIT = Duration.ofSeconds(150);
    private static final String TABLE_NAME = TestFixtures.uniqueName("sdk-ttl-stream");

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
        ddb.updateTimeToLive(UpdateTimeToLiveRequest.builder()
                .tableName(TABLE_NAME)
                .timeToLiveSpecification(TimeToLiveSpecification.builder()
                        .enabled(true).attributeName("expireAt").build())
                .build());
        String past = Long.toString(Instant.now().getEpochSecond() - 3600);
        ddb.putItem(PutItemRequest.builder().tableName(TABLE_NAME)
                .item(Map.of("pk", AttributeValue.fromS("expired"), "expireAt", AttributeValue.fromN(past)))
                .build());
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
    @DisplayName("GetRecords returns a time to live deletion as a REMOVE by the DynamoDB service")
    void ttlDeletionCarriesTheServiceIdentity() throws InterruptedException {
        Record removal = awaitRemovalOf("expired");

        assertThat(removal.userIdentity()).isNotNull();
        assertThat(removal.userIdentity().type()).isEqualTo("Service");
        assertThat(removal.userIdentity().principalId()).isEqualTo("dynamodb.amazonaws.com");
    }

    @Test
    @DisplayName("GetRecords returns a DeleteItem removal with no identity")
    void deleteItemRemovalCarriesNoIdentity() throws InterruptedException {
        Record removal = awaitRemovalOf("deleted");

        assertThat(removal.userIdentity()).isNull();
    }

    private static Record awaitRemovalOf(String pk) throws InterruptedException {
        Instant deadline = Instant.now().plus(SWEEP_WAIT);
        while (Instant.now().isBefore(deadline)) {
            Optional<Record> removal = findRemovalOf(pk);
            if (removal.isPresent()) {
                return removal.get();
            }
            Thread.sleep(1000);
        }
        return fail("no REMOVE record for " + pk + " within " + SWEEP_WAIT);
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
