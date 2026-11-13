package io.floci.aws.services.kinesis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BinaryNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.kinesis.model.KinesisShard;
import io.floci.aws.services.kinesis.model.KinesisStream;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KinesisJsonHandlerTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "123456789012";
    private static final String STREAM_ARN = "arn:aws:kinesis:us-east-1:123456789012:stream/test-stream";
    private static final String MAX_HASH_KEY_PLUS_ONE = "340282366920938463463374607431768211456";
    private static final String NON_DECIMAL_HASH_KEY = "1.5";
    private static final long NUMERIC_EXPLICIT_HASH_KEY = 12345L;
    private static final String MIN_HASH_KEY = "0";
    private static final String MAX_HASH_KEY = "340282366920938463463374607431768211455";
    private static final int TWO_SHARDS = 2;
    private static final String FIRST_SHARD_ID = "shardId-000000000000";
    private static final String SECOND_SHARD_ID = "shardId-000000000001";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KinesisService service;
    private KinesisJsonHandler handler;

    @BeforeEach
    void setUp() {
        service = new KinesisService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver(REGION, ACCOUNT)
        );
        handler = new KinesisJsonHandler(service, MAPPER, 300_000);
    }

    @Test
    void terminalGetRecordsIsJsonNullWithExactChildMetadata() throws Exception {
        createStream("test-stream");
        service.putRecord("test-stream", new byte[]{1}, "pk", REGION);
        service.splitShard("test-stream", FIRST_SHARD_ID, "100", REGION);
        String cursor = service.getShardIterator("test-stream", FIRST_SHARD_ID, "TRIM_HORIZON", null, null, REGION);
        ObjectNode request = MAPPER.createObjectNode().put("ShardIterator", cursor);
        JsonNode page = MAPPER.readTree(responseEntity(handler.handle("GetRecords", request, REGION)).toString());
        assertTrue(page.has("NextShardIterator"));
        assertTrue(page.get("NextShardIterator").isNull());
        assertEquals(1, page.path("Records").size());
        assertEquals(MAPPER.readTree("""
                [{"ShardId":"shardId-000000000001","ParentShards":["shardId-000000000000"],
                  "HashKeyRange":{"StartingHashKey":"0","EndingHashKey":"99"}},
                 {"ShardId":"shardId-000000000002","ParentShards":["shardId-000000000000"],
                  "HashKeyRange":{"StartingHashKey":"100","EndingHashKey":"340282366920938463463374607431768211455"}}]
                """), page.path("ChildShards"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void subscriptionTerminalMetadataDoesNotContinueEvenWithRecords(boolean nonempty) throws Exception {
        createStream("test-stream");
        if (nonempty) {
            service.putRecord("test-stream", new byte[]{1}, "pk", REGION);
        }
        String arn = service.registerStreamConsumer(STREAM_ARN, "terminal-consumer", REGION).getConsumerArn();
        service.splitShard("test-stream", FIRST_SHARD_ID, "100", REGION);
        ObjectNode request = MAPPER.createObjectNode().put("ConsumerARN", arn).put("ShardId", FIRST_SHARD_ID);
        request.putObject("StartingPosition").put("Type", "TRIM_HORIZON");
        byte[] wire = (byte[]) handler.handle("SubscribeToShard", request, REGION).getEntity();
        ByteBuffer buffer = ByteBuffer.wrap(wire);
        int initialLength = buffer.getInt();
        buffer.position(initialLength);
        int eventLength = buffer.getInt();
        int headerLength = buffer.getInt();
        buffer.position(initialLength + 12 + headerLength);
        byte[] payload = new byte[eventLength - headerLength - 16];
        buffer.get(payload);
        JsonNode event = MAPPER.readTree(payload);
        assertEquals(nonempty ? 1 : 0, event.path("Records").size());
        assertFalse(event.has("ContinuationSequenceNumber"));
        assertEquals(2, event.path("ChildShards").size());
        assertEquals(FIRST_SHARD_ID, event.path("ChildShards").get(0).path("ParentShards").get(0).asText());
        assertEquals("99", event.path("ChildShards").get(0).path("HashKeyRange").path("EndingHashKey").asText());
    }

    // Retention is measured against the service clock, so a test that plants a fixed arrival
    // timestamp pins the clock just after it instead of relying on the wall clock.
    private void useClockFixedAt(Instant now) {
        service = new KinesisService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver(REGION, ACCOUNT),
                Clock.fixed(now, ZoneOffset.UTC)
        );
        handler = new KinesisJsonHandler(service, MAPPER, 300_000);
    }

    private void createStream(String name) {
        createStream(name, 1);
    }

    private void createStream(String name, int shardCount) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", name);
        req.put("ShardCount", shardCount);
        assertThat(handler.handle("CreateStream", req, REGION).getStatus(), is(200));
    }

    @Test
    void registerStreamConsumerPreservesTagsForResourceOperations() {
        createStream("test-stream");
        ObjectNode registration = MAPPER.createObjectNode()
                .put("StreamARN", STREAM_ARN)
                .put("ConsumerName", "tagged-consumer");
        registration.putObject("Tags").put("team", "red");
        String consumerArn = responseEntity(handler.handle("RegisterStreamConsumer", registration, REGION))
                .path("Consumer").path("ConsumerARN").asText();
        ObjectNode resource = MAPPER.createObjectNode().put("ResourceARN", consumerArn);

        Response listed = handler.handle("ListTagsForResource", resource, REGION);
        assertEquals(200, listed.getStatus());
        assertEquals("team", responseEntity(listed).path("Tags").get(0).path("Key").asText());
        assertEquals("red", responseEntity(listed).path("Tags").get(0).path("Value").asText());

        ObjectNode tagRequest = resource.deepCopy();
        tagRequest.putObject("Tags").put("env", "dev");
        assertEquals(200, handler.handle("TagResource", tagRequest, REGION).getStatus());
        JsonNode tags = responseEntity(handler.handle("ListTagsForResource", resource, REGION)).path("Tags");
        assertEquals(2, tags.size());
        assertEquals("red", tagValue(tags, "team"));
        assertEquals("dev", tagValue(tags, "env"));
    }

    @Test
    void resourceTagOperationsKeepStreamAndConsumerTagsSeparate() {
        createStream("test-stream");
        String consumerArn = service.registerStreamConsumer(STREAM_ARN, "tagged-consumer", REGION)
                .getConsumerArn();
        ObjectNode streamRequest = MAPPER.createObjectNode().put("ResourceARN", STREAM_ARN);
        streamRequest.putObject("Tags").put("owner", "stream");
        assertEquals(200, handler.handle("TagResource", streamRequest, REGION).getStatus());
        ObjectNode consumerRequest = MAPPER.createObjectNode().put("ResourceARN", consumerArn);
        consumerRequest.putObject("Tags").put("owner", "consumer");
        assertEquals(200, handler.handle("TagResource", consumerRequest, REGION).getStatus());

        ObjectNode streamArnOnly = MAPPER.createObjectNode().put("ResourceARN", STREAM_ARN);
        ObjectNode consumerArnOnly = MAPPER.createObjectNode().put("ResourceARN", consumerArn);
        assertEquals("stream", tagValue(responseEntity(handler.handle("ListTagsForResource", streamArnOnly, REGION))
                .path("Tags"), "owner"));
        assertEquals("consumer", tagValue(responseEntity(handler.handle("ListTagsForResource", consumerArnOnly, REGION))
                .path("Tags"), "owner"));

        consumerArnOnly.putArray("TagKeys").add("owner");
        assertEquals(200, handler.handle("UntagResource", consumerArnOnly, REGION).getStatus());
        consumerArnOnly.remove("TagKeys");
        assertEquals(0, responseEntity(handler.handle("ListTagsForResource", consumerArnOnly, REGION))
                .path("Tags").size());
        assertEquals("stream", tagValue(responseEntity(handler.handle("ListTagsForResource", streamArnOnly, REGION))
                .path("Tags"), "owner"));
        ObjectNode legacyList = MAPPER.createObjectNode().put("StreamName", "test-stream");
        assertEquals("stream", tagValue(responseEntity(handler.handle("ListTagsForStream", legacyList, REGION))
                .path("Tags"), "owner"));

        streamArnOnly.putArray("TagKeys").add("owner");
        assertEquals(200, handler.handle("UntagResource", streamArnOnly, REGION).getStatus());
        streamArnOnly.remove("TagKeys");
        assertEquals(0, responseEntity(handler.handle("ListTagsForResource", streamArnOnly, REGION))
                .path("Tags").size());
        assertEquals(0, responseEntity(handler.handle("ListTagsForResource", consumerArnOnly, REGION))
                .path("Tags").size());
    }

    @Test
    void resourceTagsRejectForeignAndMissingResources() {
        createStream("test-stream");
        String consumerArn = service.registerStreamConsumer(STREAM_ARN, "tagged-consumer", REGION)
                .getConsumerArn();
        ObjectNode request = MAPPER.createObjectNode();

        request.put("ResourceARN", consumerArn.replace(ACCOUNT, "999999999999"));
        AwsException foreign = assertThrows(AwsException.class,
                () -> handler.handle("ListTagsForResource", request, REGION));
        assertEquals("ResourceNotFoundException", foreign.getErrorCode());

        request.put("ResourceARN", consumerArn.replace(REGION, "us-west-2"));
        AwsException otherRegion = assertThrows(AwsException.class,
                () -> handler.handle("ListTagsForResource", request, REGION));
        assertEquals("InvalidArgumentException", otherRegion.getErrorCode());

        service.deregisterStreamConsumer(null, null, consumerArn, REGION);
        request.put("ResourceARN", consumerArn);
        AwsException deleted = assertThrows(AwsException.class,
                () -> handler.handle("ListTagsForResource", request, REGION));
        assertEquals("ResourceNotFoundException", deleted.getErrorCode());
    }

    @Test
    void resourceTagsEnforceCapacityWithoutPartialWrites() {
        createStream("test-stream");
        ObjectNode request = MAPPER.createObjectNode().put("ResourceARN", STREAM_ARN);
        ObjectNode tags = request.putObject("Tags");
        for (int i = 0; i < 50; i++) {
            tags.put("key" + i, "value");
        }
        assertEquals(200, handler.handle("TagResource", request, REGION).getStatus());

        request.set("Tags", MAPPER.createObjectNode().put("overflow", "value"));
        AwsException overLimit = assertThrows(AwsException.class,
                () -> handler.handle("TagResource", request, REGION));
        assertEquals("LimitExceededException", overLimit.getErrorCode());
        ObjectNode list = MAPPER.createObjectNode().put("ResourceARN", STREAM_ARN);
        assertEquals(50, responseEntity(handler.handle("ListTagsForResource", list, REGION)).path("Tags").size());

        request.set("Tags", MAPPER.createObjectNode().put("key0", "updated"));
        assertEquals(200, handler.handle("TagResource", request, REGION).getStatus());
        assertEquals("updated", tagValue(responseEntity(handler.handle("ListTagsForResource", list, REGION))
                .path("Tags"), "key0"));
    }

    @Test
    void tagResourceRejectsInvalidCharactersWithoutChangingEitherResource() {
        createStream("test-stream");
        String consumerArn = service.registerStreamConsumer(STREAM_ARN, "tagged-consumer", REGION)
                .getConsumerArn();
        for (String arn : List.of(STREAM_ARN, consumerArn)) {
            ObjectNode request = MAPPER.createObjectNode().put("ResourceARN", arn);
            request.putObject("Tags").put("valid", "initial");
            assertEquals(200, handler.handle("TagResource", request, REGION).getStatus());

            for (String key : List.of("bad*key")) {
                request.set("Tags", MAPPER.createObjectNode().put(key, "value"));
                AwsException badKey = assertThrows(AwsException.class,
                        () -> handler.handle("TagResource", request, REGION));
                assertEquals("InvalidArgumentException", badKey.getErrorCode());
            }

            for (String value : List.of("bad*value")) {
                request.set("Tags", MAPPER.createObjectNode().put("valid", value));
                AwsException badValue = assertThrows(AwsException.class,
                        () -> handler.handle("TagResource", request, REGION));
                assertEquals("InvalidArgumentException", badValue.getErrorCode());
            }

            JsonNode tags = responseEntity(handler.handle("ListTagsForResource",
                    MAPPER.createObjectNode().put("ResourceARN", arn), REGION)).path("Tags");
            assertEquals(1, tags.size());
            assertEquals("initial", tagValue(tags, "valid"));
        }
    }

    @Test
    void tagResourceAcceptsDocumentedUnicodeAndSymbols() {
        createStream("test-stream");
        String consumerArn = service.registerStreamConsumer(STREAM_ARN, "tagged-consumer", REGION)
                .getConsumerArn();
        String key = "\u00c9quipe_./=+@-:%9";
        String value = "\u7a7a \u767d%:";
        for (String arn : List.of(STREAM_ARN, consumerArn)) {
            ObjectNode request = MAPPER.createObjectNode().put("ResourceARN", arn);
            request.putObject("Tags").put(key, value);
            assertEquals(200, handler.handle("TagResource", request, REGION).getStatus());
            JsonNode tags = responseEntity(handler.handle("ListTagsForResource",
                    MAPPER.createObjectNode().put("ResourceARN", arn), REGION)).path("Tags");
            assertEquals(value, tagValue(tags, key));
        }
    }

    @Test
    void tagResourceRejectsReservedTagKeyPrefix() {
        createStream("test-stream");
        String consumerArn = service.registerStreamConsumer(STREAM_ARN, "tagged-consumer", REGION)
                .getConsumerArn();
        for (String arn : List.of(STREAM_ARN, consumerArn)) {
            ObjectNode request = MAPPER.createObjectNode().put("ResourceARN", arn);
            request.putObject("Tags").put("AWS:owner", "user");
            AwsException reserved = assertThrows(AwsException.class,
                    () -> handler.handle("TagResource", request, REGION));
            assertEquals("InvalidArgumentException", reserved.getErrorCode());
            assertEquals(0, responseEntity(handler.handle("ListTagsForResource",
                    MAPPER.createObjectNode().put("ResourceARN", arn), REGION)).path("Tags").size());
        }
    }

    @Test
    void tagResourceUpdatesLegacyStreamBeyondCapacityWithoutAddingKeys() {
        createStream("test-stream");
        // A stream persisted before AddTagsToStream enforced the limit can already have 51 tags.
        Map<String, String> legacyTags = service.describeStream("test-stream", REGION).getTags();
        for (int i = 0; i < 51; i++) {
            legacyTags.put("key" + i, "old");
        }

        ObjectNode request = MAPPER.createObjectNode().put("ResourceARN", STREAM_ARN);
        request.putObject("Tags").put("key0", "updated");
        assertEquals(200, handler.handle("TagResource", request, REGION).getStatus());

        request.set("Tags", MAPPER.createObjectNode().put("key1", "would-change").put("new", "value"));
        AwsException overLimit = assertThrows(AwsException.class,
                () -> handler.handle("TagResource", request, REGION));
        assertEquals("LimitExceededException", overLimit.getErrorCode());
        JsonNode tags = responseEntity(handler.handle("ListTagsForResource",
                MAPPER.createObjectNode().put("ResourceARN", STREAM_ARN), REGION)).path("Tags");
        assertEquals(51, tags.size());
        assertEquals("updated", tagValue(tags, "key0"));
        assertEquals("old", tagValue(tags, "key1"));
    }

    @Test
    void addTagsToStreamRejectsNewKeysBeyondFiftyWithoutChangingExistingValues() {
        createStream("test-stream");
        for (int batch = 0; batch < 5; batch++) {
            ObjectNode add = MAPPER.createObjectNode().put("StreamName", "test-stream");
            ObjectNode tags = add.putObject("Tags");
            for (int i = batch * 10; i < (batch + 1) * 10; i++) {
                tags.put("key" + i, "old");
            }
            assertEquals(200, handler.handle("AddTagsToStream", add, REGION).getStatus());
        }

        ObjectNode update = MAPPER.createObjectNode().put("StreamName", "test-stream");
        update.putObject("Tags").put("key0", "updated");
        assertEquals(200, handler.handle("AddTagsToStream", update, REGION).getStatus());

        ObjectNode overflow = MAPPER.createObjectNode().put("StreamName", "test-stream");
        overflow.putObject("Tags").put("key1", "would-change").put("key50", "new");
        AwsException error = assertThrows(AwsException.class,
                () -> handler.handle("AddTagsToStream", overflow, REGION));
        assertEquals("LimitExceededException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        Map<String, String> stored = service.listTagsForStream("test-stream", REGION);
        assertEquals(50, stored.size());
        assertEquals("updated", stored.get("key0"));
        assertEquals("old", stored.get("key1"));
        assertFalse(stored.containsKey("key50"));
    }

    private static Stream<Arguments> rejectedRegistrationTags() {
        ObjectNode tooManyTags = MAPPER.createObjectNode();
        for (int i = 0; i < 51; i++) {
            tooManyTags.put("key" + i, "value");
        }
        return Stream.of(
                Arguments.of("empty map", MAPPER.createObjectNode(), "InvalidArgumentException"),
                Arguments.of("invalid key", MAPPER.createObjectNode().put("bad*key", "value"),
                        "InvalidArgumentException"),
                Arguments.of("reserved prefix", MAPPER.createObjectNode().put("aws:owner", "user"),
                        "InvalidArgumentException"),
                Arguments.of("over capacity", tooManyTags, "LimitExceededException"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedRegistrationTags")
    void invalidRegistrationTagsDoNotCreateConsumer(String scenario, JsonNode tags, String errorCode) {
        createStream("test-stream");
        ObjectNode request = MAPPER.createObjectNode()
                .put("StreamARN", STREAM_ARN)
                .put("ConsumerName", "tagged-consumer");
        request.set("Tags", tags);
        AwsException error = assertThrows(AwsException.class,
                () -> handler.handle("RegisterStreamConsumer", request, REGION));
        assertEquals(errorCode, error.getErrorCode());
        assertTrue(service.listStreamConsumers(STREAM_ARN, REGION).isEmpty());
    }

    private String tagValue(JsonNode tags, String key) {
        for (JsonNode tag : tags) {
            if (key.equals(tag.path("Key").asText())) {
                return tag.path("Value").asText();
            }
        }
        return null;
    }

    private ObjectNode responseEntity(Response response) {
        return (ObjectNode) response.getEntity();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PutRecord", "PutRecords"})
    void writesReportTheOpenHashRangeAfterRepeatedSplits(String action) {
        createStream("test-stream");
        KinesisStream stream = service.describeStream("test-stream", REGION);
        service.splitShard("test-stream", FIRST_SHARD_ID,
                "170141183460469231731687303715884105728", REGION);
        service.splitShard("test-stream", stream.getShards().get(1).getShardId(),
                "85070591730234615865843651857942052864", REGION);
        service.splitShard("test-stream", stream.getShards().get(2).getShardId(),
                "255211775190703847597530955573826158592", REGION);

        ObjectNode request = MAPPER.createObjectNode().put("StreamName", "test-stream");
        ObjectNode record = "PutRecords".equals(action) ? request.putArray("Records").addObject() : request;
        record.put("PartitionKey", "b").put("Data", "AQ==");
        ObjectNode response = responseEntity(handler.handle(action, request, REGION));
        JsonNode result = "PutRecords".equals(action) ? response.path("Records").get(0) : response;

        assertEquals(stream.getShards().get(5).getShardId(), result.path("ShardId").asText());
        assertFalse(result.path("SequenceNumber").asText().isEmpty());
        if ("PutRecords".equals(action)) {
            assertEquals(0, response.path("FailedRecordCount").asInt());
        }
        assertEquals(1, stream.getShards().get(5).recordCount());
        assertTrue(stream.getShards().stream().filter(KinesisShard::isClosed)
                .allMatch(shard -> shard.recordCount() == 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PutRecord", "PutRecords"})
    void writesFailWithoutAppendingWhenNoOpenShardCoversHash(String action) {
        createStream("test-stream");
        KinesisShard shard = service.describeStream("test-stream", REGION).getShards().getFirst();
        shard.setClosed(true);
        ObjectNode request = MAPPER.createObjectNode().put("StreamName", "test-stream");
        ObjectNode record = "PutRecords".equals(action) ? request.putArray("Records").addObject() : request;
        record.put("PartitionKey", "b").put("Data", "AQ==");

        if ("PutRecord".equals(action)) {
            AwsException error = assertThrows(AwsException.class, () -> handler.handle(action, request, REGION));
            assertEquals("InvalidArgumentException", error.getErrorCode());
            assertEquals(400, error.getHttpStatus());
        } else {
            ObjectNode response = responseEntity(handler.handle(action, request, REGION));
            assertEquals(1, response.path("FailedRecordCount").asInt());
            assertEquals("InternalFailure", response.path("Records").get(0).path("ErrorCode").asText());
            assertFalse(response.path("Records").get(0).has("SequenceNumber"));
            assertFalse(response.path("Records").get(0).has("ShardId"));
        }
        assertEquals(0, shard.recordCount());
    }

    @Test
    void describeStreamByName() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        Response resp = handler.handle("DescribeStream", req, REGION);
        assertThat(resp.getStatus(), is(200));
        ObjectNode desc = (ObjectNode) responseEntity(resp).get("StreamDescription");
        assertEquals("test-stream", desc.get("StreamName").asText());
    }

    @Test
    void describeStreamSerializesCreationTimestampAsPlainDecimal() throws Exception {
        createStream("test-stream");
        service.describeStream("test-stream", REGION)
                .setStreamCreationTimestamp(Instant.ofEpochMilli(1_785_732_980_986L));

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        ObjectNode desc = (ObjectNode) responseEntity(handler.handle("DescribeStream", req, REGION))
                .get("StreamDescription");

        assertPlainDecimalTimestamp(desc, "1785732980.986");
    }

    @Test
    void describeStreamSummarySerializesCreationTimestampAsPlainDecimal() throws Exception {
        createStream("test-stream");
        service.describeStream("test-stream", REGION)
                .setStreamCreationTimestamp(Instant.ofEpochMilli(1_785_732_980_986L));

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        ObjectNode summary = (ObjectNode) responseEntity(handler.handle("DescribeStreamSummary", req, REGION))
                .get("StreamDescriptionSummary");

        assertPlainDecimalTimestamp(summary, "1785732980.986");
    }

    @Test
    void wholeSecondCreationTimestampRemainsNumeric() throws Exception {
        createStream("test-stream");
        service.describeStream("test-stream", REGION)
                .setStreamCreationTimestamp(Instant.ofEpochMilli(1_785_732_980_000L));

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        ObjectNode desc = (ObjectNode) responseEntity(handler.handle("DescribeStream", req, REGION))
                .get("StreamDescription");

        assertPlainDecimalTimestamp(desc, "1785732980.000");
    }

    private void assertPlainDecimalTimestamp(ObjectNode description, String expected) throws Exception {
        JsonNode timestamp = description.get("StreamCreationTimestamp");
        assertTrue(timestamp.isNumber());
        assertEquals(new BigDecimal(expected), timestamp.decimalValue());

        String serialized = MAPPER.writeValueAsString(timestamp);
        assertEquals(expected, serialized);
        assertFalse(serialized.contains("E"));
        assertFalse(serialized.contains("e"));
    }

    @Test
    void describeStreamByArn() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamARN", STREAM_ARN);
        Response resp = handler.handle("DescribeStream", req, REGION);
        assertThat(resp.getStatus(), is(200));
        ObjectNode desc = (ObjectNode) responseEntity(resp).get("StreamDescription");
        assertEquals("test-stream", desc.get("StreamName").asText());
    }

    @Test
    void explicitEmptyStreamNameDoesNotFallBackToArn() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "");
        req.put("StreamARN", STREAM_ARN);
        AwsException error = assertThrows(AwsException.class,
                () -> handler.handle("DescribeStream", req, REGION));
        assertEquals("InvalidArgumentException", error.getErrorCode());
    }

    @Test
    void explicitWhitespaceStreamNameDoesNotFallBackToArn() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "   ");
        req.put("StreamARN", STREAM_ARN);
        AwsException error = assertThrows(AwsException.class,
                () -> handler.handle("DescribeStream", req, REGION));
        assertEquals("InvalidArgumentException", error.getErrorCode());
    }

    @Test
    void neitherFieldThrowsInvalidArgument() {
        ObjectNode req = MAPPER.createObjectNode();
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("DescribeStream", req, REGION));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void whitespaceOnlyNameWithoutArnThrows() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "   ");
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("DescribeStream", req, REGION));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
    }

    @Test
    void malformedArnWithoutStreamSegmentThrows() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamARN", "arn:aws:kinesis:us-east-1:123456789012:table/not-a-stream");
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("DescribeStream", req, REGION));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
    }

    @Test
    void arnEndingInSlashThrows() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamARN", "arn:aws:kinesis:us-east-1:123456789012:stream/");
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("DescribeStream", req, REGION));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
    }

    @Test
    void consumerArnExtractsStreamNameNotConsumerName() {
        createStream("my-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamARN", "arn:aws:kinesis:us-east-1:123456789012:stream/my-stream/consumer/my-consumer");
        Response resp = handler.handle("DescribeStream", req, REGION);
        assertThat(resp.getStatus(), is(200));
        assertEquals("my-stream",
                responseEntity(resp).get("StreamDescription").get("StreamName").asText());
    }

    @Test
    void putRecordByArn() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamARN", STREAM_ARN);
        req.put("Data", "dGVzdA==");
        req.put("PartitionKey", "pk1");
        Response resp = handler.handle("PutRecord", req, REGION);
        assertThat(resp.getStatus(), is(200));
        assertThat(responseEntity(resp).has("SequenceNumber"), is(true));
    }

    @Test
    void enableEnhancedMonitoringReturnsMetrics() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        req.putArray("ShardLevelMetrics").add("IncomingBytes").add("OutgoingBytes");
        Response resp = handler.handle("EnableEnhancedMonitoring", req, REGION);
        assertThat(resp.getStatus(), is(200));

        ObjectNode body = responseEntity(resp);
        assertEquals("test-stream", body.get("StreamName").asText());
        assertEquals(0, body.get("CurrentShardLevelMetrics").size());
        assertEquals(2, body.get("DesiredShardLevelMetrics").size());
    }

    @Test
    void disableEnhancedMonitoringReturnsMetrics() {
        createStream("test-stream");

        ObjectNode enableReq = MAPPER.createObjectNode();
        enableReq.put("StreamName", "test-stream");
        enableReq.putArray("ShardLevelMetrics").add("IncomingBytes").add("OutgoingBytes");
        handler.handle("EnableEnhancedMonitoring", enableReq, REGION);

        ObjectNode disableReq = MAPPER.createObjectNode();
        disableReq.put("StreamName", "test-stream");
        disableReq.putArray("ShardLevelMetrics").add("IncomingBytes");
        Response resp = handler.handle("DisableEnhancedMonitoring", disableReq, REGION);
        assertThat(resp.getStatus(), is(200));

        ObjectNode body = responseEntity(resp);
        assertEquals(2, body.get("CurrentShardLevelMetrics").size());
        assertEquals(1, body.get("DesiredShardLevelMetrics").size());
    }

    @Test
    void describeStreamIncludesEnhancedMonitoring() {
        createStream("test-stream");

        ObjectNode enableReq = MAPPER.createObjectNode();
        enableReq.put("StreamName", "test-stream");
        enableReq.putArray("ShardLevelMetrics").add("IncomingBytes");
        handler.handle("EnableEnhancedMonitoring", enableReq, REGION);

        ObjectNode descReq = MAPPER.createObjectNode();
        descReq.put("StreamName", "test-stream");
        Response resp = handler.handle("DescribeStream", descReq, REGION);
        ObjectNode desc = (ObjectNode) responseEntity(resp).get("StreamDescription");
        assertEquals(1, desc.get("EnhancedMonitoring").size());
        assertEquals(1, desc.get("EnhancedMonitoring").get(0).get("ShardLevelMetrics").size());
        assertEquals("IncomingBytes", desc.get("EnhancedMonitoring").get(0).get("ShardLevelMetrics").get(0).asText());
    }

    @Test
    void describeStreamSummaryIncludesEnhancedMonitoring() {
        createStream("test-stream");

        ObjectNode descReq = MAPPER.createObjectNode();
        descReq.put("StreamName", "test-stream");
        Response resp = handler.handle("DescribeStreamSummary", descReq, REGION);
        ObjectNode summary = (ObjectNode) responseEntity(resp).get("StreamDescriptionSummary");
        assertEquals(1, summary.get("EnhancedMonitoring").size());
        assertEquals(0, summary.get("EnhancedMonitoring").get(0).get("ShardLevelMetrics").size());
    }

    @Test
    void streamNameTakesPrecedenceOverArn() {
        createStream("by-name");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "by-name");
        req.put("StreamARN", "arn:aws:kinesis:us-east-1:123456789012:stream/nonexistent");
        Response resp = handler.handle("DescribeStream", req, REGION);
        assertThat(resp.getStatus(), is(200));
        assertEquals("by-name",
                responseEntity(resp).get("StreamDescription").get("StreamName").asText());
    }

    @Test
    void describeStreamRejectsInvalidModeledNameBeforeLookup() {
        ObjectNode request = MAPPER.createObjectNode().put("StreamName", "bad*name");

        AwsException error = assertThrows(AwsException.class,
                () -> handler.handle("DescribeStream", request, REGION));
        assertEquals("InvalidArgumentException", error.getErrorCode());
    }

    @Test
    void addTagsToStreamRejectsEmptyModeledTagMap() {
        createStream("test-stream");
        ObjectNode request = MAPPER.createObjectNode().put("StreamName", "test-stream");
        request.putObject("Tags");

        AwsException error = assertThrows(AwsException.class,
                () -> handler.handle("AddTagsToStream", request, REGION));
        assertEquals("InvalidArgumentException", error.getErrorCode());
        assertTrue(service.listTagsForStream("test-stream", REGION).isEmpty());
    }

    @Test
    void addTagsToStreamRejectsAnOverlongModeledTagKey() {
        createStream("test-stream");
        ObjectNode request = MAPPER.createObjectNode().put("StreamName", "test-stream");
        request.putObject("Tags").put("K".repeat(129), "value");

        AwsException error = assertThrows(AwsException.class,
                () -> handler.handle("AddTagsToStream", request, REGION));
        assertEquals("InvalidArgumentException", error.getErrorCode());
        assertTrue(service.listTagsForStream("test-stream", REGION).isEmpty());
    }

    @Test
    void addTagsToStreamRejectsAnOverlongModeledTagValue() {
        createStream("test-stream");
        ObjectNode request = MAPPER.createObjectNode().put("StreamName", "test-stream");
        request.putObject("Tags").put("Key", "V".repeat(257));

        AwsException error = assertThrows(AwsException.class,
                () -> handler.handle("AddTagsToStream", request, REGION));
        assertEquals("InvalidArgumentException", error.getErrorCode());
        assertTrue(service.listTagsForStream("test-stream", REGION).isEmpty());
    }

    @Test
    void createStreamRejectsNonMapTagsThroughTheModeledShape() {
        ObjectNode request = MAPPER.createObjectNode().put("StreamName", "test-stream");
        request.putArray("Tags").add("not-a-map");

        AwsException error = assertThrows(AwsException.class,
                () -> handler.handle("CreateStream", request, REGION));
        assertEquals("SerializationException", error.getErrorCode());
        assertEquals("CreateStream.Tags must be a map.", error.getMessage());
        assertTrue(service.listStreamDetails(REGION).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 51})
    void removeTagsFromStreamRejectsModeledTagKeyCounts(int count) {
        createStream("test-stream");
        ObjectNode add = MAPPER.createObjectNode().put("StreamName", "test-stream");
        add.putObject("Tags").put("Key", "value");
        handler.handle("AddTagsToStream", add, REGION);
        ObjectNode request = MAPPER.createObjectNode().put("StreamName", "test-stream");
        ArrayNode keys = request.putArray("TagKeys");
        for (int i = 0; i < count; i++) {
            keys.add("Key");
        }

        AwsException error = assertThrows(AwsException.class,
                () -> handler.handle("RemoveTagsFromStream", request, REGION));
        assertEquals("InvalidArgumentException", error.getErrorCode());
        assertEquals(Map.of("Key", "value"), service.listTagsForStream("test-stream", REGION));
    }

    @Test
    void unknownOperationKeepsItsExistingResponseBeforeShapeValidation() {
        try (Response response = handler.handle("UnknownOperation", MAPPER.createArrayNode(), REGION)) {
            assertEquals(400, response.getStatus());
            assertEquals("UnsupportedOperation", ((AwsErrorResponse) response.getEntity()).type());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CreateStream", "DeleteStream", "ListStreams", "DescribeStream",
            "DescribeStreamSummary", "RegisterStreamConsumer", "DeregisterStreamConsumer",
            "DescribeStreamConsumer", "ListStreamConsumers", "SubscribeToShard", "AddTagsToStream",
            "RemoveTagsFromStream", "ListTagsForStream", "StartStreamEncryption", "StopStreamEncryption",
            "SplitShard", "MergeShards", "PutRecord", "PutRecords", "GetShardIterator", "GetRecords",
            "ListShards", "IncreaseStreamRetentionPeriod", "DecreaseStreamRetentionPeriod",
            "EnableEnhancedMonitoring", "DisableEnhancedMonitoring", "UpdateStreamMode",
            "UpdateMaxRecordSize", "UpdateShardCount"})
    void everyServedOperationChecksItsModeledInputBeforeDispatch(String action) {
        AwsException error = assertThrows(AwsException.class,
                () -> handler.handle(action, MAPPER.createArrayNode(), REGION));
        assertEquals("SerializationException", error.getErrorCode());
    }

    @Test
    void describeStreamReturnsDefaultStreamMode() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        Response resp = handler.handle("DescribeStream", req, REGION);
        ObjectNode desc = (ObjectNode) responseEntity(resp).get("StreamDescription");
        assertEquals("PROVISIONED", desc.get("StreamModeDetails").get("StreamMode").asText());
    }

    @Test
    void describeStreamSummaryReturnsDefaultStreamMode() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        Response resp = handler.handle("DescribeStreamSummary", req, REGION);
        ObjectNode summary = (ObjectNode) responseEntity(resp).get("StreamDescriptionSummary");
        assertEquals("PROVISIONED", summary.get("StreamModeDetails").get("StreamMode").asText());
    }

    @Test
    void createStreamHonorsOnDemandStreamMode() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        req.put("ShardCount", 1);
        req.putObject("StreamModeDetails").put("StreamMode", "ON_DEMAND");
        assertThat(handler.handle("CreateStream", req, REGION).getStatus(), is(200));

        ObjectNode descReq = MAPPER.createObjectNode();
        descReq.put("StreamName", "test-stream");
        Response resp = handler.handle("DescribeStream", descReq, REGION);
        ObjectNode desc = (ObjectNode) responseEntity(resp).get("StreamDescription");
        assertEquals("ON_DEMAND", desc.get("StreamModeDetails").get("StreamMode").asText());
    }

    @Test
    void updateStreamModeSwitchesProvisionedToOnDemand() {
        createStream("test-stream");

        ObjectNode updateReq = MAPPER.createObjectNode();
        updateReq.put("StreamARN", STREAM_ARN);
        updateReq.putObject("StreamModeDetails").put("StreamMode", "ON_DEMAND");
        assertThat(handler.handle("UpdateStreamMode", updateReq, REGION).getStatus(), is(200));

        ObjectNode descReq = MAPPER.createObjectNode();
        descReq.put("StreamName", "test-stream");
        Response resp = handler.handle("DescribeStream", descReq, REGION);
        ObjectNode desc = (ObjectNode) responseEntity(resp).get("StreamDescription");
        assertEquals("ON_DEMAND", desc.get("StreamModeDetails").get("StreamMode").asText());
    }

    @Test
    void updateStreamModeSameModeIsNoOp() {
        // Terraform refresh calls UpdateStreamMode unconditionally; same-mode must succeed.
        createStream("test-stream");

        ObjectNode updateReq = MAPPER.createObjectNode();
        updateReq.put("StreamARN", STREAM_ARN);
        updateReq.putObject("StreamModeDetails").put("StreamMode", "PROVISIONED");
        assertThat(handler.handle("UpdateStreamMode", updateReq, REGION).getStatus(), is(200));
    }

    @Test
    void updateStreamModeRejectsInvalidMode() {
        createStream("test-stream");

        ObjectNode updateReq = MAPPER.createObjectNode();
        updateReq.put("StreamARN", STREAM_ARN);
        updateReq.putObject("StreamModeDetails").put("StreamMode", "BOGUS");
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("UpdateStreamMode", updateReq, REGION));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
    }

    @Test
    void updateStreamModeRequiresStreamArn() {
        createStream("test-stream");

        ObjectNode updateReq = MAPPER.createObjectNode();
        updateReq.put("StreamName", "test-stream");
        updateReq.putObject("StreamModeDetails").put("StreamMode", "ON_DEMAND");
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("UpdateStreamMode", updateReq, REGION));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
    }

    @Test
    void updateStreamModeRequiresStreamModeDetails() {
        createStream("test-stream");

        ObjectNode updateReq = MAPPER.createObjectNode();
        updateReq.put("StreamARN", STREAM_ARN);
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("UpdateStreamMode", updateReq, REGION));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
    }

    @Test
    void updateStreamModeRejectsUnknownStream() {
        ObjectNode updateReq = MAPPER.createObjectNode();
        updateReq.put("StreamARN", STREAM_ARN);
        updateReq.putObject("StreamModeDetails").put("StreamMode", "ON_DEMAND");
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("UpdateStreamMode", updateReq, REGION));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    @Test
    void putRecordRejectsRecordOverSizeLimit() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        // 1_048_574 data bytes + 3-byte partition key = 1_048_577, one over the limit
        req.put("Data", Base64.getEncoder().encodeToString(new byte[1_048_574]));
        req.put("PartitionKey", "pk1");
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("PutRecord", req, REGION));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
    }

    @Test
    void putRecordRejectsExplicitHashKeyOutsideHashKeySpace() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        req.put("Data", "dGVzdA==");
        req.put("PartitionKey", "pk1");
        req.put("ExplicitHashKey", MAX_HASH_KEY_PLUS_ONE);

        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("PutRecord", req, REGION));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
    }

    @Test
    void putRecordsRejectsNonDecimalExplicitHashKey() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        ArrayNode records = req.putArray("Records");
        records.addObject()
                .put("Data", "dGVzdA==")
                .put("PartitionKey", "pk1")
                .put("ExplicitHashKey", NON_DECIMAL_HASH_KEY);

        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("PutRecords", req, REGION));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
    }

    @Test
    void putRecordRejectsNumericExplicitHashKey() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        req.put("Data", "dGVzdA==");
        req.put("PartitionKey", "pk1");
        // A JSON number is not the string-shaped field AWS accepts; asText coercion must not
        // silently turn it into a valid decimal hash key.
        req.put("ExplicitHashKey", NUMERIC_EXPLICIT_HASH_KEY);

        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("PutRecord", req, REGION));
        assertEquals("SerializationException", ex.getErrorCode());
    }

    @Test
    void putRecordsRejectNumericExplicitHashKey() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        ArrayNode records = req.putArray("Records");
        records.addObject()
                .put("Data", "dGVzdA==")
                .put("PartitionKey", "pk1")
                .put("ExplicitHashKey", NUMERIC_EXPLICIT_HASH_KEY);

        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("PutRecords", req, REGION));
        assertEquals("SerializationException", ex.getErrorCode());
    }

    @Test
    void putRecordMigratesLegacyOverlappingShardRangesForExplicitHashKey() {
        // A multi-shard stream persisted before disjoint ranges existed carries the full 128-bit
        // span on every open shard, so an explicit key matches all of them. Naive first-match
        // routing would then collapse every explicit key onto the first shard.
        createStream("legacy-stream", TWO_SHARDS);
        KinesisStream stream = service.describeStream("legacy-stream", REGION);
        for (KinesisShard shard : stream.getShards()) {
            shard.setHashKeyRange(new KinesisShard.HashKeyRange(MIN_HASH_KEY, MAX_HASH_KEY));
        }

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "legacy-stream");
        req.put("Data", "dGVzdA==");
        req.put("PartitionKey", "pk1");
        req.put("ExplicitHashKey", MAX_HASH_KEY);

        ObjectNode resp = responseEntity(handler.handle("PutRecord", req, REGION));
        assertEquals(SECOND_SHARD_ID, resp.get("ShardId").asText());

        req.put("ExplicitHashKey", MIN_HASH_KEY);
        ObjectNode respMin = responseEntity(handler.handle("PutRecord", req, REGION));
        assertEquals(FIRST_SHARD_ID, respMin.get("ShardId").asText());
    }

    @Test
    void ordinaryPartitionKeysUseMd5HashRangesReportedByListShards() {
        createStream("md5-routing", TWO_SHARDS);
        ObjectNode list = MAPPER.createObjectNode().put("StreamName", "md5-routing");
        ArrayNode shards = (ArrayNode) responseEntity(handler.handle("ListShards", list, REGION)).get("Shards");

        ObjectNode put = MAPPER.createObjectNode()
                .put("StreamName", "md5-routing")
                .put("Data", "dGVzdA==")
                .put("PartitionKey", "pk-0");
        String singleShard = responseEntity(handler.handle("PutRecord", put, REGION)).get("ShardId").asText();
        assertEquals(shardForMd5Hash(shards, "EFFC8E8920E4EE5505B7F963C5D72F6D"), singleShard);

        ObjectNode batch = MAPPER.createObjectNode().put("StreamName", "md5-routing");
        ArrayNode records = batch.putArray("Records");
        records.addObject().put("Data", "dGVzdA==").put("PartitionKey", "a");
        records.addObject().put("Data", "dGVzdA==").put("PartitionKey", "b");
        records.addObject().put("Data", "dGVzdA==").put("PartitionKey", "é-0");
        ArrayNode results = (ArrayNode) responseEntity(handler.handle("PutRecords", batch, REGION)).get("Records");
        assertEquals(shardForMd5Hash(shards, "0CC175B9C0F1B6A831C399E269772661"),
                results.get(0).get("ShardId").asText());
        assertEquals(shardForMd5Hash(shards, "92EB5FFEE6AE2FEC3AD71C777531578F"),
                results.get(1).get("ShardId").asText());
        assertEquals(shardForMd5Hash(shards, "2F6923E2AAC1CFC8729E5C892BBE2244"),
                results.get(2).get("ShardId").asText());
    }

    @Test
    void ordinaryPartitionKeyNormalizesLegacyOverlappingShardRanges() {
        createStream("legacy-md5-routing", TWO_SHARDS);
        KinesisStream stream = service.describeStream("legacy-md5-routing", REGION);
        for (KinesisShard shard : stream.getShards()) {
            shard.setHashKeyRange(new KinesisShard.HashKeyRange(MIN_HASH_KEY, MAX_HASH_KEY));
        }

        ObjectNode put = MAPPER.createObjectNode()
                .put("StreamName", "legacy-md5-routing")
                .put("Data", "dGVzdA==")
                .put("PartitionKey", "pk-0");
        assertEquals(SECOND_SHARD_ID,
                responseEntity(handler.handle("PutRecord", put, REGION)).get("ShardId").asText());
        assertEquals(MIN_HASH_KEY, stream.getShards().getFirst().getHashKeyRange().startingHashKey());
        assertEquals(MAX_HASH_KEY, stream.getShards().get(1).getHashKeyRange().endingHashKey());
    }

    @Test
    void putRecordsDoesNotRescanEveryShardForEachEntry() {
        KinesisStream stream = service.createStream("indexed-routing", 256, REGION);
        AtomicInteger rangeReads = new AtomicInteger();
        List<KinesisShard> counted = new ArrayList<>();
        for (KinesisShard shard : stream.getShards()) {
            counted.add(new KinesisShard(shard.getShardId(),
                    shard.getHashKeyRange().startingHashKey(), shard.getHashKeyRange().endingHashKey(),
                    shard.getSequenceNumberRange().startingSequenceNumber()) {
                @Override
                public HashKeyRange getHashKeyRange() {
                    rangeReads.incrementAndGet();
                    return super.getHashKeyRange();
                }
            });
        }
        stream.setShards(counted);

        ObjectNode batch = MAPPER.createObjectNode().put("StreamName", "indexed-routing");
        ArrayNode records = batch.putArray("Records");
        for (int i = 0; i < 100; i++) {
            records.addObject().put("Data", "dGVzdA==").put("PartitionKey", "pk-" + i);
        }

        ObjectNode result = responseEntity(handler.handle("PutRecords", batch, REGION));

        assertEquals(0, result.path("FailedRecordCount").asInt());
        assertEquals(100, result.path("Records").size());
        assertTrue(rangeReads.get() < 8_000, () -> "Shard range reads: " + rangeReads.get());
    }

    private static String shardForMd5Hash(ArrayNode shards, String md5Hex) {
        BigInteger hash = new BigInteger(md5Hex, 16);
        for (JsonNode shard : shards) {
            BigInteger start = new BigInteger(shard.path("HashKeyRange").path("StartingHashKey").asText());
            BigInteger end = new BigInteger(shard.path("HashKeyRange").path("EndingHashKey").asText());
            if (hash.compareTo(start) >= 0 && hash.compareTo(end) <= 0) {
                return shard.path("ShardId").asText();
            }
        }
        throw new AssertionError("No shard covers MD5 hash " + md5Hex);
    }

    @Test
    void putRecordAcceptsRecordAtSizeLimit() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        // 1_048_573 data bytes + 3-byte partition key = exactly 1_048_576
        req.put("Data", Base64.getEncoder().encodeToString(new byte[1_048_573]));
        req.put("PartitionKey", "pk1");
        assertThat(handler.handle("PutRecord", req, REGION).getStatus(), is(200));
    }

    @Test
    void putRecordsRejectsWholeBatchOnOversizedRecord() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        ArrayNode records = req.putArray("Records");
        records.addObject().put("Data", "dGVzdA==").put("PartitionKey", "pk1");
        records.addObject()
                .put("Data", Base64.getEncoder().encodeToString(new byte[1_048_574]))
                .put("PartitionKey", "pk1");
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("PutRecords", req, REGION));
        assertEquals("InvalidArgumentException", ex.getErrorCode());

        // The valid first record must not have landed either
        ObjectNode descReq = MAPPER.createObjectNode();
        descReq.put("StreamName", "test-stream");
        String shardId = responseEntity(handler.handle("DescribeStream", descReq, REGION))
                .get("StreamDescription").get("Shards").get(0).get("ShardId").asText();

        ObjectNode iterReq = MAPPER.createObjectNode();
        iterReq.put("StreamName", "test-stream");
        iterReq.put("ShardId", shardId);
        iterReq.put("ShardIteratorType", "TRIM_HORIZON");
        String iterator = responseEntity(handler.handle("GetShardIterator", iterReq, REGION))
                .get("ShardIterator").asText();

        ObjectNode recReq = MAPPER.createObjectNode();
        recReq.put("ShardIterator", iterator);
        assertEquals(0, responseEntity(handler.handle("GetRecords", recReq, REGION))
                .get("Records").size());
    }

    @Test
    void putRecordsKeepsMalformedDataAsPerRecordFailure() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        ArrayNode records = req.putArray("Records");
        records.addObject().put("Data", "dGVzdA==").put("PartitionKey", "pk1");
        records.addObject().put("Data", "!!!not-base64!!!").put("PartitionKey", "pk2");
        records.addObject().put("Data", "dGVzdA==").put("PartitionKey", "pk3");

        Response resp = handler.handle("PutRecords", req, REGION);
        assertThat(resp.getStatus(), is(200));
        ObjectNode body = responseEntity(resp);
        assertEquals(1, body.get("FailedRecordCount").asInt());

        ArrayNode results = (ArrayNode) body.get("Records");
        assertThat(results.get(0).has("SequenceNumber"), is(true));
        assertEquals("InternalFailure", results.get(1).get("ErrorCode").asText());
        assertThat(results.get(2).has("SequenceNumber"), is(true));
    }

    @Test
    void putRecordRejectsNonStringData() {
        createStream("test-stream");

        ObjectNode missing = MAPPER.createObjectNode();
        missing.put("StreamName", "test-stream");
        missing.put("PartitionKey", "pk1");
        ObjectNode nullData = missing.deepCopy();
        nullData.putNull("Data");
        ObjectNode boolData = missing.deepCopy();
        boolData.put("Data", true);
        ObjectNode numberData = missing.deepCopy();
        numberData.put("Data", 1234);
        ObjectNode containerData = missing.deepCopy();
        containerData.putObject("Data");

        for (ObjectNode req : List.of(missing, nullData, boolData, numberData, containerData)) {
            AwsException ex = assertThrows(AwsException.class,
                    () -> handler.handle("PutRecord", req, REGION));
            assertEquals("SerializationException", ex.getErrorCode());
            assertEquals("Data must be a base64-encoded string.", ex.getMessage());
            assertEquals(400, ex.getHttpStatus());
        }
        assertEquals(0, readAllRecords("test-stream").size());
    }

    @Test
    void putRecordRejectsUndecodableData() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        req.put("Data", "!!!not-base64!!!");
        req.put("PartitionKey", "pk1");
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("PutRecord", req, REGION));
        assertEquals("SerializationException", ex.getErrorCode());
        assertEquals("Data is not valid base64.", ex.getMessage());
        assertEquals(400, ex.getHttpStatus());
    }

    /**
     * The CBOR transports decode Data as a binary node, not base64 text — the shape an
     * unmodified AWS SDK for Java Kinesis client sends — so the non-string gate must
     * accept it.
     */
    @Test
    void putRecordAcceptsBinaryData() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        req.set("Data", BinaryNode.valueOf(new byte[] {1, 2, 3}));
        req.put("PartitionKey", "pk1");
        assertThat(handler.handle("PutRecord", req, REGION).getStatus(), is(200));

        ArrayNode records = readAllRecords("test-stream");
        assertEquals(1, records.size());
        assertEquals("AQID", records.get(0).get("Data").asText());
    }

    @Test
    void putRecordAcceptsEmptyStringData() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        req.put("Data", "");
        req.put("PartitionKey", "pk1");
        assertThat(handler.handle("PutRecord", req, REGION).getStatus(), is(200));

        ArrayNode records = readAllRecords("test-stream");
        assertEquals(1, records.size());
        assertEquals("", records.get(0).get("Data").asText());
    }

    @Test
    void putRecordsFailsNonStringDataPerRecord() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        ArrayNode records = req.putArray("Records");
        records.addObject().put("Data", "dGVzdA==").put("PartitionKey", "pk1");
        records.addObject().put("Data", "").put("PartitionKey", "pk2");
        records.addObject().put("Data", true).put("PartitionKey", "pk3");
        records.addObject().put("Data", 1234).put("PartitionKey", "pk4");
        ObjectNode containerRow = records.addObject();
        containerRow.putObject("Data");
        containerRow.put("PartitionKey", "pk5");
        ObjectNode binaryRow = records.addObject();
        binaryRow.set("Data", BinaryNode.valueOf(new byte[] {1, 2, 3}));
        binaryRow.put("PartitionKey", "pk6");

        Response resp = handler.handle("PutRecords", req, REGION);
        assertThat(resp.getStatus(), is(200));
        ObjectNode body = responseEntity(resp);
        assertEquals(3, body.get("FailedRecordCount").asInt());

        ArrayNode results = (ArrayNode) body.get("Records");
        assertTrue(results.get(0).has("SequenceNumber"));
        assertTrue(results.get(1).has("SequenceNumber"));
        for (int i = 2; i <= 4; i++) {
            assertEquals("InternalFailure", results.get(i).get("ErrorCode").asText());
            assertEquals("Data must be a base64-encoded string.", results.get(i).get("ErrorMessage").asText());
        }
        assertTrue(results.get(5).has("SequenceNumber"));

        ArrayNode landed = readAllRecords("test-stream");
        assertEquals(3, landed.size());
        assertEquals("dGVzdA==", landed.get(0).get("Data").asText());
        assertEquals("", landed.get(1).get("Data").asText());
        assertEquals("AQID", landed.get(2).get("Data").asText());
    }

    @Test
    void putRecordsRejectsMissingOrNullDataBeforeWritingAnyRecord() {
        createStream("test-stream");
        for (boolean nullData : new boolean[] {false, true}) {
            ObjectNode request = MAPPER.createObjectNode().put("StreamName", "test-stream");
            ArrayNode records = request.putArray("Records");
            records.addObject().put("Data", "dGVzdA==").put("PartitionKey", "valid");
            ObjectNode invalid = records.addObject().put("PartitionKey", "invalid");
            if (nullData) {
                invalid.putNull("Data");
            }

            AwsException error = assertThrows(AwsException.class,
                    () -> handler.handle("PutRecords", request, REGION));
            assertEquals("InvalidArgumentException", error.getErrorCode());
            assertEquals(0, readAllRecords("test-stream").size());
        }
    }

    @Test
    void putRecordsCountsABinaryEntryAtItsDecodedSize() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        ArrayNode records = req.putArray("Records");
        // 9 x 900,000 decoded bytes = 8.1 MB, under the 10 MiB request cap — while the
        // base64 rendering of the same entries would exceed it.
        for (int i = 0; i < 9; i++) {
            ObjectNode row = records.addObject();
            row.set("Data", BinaryNode.valueOf(new byte[900_000]));
            row.put("PartitionKey", "pk" + i);
        }

        Response resp = handler.handle("PutRecords", req, REGION);
        assertThat(resp.getStatus(), is(200));
        assertEquals(0, responseEntity(resp).get("FailedRecordCount").asInt());
    }

    @Test
    void putRecordsRejectsWholeBatchOnOversizedBinaryRecord() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        ArrayNode records = req.putArray("Records");
        records.addObject().put("Data", "dGVzdA==").put("PartitionKey", "pk1");
        ObjectNode oversized = records.addObject();
        oversized.set("Data", BinaryNode.valueOf(new byte[1_048_574]));
        oversized.put("PartitionKey", "pk1");

        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("PutRecords", req, REGION));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
        assertEquals(0, readAllRecords("test-stream").size());
    }

    @Test
    void putRecordsCountsThePartitionKeyOfANonStringEntry() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        ArrayNode records = req.putArray("Records");
        records.addObject().put("Data", "dGVzdA==").put("PartitionKey", "pk1");
        ObjectNode badRow = records.addObject();
        badRow.putObject("Data");
        badRow.put("PartitionKey", "x".repeat(1_048_577));

        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("PutRecords", req, REGION));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
        assertEquals(0, readAllRecords("test-stream").size());
    }

    @Test
    void putRecordsRejectsUnknownStream() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "missing-stream");
        req.putArray("Records").addObject().put("Data", "dGVzdA==").put("PartitionKey", "pk1");
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("PutRecords", req, REGION));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    @Test
    void getRecordsSerializesApproximateArrivalTimestampAsPlainDecimal() throws Exception {
        useClockFixedAt(Instant.ofEpochMilli(1_786_959_660_000L));
        createStream("test-stream");

        ObjectNode putReq = MAPPER.createObjectNode();
        putReq.put("StreamName", "test-stream");
        putReq.put("Data", "dGVzdA==");
        putReq.put("PartitionKey", "pk1");
        handler.handle("PutRecord", putReq, REGION);

        ObjectNode descReq = MAPPER.createObjectNode();
        descReq.put("StreamName", "test-stream");
        String shardId = responseEntity(handler.handle("DescribeStream", descReq, REGION))
                .get("StreamDescription").get("Shards").get(0).get("ShardId").asText();

        // A fixed, realistic (2020s-era) arrival timestamp - Instant.now() would also trigger
        // the bug, but a fixed value makes the expected serialized decimal assertable exactly.
        service.describeStream("test-stream", REGION).getShards().get(0).getRecords().get(0)
                .setApproximateArrivalTimestamp(Instant.ofEpochMilli(1_786_959_659_083L));

        ObjectNode iterReq = MAPPER.createObjectNode();
        iterReq.put("StreamName", "test-stream");
        iterReq.put("ShardId", shardId);
        iterReq.put("ShardIteratorType", "TRIM_HORIZON");
        String iterator = responseEntity(handler.handle("GetShardIterator", iterReq, REGION))
                .get("ShardIterator").asText();

        ObjectNode recReq = MAPPER.createObjectNode();
        recReq.put("ShardIterator", iterator);
        JsonNode timestampNode = responseEntity(handler.handle("GetRecords", recReq, REGION))
                .get("Records").get(0).get("ApproximateArrivalTimestamp");

        assertTrue(timestampNode.isNumber());
        assertEquals(new BigDecimal("1786959659.083"), timestampNode.decimalValue());

        String serialized = MAPPER.writeValueAsString(timestampNode);
        assertEquals("1786959659.083", serialized);
        assertFalse(serialized.contains("E"));
        assertFalse(serialized.contains("e"));
    }

    @Test
    void getRecordsWholeSecondArrivalTimestampRemainsNumeric() throws Exception {
        useClockFixedAt(Instant.ofEpochMilli(1_786_959_660_000L));
        createStream("test-stream");

        ObjectNode putReq = MAPPER.createObjectNode();
        putReq.put("StreamName", "test-stream");
        putReq.put("Data", "dGVzdA==");
        putReq.put("PartitionKey", "pk1");
        handler.handle("PutRecord", putReq, REGION);

        ObjectNode descReq = MAPPER.createObjectNode();
        descReq.put("StreamName", "test-stream");
        String shardId = responseEntity(handler.handle("DescribeStream", descReq, REGION))
                .get("StreamDescription").get("Shards").get(0).get("ShardId").asText();

        service.describeStream("test-stream", REGION).getShards().get(0).getRecords().get(0)
                .setApproximateArrivalTimestamp(Instant.ofEpochMilli(1_786_959_659_000L));

        ObjectNode iterReq = MAPPER.createObjectNode();
        iterReq.put("StreamName", "test-stream");
        iterReq.put("ShardId", shardId);
        iterReq.put("ShardIteratorType", "TRIM_HORIZON");
        String iterator = responseEntity(handler.handle("GetShardIterator", iterReq, REGION))
                .get("ShardIterator").asText();

        ObjectNode recReq = MAPPER.createObjectNode();
        recReq.put("ShardIterator", iterator);
        JsonNode timestampNode = responseEntity(handler.handle("GetRecords", recReq, REGION))
                .get("Records").get(0).get("ApproximateArrivalTimestamp");

        assertEquals(new BigDecimal("1786959659.000"), timestampNode.decimalValue());
        assertEquals("1786959659.000", MAPPER.writeValueAsString(timestampNode));
    }

    @Test
    void getRecordsOmitsArrivalTimestampRatherThanThrowingWhenNull() throws Exception {
        // A record loaded back from hybrid/persistent JSON-backed storage (KinesisRecord is
        // @RegisterForReflection with a no-arg constructor) can have a null
        // approximateArrivalTimestamp if it was persisted by an older Floci version, or the
        // field was otherwise dropped - GetRecords must not NPE serializing the whole response
        // just because one record's timestamp is missing.
        createStream("test-stream");

        ObjectNode putReq = MAPPER.createObjectNode();
        putReq.put("StreamName", "test-stream");
        putReq.put("Data", "dGVzdA==");
        putReq.put("PartitionKey", "pk1");
        handler.handle("PutRecord", putReq, REGION);

        ObjectNode descReq = MAPPER.createObjectNode();
        descReq.put("StreamName", "test-stream");
        String shardId = responseEntity(handler.handle("DescribeStream", descReq, REGION))
                .get("StreamDescription").get("Shards").get(0).get("ShardId").asText();

        service.describeStream("test-stream", REGION).getShards().get(0).getRecords().get(0)
                .setApproximateArrivalTimestamp(null);

        ObjectNode iterReq = MAPPER.createObjectNode();
        iterReq.put("StreamName", "test-stream");
        iterReq.put("ShardId", shardId);
        iterReq.put("ShardIteratorType", "TRIM_HORIZON");
        String iterator = responseEntity(handler.handle("GetShardIterator", iterReq, REGION))
                .get("ShardIterator").asText();

        ObjectNode recReq = MAPPER.createObjectNode();
        recReq.put("ShardIterator", iterator);
        Response resp = handler.handle("GetRecords", recReq, REGION);
        assertThat(resp.getStatus(), is(200));

        ObjectNode record = (ObjectNode) responseEntity(resp).get("Records").get(0);
        assertFalse(record.has("ApproximateArrivalTimestamp"));
        assertTrue(record.has("SequenceNumber"));
    }

    private ArrayNode readAllRecords(String streamName) {
        ObjectNode descReq = MAPPER.createObjectNode();
        descReq.put("StreamName", streamName);
        String shardId = responseEntity(handler.handle("DescribeStream", descReq, REGION))
                .get("StreamDescription").get("Shards").get(0).get("ShardId").asText();

        ObjectNode iterReq = MAPPER.createObjectNode();
        iterReq.put("StreamName", streamName);
        iterReq.put("ShardId", shardId);
        iterReq.put("ShardIteratorType", "TRIM_HORIZON");
        String iterator = responseEntity(handler.handle("GetShardIterator", iterReq, REGION))
                .get("ShardIterator").asText();

        ObjectNode recReq = MAPPER.createObjectNode();
        recReq.put("ShardIterator", iterator);
        return (ArrayNode) responseEntity(handler.handle("GetRecords", recReq, REGION)).get("Records");
    }

    private String streamArn(String name) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", name);
        return responseEntity(handler.handle("DescribeStreamSummary", req, REGION))
                .get("StreamDescriptionSummary").get("StreamARN").asText();
    }

    private AwsException putRecord(String streamName, int dataBytes) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", streamName);
        req.put("Data", Base64.getEncoder().encodeToString(new byte[dataBytes]));
        req.put("PartitionKey", "pk1");
        return assertThrows(AwsException.class, () -> handler.handle("PutRecord", req, REGION));
    }

    /**
     * Only PutRecord exposes the ordering: PutRecords resolves the stream in its own preflight, so
     * a swap inside putRecordWithShardId stays invisible there.
     */
    @Test
    void putRecordResolvesTheStreamBeforeValidatingRecordSize() {
        assertEquals("ResourceNotFoundException", putRecord("missing-stream", 1_048_577).getErrorCode());
    }

    /** The partition key counts as UTF-8 bytes: 100 euro signs weigh 300, not 100. */
    @Test
    void putRecordMeasuresThePartitionKeyAsUtf8Bytes() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        req.put("Data", Base64.getEncoder().encodeToString(new byte[1_048_400]));
        req.put("PartitionKey", "€".repeat(100));
        AwsException ex = assertThrows(AwsException.class, () -> handler.handle("PutRecord", req, REGION));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
    }

    @Test
    void putRecordsRejectsMoreThanFiveHundredRecords() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        ArrayNode records = req.putArray("Records");
        for (int i = 0; i < 501; i++) {
            records.addObject().put("Data", "dGVzdA==").put("PartitionKey", "pk1");
        }
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("PutRecords", req, REGION));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
    }

    /** The count cap is inclusive: 500 is the largest batch AWS accepts, not the first rejected. */
    @Test
    void putRecordsAcceptsExactlyFiveHundredRecords() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        ArrayNode records = req.putArray("Records");
        for (int i = 0; i < 500; i++) {
            records.addObject().put("Data", "dGVzdA==").put("PartitionKey", "pk1");
        }
        Response resp = handler.handle("PutRecords", req, REGION);
        assertThat(resp.getStatus(), is(200));
        assertEquals(0, responseEntity(resp).get("FailedRecordCount").asInt());
    }

    /** Eleven records that each pass the per-record check still exceed the 10 MiB request cap. */
    @Test
    void putRecordsRejectsRequestOverTheTotalSizeLimit() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        ArrayNode records = req.putArray("Records");
        String data = Base64.getEncoder().encodeToString(new byte[1_048_000]);
        for (int i = 0; i < 11; i++) {
            records.addObject().put("Data", data).put("PartitionKey", "pk1");
        }
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("PutRecords", req, REGION));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
    }

    @Test
    void describeStreamSummaryReportsTheDefaultMaxRecordSize() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        assertEquals(1024, responseEntity(handler.handle("DescribeStreamSummary", req, REGION))
                .get("StreamDescriptionSummary").get("MaxRecordSizeInKiB").asInt());
    }

    /** An explicit JSON null means "not specified", not a type error — the default still applies. */
    @Test
    void createStreamTreatsANullMaxRecordSizeAsAbsent() {
        ObjectNode create = MAPPER.createObjectNode();
        create.put("StreamName", "null-size-stream");
        create.put("ShardCount", 1);
        create.putNull("MaxRecordSizeInKiB");
        assertThat(handler.handle("CreateStream", create, REGION).getStatus(), is(200));

        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("StreamName", "null-size-stream");
        assertEquals(1024, responseEntity(handler.handle("DescribeStreamSummary", summary, REGION))
                .get("StreamDescriptionSummary").get("MaxRecordSizeInKiB").asInt());
    }

    @Test
    void createStreamHonorsMaxRecordSizeInKiB() {
        ObjectNode create = MAPPER.createObjectNode();
        create.put("StreamName", "big-stream");
        create.put("ShardCount", 1);
        create.put("MaxRecordSizeInKiB", 2048);
        assertThat(handler.handle("CreateStream", create, REGION).getStatus(), is(200));

        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("StreamName", "big-stream");
        assertEquals(2048, responseEntity(handler.handle("DescribeStreamSummary", summary, REGION))
                .get("StreamDescriptionSummary").get("MaxRecordSizeInKiB").asInt());

        ObjectNode put = MAPPER.createObjectNode();
        put.put("StreamName", "big-stream");
        put.put("Data", Base64.getEncoder().encodeToString(new byte[1_500_000]));
        put.put("PartitionKey", "pk1");
        assertThat(handler.handle("PutRecord", put, REGION).getStatus(), is(200));
    }

    private AwsException createStreamWithMaxRecordSize(String name, int maxRecordSizeInKiB) {
        ObjectNode create = MAPPER.createObjectNode();
        create.put("StreamName", name);
        create.put("ShardCount", 1);
        create.put("MaxRecordSizeInKiB", maxRecordSizeInKiB);
        return assertThrows(AwsException.class, () -> handler.handle("CreateStream", create, REGION));
    }

    /**
     * Both bounds, and 0 — a real out-of-range value rather than "not specified". CreateStream
     * reserves ValidationException for the on-demand case, so a range violation is InvalidArgument.
     */
    @Test
    void createStreamRejectsMaxRecordSizeOutOfRange() {
        assertEquals("InvalidArgumentException", createStreamWithMaxRecordSize("low-stream", 1023).getErrorCode());
        assertEquals("InvalidArgumentException", createStreamWithMaxRecordSize("high-stream", 10241).getErrorCode());
        assertEquals("InvalidArgumentException", createStreamWithMaxRecordSize("zero-stream", 0).getErrorCode());
    }

    /** A long or a float would otherwise truncate into range: 4294969344 narrows to 2048. */
    @Test
    void maxRecordSizeMustBeAnIntegerRatherThanTruncatedIntoRange() {
        for (Object bad : new Object[] {4294969344L, 10240.9d, "2048"}) {
            ObjectNode create = MAPPER.createObjectNode();
            create.put("StreamName", "typed-stream");
            create.put("ShardCount", 1);
            if (bad instanceof Long l) {
                create.put("MaxRecordSizeInKiB", l);
            } else if (bad instanceof Double d) {
                create.put("MaxRecordSizeInKiB", d);
            } else {
                create.put("MaxRecordSizeInKiB", (String) bad);
            }
            AwsException ex = assertThrows(AwsException.class,
                    () -> handler.handle("CreateStream", create, REGION));
            assertEquals("SerializationException", ex.getErrorCode(), "for " + bad);
        }
    }

    @Test
    void createStreamAcceptsBothEndsOfTheAllowedRange() {
        for (int size : new int[] {1024, 10240}) {
            ObjectNode create = MAPPER.createObjectNode();
            create.put("StreamName", "edge-" + size);
            create.put("ShardCount", 1);
            create.put("MaxRecordSizeInKiB", size);
            assertThat(handler.handle("CreateStream", create, REGION).getStatus(), is(200));

            ObjectNode summary = MAPPER.createObjectNode();
            summary.put("StreamName", "edge-" + size);
            assertEquals(size, responseEntity(handler.handle("DescribeStreamSummary", summary, REGION))
                    .get("StreamDescriptionSummary").get("MaxRecordSizeInKiB").asInt());
        }
    }

    /** A Data that is not a base64 string cannot weigh nothing, or it smuggles the payload through. */
    @Test
    void putRecordsCountsNonStringDataTowardTheTotalSizeLimit() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        ArrayNode records = req.putArray("Records");
        for (int i = 0; i < 500; i++) {
            records.addObject().putObject("Data").put("pad", "X".repeat(21_000));
        }
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("PutRecords", req, REGION));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
    }

    /** Multi-byte characters must be counted as UTF-8 bytes, not UTF-16 units. */
    @Test
    void putRecordsCountsUndecodableDataAsUtf8Bytes() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        req.putArray("Records").addObject()
                .put("Data", "€".repeat(4_000_000))
                .put("PartitionKey", "pk1");
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("PutRecords", req, REGION));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
    }

    private AwsException updateMaxRecordSize(String streamName, int maxRecordSizeInKiB) {
        ObjectNode update = MAPPER.createObjectNode();
        update.put("StreamARN", streamArn(streamName));
        update.put("MaxRecordSizeInKiB", maxRecordSizeInKiB);
        return assertThrows(AwsException.class,
                () -> handler.handle("UpdateMaxRecordSize", update, REGION));
    }

    @Test
    void updateMaxRecordSizeRejectsMissingStreamArn() {
        ObjectNode update = MAPPER.createObjectNode();
        update.put("MaxRecordSizeInKiB", 2048);
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("UpdateMaxRecordSize", update, REGION));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
    }

    @Test
    void updateMaxRecordSizeRejectsAMissingSize() {
        createStream("test-stream");

        ObjectNode update = MAPPER.createObjectNode();
        update.put("StreamARN", streamArn("test-stream"));
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("UpdateMaxRecordSize", update, REGION));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
        assertEquals("UpdateMaxRecordSize.MaxRecordSizeInKiB is required.", ex.getMessage());
    }

    @Test
    void putRecordsRejectsAnEmptyRecordArray() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        req.putArray("Records");
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("PutRecords", req, REGION));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
    }

    /** The modeled batch size is checked before looking up the stream. */
    @Test
    void putRecordsRejectsAnEmptyBatchBeforeLookingUpTheStream() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "missing-stream");
        req.putArray("Records");
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("PutRecords", req, REGION));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
    }

    /** Pins the cap's value: ten near-limit records sit just under 10 MiB and must be accepted. */
    @Test
    void putRecordsAcceptsARequestJustUnderTheTotalSizeLimit() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        ArrayNode records = req.putArray("Records");
        String data = Base64.getEncoder().encodeToString(new byte[1_048_000]);
        for (int i = 0; i < 10; i++) {
            records.addObject().put("Data", data).put("PartitionKey", "pk1");
        }
        assertThat(handler.handle("PutRecords", req, REGION).getStatus(), is(200));
    }

    /** And the cap itself is inclusive: ten records of 1_048_573 + "pk1" total exactly 10 MiB. */
    @Test
    void putRecordsAcceptsARequestExactlyAtTheTotalSizeLimit() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        ArrayNode records = req.putArray("Records");
        String data = Base64.getEncoder().encodeToString(new byte[1_048_573]);
        for (int i = 0; i < 10; i++) {
            records.addObject().put("Data", data).put("PartitionKey", "pk1");
        }
        Response resp = handler.handle("PutRecords", req, REGION);
        assertThat(resp.getStatus(), is(200));
        assertEquals(0, responseEntity(resp).get("FailedRecordCount").asInt());
    }

    /** Data that fails to decode still occupies the request, so it cannot be free of the cap. */
    @Test
    void putRecordsCountsUndecodableDataTowardTheTotalSizeLimit() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        ArrayNode records = req.putArray("Records");
        String malformed = "!".repeat(1_400_000);
        for (int i = 0; i < 12; i++) {
            records.addObject().put("Data", malformed).put("PartitionKey", "pk1");
        }
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("PutRecords", req, REGION));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
    }

    @Test
    void updateMaxRecordSizeRaisesTheStreamLimit() {
        createStream("test-stream");
        assertEquals("InvalidArgumentException", putRecord("test-stream", 1_500_000).getErrorCode());

        ObjectNode update = MAPPER.createObjectNode();
        update.put("StreamARN", streamArn("test-stream"));
        update.put("MaxRecordSizeInKiB", 2048);
        assertThat(handler.handle("UpdateMaxRecordSize", update, REGION).getStatus(), is(200));

        ObjectNode put = MAPPER.createObjectNode();
        put.put("StreamName", "test-stream");
        put.put("Data", Base64.getEncoder().encodeToString(new byte[1_500_000]));
        put.put("PartitionKey", "pk1");
        assertThat(handler.handle("PutRecord", put, REGION).getStatus(), is(200));
    }

    @Test
    void updateMaxRecordSizeRejectsOnDemandStreams() {
        ObjectNode create = MAPPER.createObjectNode();
        create.put("StreamName", "on-demand-stream");
        create.put("ShardCount", 1);
        create.putObject("StreamModeDetails").put("StreamMode", "ON_DEMAND");
        assertThat(handler.handle("CreateStream", create, REGION).getStatus(), is(200));

        ObjectNode update = MAPPER.createObjectNode();
        update.put("StreamARN", streamArn("on-demand-stream"));
        update.put("MaxRecordSizeInKiB", 2048);
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("UpdateMaxRecordSize", update, REGION));
        assertEquals("ValidationException", ex.getErrorCode());
    }

    @Test
    void updateMaxRecordSizeRejectsAStreamThatIsNotActive() {
        createStream("test-stream");
        service.describeStream("test-stream", REGION).setStreamStatus("UPDATING");

        ObjectNode update = MAPPER.createObjectNode();
        update.put("StreamARN", streamArn("test-stream"));
        update.put("MaxRecordSizeInKiB", 2048);
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("UpdateMaxRecordSize", update, REGION));
        assertEquals("ResourceInUseException", ex.getErrorCode());
    }

    @Test
    void updateMaxRecordSizeRejectsSizeOutOfRange() {
        createStream("test-stream");
        assertEquals("ValidationException", updateMaxRecordSize("test-stream", 10241).getErrorCode());
        assertEquals("ValidationException", updateMaxRecordSize("test-stream", 1023).getErrorCode());
    }

    /** Modeled bounds reject an invalid size before looking up the stream. */
    @Test
    void updateMaxRecordSizeRejectsAnInvalidSizeBeforeLookingUpTheStream() {
        ObjectNode update = MAPPER.createObjectNode();
        update.put("StreamARN", "arn:aws:kinesis:us-east-1:123456789012:stream/missing-stream");
        update.put("MaxRecordSizeInKiB", 10241);
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("UpdateMaxRecordSize", update, REGION));
        assertEquals("ValidationException", ex.getErrorCode());
    }

    private ObjectNode updateShardCount(String streamName, int targetShardCount, String scalingType) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", streamName);
        req.put("TargetShardCount", targetShardCount);
        req.put("ScalingType", scalingType);
        return responseEntity(handler.handle("UpdateShardCount", req, REGION));
    }

    @Test
    void updateShardCountRejectsNonIntegerTargetThroughTheModeledShape() {
        createStream("test-stream");
        ObjectNode request = MAPPER.createObjectNode();
        request.put("StreamName", "test-stream");
        request.put("TargetShardCount", "two");
        request.put("ScalingType", "UNIFORM_SCALING");

        AwsException error = assertThrows(AwsException.class,
                () -> handler.handle("UpdateShardCount", request, REGION));
        assertEquals("SerializationException", error.getErrorCode());
        assertEquals("UpdateShardCount.TargetShardCount must be a integer.", error.getMessage());
    }

    @Test
    void updateShardCountScalesUpAndReturnsExpectedShape() {
        createStream("test-stream", 2);

        ObjectNode response = updateShardCount("test-stream", 4, "UNIFORM_SCALING");
        assertEquals("test-stream", response.get("StreamName").asText());
        assertEquals(streamArn("test-stream"), response.get("StreamARN").asText());
        assertEquals(2, response.get("CurrentShardCount").asInt());
        assertEquals(4, response.get("TargetShardCount").asInt());

        ObjectNode summaryReq = MAPPER.createObjectNode();
        summaryReq.put("StreamName", "test-stream");
        ObjectNode summary = (ObjectNode) responseEntity(
                handler.handle("DescribeStreamSummary", summaryReq, REGION)).get("StreamDescriptionSummary");
        assertEquals(4, summary.get("OpenShardCount").asInt());
    }

    @Test
    void updateShardCountScalesDown() {
        createStream("test-stream", 4);

        ObjectNode response = updateShardCount("test-stream", 2, "UNIFORM_SCALING");
        assertEquals(4, response.get("CurrentShardCount").asInt());
        assertEquals(2, response.get("TargetShardCount").asInt());

        ObjectNode summaryReq = MAPPER.createObjectNode();
        summaryReq.put("StreamName", "test-stream");
        ObjectNode summary = (ObjectNode) responseEntity(
                handler.handle("DescribeStreamSummary", summaryReq, REGION)).get("StreamDescriptionSummary");
        assertEquals(2, summary.get("OpenShardCount").asInt());
    }

    @Test
    void updateShardCountRejectsTargetAboveDouble() {
        createStream("test-stream", 2);

        AwsException ex = assertThrows(AwsException.class,
                () -> updateShardCount("test-stream", 5, "UNIFORM_SCALING"));
        assertEquals("LimitExceededException", ex.getErrorCode());
    }

    @Test
    void updateShardCountRejectsTargetBelowHalf() {
        createStream("test-stream", 4);

        AwsException ex = assertThrows(AwsException.class,
                () -> updateShardCount("test-stream", 1, "UNIFORM_SCALING"));
        assertEquals("LimitExceededException", ex.getErrorCode());
    }

    @Test
    void updateShardCountRejectsNonUniformScalingType() {
        createStream("test-stream", 2);

        AwsException ex = assertThrows(AwsException.class,
                () -> updateShardCount("test-stream", 4, "BOGUS"));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
    }

    @Test
    void updateShardCountRejectsOnDemandStream() {
        ObjectNode create = MAPPER.createObjectNode();
        create.put("StreamName", "on-demand-stream");
        create.put("ShardCount", 2);
        create.putObject("StreamModeDetails").put("StreamMode", "ON_DEMAND");
        assertThat(handler.handle("CreateStream", create, REGION).getStatus(), is(200));

        AwsException ex = assertThrows(AwsException.class,
                () -> updateShardCount("on-demand-stream", 4, "UNIFORM_SCALING"));
        assertEquals("ValidationException", ex.getErrorCode());
    }

    @Test
    void updateShardCountRejectsAStreamThatIsNotActive() {
        createStream("test-stream", 2);
        service.describeStream("test-stream", REGION).setStreamStatus("UPDATING");

        AwsException ex = assertThrows(AwsException.class,
                () -> updateShardCount("test-stream", 4, "UNIFORM_SCALING"));
        assertEquals("ResourceInUseException", ex.getErrorCode());
    }

    @Test
    void updateShardCountRejectsUnknownStream() {
        AwsException ex = assertThrows(AwsException.class,
                () -> updateShardCount("missing-stream", 2, "UNIFORM_SCALING"));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    @Test
    void updateShardCountLineageAfterSplitCoversWholeParentRange() {
        createStream("test-stream", 1);

        updateShardCount("test-stream", 2, "UNIFORM_SCALING");

        ObjectNode descReq = MAPPER.createObjectNode();
        descReq.put("StreamName", "test-stream");
        ArrayNode shards = (ArrayNode) responseEntity(
                handler.handle("DescribeStream", descReq, REGION)).get("StreamDescription").get("Shards");
        assertEquals(3, shards.size());

        ObjectNode parent = (ObjectNode) shards.get(0);
        List<ObjectNode> children = new ArrayList<>();
        for (JsonNode s : shards) {
            if (s.has("ParentShardId") && s.get("ParentShardId").asText().equals(parent.get("ShardId").asText())) {
                children.add((ObjectNode) s);
            }
        }
        assertEquals(2, children.size());
        assertFalse(children.get(0).has("AdjacentParentShardId"));
        assertFalse(children.get(1).has("AdjacentParentShardId"));
    }

    @Test
    void createStreamAppliesTagsFromTheRequest() {
        ObjectNode create = MAPPER.createObjectNode();
        create.put("StreamName", "tagged-stream");
        create.put("ShardCount", 1);
        create.putObject("Tags").put("Foo", "Bar").put("gw:example", "kinesis");
        assertThat(handler.handle("CreateStream", create, REGION).getStatus(), is(200));

        assertEquals(Map.of("Foo", "Bar", "gw:example", "kinesis"),
                service.listTagsForStream("tagged-stream", REGION));
    }

    @Test
    void createStreamWithoutTagsLeavesTheStreamUntagged() {
        createStream("test-stream");

        assertTrue(service.listTagsForStream("test-stream", REGION).isEmpty());
    }

    /** A present Tags map must meet the model's minimum size before stream creation. */
    @Test
    void createStreamRejectsAnExplicitlyEmptyTagsObject() {
        ObjectNode create = MAPPER.createObjectNode();
        create.put("StreamName", "empty-tags-stream");
        create.put("ShardCount", 1);
        create.putObject("Tags");
        AwsException error = assertThrows(AwsException.class,
                () -> handler.handle("CreateStream", create, REGION));
        assertEquals("InvalidArgumentException", error.getErrorCode());
        assertTrue(service.listStreamDetails(REGION).stream()
                .noneMatch(stream -> "empty-tags-stream".equals(stream.getStreamName())));
    }

    /** CreateStream and AddTagsToStream share one parser, so the same map has to land either way. */
    @Test
    void addTagsToStreamAppliesTheSameTagsCreateStreamWould() {
        createStream("test-stream");

        ObjectNode add = MAPPER.createObjectNode();
        add.put("StreamName", "test-stream");
        add.putObject("Tags").put("Foo", "Bar").put("gw:example", "kinesis");
        assertThat(handler.handle("AddTagsToStream", add, REGION).getStatus(), is(200));

        assertEquals(Map.of("Foo", "Bar", "gw:example", "kinesis"),
                service.listTagsForStream("test-stream", REGION));
    }

    @Test
    void createStreamRejectsANonStringTagValueBeforeCreatingTheStream() {
        for (ObjectNode tags : nonStringTagValues()) {
            ObjectNode create = MAPPER.createObjectNode();
            create.put("StreamName", "rejected-tags-stream");
            create.put("ShardCount", 1);
            create.set("Tags", tags);
            AwsException ex = assertThrows(AwsException.class,
                    () -> handler.handle("CreateStream", create, REGION));
            assertEquals("SerializationException", ex.getErrorCode());
            assertTrue(ex.getMessage().startsWith("CreateStream.Tags."));
            assertTrue(ex.getMessage().endsWith(" must be a string."));
            assertEquals(400, ex.getHttpStatus());
        }

        ObjectNode describe = MAPPER.createObjectNode();
        describe.put("StreamName", "rejected-tags-stream");
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("DescribeStream", describe, REGION));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    @Test
    void createStreamRejectsANonObjectTagsMember() {
        for (JsonNode tags : List.of(MAPPER.getNodeFactory().textNode("Foo=Bar"),
                MAPPER.getNodeFactory().numberNode(5), MAPPER.createArrayNode().add("Foo"))) {
            ObjectNode create = MAPPER.createObjectNode();
            create.put("StreamName", "rejected-tags-stream");
            create.put("ShardCount", 1);
            create.set("Tags", tags);
            AwsException ex = assertThrows(AwsException.class,
                    () -> handler.handle("CreateStream", create, REGION));
            assertEquals("SerializationException", ex.getErrorCode());
        }
    }

    @Test
    void createStreamWithANullTagsMemberLeavesTheStreamUntagged() {
        ObjectNode create = MAPPER.createObjectNode();
        create.put("StreamName", "null-tags-stream");
        create.put("ShardCount", 1);
        create.putNull("Tags");
        assertThat(handler.handle("CreateStream", create, REGION).getStatus(), is(200));

        assertTrue(service.listTagsForStream("null-tags-stream", REGION).isEmpty());
    }

    @Test
    void addTagsToStreamRejectsANonStringTagValueWithoutTouchingExistingTags() {
        createStream("test-stream");
        ObjectNode add = MAPPER.createObjectNode();
        add.put("StreamName", "test-stream");
        add.putObject("Tags").put("Foo", "Bar");
        assertThat(handler.handle("AddTagsToStream", add, REGION).getStatus(), is(200));

        for (ObjectNode tags : nonStringTagValues()) {
            ObjectNode bad = MAPPER.createObjectNode();
            bad.put("StreamName", "test-stream");
            bad.set("Tags", tags);
            AwsException ex = assertThrows(AwsException.class,
                    () -> handler.handle("AddTagsToStream", bad, REGION));
            assertEquals("SerializationException", ex.getErrorCode());
            assertEquals(400, ex.getHttpStatus());
        }

        assertEquals(Map.of("Foo", "Bar"), service.listTagsForStream("test-stream", REGION));
    }

    @Test
    void removeTagsFromStreamRejectsANonStringTagKey() {
        createStream("test-stream");
        ObjectNode add = MAPPER.createObjectNode();
        add.put("StreamName", "test-stream");
        add.putObject("Tags").put("Foo", "Bar");
        assertThat(handler.handle("AddTagsToStream", add, REGION).getStatus(), is(200));

        for (JsonNode tagKeys : List.of(MAPPER.createArrayNode().add(5), MAPPER.createArrayNode().add(true),
                MAPPER.createArrayNode().addNull(), MAPPER.createArrayNode().add("Foo").add(5),
                MAPPER.getNodeFactory().textNode("Foo"), MAPPER.createObjectNode().put("a", "Foo"))) {
            ObjectNode remove = MAPPER.createObjectNode();
            remove.put("StreamName", "test-stream");
            remove.set("TagKeys", tagKeys);
            AwsException ex = assertThrows(AwsException.class,
                    () -> handler.handle("RemoveTagsFromStream", remove, REGION));
            assertEquals("SerializationException", ex.getErrorCode());
            assertTrue(ex.getMessage().startsWith("RemoveTagsFromStream.TagKeys"));
            assertEquals(400, ex.getHttpStatus());
        }

        assertEquals(Map.of("Foo", "Bar"), service.listTagsForStream("test-stream", REGION));
    }

    @Test
    void removeTagsFromStreamStillRemovesStringTagKeys() {
        createStream("test-stream");
        ObjectNode add = MAPPER.createObjectNode();
        add.put("StreamName", "test-stream");
        add.putObject("Tags").put("Foo", "Bar").put("Baz", "Qux");
        assertThat(handler.handle("AddTagsToStream", add, REGION).getStatus(), is(200));

        ObjectNode remove = MAPPER.createObjectNode();
        remove.put("StreamName", "test-stream");
        remove.putArray("TagKeys").add("Foo");
        assertThat(handler.handle("RemoveTagsFromStream", remove, REGION).getStatus(), is(200));

        assertEquals(Map.of("Baz", "Qux"), service.listTagsForStream("test-stream", REGION));
    }

    @Test
    void removeTagsFromStreamRejectsNullOrMissingTagKeys() {
        createStream("test-stream");
        ObjectNode add = MAPPER.createObjectNode();
        add.put("StreamName", "test-stream");
        add.putObject("Tags").put("Foo", "Bar");
        assertThat(handler.handle("AddTagsToStream", add, REGION).getStatus(), is(200));

        ObjectNode nullKeys = MAPPER.createObjectNode();
        nullKeys.put("StreamName", "test-stream");
        nullKeys.putNull("TagKeys");
        AwsException nullError = assertThrows(AwsException.class,
                () -> handler.handle("RemoveTagsFromStream", nullKeys, REGION));
        assertEquals("InvalidArgumentException", nullError.getErrorCode());
        ObjectNode missingKeys = MAPPER.createObjectNode();
        missingKeys.put("StreamName", "test-stream");
        AwsException missingError = assertThrows(AwsException.class,
                () -> handler.handle("RemoveTagsFromStream", missingKeys, REGION));
        assertEquals("InvalidArgumentException", missingError.getErrorCode());

        assertEquals(Map.of("Foo", "Bar"), service.listTagsForStream("test-stream", REGION));
    }

    @Test
    void listShardsResumesFromTokenAloneWithoutTheStreamName() {
        createStream("test-stream", 3);

        ObjectNode first = MAPPER.createObjectNode();
        first.put("StreamName", "test-stream");
        first.put("MaxResults", 1);
        ObjectNode firstPage = responseEntity(handler.handle("ListShards", first, REGION));
        String token = firstPage.get("NextToken").asText();
        String firstShardId = firstPage.get("Shards").get(0).get("ShardId").asText();

        ObjectNode second = MAPPER.createObjectNode();
        second.put("NextToken", token);
        ObjectNode secondPage = responseEntity(handler.handle("ListShards", second, REGION));

        assertEquals(2, secondPage.get("Shards").size(), "the token alone resumes after the first shard");
        for (JsonNode shard : secondPage.get("Shards")) {
            assertFalse(shard.get("ShardId").asText().equals(firstShardId));
        }
    }

    @Test
    void listShardsRejectsStreamNameTogetherWithNextToken() {
        createStream("test-stream", 3);

        ObjectNode first = MAPPER.createObjectNode();
        first.put("StreamName", "test-stream");
        first.put("MaxResults", 1);
        String token = responseEntity(handler.handle("ListShards", first, REGION)).get("NextToken").asText();

        ObjectNode both = MAPPER.createObjectNode();
        both.put("StreamName", "test-stream");
        both.put("NextToken", token);
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("ListShards", both, REGION));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
    }

    @Test
    void listShardsAcceptsAStreamArnThatNamesTheTokenStream() {
        createStream("test-stream", 3);

        ObjectNode first = MAPPER.createObjectNode();
        first.put("StreamName", "test-stream");
        first.put("MaxResults", 1);
        String token = responseEntity(handler.handle("ListShards", first, REGION)).get("NextToken").asText();

        ObjectNode second = MAPPER.createObjectNode();
        second.put("NextToken", token);
        second.put("StreamARN", STREAM_ARN);
        ObjectNode secondPage = responseEntity(handler.handle("ListShards", second, REGION));

        assertEquals(2, secondPage.get("Shards").size());
    }

    @Test
    void listShardsRejectsAStreamArnFromAnotherStream() {
        createStream("test-stream", 3);

        ObjectNode first = MAPPER.createObjectNode();
        first.put("StreamName", "test-stream");
        first.put("MaxResults", 1);
        String token = responseEntity(handler.handle("ListShards", first, REGION)).get("NextToken").asText();

        ObjectNode second = MAPPER.createObjectNode();
        second.put("NextToken", token);
        second.put("StreamARN", "arn:aws:kinesis:us-east-1:123456789012:stream/other-stream");
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("ListShards", second, REGION));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
    }

    @Test
    void listShardsRejectsAnExpiredNextToken() {
        KinesisJsonHandler zeroTtlHandler = new KinesisJsonHandler(service, MAPPER, 0);
        createStream("test-stream", 3);

        ObjectNode first = MAPPER.createObjectNode();
        first.put("StreamName", "test-stream");
        first.put("MaxResults", 1);
        ObjectNode firstPage = (ObjectNode) zeroTtlHandler.handle("ListShards", first, REGION).getEntity();
        String token = firstPage.get("NextToken").asText();

        ObjectNode second = MAPPER.createObjectNode();
        second.put("NextToken", token);
        AwsException ex = assertThrows(AwsException.class,
                () -> zeroTtlHandler.handle("ListShards", second, REGION));
        assertEquals("ExpiredNextTokenException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    /** Every non-string tag value shape — number, boolean, null, object, array — plus a map mixing a valid and an invalid value. */
    private static List<ObjectNode> nonStringTagValues() {
        ObjectNode number = MAPPER.createObjectNode();
        number.put("Foo", 5);
        ObjectNode bool = MAPPER.createObjectNode();
        bool.put("Foo", true);
        ObjectNode nul = MAPPER.createObjectNode();
        nul.putNull("Foo");
        ObjectNode object = MAPPER.createObjectNode();
        object.putObject("Foo");
        ObjectNode array = MAPPER.createObjectNode();
        array.putArray("Foo");
        ObjectNode mixed = MAPPER.createObjectNode();
        mixed.put("Foo", "Bar");
        mixed.put("Baz", 5);
        return List.of(number, bool, nul, object, array, mixed);
    }
}
