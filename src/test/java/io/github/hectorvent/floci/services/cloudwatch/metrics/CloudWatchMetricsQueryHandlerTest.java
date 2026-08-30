package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
    private final CloudWatchMetricsQueryHandler handler = new CloudWatchMetricsQueryHandler(metricsService);

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
}
