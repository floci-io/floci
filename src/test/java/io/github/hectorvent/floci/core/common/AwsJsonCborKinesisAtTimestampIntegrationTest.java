package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.dataformat.cbor.CBORGenerator;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * End-to-end reproduction of floci-io/floci#2368's actual reported symptom, driven
 * through the real HTTP CBOR endpoint (not just the unit-level encode/decode round-trip
 * already covered by {@link AwsJsonCborSerializerTest}): an {@code AT_TIMESTAMP} Kinesis
 * shard iterator built over the CBOR wire protocol - the AWS SDK for Java v2's default
 * transport - for a timestamp strictly before an already-written record.
 * <p>
 * Before the fix, this never found that record: {@code handleGetShardIterator} treated
 * the decoded {@code Timestamp} as epoch seconds when the CBOR wire value a real client
 * actually sends is epoch milliseconds, landing the iterator around the year 58598 -
 * past every real record - so {@code GetRecords} returned an empty page forever, with no
 * error at all.
 * <p>
 * The {@code GetShardIterator} request's {@code Timestamp} is hand-built with a raw
 * tag(1) epoch-<em>millisecond</em> value via {@link #cborTag1MillisTimestampRequest},
 * deliberately bypassing {@link AwsJsonCborController#nodeToLegacyCbor} (the method that
 * would normally build this exact request, correctly, for the legacy
 * {@code application/x-amz-cbor-1.1} dialect Kinesis uses) - building the request
 * through Floci's own encoder would only prove Floci's own encoder and decoder agree
 * with each other, not that Floci actually speaks the real AWS wire format a genuine
 * CBOR-default SDK client sends. Hand-building the millis value directly is what makes
 * this test fail against the pre-fix code and pass against the fix, matching the
 * issue's own wire capture (tag(1) followed by an 8-byte integer, e.g.
 * {@code c1 1b 000001a013accc38}).
 * <p>
 * The other requests here (none carry a timestamp field, so the choice of encoder makes
 * no difference to their bytes) go through {@link AwsJsonCborController#nodeToSmithyCbor}
 * as a matter of convenience, and CBOR response bodies are decoded with a plain CBOR
 * {@link ObjectMapper} since the server mirrors the request's own content type back on
 * the response.
 */
@QuarkusTest
class AwsJsonCborKinesisAtTimestampIntegrationTest {

    private static final String CBOR_CONTENT_TYPE = "application/x-amz-cbor-1.1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper CBOR = new ObjectMapper(new CBORFactory());

    @Test
    void atTimestampShardIteratorOverCborFindsAnAlreadyWrittenRecord() throws Exception {
        String streamName = "cbor-at-timestamp-" + System.nanoTime();

        cborCall("Kinesis_20131202.CreateStream",
                AwsJsonCborController.nodeToSmithyCbor(
                        JSON.createObjectNode().put("StreamName", streamName).put("ShardCount", 1)));

        // Cutoff strictly before the record about to be written.
        Instant cutoff = Instant.now().minusSeconds(30);

        cborCall("Kinesis_20131202.PutRecord", AwsJsonCborController.nodeToSmithyCbor(JSON.createObjectNode()
                .put("StreamName", streamName)
                .put("Data", Base64.getEncoder().encodeToString("hello".getBytes()))
                .put("PartitionKey", "pk")));

        byte[] getShardIteratorRequest = cborTag1MillisTimestampRequest(
                streamName, "shardId-000000000000", cutoff.toEpochMilli());
        JsonNode shardIteratorResponse = cborCall("Kinesis_20131202.GetShardIterator", getShardIteratorRequest);
        String shardIterator = shardIteratorResponse.path("ShardIterator").asText();
        assertFalse(shardIterator.isEmpty(), "GetShardIterator must actually return an iterator");

        JsonNode records = cborCall("Kinesis_20131202.GetRecords", AwsJsonCborController.nodeToSmithyCbor(
                JSON.createObjectNode().put("ShardIterator", shardIterator)));

        // Before the #2368 fix, this was always 0 regardless of how long the caller waited.
        assertEquals(1, records.path("Records").size(),
                "AT_TIMESTAMP over CBOR must find the record written after the cutoff");
    }

    /**
     * Hand-builds a {@code GetShardIterator} CBOR request body with {@code Timestamp}
     * encoded exactly as a real AWS SDK for Java v2 client sends it: CBOR tag(1)
     * immediately followed by an integer epoch-<em>millisecond</em> value - see this
     * class's own javadoc for why this must not go through
     * {@link AwsJsonCborController#nodeToSmithyCbor}.
     */
    private static byte[] cborTag1MillisTimestampRequest(String streamName, String shardId, long epochMillis)
            throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CBORFactory factory = (CBORFactory) CBOR.getFactory();
        try (CBORGenerator gen = factory.createGenerator(out)) {
            gen.writeStartObject();
            gen.writeStringField("StreamName", streamName);
            gen.writeStringField("ShardId", shardId);
            gen.writeStringField("ShardIteratorType", "AT_TIMESTAMP");
            gen.writeFieldName("Timestamp");
            gen.writeTag(1);
            gen.writeNumber(epochMillis);
            gen.writeEndObject();
        }
        return out.toByteArray();
    }

    private static JsonNode cborCall(String target, byte[] requestCbor) throws Exception {
        byte[] responseCbor = given()
                .header("X-Amz-Target", target)
                .contentType(CBOR_CONTENT_TYPE)
                .body(requestCbor)
                .when().post("/")
                .then().statusCode(200)
                .extract().asByteArray();
        return CBOR.readTree(responseCbor);
    }
}
