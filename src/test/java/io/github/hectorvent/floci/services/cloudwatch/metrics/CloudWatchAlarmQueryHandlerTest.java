package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Alarm;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import java.time.Duration;
import java.time.Instant;
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

        MultivaluedMap<String, String> describe = new MultivaluedHashMap<>();
        describe.putSingle("AlarmTypes.member.1", "MetricAlarm");
        describe.putSingle("AlarmTypes.member.2", "CompositeAlarm");
        String xml = (String) handler.handle("DescribeAlarms", describe, region).getEntity();
        String updated = Instant.ofEpochSecond(alarm.getStateUpdatedTimestamp()).toString();
        assertTrue(xml.contains("<StateTransitionedTimestamp>" + updated + "</StateTransitionedTimestamp>"), xml);

        alarm.setStateTransitionedTimestamp(100);
        xml = (String) handler.handle("DescribeAlarms", describe, region).getEntity();
        assertTrue(xml.contains("<StateUpdatedTimestamp>" + updated + "</StateUpdatedTimestamp>"), xml);
        assertTrue(xml.contains("<StateTransitionedTimestamp>1970-01-01T00:01:40Z</StateTransitionedTimestamp>"), xml);
    }

    @Test
    void describeAlarmHistoryRendersXmlItemsAndPages() throws Exception {
        String region = "us-east-1";
        CloudWatchMetricsService service = new CloudWatchMetricsService(new InMemoryStorage<>(),
                new InMemoryStorage<>(), new RegionResolver(region, "123456789012"));
        CloudWatchMetricsQueryHandler handler = new CloudWatchMetricsQueryHandler(service, null, null);
        MultivaluedMap<String, String> put = new MultivaluedHashMap<>();
        put.putSingle("AlarmName", "query-history");
        handler.handle("PutMetricAlarm", put, region);
        for (String state : List.of("OK", "ALARM")) {
            MultivaluedMap<String, String> set = new MultivaluedHashMap<>();
            set.putSingle("AlarmName", "query-history");
            set.putSingle("StateValue", state);
            set.putSingle("StateReason", "manual");
            handler.handle("SetAlarmState", set, region);
        }
        MultivaluedMap<String, String> request = new MultivaluedHashMap<>();
        request.putSingle("AlarmName", "query-history");
        request.putSingle("AlarmTypes.member.1", "MetricAlarm");
        request.putSingle("HistoryItemType", "StateUpdate");
        request.putSingle("MaxRecords", "1");

        Document first = XmlParser.parseDocument((String) handler.handle("DescribeAlarmHistory", request, region)
                .getEntity());
        assertEquals(AwsNamespaces.CW, first.getDocumentElement().getNamespaceURI());
        assertEquals("DescribeAlarmHistoryResponse", first.getDocumentElement().getLocalName());
        assertEquals(1, elements(first, "AlarmHistoryItems").getLength());
        assertEquals(1, elements(first, "member").getLength());
        assertEquals("query-history", text(first, "AlarmName"));
        assertEquals("MetricAlarm", text(first, "AlarmType"));
        assertEquals("StateUpdate", text(first, "HistoryItemType"));
        assertEquals("Alarm updated from OK to ALARM", text(first, "HistorySummary"));
        assertTrue(text(first, "HistoryData").startsWith("{\"version\":\"1.0\""));
        Instant timestamp = Instant.parse(text(first, "Timestamp"));
        assertTrue(Duration.between(timestamp, Instant.now()).abs().toSeconds() < 60);
        String token = text(first, "NextToken");
        assertFalse(token.isEmpty());

        request.putSingle("NextToken", token);
        Document last = XmlParser.parseDocument((String) handler.handle("DescribeAlarmHistory", request, region)
                .getEntity());
        assertEquals("Alarm updated from INSUFFICIENT_DATA to OK", text(last, "HistorySummary"));
        assertEquals(0, elements(last, "NextToken").getLength());

        MultivaluedMap<String, String> reversed = new MultivaluedHashMap<>();
        reversed.putSingle("StartDate", "2026-10-26T16:23:10Z");
        reversed.putSingle("EndDate", "2026-10-26T16:22:10Z");
        AwsException error = assertThrows(AwsException.class,
                () -> handler.handle("DescribeAlarmHistory", reversed, region));
        assertEquals("InvalidParameterValue", error.getErrorCode());
        assertEquals("Start date Mon Oct 26 16:23:10 UTC 2026 must be before end date Mon Oct 26 16:22:10 UTC 2026",
                error.getMessage());
    }

    private static NodeList elements(Document document, String name) {
        return document.getElementsByTagNameNS(AwsNamespaces.CW, name);
    }

    private static String text(Document document, String name) {
        return elements(document, name).item(0).getTextContent();
    }
}
