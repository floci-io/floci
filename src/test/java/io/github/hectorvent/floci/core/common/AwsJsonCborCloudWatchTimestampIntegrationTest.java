package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the exact bug this repo's own {@code AwsJsonCborSerializerTest}
 * originally introduced while fixing floci-io/floci#2368: scaling smithy-rpc-v2-cbor
 * timestamps to milliseconds (correct for the legacy {@code application/x-amz-cbor-1.1}
 * dialect, per the same issue) broke CloudWatch Metrics, whose {@code PutMetricData}/
 * {@code GetMetricStatistics} round-trip a {@code Timestamp} field over the
 * smithy-rpc-v2-cbor protocol - which uses RFC 8949's plain, unscaled epoch-seconds
 * tag(1) convention. A wrongly-scaled timestamp lands outside any query window, so
 * {@code GetMetricStatistics} silently returns zero datapoints (matches the real Go SDK
 * compat test failure this was caught by, {@code TestCloudWatch/GetMetricStatistics}
 * asserting {@code Datapoints} non-empty).
 * <p>
 * Drives the real HTTP endpoint end-to-end via the same {@code
 * POST /service/GraniteServiceVersion20100801/operation/{action}} routing
 * {@link SmithyRpcV2RoutingIntegrationTest#cloudWatchRoutesViaGraniteServiceName} uses,
 * with request bodies built via {@link AwsJsonCborController#nodeToSmithyCbor} - the
 * correct (unscaled) encoder for this protocol - so this test would fail the same way
 * the real regression did if that scaling were ever mistakenly reapplied here.
 */
@QuarkusTest
class AwsJsonCborCloudWatchTimestampIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper CBOR = new ObjectMapper(new CBORFactory());

    @Test
    void getMetricStatisticsFindsADatapointPublishedJustNow() throws Exception {
        String namespace = "CborRpcV2TimestampTest-" + System.nanoTime();
        Instant now = Instant.now();

        ObjectNode putRequest = JSON.createObjectNode();
        putRequest.put("Namespace", namespace);
        ArrayNode metricData = putRequest.putArray("MetricData");
        ObjectNode datum = metricData.addObject();
        datum.put("MetricName", "RequestCount");
        datum.put("Value", 42.0);
        datum.put("Unit", "Count");
        datum.put("Timestamp", now.getEpochSecond());
        rpcV2Call("PutMetricData", putRequest);

        ObjectNode statsRequest = JSON.createObjectNode();
        statsRequest.put("Namespace", namespace);
        statsRequest.put("MetricName", "RequestCount");
        statsRequest.put("StartTime", now.minusSeconds(300).getEpochSecond());
        statsRequest.put("EndTime", now.plusSeconds(60).getEpochSecond());
        statsRequest.put("Period", 60);
        statsRequest.putArray("Statistics").add("Sum");
        JsonNode statsResponse = rpcV2Call("GetMetricStatistics", statsRequest);

        // Before this fix, a stray millisecond-scaling of "Timestamp" here would have
        // stored the datapoint far outside [StartTime, EndTime], and this would be 0 -
        // exactly the real Go SDK compat test failure this class guards against.
        assertFalse(statsResponse.path("Datapoints").isEmpty(),
                "GetMetricStatistics must find the datapoint just published: " + statsResponse);

        // Not an exact match: CloudWatch buckets a datapoint's own returned Timestamp
        // to the start of its Period (60s here), so it legitimately differs from the
        // submitted value by up to one period - a real, unrelated behaviour, not this
        // fix's concern. What this DOES still catch: a 1000x scale error would push the
        // value thousands of years away, far outside this window.
        JsonNode datapoint = statsResponse.path("Datapoints").get(0);
        long bucketedEpochSecond = datapoint.path("Timestamp").asLong();
        long driftSeconds = Math.abs(now.getEpochSecond() - bucketedEpochSecond);
        assertTrue(driftSeconds <= 60,
                "the round-tripped Timestamp must stay in plain epoch seconds, unscaled (drifted "
                        + driftSeconds + "s): " + datapoint);
    }

    private static JsonNode rpcV2Call(String action, ObjectNode body) throws Exception {
        byte[] responseCbor = given()
                .contentType("application/cbor")
                .accept("application/cbor")
                .header("smithy-protocol", "rpc-v2-cbor")
                .body(AwsJsonCborController.nodeToSmithyCbor(body))
                .when().post("/service/GraniteServiceVersion20100801/operation/" + action)
                .then().statusCode(200)
                .extract().asByteArray();
        return CBOR.readTree(responseCbor);
    }
}
