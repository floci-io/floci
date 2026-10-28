package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CloudWatchAlarmHistoryIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SCOPE =
            "AWS4-HMAC-SHA256 Credential=test/20261026/us-east-1/monitoring/aws4_request";
    private static final String RESULT = "DescribeAlarmHistoryResponse.DescribeAlarmHistoryResult.";

    @TestHTTPResource
    URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothProtocolsListManualTransitionsNewestFirst(boolean json) throws Exception {
        String name = "wire-history-" + Long.toString(System.nanoTime(), 36);
        call(json, "PutMetricAlarm", Map.of("AlarmName", name, "MetricName", "Depth", "Namespace", "Floci/History",
                "Statistic", "Maximum", "Period", 60, "EvaluationPeriods", 1, "Threshold", 1,
                "ComparisonOperator", "GreaterThanOrEqualToThreshold", "TreatMissingData", "ignore"))
                .then().statusCode(200);
        for (String state : List.of("OK", "ALARM", "OK")) {
            call(json, "SetAlarmState", Map.of("AlarmName", name, "StateValue", state, "StateReason", "manual"))
                    .then().statusCode(200);
        }

        Response history = call(json, "DescribeAlarmHistory", Map.of("AlarmName", name,
                "HistoryItemType", "StateUpdate"));
        history.then().statusCode(200);
        assertEquals(List.of("Alarm updated from ALARM to OK", "Alarm updated from OK to ALARM",
                "Alarm updated from INSUFFICIENT_DATA to OK"), json
                ? history.jsonPath().getList("AlarmHistoryItems.HistorySummary")
                : history.xmlPath().getList(RESULT + "AlarmHistoryItems.member.HistorySummary"));
        String type = json ? history.jsonPath().getString("AlarmHistoryItems[0].AlarmType")
                : history.xmlPath().getString(RESULT + "AlarmHistoryItems.member[0].AlarmType");
        assertEquals("MetricAlarm", type);

        Response first = call(json, "DescribeAlarmHistory", Map.of("AlarmName", name, "MaxRecords", 1));
        first.then().statusCode(200);
        String token = json ? first.jsonPath().getString("NextToken")
                : first.xmlPath().getString(RESULT + "NextToken");
        assertFalse(token == null || token.isEmpty());
        Response second = call(json, "DescribeAlarmHistory", Map.of("AlarmName", name, "MaxRecords", 1,
                "NextToken", token));
        assertEquals("Alarm updated from OK to ALARM", json
                ? second.jsonPath().getString("AlarmHistoryItems[0].HistorySummary")
                : second.xmlPath().getString(RESULT + "AlarmHistoryItems.member.HistorySummary"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void errorsCarryEachProtocolsCode(boolean json) throws Exception {
        Instant start = Instant.parse("2026-10-26T16:21:49.900Z");
        Instant end = Instant.parse("2026-10-26T16:21:49.500Z");
        Response reversed = call(json, "DescribeAlarmHistory", json
                ? Map.of("StartDate", start.toEpochMilli() / 1000.0, "EndDate", end.toEpochMilli() / 1000.0)
                : Map.of("StartDate", start.toString(), "EndDate", end.toString()));
        reversed.then().statusCode(400);
        assertEquals(json ? "InvalidParameterValueException" : "InvalidParameterValue", errorCode(json, reversed));
        assertEquals("Start date Mon Oct 26 16:21:49 UTC 2026 must be before end date Mon Oct 26 16:21:49 UTC 2026",
                errorMessage(json, reversed));

        Response malformed = call(json, "DescribeAlarmHistory", Map.of("MaxRecords", "abc"));
        malformed.then().statusCode(400);
        assertEquals("MalformedInput", errorCode(json, malformed));

        Response token = call(json, "DescribeAlarmHistory", Map.of("NextToken", "bogus"));
        token.then().statusCode(400);
        assertEquals("InvalidNextToken", errorCode(json, token));
        assertEquals("Invalid NextToken : bogus", errorMessage(json, token));

        Response scan = call(json, "DescribeAlarmHistory", Map.of("ScanBy", "Bogus"));
        scan.then().statusCode(400);
        assertEquals("1 validation error detected: Value 'Bogus' at 'scanBy' failed to satisfy constraint: "
                + "Member must satisfy enum value set: [TimestampAscending, TimestampDescending]",
                errorMessage(json, scan));
    }

    private String errorCode(boolean json, Response response) {
        return json ? response.jsonPath().getString("__type") : response.xmlPath().getString("ErrorResponse.Error.Code");
    }

    private String errorMessage(boolean json, Response response) {
        return json ? response.jsonPath().getString("message") : response.xmlPath().getString("ErrorResponse.Error.Message");
    }

    private Response call(boolean json, String operation, Map<String, ?> body)
            throws JsonProcessingException {
        if ("PutMetricAlarm".equals(operation)) {
            String name = body.get("AlarmName").toString();
            cleanup.register(() -> given().port(baseUri.getPort()).contentType("application/x-amz-json-1.0")
                    .header("Authorization", SCOPE)
                    .header("X-Amz-Target", "GraniteServiceVersion20100801.DeleteAlarms")
                    .body(MAPPER.valueToTree(Map.of("AlarmNames", List.of(name))).toString())
                    .post("/").then().statusCode(200));
        }
        if (json) {
            return given().contentType("application/x-amz-json-1.0").header("Authorization", SCOPE)
                    .header("X-Amz-Target", "GraniteServiceVersion20100801." + operation)
                    .body(MAPPER.writeValueAsString(body)).post("/");
        }
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("Action", operation);
        form.putAll(body);
        return given().contentType("application/x-www-form-urlencoded").header("Authorization", SCOPE)
                .formParams(form).post("/");
    }
}
