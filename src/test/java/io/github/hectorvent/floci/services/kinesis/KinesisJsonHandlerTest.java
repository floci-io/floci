package io.github.hectorvent.floci.services.kinesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KinesisJsonHandlerTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "123456789012";
    private static final String STREAM_ARN = "arn:aws:kinesis:us-east-1:123456789012:stream/test-stream";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KinesisJsonHandler handler;

    @BeforeEach
    void setUp() {
        KinesisService service = new KinesisService(
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
}
