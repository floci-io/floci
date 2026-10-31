package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.element.Node;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DescribeAlarms' StateValue, ActionPrefix, ChildrenOfAlarmName and ParentsOfAlarmName over Query and JSON. */
@QuarkusTest
class CloudWatchDescribeAlarmsFiltersIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SCOPE =
            "AWS4-HMAC-SHA256 Credential=test/20261027/us-east-1/monitoring/aws4_request";
    private static final List<String> BOTH_KINDS = List.of("MetricAlarm", "CompositeAlarm");

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
    void stateValueAndActionPrefixNarrowMetricAndCompositeAlarms(boolean json) throws Exception {
        String prefix = unique("filtered");
        String topic = "arn:aws:sns:us-east-1:000000000000:" + prefix;
        String alarm = prefix + "-alarm";
        String ok = prefix + "-ok";
        String unknown = prefix + "-unknown";
        String compositeAlarm = prefix + "-composite-alarm";
        String compositeOk = prefix + "-composite-ok";
        putMetricAlarm(json, alarm, Map.of("AlarmActions", List.of(topic + "-page")));
        putMetricAlarm(json, ok, Map.of("OKActions", List.of(topic + "-info")));
        putMetricAlarm(json, unknown, Map.of("InsufficientDataActions", List.of(topic + "-info")));
        setState(json, alarm, "ALARM");
        setState(json, ok, "OK");
        putCompositeAlarm(json, compositeAlarm, "TRUE", Map.of("AlarmActions", List.of(topic + "-page")));
        putCompositeAlarm(json, compositeOk, "FALSE", Map.of("OKActions", List.of(topic + "-info")));

        assertEquals(listed(List.of(alarm), List.of(compositeAlarm)), names(json,
                Map.of("AlarmNamePrefix", prefix, "StateValue", "ALARM", "AlarmTypes", BOTH_KINDS)));
        assertEquals(listed(List.of(ok), List.of(compositeOk)), names(json,
                Map.of("AlarmNamePrefix", prefix, "StateValue", "OK", "AlarmTypes", BOTH_KINDS)));
        assertEquals(listed(List.of(unknown), List.of()), names(json,
                Map.of("AlarmNamePrefix", prefix, "StateValue", "INSUFFICIENT_DATA", "AlarmTypes", BOTH_KINDS)));
        assertEquals(listed(List.of(ok), List.of()), names(json,
                Map.of("AlarmNamePrefix", prefix, "StateValue", "OK")));

        assertEquals(listed(List.of(alarm), List.of(compositeAlarm)), names(json,
                Map.of("ActionPrefix", topic + "-page", "AlarmTypes", BOTH_KINDS)));
        assertEquals(listed(List.of(ok, unknown), List.of(compositeOk)), names(json,
                Map.of("ActionPrefix", topic + "-info", "AlarmTypes", BOTH_KINDS)));
        assertEquals(listed(List.of(alarm, ok, unknown), List.of()), names(json, Map.of("ActionPrefix", topic)));
        assertEquals(listed(List.of(unknown), List.of()), names(json,
                Map.of("ActionPrefix", topic, "StateValue", "INSUFFICIENT_DATA", "AlarmTypes", BOTH_KINDS)));
        assertEquals(listed(List.of(ok), List.of()), names(json,
                Map.of("ActionPrefix", topic, "AlarmNames", List.of(ok, compositeOk))));
        assertEquals(listed(List.of(), List.of()), names(json,
                Map.of("ActionPrefix", topic + "-page", "StateValue", "OK", "AlarmTypes", BOTH_KINDS)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void childrenAndParentsListTheRelatedAlarmsWithAwsMembers(boolean json) throws Exception {
        String prefix = unique("related");
        String cpu = prefix + "-cpu";
        String disk = prefix + "-disk";
        String host = prefix + "-host";
        String fleet = prefix + "-fleet";
        putMetricAlarm(json, cpu, Map.of());
        putMetricAlarm(json, disk, Map.of());
        setState(json, cpu, "ALARM");
        String diskArn = arnOf(json, disk, "MetricAlarm");
        putCompositeAlarm(json, host, "ALARM(" + cpu + ") OR ALARM(\"" + diskArn + "\")", Map.of());
        putCompositeAlarm(json, fleet, "ALARM(\"" + host + "\") AND NOT OK(" + cpu + ")", Map.of());

        Response hostChildren = describe(json, Map.of("ChildrenOfAlarmName", host));
        assertEquals(listed(List.of(cpu, disk), List.of()), names(json, hostChildren));
        Map<String, String> cpuChild = members(json, hostChildren, "MetricAlarms", cpu);
        assertEquals(List.of("AlarmArn", "AlarmName", "StateUpdatedTimestamp", "StateValue"),
                List.copyOf(cpuChild.keySet()));
        assertEquals(arnOf(json, cpu, "MetricAlarm"), cpuChild.get("AlarmArn"));
        assertEquals("ALARM", cpuChild.get("StateValue"));
        assertEquals("INSUFFICIENT_DATA", members(json, hostChildren, "MetricAlarms", disk).get("StateValue"));

        Response fleetChildren = describe(json, Map.of("ChildrenOfAlarmName", fleet, "MaxRecords", 10));
        assertEquals(listed(List.of(cpu), List.of(host)), names(json, fleetChildren));
        Map<String, String> hostChild = members(json, fleetChildren, "CompositeAlarms", host);
        assertEquals(List.of("AlarmArn", "AlarmName", "StateUpdatedTimestamp", "StateValue"),
                List.copyOf(hostChild.keySet()));
        assertEquals("ALARM", hostChild.get("StateValue"));

        Response cpuParents = describe(json, Map.of("ParentsOfAlarmName", cpu));
        assertEquals(listed(List.of(), List.of(fleet, host)), names(json, cpuParents));
        Map<String, String> hostParent = members(json, cpuParents, "CompositeAlarms", host);
        assertEquals(List.of("AlarmArn", "AlarmName"), List.copyOf(hostParent.keySet()));
        assertEquals(arnOf(json, host, "CompositeAlarm"), hostParent.get("AlarmArn"));
        assertEquals(listed(List.of(), List.of(host)), names(json, Map.of("ParentsOfAlarmName", disk)));
        assertEquals(listed(List.of(), List.of(fleet)), names(json,
                Map.of("ParentsOfAlarmName", host, "MaxRecords", 10)));

        assertEquals(listed(List.of(), List.of()), names(json, Map.of("ParentsOfAlarmName", fleet)));
        assertEquals(listed(List.of(), List.of()), names(json, Map.of("ChildrenOfAlarmName", cpu)));
        assertEquals(listed(List.of(), List.of()), names(json, Map.of("ChildrenOfAlarmName", prefix + "-missing")));
        assertEquals(listed(List.of(), List.of()), names(json, Map.of("ParentsOfAlarmName", prefix + "-missing")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void childrenAndParentsRefuseEveryOtherFilter(boolean json) throws Exception {
        String name = unique("combined");
        List<Map.Entry<String, Object>> others = List.of(Map.entry("AlarmNames", List.of(name)),
                Map.entry("AlarmNamePrefix", name), Map.entry("AlarmTypes", List.of("CompositeAlarm")),
                Map.entry("StateValue", "OK"), Map.entry("ActionPrefix", "arn:"));
        for (String related : List.of("ChildrenOfAlarmName", "ParentsOfAlarmName")) {
            for (Map.Entry<String, Object> other : others) {
                assertRefused(json, related, Map.of(related, name, other.getKey(), other.getValue()));
            }
        }
        assertRefused(json, "ChildrenOfAlarmName", Map.of("ChildrenOfAlarmName", name, "ParentsOfAlarmName", name));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void filterValuesOutsideTheModelUseSmithyValidation(boolean json) throws Exception {
        assertValidationError(json, Map.of("StateValue", "BOGUS"), "1 validation error detected: Value 'BOGUS' at "
                + "'stateValue' failed to satisfy constraint: Member must satisfy enum value set: "
                + "[INSUFFICIENT_DATA, ALARM, OK]");
        assertValidationError(json, Map.of("ActionPrefix", ""), "1 validation error detected: Value '' at "
                + "'actionPrefix' failed to satisfy constraint: Member must have length greater than or equal to 1");
        String tooLong = "x".repeat(256);
        assertValidationError(json, Map.of("ChildrenOfAlarmName", tooLong), "1 validation error detected: Value '"
                + tooLong + "' at 'childrenOfAlarmName' failed to satisfy constraint: "
                + "Member must have length less than or equal to 255");
        assertValidationError(json, Map.of("ParentsOfAlarmName", ""), "1 validation error detected: Value '' at "
                + "'parentsOfAlarmName' failed to satisfy constraint: "
                + "Member must have length greater than or equal to 1");
        assertValidationError(json, Map.of("AlarmTypes", List.of("Unknown"), "StateValue", "alarm"),
                "2 validation errors detected: Value '[Unknown]' at 'alarmTypes' failed to satisfy constraint: "
                        + "Member must satisfy constraint: [Member must satisfy enum value set: "
                        + "[CompositeAlarm, MetricAlarm, LogAlarm]]; Value 'alarm' at 'stateValue' failed to satisfy "
                        + "constraint: Member must satisfy enum value set: [INSUFFICIENT_DATA, ALARM, OK]");
    }

    private void assertRefused(boolean json, String named, Map<String, Object> request) throws Exception {
        assertValidationError(json, request, "If you specify " + named + ", you cannot specify any other parameters "
                + "in the request except for MaxRecords and NextToken.");
    }

    private void assertValidationError(boolean json, Map<String, Object> request, String message) throws Exception {
        Response error = describe(json, request);
        assertEquals(400, error.statusCode(), request + ": " + error.asString());
        if (json) {
            assertEquals("ValidationError;Sender", error.header("x-amzn-query-error"), request.toString());
            assertEquals(message, error.jsonPath().getString("message"), request.toString());
        } else {
            assertEquals("ValidationError", error.xmlPath().getString("ErrorResponse.Error.Code"), request.toString());
            assertEquals(message, error.xmlPath().getString("ErrorResponse.Error.Message"), request.toString());
        }
    }

    /** The ARN DescribeAlarms reports for an alarm of {@code kind}, so no partition is spelled out here. */
    private String arnOf(boolean json, String name, String kind) throws Exception {
        Response response = describe(json, Map.of("AlarmNames", List.of(name), "AlarmTypes", List.of(kind)));
        return members(json, response, kind + "s", name).get("AlarmArn");
    }

    private static Map<String, List<String>> listed(List<String> metricAlarms, List<String> compositeAlarms) {
        return Map.of("MetricAlarms", metricAlarms.stream().sorted().toList(),
                "CompositeAlarms", compositeAlarms.stream().sorted().toList());
    }

    private Map<String, List<String>> names(boolean json, Map<String, Object> request) throws Exception {
        return names(json, describe(json, request));
    }

    private static Map<String, List<String>> names(boolean json, Response response) {
        assertEquals(200, response.statusCode(), response.asString());
        Map<String, List<String>> names = new LinkedHashMap<>();
        for (String kind : List.of("MetricAlarms", "CompositeAlarms")) {
            List<String> listedNames = json ? response.jsonPath().getList(kind + ".AlarmName", String.class)
                    : response.xmlPath().getList("DescribeAlarmsResponse.DescribeAlarmsResult." + kind
                            + ".member.AlarmName", String.class);
            names.put(kind, listedNames.stream().sorted().toList());
        }
        return names;
    }

    /** The members one listed alarm carries, by name, each with its value as text. */
    private static Map<String, String> members(boolean json, Response response, String kind, String alarmName) {
        Map<String, String> members = new TreeMap<>();
        if (json) {
            List<Map<String, Object>> alarms = response.jsonPath().getList(kind);
            Map<String, Object> alarm = alarms.stream().filter(a -> alarmName.equals(a.get("AlarmName")))
                    .findFirst().orElseThrow();
            alarm.forEach((key, value) -> members.put(key, String.valueOf(value)));
        } else {
            List<Node> alarms = response.xmlPath().getList(
                    "DescribeAlarmsResponse.DescribeAlarmsResult." + kind + ".member", Node.class);
            Node alarm = alarms.stream().filter(a -> alarmName.equals(a.getNode("AlarmName").value()))
                    .findFirst().orElseThrow();
            alarm.children().list().forEach(child -> members.put(child.name(), child.value()));
        }
        assertTrue(members.containsKey("AlarmName"), members.toString());
        return members;
    }

    private void putMetricAlarm(boolean json, String name, Map<String, Object> actions) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>(Map.of("AlarmName", name, "Namespace", "Filters",
                "MetricName", name, "Statistic", "Average", "Period", 60, "EvaluationPeriods", 1,
                "Threshold", 1, "ComparisonOperator", "GreaterThanThreshold", "TreatMissingData", "ignore"));
        request.put("ActionsEnabled", false);
        request.putAll(actions);
        put(json, "PutMetricAlarm", request);
    }

    private void putCompositeAlarm(boolean json, String name, String rule, Map<String, Object> actions)
            throws Exception {
        Map<String, Object> request = new LinkedHashMap<>(Map.of("AlarmName", name, "AlarmRule", rule,
                "ActionsEnabled", false));
        request.putAll(actions);
        put(json, "PutCompositeAlarm", request);
    }

    private void put(boolean json, String operation, Map<String, Object> request) throws Exception {
        call(json, operation, request).then().statusCode(200);
        String name = request.get("AlarmName").toString();
        cleanup.register(() -> {
            try {
                call(true, "DeleteAlarms", Map.of("AlarmNames", List.of(name))).then().statusCode(200);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private void setState(boolean json, String name, String state) throws Exception {
        call(json, "SetAlarmState", Map.of("AlarmName", name, "StateValue", state, "StateReason", "test"))
                .then().statusCode(200);
    }

    private Response describe(boolean json, Map<String, Object> request) throws Exception {
        return call(json, "DescribeAlarms", request);
    }

    private static String unique(String prefix) {
        return "describe-" + prefix + "-" + Long.toString(System.nanoTime(), 36);
    }

    private Response call(boolean json, String operation, Map<String, ?> body) throws JsonProcessingException {
        if (json) {
            return given().port(baseUri.getPort()).contentType("application/x-amz-json-1.0")
                    .header("Authorization", SCOPE)
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
        return given().port(baseUri.getPort()).contentType("application/x-www-form-urlencoded")
                .header("Authorization", SCOPE).formParams(form).post("/");
    }
}
