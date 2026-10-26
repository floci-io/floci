package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Alarm;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CloudWatchAlarmQueryHandlerTest {
    @ParameterizedTest
    @ValueSource(strings = {"PutMetricAlarm", "PutCompositeAlarm"})
    void sharedFieldsReachBothAlarmTypes(String operation) {
        String region = "us-east-1";
        CloudWatchMetricsService service = new CloudWatchMetricsService(new InMemoryStorage<>(),
                new InMemoryStorage<>(), new RegionResolver(region, "123456789012"));
        CloudWatchMetricsQueryHandler handler = new CloudWatchMetricsQueryHandler(service, null, null);
        MultivaluedMap<String, String> request = new MultivaluedHashMap<>();
        request.putSingle("AlarmName", "shared");
        request.putSingle("AlarmRule", "FALSE");
        request.putSingle("AlarmDescription", "shared fields");
        request.putSingle("ActionsEnabled", "false");
        request.putSingle("AlarmActions.member.1", "alarm-action");
        request.putSingle("OKActions.member.1", "ok-action");
        request.putSingle("InsufficientDataActions.member.1", "missing-action");
        request.putSingle("Tags.member.1.Key", "team");
        request.putSingle("Tags.member.1.Value", "metrics");
        assertEquals(200, handler.handle(operation, request, region).getStatus());
        Alarm alarm = "PutMetricAlarm".equals(operation)
                ? service.describeAlarms(List.of("shared"), null, region).getFirst()
                : service.describeCompositeAlarms(List.of("shared"), null, region).getFirst();
        assertEquals("shared fields", alarm.getAlarmDescription());
        assertFalse(alarm.isActionsEnabled());
        assertEquals(List.of("alarm-action"), alarm.getAlarmActions());
        assertEquals(List.of("ok-action"), alarm.getOkActions());
        assertEquals(List.of("missing-action"), alarm.getInsufficientDataActions());
        assertEquals(Map.of("team", "metrics"), alarm.getTags());
    }
}
