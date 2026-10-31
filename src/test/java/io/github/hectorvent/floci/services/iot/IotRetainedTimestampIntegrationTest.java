package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.iot.model.IotRetainedMessage;
import io.github.hectorvent.floci.testing.IotDataRequests;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.iotdataplane.IotDataPlaneClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@QuarkusTest
class IotRetainedTimestampIntegrationTest {

    @ParameterizedTest
    @CsvSource({"false,0", "true,0", "false,1234", "true,1234",
            "false,1792767673821", "true,1792767673821"})
    void retainedTimestampsAreIntegralMilliseconds(boolean list, long milliseconds) {
        JsonNode timestamp = rendered(list, Instant.ofEpochMilli(milliseconds)).path("lastModifiedTime");
        assertTrue(timestamp.isIntegralNumber(), timestamp.toString());
        assertEquals(milliseconds, timestamp.longValue());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingLegacyTimestampRemainsAbsent(boolean list) {
        assertFalse(rendered(list, null).has("lastModifiedTime"));
    }

    private JsonNode rendered(boolean list, Instant instant) {
        IotRetainedMessage retained = new IotRetainedMessage();
        retained.setTopic("devices/timestamp");
        retained.setPayload("b25saW5l");
        retained.setLastModifiedTime(instant);
        IotService service = mock(IotService.class);
        when(service.getRetainedMessage(retained.getTopic())).thenReturn(retained);
        when(service.listRetainedMessages(null, null)).thenReturn(new IotService.Page<>(List.of(retained), null));
        IotDataController controller = new IotDataController(service, mock(RegionResolver.class), new ObjectMapper());
        try (Response response = list ? controller.listRetainedMessages(null, null)
                : controller.getRetainedMessage(retained.getTopic())) {
            JsonNode node = (JsonNode) response.getEntity();
            return list ? node.path("retainedTopics").get(0) : node;
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void sdkAndWireReadTheStoredTimeInMilliseconds(int qos) throws Exception {
        String account = "74163610000" + qos;
        String topic = "timestamp/" + UUID.randomUUID();
        String authorization = "AWS4-HMAC-SHA256 Credential=" + account
                + "/20261023/us-east-1/iotdata/aws4_request, SignedHeaders=host, Signature=test";
        ObjectMapper mapper = new ObjectMapper();
        try (IotDataPlaneClient client = IotDataRequests.iotDataClient(account)) {
            long before = System.currentTimeMillis();
            client.publish(request -> request.topic(topic).qos(qos).retain(true).payload(SdkBytes.fromUtf8String("online")));
            long after = System.currentTimeMillis();
            try {
                JsonNode fetched = mapper.readTree(given().header("Authorization", authorization)
                        .get("/retainedMessage/" + topic).then().statusCode(200).extract().asString());
                JsonNode timestamp = fetched.path("lastModifiedTime");
                assertTrue(timestamp.isIntegralNumber(), timestamp.toString());
                long stored = timestamp.longValue();
                assertTrue(stored >= before && stored <= after, timestamp.toString());
                JsonNode listed = mapper.readTree(given().header("Authorization", authorization)
                        .get("/retainedMessage").then().statusCode(200).extract().asString()).path("retainedTopics");
                assertEquals(1, listed.size());
                assertTrue(listed.get(0).path("lastModifiedTime").isIntegralNumber());
                assertEquals(stored, listed.get(0).path("lastModifiedTime").longValue());
                assertEquals(stored, client.getRetainedMessage(request -> request.topic(topic)).lastModifiedTime());
                assertEquals(stored, client.listRetainedMessages(request -> request.maxResults(100))
                        .retainedTopics().get(0).lastModifiedTime());
            } finally {
                client.publish(request -> request.topic(topic).retain(true).payload(SdkBytes.fromByteArray(new byte[0])));
            }
        }
    }
}
