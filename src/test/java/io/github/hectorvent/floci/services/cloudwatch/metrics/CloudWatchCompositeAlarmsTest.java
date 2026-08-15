package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.CompositeAlarm;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudWatchCompositeAlarmsTest {

    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RULE = "ALARM(cpu-high) AND NOT ALARM(deploying)";

    private CloudWatchMetricsService service;
    private CloudWatchMetricsQueryHandler queryHandler;
    private CloudWatchMetricsJsonHandler jsonHandler;

    @BeforeEach
    void setUp() {
        service = new CloudWatchMetricsService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver(REGION, "000000000000"),
                MAPPER
        );
        queryHandler = new CloudWatchMetricsQueryHandler(service);
        jsonHandler = new CloudWatchMetricsJsonHandler(service, MAPPER);
    }

    private MultivaluedMap<String, String> putParams(String name) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("AlarmName", name);
        params.add("AlarmRule", RULE);
        params.add("AlarmDescription", "page only when we are not deploying");
        params.add("AlarmActions.member.1", "arn:aws:sns:us-east-1:000000000000:oncall");
        return params;
    }

    private MultivaluedMap<String, String> describeParams(String name, String alarmType) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        if (name != null) {
            params.add("AlarmNames.member.1", name);
        }
        if (alarmType != null) {
            params.add("AlarmTypes.member.1", alarmType);
        }
        return params;
    }

    @Test
    void putCompositeAlarmStoresTheRuleAndTerminalOkState() {
        queryHandler.handle("PutCompositeAlarm", putParams("composite-a"), REGION);

        CompositeAlarm stored = service.describeCompositeAlarms(List.of("composite-a"), null, REGION).getFirst();
        assertEquals(RULE, stored.getAlarmRule());
        assertEquals("OK", stored.getStateValue());
        assertEquals("arn:aws:cloudwatch:us-east-1:000000000000:alarm:composite-a", stored.getAlarmArn());
        assertTrue(stored.isActionsEnabled());
        assertEquals(List.of("arn:aws:sns:us-east-1:000000000000:oncall"), stored.getAlarmActions());
    }

    @Test
    void describeAlarmsReturnsCompositeAlarmsOnlyWhenAsked() {
        queryHandler.handle("PutCompositeAlarm", putParams("composite-b"), REGION);

        String metricOnly = (String) queryHandler.handle("DescribeAlarms",
                describeParams("composite-b", null), REGION).getEntity();
        assertTrue(metricOnly.contains("<CompositeAlarms></CompositeAlarms>"));

        String composite = (String) queryHandler.handle("DescribeAlarms",
                describeParams("composite-b", "CompositeAlarm"), REGION).getEntity();
        assertTrue(composite.contains("<AlarmName>composite-b</AlarmName>"));
        assertTrue(composite.contains("<StateValue>OK</StateValue>"));
        assertTrue(composite.contains("<MetricAlarms></MetricAlarms>"));
    }

    @Test
    void describeAlarmsKeepsMetricAndCompositeAlarmsInSeparateLists() {
        queryHandler.handle("PutCompositeAlarm", putParams("shared-name-composite"), REGION);

        MultivaluedMap<String, String> metricAlarm = new MultivaluedHashMap<>();
        metricAlarm.add("AlarmName", "shared-name-metric");
        metricAlarm.add("MetricName", "CPUUtilization");
        metricAlarm.add("Namespace", "AWS/EC2");
        queryHandler.handle("PutMetricAlarm", metricAlarm, REGION);

        String xml = (String) queryHandler.handle("DescribeAlarms", new MultivaluedHashMap<>(), REGION).getEntity();
        assertTrue(xml.contains("<AlarmName>shared-name-metric</AlarmName>"));
        assertFalse(xml.contains("<AlarmName>shared-name-composite</AlarmName>"));
    }

    @Test
    void deleteAlarmsRemovesCompositeAlarms() {
        queryHandler.handle("PutCompositeAlarm", putParams("composite-c"), REGION);

        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("AlarmNames.member.1", "composite-c");
        queryHandler.handle("DeleteAlarms", params, REGION);

        assertTrue(service.describeCompositeAlarms(List.of(), null, REGION).isEmpty());
    }

    @Test
    void compositeAlarmTagsAreReadableByListTagsForResource() {
        MultivaluedMap<String, String> params = putParams("composite-d");
        params.add("Tags.member.1.Key", "tofu-estate");
        params.add("Tags.member.1.Value", "probe1");
        queryHandler.handle("PutCompositeAlarm", params, REGION);

        String arn = service.describeCompositeAlarms(List.of("composite-d"), null, REGION).getFirst().getAlarmArn();
        assertEquals("probe1", service.listTagsForResource(arn, REGION).get("tofu-estate"));

        service.tagResource(arn, Map.of("owner", "platform"), REGION);
        assertEquals("platform", service.listTagsForResource(arn, REGION).get("owner"));

        service.untagResource(arn, List.of("owner"), REGION);
        assertFalse(service.listTagsForResource(arn, REGION).containsKey("owner"));
    }

    @Test
    void repeatingAPutKeepsTheStateAndTheTags() {
        MultivaluedMap<String, String> params = putParams("composite-e");
        params.add("Tags.member.1.Key", "env");
        params.add("Tags.member.1.Value", "dev");
        queryHandler.handle("PutCompositeAlarm", params, REGION);
        long stateUpdatedAt = service.describeCompositeAlarms(List.of("composite-e"), null, REGION)
                .getFirst().getStateUpdatedTimestamp();

        MultivaluedMap<String, String> update = putParams("composite-e");
        update.putSingle("AlarmRule", "ALARM(cpu-high)");
        queryHandler.handle("PutCompositeAlarm", update, REGION);

        CompositeAlarm stored = service.describeCompositeAlarms(List.of("composite-e"), null, REGION).getFirst();
        assertEquals("ALARM(cpu-high)", stored.getAlarmRule());
        assertEquals("OK", stored.getStateValue());
        assertEquals(stateUpdatedAt, stored.getStateUpdatedTimestamp());
        assertEquals("dev", stored.getTags().get("env"));
    }

    @Test
    void jsonHandlerRoundTripsACompositeAlarm() throws Exception {
        JsonNode request = MAPPER.readTree("""
                {
                  "AlarmName": "json-composite",
                  "AlarmRule": "ALARM(cpu-high) OR ALARM(mem-high)",
                  "AlarmDescription": "json path",
                  "ActionsEnabled": false,
                  "AlarmActions": ["arn:aws:sns:us-east-1:000000000000:oncall"],
                  "ActionsSuppressor": "arn:aws:cloudwatch:us-east-1:000000000000:alarm:deploying",
                  "ActionsSuppressorWaitPeriod": 120,
                  "ActionsSuppressorExtensionPeriod": 180,
                  "Tags": [{"Key": "env", "Value": "test"}]
                }
                """);
        jsonHandler.handle("PutCompositeAlarm", request, REGION);

        JsonNode describe = MAPPER.valueToTree(jsonHandler.handle("DescribeAlarms",
                MAPPER.readTree("{\"AlarmNames\": [\"json-composite\"], \"AlarmTypes\": [\"CompositeAlarm\"]}"),
                REGION).getEntity());

        assertEquals(0, describe.path("MetricAlarms").size());
        JsonNode alarm = describe.path("CompositeAlarms").get(0);
        assertEquals("json-composite", alarm.path("AlarmName").asText());
        assertEquals("ALARM(cpu-high) OR ALARM(mem-high)", alarm.path("AlarmRule").asText());
        assertEquals("OK", alarm.path("StateValue").asText());
        assertFalse(alarm.path("ActionsEnabled").asBoolean());
        assertEquals(120, alarm.path("ActionsSuppressorWaitPeriod").asInt());
        assertEquals(180, alarm.path("ActionsSuppressorExtensionPeriod").asInt());

        String arn = alarm.path("AlarmArn").asText();
        assertEquals("test", service.listTagsForResource(arn, REGION).get("env"));

        jsonHandler.handle("DeleteAlarms", MAPPER.readTree("{\"AlarmNames\": [\"json-composite\"]}"), REGION);
        assertTrue(service.describeCompositeAlarms(List.of(), null, REGION).isEmpty());
    }
}
