package io.github.hectorvent.floci.services.kinesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BinaryNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

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
        handler = new KinesisJsonHandler(service, MAPPER);
    }

    private void createStream(String name) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", name);
        req.put("ShardCount", 1);
        assertThat(handler.handle("CreateStream", req, REGION).getStatus(), is(200));
    }

    private ObjectNode responseEntity(Response response) {
        return (ObjectNode) response.getEntity();
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
        var timestamp = description.get("StreamCreationTimestamp");
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
    void arnFallbackWhenNameIsEmpty() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "");
        req.put("StreamARN", STREAM_ARN);
        Response resp = handler.handle("DescribeStream", req, REGION);
        assertThat(resp.getStatus(), is(200));
        assertEquals("test-stream",
                responseEntity(resp).get("StreamDescription").get("StreamName").asText());
    }

    @Test
    void arnFallbackWhenNameIsWhitespace() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "   ");
        req.put("StreamARN", STREAM_ARN);
        Response resp = handler.handle("DescribeStream", req, REGION);
        assertThat(resp.getStatus(), is(200));
        assertEquals("test-stream",
                responseEntity(resp).get("StreamDescription").get("StreamName").asText());
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
        records.addObject().put("PartitionKey", "pk3");
        records.addObject().putNull("Data").put("PartitionKey", "pk4");
        records.addObject().put("Data", true).put("PartitionKey", "pk5");
        records.addObject().put("Data", 1234).put("PartitionKey", "pk6");
        ObjectNode containerRow = records.addObject();
        containerRow.putObject("Data");
        containerRow.put("PartitionKey", "pk7");
        ObjectNode binaryRow = records.addObject();
        binaryRow.set("Data", BinaryNode.valueOf(new byte[] {1, 2, 3}));
        binaryRow.put("PartitionKey", "pk8");

        Response resp = handler.handle("PutRecords", req, REGION);
        assertThat(resp.getStatus(), is(200));
        ObjectNode body = responseEntity(resp);
        assertEquals(5, body.get("FailedRecordCount").asInt());

        ArrayNode results = (ArrayNode) body.get("Records");
        assertTrue(results.get(0).has("SequenceNumber"));
        assertTrue(results.get(1).has("SequenceNumber"));
        for (int i = 2; i <= 6; i++) {
            assertEquals("InternalFailure", results.get(i).get("ErrorCode").asText());
            assertEquals("Data must be a base64-encoded string.", results.get(i).get("ErrorMessage").asText());
        }
        assertTrue(results.get(7).has("SequenceNumber"));

        ArrayNode landed = readAllRecords("test-stream");
        assertEquals(3, landed.size());
        assertEquals("dGVzdA==", landed.get(0).get("Data").asText());
        assertEquals("", landed.get(1).get("Data").asText());
        assertEquals("AQID", landed.get(2).get("Data").asText());
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
            assertEquals("InvalidArgumentException", ex.getErrorCode(), "for " + bad);
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
        assertEquals("MaxRecordSizeInKiB is required", ex.getMessage());
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

    /** The preflight runs first, so an empty batch on an unknown stream is still a 404. */
    @Test
    void putRecordsResolvesTheStreamBeforeValidatingTheRecordCount() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "missing-stream");
        req.putArray("Records");
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("PutRecords", req, REGION));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
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

    /** Resolve before validate, the same ordering PutRecord follows. */
    @Test
    void updateMaxRecordSizeResolvesTheStreamBeforeValidatingTheSize() {
        ObjectNode update = MAPPER.createObjectNode();
        update.put("StreamARN", "arn:aws:kinesis:us-east-1:123456789012:stream/missing-stream");
        update.put("MaxRecordSizeInKiB", 10241);
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("UpdateMaxRecordSize", update, REGION));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
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

    /** An empty Tags object is not an error; it simply leaves the stream untagged. */
    @Test
    void createStreamWithAnEmptyTagsObjectLeavesTheStreamUntagged() {
        ObjectNode create = MAPPER.createObjectNode();
        create.put("StreamName", "empty-tags-stream");
        create.put("ShardCount", 1);
        create.putObject("Tags");
        assertThat(handler.handle("CreateStream", create, REGION).getStatus(), is(200));

        assertTrue(service.listTagsForStream("empty-tags-stream", REGION).isEmpty());
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
}
