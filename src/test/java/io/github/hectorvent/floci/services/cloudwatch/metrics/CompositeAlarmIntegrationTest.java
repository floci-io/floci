package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CompositeAlarmIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SCOPE =
            "AWS4-HMAC-SHA256 Credential=test/20261010/cn-north-1/monitoring/aws4_request";

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
    void bothProtocolsCreateDescribeEvaluateAndDeleteRealAlarms(boolean json) throws Exception {
        String prefix = "wire-composite-" + Long.toString(System.nanoTime(), 36);
        String source = prefix + "-source";
        String aggregate = prefix + "-aggregate";
        call(json, "PutCompositeAlarm", Map.of("AlarmName", source, "AlarmRule", "TRUE")).then().statusCode(200);
        call(json, "PutCompositeAlarm", Map.of("AlarmName", aggregate, "AlarmRule", "ALARM(\"" + source + "\")",
                "ActionsEnabled", false, "AlarmDescription", "combined")).then().statusCode(200);
        Response result = call(json, "DescribeAlarms", Map.of("AlarmNames", List.of(aggregate),
                "AlarmTypes", List.of("CompositeAlarm")));
        result.then().statusCode(200);
        if (json) {
            assertEquals(List.of(), result.jsonPath().getList("LogAlarms"));
            assertEquals(1, result.jsonPath().getList("CompositeAlarms").size());
            assertEquals("ALARM", result.jsonPath().getString("CompositeAlarms[0].StateValue"));
            assertEquals("arn:aws-cn:cloudwatch:cn-north-1:000000000000:alarm:" + aggregate,
                    result.jsonPath().getString("CompositeAlarms[0].AlarmArn"));
            assertFalse(result.jsonPath().getBoolean("CompositeAlarms[0].ActionsEnabled"));
        } else {
            assertTrue(result.asString().contains("<LogAlarms>"));
            assertEquals("ALARM", result.xmlPath().getString(
                    "DescribeAlarmsResponse.DescribeAlarmsResult.CompositeAlarms.member.StateValue"));
            assertTrue(result.asString().contains("arn:aws-cn:cloudwatch:cn-north-1:000000000000:alarm:" + aggregate));
            assertEquals("false", result.xmlPath().getString(
                    "DescribeAlarmsResponse.DescribeAlarmsResult.CompositeAlarms.member.ActionsEnabled"));
        }
        assertFalse(call(json, "DescribeAlarms", Map.of("AlarmNames", List.of(aggregate)))
                .then().statusCode(200).extract().asString().contains(aggregate));
        call(json, "SetAlarmState", Map.of("AlarmName", source, "StateValue", "OK",
                "StateReason", "recovery")).then().statusCode(200);
        Response recovered = call(json, "DescribeAlarms", Map.of("AlarmNames", List.of(aggregate),
                "AlarmTypes", List.of("CompositeAlarm")));
        assertEquals("OK", json ? recovered.jsonPath().getString("CompositeAlarms[0].StateValue")
                : recovered.xmlPath().getString(
                        "DescribeAlarmsResponse.DescribeAlarmsResult.CompositeAlarms.member.StateValue"));
        for (String name : List.of(aggregate, source)) {
            call(json, "DeleteAlarms", Map.of("AlarmNames", List.of(name))).then().statusCode(200);
        }
        Response empty = call(json, "DescribeAlarms", Map.of("AlarmNamePrefix", prefix,
                "AlarmTypes", List.of("CompositeAlarm")));
        assertFalse(empty.asString().contains(aggregate));
        assertFalse(empty.asString().contains(source));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidRuleAndUnsupportedSuppressionCannotCreateAnAlarm(boolean json) throws Exception {
        String name = "invalid-composite-" + Long.toString(System.nanoTime(), 36);
        call(json, "PutCompositeAlarm", Map.of("AlarmName", name, "AlarmRule", "TRUE AND"))
                .then().statusCode(400);
        call(json, "PutCompositeAlarm", Map.of("AlarmName", name, "AlarmRule", "TRUE",
                "ActionsSuppressor", "other")).then().statusCode(400);
        assertFalse(call(json, "DescribeAlarms", Map.of("AlarmNames", List.of(name),
                "AlarmTypes", List.of("CompositeAlarm"))).asString().contains(name));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void alarmTypeValidationAcceptsLogAlarmsAndUsesSmithyErrors(boolean json) throws Exception {
        call(json, "DescribeAlarms", Map.of("AlarmTypes", List.of("LogAlarm"))).then().statusCode(200);
        Response error = call(json, "DescribeAlarms", Map.of("AlarmTypes", List.of("Unknown")));
        error.then().statusCode(400);
        assertEquals("1 validation error detected: Value '[Unknown]' at 'alarmTypes' failed to satisfy constraint: "
                + "Member must satisfy constraint: [Member must satisfy enum value set: "
                + "[CompositeAlarm, MetricAlarm, LogAlarm]]", errorMessage(json, error));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lengthErrorsUseSmithyMessages(boolean json) throws Exception {
        for (String field : List.of("AlarmName", "AlarmRule")) {
            int maximum = "AlarmName".equals(field) ? 255 : 10240;
            String path = "AlarmName".equals(field) ? "alarmName" : "alarmRule";
            for (String value : List.of("", " ", "x".repeat(maximum + 1), " ".repeat(maximum + 1))) {
                Map<String, String> request = new LinkedHashMap<>(Map.of("AlarmName", "length-validation", "AlarmRule", "TRUE"));
                request.put(field, value);
                Response error = call(json, "PutCompositeAlarm", request);
                error.then().statusCode(400);
                assertEquals("1 validation error detected: Value '" + value + "' at '" + path
                        + "' failed to satisfy constraint: Member must have length "
                        + (value.length() <= maximum ? "greater than or equal to 1" : "less than or equal to " + maximum),
                        errorMessage(json, error));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingStateReasonPreservesAlarmAndDependents(boolean json) throws Exception {
        String name = "reason-required-" + Long.toString(System.nanoTime(), 36);
        String dependent = name + "-dependent";
        call(json, "PutCompositeAlarm", Map.of("AlarmName", name, "AlarmRule", "FALSE"))
                .then().statusCode(200);
        call(json, "PutCompositeAlarm", Map.of("AlarmName", dependent, "AlarmRule", "ALARM(\"" + name + "\")"))
                .then().statusCode(200);
        call(json, "SetAlarmState", Map.of("AlarmName", name, "StateValue", "OK",
                "StateReason", "preserved", "StateReasonData", "{}"))
                .then().statusCode(200);
        Map<String, Object> describe = Map.of("AlarmNames", List.of(name, dependent),
                "AlarmTypes", List.of("CompositeAlarm"));
        Response before = call(json, "DescribeAlarms", describe).then().statusCode(200).extract().response();
        Map<String, Object> request = new LinkedHashMap<>(Map.of("AlarmName", name, "StateValue", "ALARM",
                "StateReasonData", "changed"));
        Response error = call(json, "SetAlarmState", request);
        error.then().statusCode(400);
        assertEquals("1 validation error detected: Value null at 'stateReason' failed to satisfy constraint: "
                + "Member must not be null", errorMessage(json, error));
        if (json) {
            request.put("StateReason", null);
            Response explicitNull = call(true, "SetAlarmState", request);
            explicitNull.then().statusCode(400);
            assertEquals(errorMessage(true, error), errorMessage(true, explicitNull));
        }
        Response after = call(json, "DescribeAlarms", describe).then().statusCode(200).extract().response();
        if (json) {
            assertEquals(before.jsonPath().getList("CompositeAlarms"), after.jsonPath().getList("CompositeAlarms"));
        } else {
            assertEquals(before.xmlPath().getString("DescribeAlarmsResponse.DescribeAlarmsResult.CompositeAlarms"),
                    after.xmlPath().getString("DescribeAlarmsResponse.DescribeAlarmsResult.CompositeAlarms"));
        }
        request.put("StateReason", "accepted");
        call(json, "SetAlarmState", request).then().statusCode(200);
        Response changed = call(json, "DescribeAlarms", Map.of("AlarmNames", List.of(dependent),
                "AlarmTypes", List.of("CompositeAlarm"))).then().statusCode(200).extract().response();
        assertEquals("ALARM", json ? changed.jsonPath().getString("CompositeAlarms[0].StateValue")
                : changed.xmlPath().getString(
                        "DescribeAlarmsResponse.DescribeAlarmsResult.CompositeAlarms.member.StateValue"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reasonLengthRejectionPreservesSourceAndDependentState(boolean json) throws Exception {
        String source = "reason-length-" + Long.toString(System.nanoTime(), 36);
        String dependent = source + "-dependent";
        call(json, "PutCompositeAlarm", Map.of("AlarmName", source, "AlarmRule", "FALSE"))
                .then().statusCode(200);
        call(json, "PutCompositeAlarm", Map.of("AlarmName", dependent, "AlarmRule", "ALARM(\"" + source + "\")"))
                .then().statusCode(200);
        for (boolean data : List.of(false, true)) {
            int maximum = data ? 4000 : 1023;
            String field = data ? "StateReasonData" : "StateReason";
            String path = data ? "stateReasonData" : "stateReason";
            String boundary = data ? "{\"text\":\"" + "x".repeat(maximum - 11) + "\"}" : "x".repeat(maximum);
            Map<String, Object> request = new LinkedHashMap<>(Map.of("AlarmName", source, "StateValue", "OK",
                    "StateReason", "accepted", "StateReasonData", "{}"));
            request.put(field, boundary);
            call(json, "SetAlarmState", request).then().statusCode(200);
            Response before = call(json, "DescribeAlarms", Map.of("AlarmNames", List.of(source, dependent),
                    "AlarmTypes", List.of("CompositeAlarm")));
            String rejected = boundary + " ";
            request.put(field, rejected);
            request.put("StateValue", "ALARM");
            Response error = call(json, "SetAlarmState", request);
            error.then().statusCode(400);
            assertEquals("1 validation error detected: Value '" + rejected + "' at '" + path
                    + "' failed to satisfy constraint: Member must have length less than or equal to " + maximum,
                    errorMessage(json, error));
            Response after = call(json, "DescribeAlarms", Map.of("AlarmNames", List.of(source, dependent),
                    "AlarmTypes", List.of("CompositeAlarm")));
            before.then().statusCode(200);
            after.then().statusCode(200);
            if (json) {
                assertEquals(before.jsonPath().getList("CompositeAlarms"), after.jsonPath().getList("CompositeAlarms"));
            } else {
                assertEquals(before.xmlPath().getString("DescribeAlarmsResponse.DescribeAlarmsResult"),
                        after.xmlPath().getString("DescribeAlarmsResponse.DescribeAlarmsResult"));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void evaluationReasonsAndRefusalsUseCloudWatchTexts(boolean json) throws Exception {
        String prefix = "wire-fidelity-" + Long.toString(System.nanoTime(), 36);
        String child = prefix + "-child";
        String parent = prefix + "-parent";
        String other = prefix + "-other";
        String arn = "arn:aws-cn:cloudwatch:cn-north-1:000000000000:alarm:";
        // TreatMissingData=ignore keeps the alarm evaluator from resetting the state this test sets.
        call(json, "PutMetricAlarm", Map.of("AlarmName", child, "Namespace", "Wire/Fidelity", "MetricName", "Errors",
                "Statistic", "Sum", "Period", 60, "EvaluationPeriods", 1, "Threshold", 1,
                "ComparisonOperator", "GreaterThanThreshold", "TreatMissingData", "ignore")).then().statusCode(200);
        call(json, "PutCompositeAlarm", Map.of("AlarmName", parent, "AlarmRule", "ALARM(\"" + child + "\")"))
                .then().statusCode(200);
        Map<String, String> created = composite(json, parent);
        assertEquals("OK", created.get("StateValue"));
        assertEquals(arn + parent + " was created and its alarm rule evaluates to OK", created.get("StateReason"));
        JsonNode triggering = MAPPER.readTree(created.get("StateReasonData")).path("triggeringAlarms");
        assertEquals(1, triggering.size(), created.get("StateReasonData"));
        assertEquals(arn + child, triggering.path(0).path("arn").asText());
        assertEquals("INSUFFICIENT_DATA", triggering.path(0).path("state").path("value").asText());
        assertTrue(triggering.path(0).path("state").path("timestamp").asText()
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\+0000"), created.get("StateReasonData"));

        call(json, "SetAlarmState", Map.of("AlarmName", child, "StateValue", "ALARM", "StateReason", "breached"))
                .then().statusCode(200);
        Map<String, String> transitioned = composite(json, parent);
        assertEquals("ALARM", transitioned.get("StateValue"));
        assertTrue(transitioned.get("StateReason").matches("\\Q" + arn + child + " transitioned to ALARM at \\E"
                + "[A-Z][a-z]+day \\d{2} [A-Z][a-z]+, \\d{4} \\d{2}:\\d{2}:\\d{2} UTC"), transitioned.get("StateReason"));

        call(json, "PutCompositeAlarm", Map.of("AlarmName", parent, "AlarmRule", "OK(\"" + child + "\")"))
                .then().statusCode(200);
        Map<String, String> updated = composite(json, parent);
        assertEquals("OK", updated.get("StateValue"));
        assertEquals(arn + parent + " was updated and its new alarm rule evaluates to OK", updated.get("StateReason"));

        Response missing = call(json, "PutCompositeAlarm", Map.of("AlarmName", other,
                "AlarmRule", "ALARM(" + prefix + "-missing)"));
        missing.then().statusCode(400);
        assertEquals("Could not save the composite alarm as alarms [" + arn + prefix
                + "-missing] in the alarm rule do not exist", errorMessage(json, missing));
        Response referenced = call(json, "DeleteAlarms", Map.of("AlarmNames", List.of(child)));
        referenced.then().statusCode(400);
        assertEquals("Cannot delete " + child + " as there are composite alarm(s) depending on it.",
                errorMessage(json, referenced));
        call(json, "PutCompositeAlarm", Map.of("AlarmName", other, "AlarmRule", "TRUE")).then().statusCode(200);
        Response two = call(json, "DeleteAlarms", Map.of("AlarmNames", List.of(parent, other)));
        two.then().statusCode(400);
        assertEquals("Cannot delete [2] Composite Alarms as only one composite alarm delete is allowed in single request",
                errorMessage(json, two));
        assertEquals("OK", composite(json, parent).get("StateValue"));
        for (String name : List.of(parent, other, child)) {
            call(json, "DeleteAlarms", Map.of("AlarmNames", List.of(name))).then().statusCode(200);
        }
    }

    private Map<String, String> composite(boolean json, String name) throws JsonProcessingException {
        Response response = call(json, "DescribeAlarms", Map.of("AlarmNames", List.of(name),
                "AlarmTypes", List.of("CompositeAlarm")));
        response.then().statusCode(200);
        Map<String, String> fields = new LinkedHashMap<>();
        for (String field : List.of("StateValue", "StateReason", "StateReasonData")) {
            fields.put(field, json ? response.jsonPath().getString("CompositeAlarms[0]." + field)
                    : response.xmlPath().getString("DescribeAlarmsResponse.DescribeAlarmsResult.CompositeAlarms.member."
                            + field));
        }
        return fields;
    }

    private String errorMessage(boolean json, Response response) {
        return json ? response.jsonPath().getString("message") : response.xmlPath().getString("ErrorResponse.Error.Message");
    }

    private Response call(boolean json, String operation, Map<String, ?> body)
            throws JsonProcessingException {
        if ("PutCompositeAlarm".equals(operation) || "PutMetricAlarm".equals(operation)) {
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
        body.forEach((key, value) -> {
            if (value instanceof List<?> values) {
                for (int index = 0; index < values.size(); index++) {
                    form.put(key + ".member." + (index + 1), values.get(index));
                }
            } else {
                form.put(key, value);
            }
        });
        return given().contentType("application/x-www-form-urlencoded").header("Authorization", SCOPE)
                .formParams(form).post("/");
    }
}
