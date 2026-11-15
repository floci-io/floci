package io.floci.aws.services.cloudwatch.metrics;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.AlarmType;
import software.amazon.awssdk.services.cloudwatch.model.DescribeAlarmsResponse;
import software.amazon.awssdk.services.cloudwatch.model.MetricAlarm;
import software.amazon.awssdk.services.cloudwatch.model.Tag;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudWatchAlarmActionsIntegrationTest {
    private static final String SCOPE =
            "AWS4-HMAC-SHA256 Credential=test/20261110/cn-north-1/monitoring/aws4_request";

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void togglesBothAlarmTypesAndPreservesStateAndMetricConfiguration(boolean json) throws Exception {
        String prefix = "alarm-actions-" + Long.toString(System.nanoTime(), 36);
        String metric = prefix + "-metric";
        String composite = prefix + "-composite";
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("AlarmName", metric);
        definition.put("Namespace", "Custom/AlarmActions");
        definition.put("MetricName", "Load");
        definition.put("Statistic", "Average");
        definition.put("Period", 60);
        definition.put("EvaluationPeriods", 3);
        definition.put("Threshold", 70);
        definition.put("ComparisonOperator", "GreaterThanThreshold");
        definition.put("TreatMissingData", "ignore");
        call(json, "PutMetricAlarm", definition).then().statusCode(200);
        registerAlarmCleanup(metric);
        call(json, "PutCompositeAlarm", Map.of("AlarmName", composite, "AlarmRule", "FALSE"))
                .then().statusCode(200);
        registerAlarmCleanup(composite);
        call(json, "SetAlarmState", Map.of("AlarmName", metric, "StateValue", "ALARM",
                "StateReason", "preserve this state")).then().statusCode(200);
        Map<String, Object> selection = Map.of("AlarmNames", List.of(metric, composite),
                "AlarmTypes", List.of("MetricAlarm", "CompositeAlarm"));
        Map<String, Object> actions = Map.of("AlarmNames", List.of(metric, composite, prefix + "-absent", metric));
        for (boolean enabled : List.of(false, true, false)) {
            call(json, enabled ? "EnableAlarmActions" : "DisableAlarmActions", actions).then().statusCode(200);
            Response alarms = call(json, "DescribeAlarms", selection).then().statusCode(200).extract().response();
            assertEquals(Boolean.toString(enabled), field(json, alarms, "MetricAlarms", "ActionsEnabled"));
            assertEquals(Boolean.toString(enabled), field(json, alarms, "CompositeAlarms", "ActionsEnabled"));
            assertEquals("ALARM", field(json, alarms, "MetricAlarms", "StateValue"));
            assertEquals("preserve this state", field(json, alarms, "MetricAlarms", "StateReason"));
            assertEquals("Load", field(json, alarms, "MetricAlarms", "MetricName"));
            assertEquals("3", field(json, alarms, "MetricAlarms", "EvaluationPeriods"));
            assertEquals("FALSE", field(json, alarms, "CompositeAlarms", "AlarmRule"));
        }
        call(json, "DeleteAlarms", Map.of("AlarmNames", List.of(composite, metric))).then().statusCode(200);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidBatchDoesNotChangeAnEarlierValidAlarm(boolean json) throws Exception {
        String name = "alarm-actions-validation-" + Long.toString(System.nanoTime(), 36);
        call(json, "PutCompositeAlarm", Map.of("AlarmName", name, "AlarmRule", "FALSE")).then().statusCode(200);
        registerAlarmCleanup(name);
        call(json, "DisableAlarmActions", Map.of()).then().statusCode(400);
        List<String> tooMany = new ArrayList<>();
        tooMany.add(name);
        for (int i = 0; i < 100; i++) {
            tooMany.add(name + "-" + i);
        }
        for (List<String> names : List.of(tooMany, List.of(name, ""), List.of(name, "x".repeat(256)))) {
            call(json, "DisableAlarmActions", Map.of("AlarmNames", names)).then().statusCode(400);
        }
        Response result = call(json, "DescribeAlarms", Map.of("AlarmNames", List.of(name),
                "AlarmTypes", List.of("CompositeAlarm"))).then().statusCode(200).extract().response();
        assertEquals("true", field(json, result, "CompositeAlarms", "ActionsEnabled"));
        call(json, "DisableAlarmActions", Map.of("AlarmNames", List.of())).then().statusCode(200);
        call(json, "DeleteAlarms", Map.of("AlarmNames", List.of(name))).then().statusCode(200);
        assertFalse(call(json, "DescribeAlarms", Map.of("AlarmNames", List.of(name),
                "AlarmTypes", List.of("CompositeAlarm"))).asString().contains(name));
    }

    @ParameterizedTest
    @ValueSource(strings = {"EnableAlarmActions", "DisableAlarmActions"})
    void queryValidatesEveryNameAfterAnIndexGapBeforeChangingActions(String action) throws Exception {
        String name = "alarm-actions-gap-" + Long.toString(System.nanoTime(), 36);
        boolean enabled = "EnableAlarmActions".equals(action);
        call(false, "PutCompositeAlarm", Map.of("AlarmName", name, "AlarmRule", "FALSE",
                "ActionsEnabled", !enabled)).then().statusCode(200);
        registerAlarmCleanup(name);

        call(false, action, Map.of("AlarmNames.member.1", name, "AlarmNames.member.3", ""))
                .then().statusCode(400).body(containsString("ValidationError"));
        Response unchanged = call(false, "DescribeAlarms", Map.of("AlarmNames", List.of(name),
                "AlarmTypes", List.of("CompositeAlarm"))).then().statusCode(200).extract().response();
        assertEquals(Boolean.toString(!enabled), field(false, unchanged, "CompositeAlarms", "ActionsEnabled"));

        call(false, action, Map.of("AlarmNames.member.1", name + "-missing", "AlarmNames.member.3", name))
                .then().statusCode(200);
        Response changed = call(false, "DescribeAlarms", Map.of("AlarmNames", List.of(name),
                "AlarmTypes", List.of("CompositeAlarm"))).then().statusCode(200).extract().response();
        assertEquals(Boolean.toString(enabled), field(false, changed, "CompositeAlarms", "ActionsEnabled"));

        call(false, enabled ? "DisableAlarmActions" : "EnableAlarmActions", Map.of("AlarmNames.member.3", name))
                .then().statusCode(200);
        Response withoutFirst = call(false, "DescribeAlarms", Map.of("AlarmNames", List.of(name),
                "AlarmTypes", List.of("CompositeAlarm"))).then().statusCode(200).extract().response();
        assertEquals(Boolean.toString(!enabled), field(false, withoutFirst, "CompositeAlarms", "ActionsEnabled"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void sdkTogglesActionsWithoutChangingStateConfigurationOrTags(String region) {
        String prefix = "sdk-alarm-actions-" + Long.toString(System.nanoTime(), 36);
        String metric = prefix + "-metric";
        String composite = prefix + "-composite";
        try (CloudWatchClient client = AwsTestClients.create(
                CloudWatchClient.builder(), "000000000356", Region.of(region))) {
            try {
                client.putMetricAlarm(request -> request.alarmName(metric).namespace("Custom/AlarmActions")
                        .metricName("Load").statistic("Average").period(60).evaluationPeriods(3)
                        .threshold(70.0).comparisonOperator("GreaterThanThreshold").treatMissingData("ignore")
                        .tags(Tag.builder().key("purpose").value("alarm-actions").build()));
                client.putCompositeAlarm(request -> request.alarmName(composite).alarmRule("FALSE"));
                client.setAlarmState(request -> request.alarmName(metric).stateValue("ALARM")
                        .stateReason("state must survive action changes"));
                for (boolean enabled : List.of(false, false, true)) {
                    assertDoesNotThrow(() -> {
                        if (enabled) {
                            client.enableAlarmActions(request -> request.alarmNames(
                                    metric, composite, prefix + "-missing", metric));
                        } else {
                            client.disableAlarmActions(request -> request.alarmNames(
                                    metric, composite, prefix + "-missing", metric));
                        }
                    });
                    DescribeAlarmsResponse alarms = client.describeAlarms(request -> request
                            .alarmNames(metric, composite).alarmTypes(AlarmType.METRIC_ALARM, AlarmType.COMPOSITE_ALARM));
                    assertEquals(1, alarms.metricAlarms().size());
                    assertEquals(1, alarms.compositeAlarms().size());
                    MetricAlarm current = alarms.metricAlarms().getFirst();
                    assertEquals(enabled, current.actionsEnabled());
                    assertEquals(enabled, alarms.compositeAlarms().getFirst().actionsEnabled());
                    assertEquals("ALARM", current.stateValueAsString());
                    assertEquals("state must survive action changes", current.stateReason());
                    assertEquals("Load", current.metricName());
                    assertEquals(3, current.evaluationPeriods());
                    assertEquals(70.0, current.threshold());
                    assertEquals("FALSE", alarms.compositeAlarms().getFirst().alarmRule());
                    assertEquals(List.of(Tag.builder().key("purpose").value("alarm-actions").build()),
                            client.listTagsForResource(request -> request.resourceARN(current.alarmArn())).tags());
                }
            } finally {
                client.deleteAlarms(request -> request.alarmNames(composite, metric));
            }
            DescribeAlarmsResponse remaining = client.describeAlarms(request -> request.alarmNames(metric, composite)
                    .alarmTypes(AlarmType.METRIC_ALARM, AlarmType.COMPOSITE_ALARM));
            assertTrue(remaining.metricAlarms().isEmpty());
            assertTrue(remaining.compositeAlarms().isEmpty());
        }
    }

    private void registerAlarmCleanup(String name) {
        cleanup.register(() -> {
            try (CloudWatchClient client = AwsTestClients.create(
                    CloudWatchClient.builder(), "000000000000", Region.CN_NORTH_1, endpoint)) {
                client.deleteAlarms(request -> request.alarmNames(name));
            }
        });
    }

    private static String field(boolean json, Response result, String type, String name) {
        return json ? result.jsonPath().getString(type + "[0]." + name)
                : result.xmlPath().getString("DescribeAlarmsResponse.DescribeAlarmsResult." + type + ".member." + name);
    }

    private Response call(boolean json, String action, Map<String, ?> body) throws JsonProcessingException {
        Response response = CloudWatchTestRequests.call(endpoint, SCOPE, json, action, body);
        if (!json && response.statusCode() == 200) {
            assertTrue(response.asString().contains(action + "Response"));
        }
        return response;
    }
}
