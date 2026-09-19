package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.services.cloudwatch.dashboards.CloudWatchDashboardsService;
import io.github.hectorvent.floci.services.cloudwatch.metricstreams.CloudWatchMetricStreamsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The JSON 1.0 handler defaults {@code ActionsEnabled} to {@code true} when omitted, matching
 * AWS's documented default for {@code PutMetricAlarm}. This handler must do the same over the
 * Query protocol — a mismatch here would silently disable {@link AlarmEvaluator} dispatch for
 * any hand-created alarm (e.g. behind a StepScaling policy) that doesn't explicitly pass the
 * parameter, since {@code Boolean.parseBoolean(null)} defaults to {@code false}.
 */
class CloudWatchMetricsQueryHandlerTest {

    private static final String REGION = "us-east-1";

    private final CloudWatchMetricsService metricsService = mock(CloudWatchMetricsService.class);
    // The alarm cases below never reach a dashboard operation; the handler simply routes both.
    private final CloudWatchMetricsQueryHandler handler = new CloudWatchMetricsQueryHandler(
            metricsService, mock(CloudWatchDashboardsService.class),
            mock(CloudWatchMetricStreamsService.class));

    @Test
    void putMetricAlarmDefaultsActionsEnabledToTrueWhenOmitted() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("AlarmName", "TestAlarm");
        params.putSingle("MetricName", "M");
        params.putSingle("Namespace", "NS");

        handler.handle("PutMetricAlarm", params, REGION);

        ArgumentCaptor<MetricAlarm> captor = ArgumentCaptor.forClass(MetricAlarm.class);
        verify(metricsService).putMetricAlarm(captor.capture(), anyString());
        assertTrue(captor.getValue().isActionsEnabled());
    }

    @Test
    void putMetricAlarmRespectsExplicitActionsEnabledFalse() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("AlarmName", "TestAlarm");
        params.putSingle("MetricName", "M");
        params.putSingle("Namespace", "NS");
        params.putSingle("ActionsEnabled", "false");

        handler.handle("PutMetricAlarm", params, REGION);

        ArgumentCaptor<MetricAlarm> captor = ArgumentCaptor.forClass(MetricAlarm.class);
        verify(metricsService).putMetricAlarm(captor.capture(), anyString());
        assertFalse(captor.getValue().isActionsEnabled());
    }

    // Terraform's provider speaks the Query protocol to CloudWatch, so this is the path that
    // decides whether a re-plan reports drift. An alarm created without DatapointsToAlarm must
    // come back without it. floci-io/floci#3660 is what echoing EvaluationPeriods here costs.
    @Test
    void putMetricAlarmLeavesDatapointsToAlarmUnsetWhenOmitted() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("AlarmName", "TestAlarm");
        params.putSingle("MetricName", "M");
        params.putSingle("Namespace", "NS");
        params.putSingle("EvaluationPeriods", "3");

        handler.handle("PutMetricAlarm", params, REGION);

        ArgumentCaptor<MetricAlarm> captor = ArgumentCaptor.forClass(MetricAlarm.class);
        verify(metricsService).putMetricAlarm(captor.capture(), anyString());
        assertEquals(3, captor.getValue().getEvaluationPeriods());
        assertNull(captor.getValue().getDatapointsToAlarm());
    }

    @Test
    void putMetricAlarmKeepsAnExplicitDatapointsToAlarm() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("AlarmName", "TestAlarm");
        params.putSingle("MetricName", "M");
        params.putSingle("Namespace", "NS");
        params.putSingle("EvaluationPeriods", "3");
        params.putSingle("DatapointsToAlarm", "2");

        handler.handle("PutMetricAlarm", params, REGION);

        ArgumentCaptor<MetricAlarm> captor = ArgumentCaptor.forClass(MetricAlarm.class);
        verify(metricsService).putMetricAlarm(captor.capture(), anyString());
        assertEquals(2, captor.getValue().getDatapointsToAlarm());
    }

    // The JSON handler forwards the Unit filter now; this pins the Query path to the same
    // contract so the two protocols cannot drift apart again.
    @Test
    void getMetricStatisticsForwardsTheUnitFilterToTheService() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("Namespace", "NS");
        params.putSingle("MetricName", "M");
        params.putSingle("Period", "60");
        params.putSingle("Statistics.member.1", "Sum");
        params.putSingle("Unit", "Count");

        when(metricsService.getMetricStatistics(any(), any(), any(), any(), any(),
                anyInt(), any(), any(), any())).thenReturn(List.of());

        handler.handle("GetMetricStatistics", params, REGION);

        ArgumentCaptor<String> unitCaptor = ArgumentCaptor.forClass(String.class);
        verify(metricsService).getMetricStatistics(any(), any(), any(), any(), any(),
                anyInt(), any(), unitCaptor.capture(), any());
        assertEquals("Count", unitCaptor.getValue());
    }
}
