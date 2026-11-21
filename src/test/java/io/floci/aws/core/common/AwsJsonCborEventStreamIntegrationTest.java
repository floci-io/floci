package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.dataformat.cbor.CBORParser;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EventStreamMessages;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kinesis.KinesisClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AwsJsonCborEventStreamIntegrationTest {

    private static final ObjectMapper CBOR = new ObjectMapper(new CBORFactory());

    @ParameterizedTest
    @ValueSource(strings = {"application/vnd.amazon.eventstream", "application/cbor"})
    void subscribeReturnsFramedCborEventsWithBinaryRecordsAndTaggedDates(String accept) throws Exception {
        String stream = "cbor-events-" + UUID.randomUUID();
        byte[] content = {0, (byte) 255, 1};
        try (KinesisClient kinesis = AwsTestClients.create(KinesisClient.builder(), "test", Region.US_EAST_1)) {
            kinesis.createStream(request -> request.streamName(stream).shardCount(1));
            String consumer = null;
            try {
                String arn = kinesis.describeStreamSummary(request -> request.streamName(stream))
                        .streamDescriptionSummary().streamARN();
                consumer = kinesis.registerStreamConsumer(request -> request.streamARN(arn).consumerName("reader"))
                        .consumer().consumerARN();
                kinesis.putRecord(request -> request.streamName(stream).partitionKey("pk")
                        .data(SdkBytes.fromByteArray(content)));
                byte[] request = CBOR.writeValueAsBytes(Map.of("ConsumerARN", consumer,
                        "ShardId", "shardId-000000000000", "StartingPosition", Map.of("Type", "TRIM_HORIZON")));

                byte[] response = given().contentType("application/cbor").accept(accept)
                        .header("smithy-protocol", "rpc-v2-cbor").body(request)
                        .post("/service/Kinesis_20131202/operation/SubscribeToShard")
                        .then().statusCode(200).contentType("application/vnd.amazon.eventstream")
                        .header("smithy-protocol", "rpc-v2-cbor").extract().asByteArray();

                List<EventStreamMessages.Message> frames = EventStreamMessages.decode(response);
                assertEquals(2, frames.size());
                EventStreamMessages.Message initial = frames.getFirst();
                assertEquals("initial-response", initial.headers().get(":event-type"));
                assertEquals("application/cbor", initial.headers().get(":content-type"));
                assertTrue(CBOR.readTree(initial.payload()).isEmpty());
                EventStreamMessages.Message event = frames.get(1);
                assertEquals("SubscribeToShardEvent", event.headers().get(":event-type"));
                assertEquals("application/cbor", event.headers().get(":content-type"));
                JsonNode record = CBOR.readTree(event.payload()).path("Records").get(0);
                assertEquals("pk", record.path("PartitionKey").asText());
                assertTrue(record.path("Data").isBinary());
                assertArrayEquals(content, record.path("Data").binaryValue());
                try (CBORParser parser = (CBORParser) CBOR.getFactory().createParser(event.payload())) {
                    boolean taggedDate = false;
                    while (parser.nextToken() != null) {
                        if (parser.currentToken().isNumeric()
                                && "ApproximateArrivalTimestamp".equals(parser.currentName())) {
                            assertEquals(1, parser.getCurrentTag());
                            taggedDate = true;
                        }
                    }
                    assertTrue(taggedDate);
                }
            } finally {
                if (consumer != null) {
                    String consumerArn = consumer;
                    kinesis.deregisterStreamConsumer(request -> request.consumerARN(consumerArn));
                }
                kinesis.deleteStream(request -> request.streamName(stream));
            }
        }
    }

}
