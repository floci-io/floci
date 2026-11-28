package io.floci.aws.services.kinesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Kinesis DryRun, as measured on AWS: checked as the call is, answered DryRunOperationException, nothing changed. */
class KinesisDryRunTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "123456789012";
    private static final String STREAM = "dry";
    private static final String STREAM_ARN = "arn:aws:kinesis:us-east-1:123456789012:stream/dry";
    private static final String SHARD = "shardId-000000000000";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KinesisService service;
    private KinesisJsonHandler handler;

    @BeforeEach
    void setUp() {
        service = new KinesisService(new InMemoryStorage<>(), new InMemoryStorage<>(), new RegionResolver(REGION, ACCOUNT));
        handler = new KinesisJsonHandler(service, MAPPER, 300_000);
        handler.handle("CreateStream", MAPPER.createObjectNode().put("StreamName", STREAM).put("ShardCount", 1), REGION);
    }

    @Test
    void dryRunPutsAreAnsweredAsAwsDoesAndWriteNothing() {
        assertDryRun("PutRecord", putRecord(STREAM, "k"));
        ObjectNode puts = MAPPER.createObjectNode().put("StreamName", STREAM).put("DryRun", true);
        puts.putArray("Records").addObject().put("Data", data()).put("PartitionKey", "k");
        assertDryRun("PutRecords", puts);

        ObjectNode iterator = MAPPER.createObjectNode().put("StreamName", STREAM).put("ShardId", SHARD)
                .put("ShardIteratorType", "TRIM_HORIZON");
        String cursor = ((ObjectNode) handler.handle("GetShardIterator", iterator, REGION).getEntity())
                .path("ShardIterator").asText();
        ObjectNode page = (ObjectNode) handler.handle("GetRecords", MAPPER.createObjectNode().put("ShardIterator", cursor),
                REGION).getEntity();
        assertEquals(0, page.path("Records").size());
    }

    @Test
    void dryRunReadsAreAnsweredInPlaceOfTheirResult() {
        assertDryRun("GetShardIterator", MAPPER.createObjectNode().put("StreamName", STREAM).put("ShardId", SHARD)
                .put("ShardIteratorType", "TRIM_HORIZON").put("DryRun", true));
        String cursor = service.getShardIterator(STREAM, SHARD, "TRIM_HORIZON", null, null, REGION);
        assertDryRun("GetRecords", MAPPER.createObjectNode().put("ShardIterator", cursor).put("DryRun", true));
        String consumer = service.registerStreamConsumer(STREAM_ARN, "dry-consumer", REGION).getConsumerArn();
        ObjectNode subscribe = MAPPER.createObjectNode().put("ConsumerARN", consumer).put("ShardId", SHARD)
                .put("DryRun", true);
        subscribe.putObject("StartingPosition").put("Type", "LATEST");
        assertDryRun("SubscribeToShard", subscribe);
    }

    /** Measured: the stream is checked, the shard isn't. */
    @Test
    void aDryRunChecksTheStreamButNotTheShard() {
        AwsException missing = assertThrows(AwsException.class,
                () -> handler.handle("PutRecord", putRecord("absent", "k"), REGION));
        assertEquals("ResourceNotFoundException", missing.getErrorCode());
        assertDryRun("GetShardIterator", MAPPER.createObjectNode().put("StreamName", STREAM)
                .put("ShardId", "shardId-000000000009").put("ShardIteratorType", "TRIM_HORIZON").put("DryRun", true));
    }

    @Test
    void aDryRunOfARequestTheCallRefusesIsRefusedAlike() {
        AwsException dryRun = assertThrows(AwsException.class,
                () -> handler.handle("PutRecord", putRecord(STREAM, ""), REGION));
        AwsException call = assertThrows(AwsException.class,
                () -> handler.handle("PutRecord", putRecord(STREAM, "").put("DryRun", false), REGION));
        assertEquals(call.getErrorCode(), dryRun.getErrorCode());
        assertEquals(call.getMessage(), dryRun.getMessage());
        AwsException badIterator = assertThrows(AwsException.class, () -> handler.handle("GetRecords",
                MAPPER.createObjectNode().put("ShardIterator", "not-an-iterator").put("DryRun", true), REGION));
        assertEquals("InvalidArgumentException", badIterator.getErrorCode());
    }

    /** Kinesis's deserializer reads DryRun as KMS's does, but words a decimal as a number (measured). */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "\"maybe\"|STRING_VALUE can not be converted to an Boolean",
        "1|NUMBER_VALUE can not be converted to a Boolean",
        "1.5|NUMBER_VALUE can not be converted to a Boolean",
        "[]|Start of list found where not expected",
        "{}|Start of structure or map found where not expected."})
    void aDryRunThatIsNoBooleanIsRefusedAsAwsRefusesIt(String json, String message) throws Exception {
        ObjectNode request = putRecord(STREAM, "k");
        request.set("DryRun", MAPPER.readTree(json));

        AwsException refused = assertThrows(AwsException.class, () -> handler.handle("PutRecord", request, REGION));

        assertEquals("SerializationException", refused.getErrorCode());
        assertEquals(message, refused.getMessage());
    }

    @Test
    void aDryRunNamedByAStringIsADryRun() {
        ObjectNode request = putRecord(STREAM, "k").put("DryRun", "yes");
        assertDryRun("PutRecord", request);
    }

    private void assertDryRun(String operation, ObjectNode request) {
        AwsException dryRun = assertThrows(AwsException.class, () -> handler.handle(operation, request, REGION));
        assertEquals("DryRunOperationException", dryRun.getErrorCode());
        assertEquals("DryRunOperation validation succeeded while calling " + operation
                + " operation.: Request would have succeeded, but DryRun flag is set.", dryRun.getMessage());
    }

    private static ObjectNode putRecord(String stream, String partitionKey) {
        return MAPPER.createObjectNode().put("StreamName", stream).put("PartitionKey", partitionKey)
                .put("Data", data()).put("DryRun", true);
    }

    private static String data() {
        return Base64.getEncoder().encodeToString("hi".getBytes());
    }
}
