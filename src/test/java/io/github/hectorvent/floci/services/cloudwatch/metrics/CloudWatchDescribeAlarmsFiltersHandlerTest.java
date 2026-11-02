package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.dashboards.CloudWatchDashboardsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Alarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.CompositeAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metricstreams.CloudWatchMetricStreamsService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ChildrenOfAlarmName and ParentsOfAlarmName over rules the API's own checks might refuse to store, so
 * those rules are written to the alarm store directly.
 */
class CloudWatchDescribeAlarmsFiltersHandlerTest {
    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RegionResolver regionResolver = new RegionResolver(REGION, "000000000000");
    private final InMemoryStorage<String, Alarm> alarms = new InMemoryStorage<>();
    private final CloudWatchMetricsService service = new CloudWatchMetricsService(new InMemoryStorage<>(), alarms,
            regionResolver);
    private final CloudWatchMetricsJsonHandler handler = new CloudWatchMetricsJsonHandler(service,
            new CloudWatchDashboardsService(new InMemoryStorage<>(), regionResolver),
            new CloudWatchMetricStreamsService(new InMemoryStorage<>(), regionResolver), MAPPER);

    @Test
    void anAlarmIsNeverListedAsItsOwnChildOrParent() {
        putMetric("source", REGION);
        putMetric("unrelated", REGION);
        storeComposite("self", "ALARM(self) OR ALARM(\"" + arn("source") + "\")");
        storeComposite("watcher", "ALARM(self)");

        assertEquals(List.of("source"), names(describe("ChildrenOfAlarmName", "self", REGION), "MetricAlarms"));
        assertEquals(List.of(), names(describe("ChildrenOfAlarmName", "self", REGION), "CompositeAlarms"));
        assertEquals(List.of("watcher"), names(describe("ParentsOfAlarmName", "self", REGION), "CompositeAlarms"));
    }

    @Test
    void referencesToMissingAlarmsAreNotListedAsChildrenButStillHaveParents() {
        putMetric("present", REGION);
        putMetric("unrelated", REGION);
        storeComposite("stale", "ALARM(present) OR ALARM(\"" + arn("gone") + "\")");

        assertEquals(List.of("present"), names(describe("ChildrenOfAlarmName", "stale", REGION), "MetricAlarms"));
        assertEquals(List.of("stale"), names(describe("ParentsOfAlarmName", "gone", REGION), "CompositeAlarms"));
        assertEquals(List.of("stale"), names(describe("ParentsOfAlarmName", "present", REGION), "CompositeAlarms"));
    }

    @Test
    void relativesAreLookedUpInTheRequestRegionOnly() {
        putMetric("shared", REGION);
        putMetric("shared", "us-west-2");
        putMetric("unrelated", REGION);
        storeComposite("aggregate", "ALARM(shared)");

        JsonNode children = describe("ChildrenOfAlarmName", "aggregate", REGION);
        assertEquals(1, children.path("MetricAlarms").size());
        assertEquals(arn("shared"), children.path("MetricAlarms").path(0).path("AlarmArn").asText());
        assertEquals(List.of(), names(describe("ParentsOfAlarmName", "shared", "us-west-2"), "CompositeAlarms"));
    }

    @Test
    void jsonNullMembersAreLeftOut() {
        putMetric("source", REGION);
        storeComposite("aggregate", "ALARM(source)");

        ObjectNode children = MAPPER.createObjectNode().put("ChildrenOfAlarmName", "aggregate");
        children.putNull("AlarmNamePrefix");
        children.putNull("StateValue");
        assertEquals(List.of("source"), names(describe(children, REGION), "MetricAlarms"));
        ObjectNode unfiltered = MAPPER.createObjectNode();
        unfiltered.putNull("AlarmNamePrefix");
        unfiltered.putNull("ActionPrefix");
        unfiltered.putNull("ParentsOfAlarmName");
        assertEquals(List.of("source"), names(describe(unfiltered, REGION), "MetricAlarms"));
    }

    private String arn(String name) {
        return regionResolver.buildArn("cloudwatch", REGION, "alarm:" + name);
    }

    private void putMetric(String name, String region) {
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName(name);
        service.putMetricAlarm(alarm, region);
    }

    private void storeComposite(String name, String rule) {
        CompositeAlarm alarm = new CompositeAlarm();
        alarm.setAlarmName(name);
        alarm.setAlarmArn(arn(name));
        alarm.setAlarmRule(rule);
        alarm.setRegion(REGION);
        alarms.put(REGION + "::" + name, alarm);
    }

    private JsonNode describe(String member, String value, String region) {
        return describe(MAPPER.createObjectNode().put(member, value), region);
    }

    private JsonNode describe(ObjectNode request, String region) {
        return MAPPER.valueToTree(handler.handle("DescribeAlarms", request, region).getEntity());
    }

    private static List<String> names(JsonNode response, String kind) {
        List<String> names = new ArrayList<>();
        response.path(kind).forEach(alarm -> names.add(alarm.path("AlarmName").asText()));
        return names.stream().sorted().toList();
    }
}
