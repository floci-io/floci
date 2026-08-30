package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlarmEvaluatorTest {

    private static final String REGION = "us-east-1";
    private static final String POLICY_ARN =
            "arn:aws:autoscaling:us-east-1:000000000000:scalingPolicy:x:resource/ecs/y:policyName/z";

    private final CloudWatchMetricsService metricsService = mock(CloudWatchMetricsService.class);
    @SuppressWarnings("unchecked")
    private final Instance<AlarmActionHandler> handlers = mock(Instance.class);
    private final AlarmActionHandler handler = mock(AlarmActionHandler.class);
    private final AlarmEvaluator evaluator = new AlarmEvaluator(metricsService, handlers);

    private static MetricAlarm alarm() {
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName("TargetTracking-svc-AlarmHigh-abc");
        alarm.setRegion(REGION);
        alarm.setNamespace("AWS/ECS");
        alarm.setMetricName("CPUUtilization");
        alarm.setStatistic("Average");
        alarm.setPeriod(60);
        alarm.setEvaluationPeriods(3);
        alarm.setThreshold(50.0);
        alarm.setComparisonOperator("GreaterThanThreshold");
        alarm.setActionsEnabled(true);
        alarm.setAlarmActions(List.of(POLICY_ARN));
        return alarm;
    }

    private static List<CloudWatchMetricsService.Datapoint> datapoints(double... values) {
        List<CloudWatchMetricsService.Datapoint> points = new ArrayList<>();
        Instant now = Instant.now();
        for (int i = 0; i < values.length; i++) {
            points.add(new CloudWatchMetricsService.Datapoint(
                    now.minusSeconds(60L * (values.length - i)),
                    1, values[i], values[i], values[i], values[i], "Percent"));
        }
        return points;
    }

    private void stubMetrics(List<CloudWatchMetricsService.Datapoint> points) {
        when(metricsService.getMetricStatistics(
                any(), any(), any(), any(), any(), anyInt(), any(), any(), anyString()))
                .thenReturn(points);
    }

    @Test
    void transitionsToAlarmAndDispatchesWhenThresholdBreachedForAllPeriods() {
        stubMetrics(datapoints(80, 85, 90));
        when(handlers.iterator()).thenAnswer(inv -> List.of(handler).iterator());
        when(handler.supports(POLICY_ARN)).thenReturn(true);

        MetricAlarm alarm = alarm();
        evaluator.evaluate(alarm);

        verify(metricsService).setAlarmState(
                eq(alarm.getAlarmName()), eq("ALARM"), anyString(), any(), eq(REGION));
        verify(handler).handle(eq(POLICY_ARN), eq(alarm), eq(90.0), eq(REGION));
    }

    @Test
    void redispatchesOnEveryTickWhileStillInAlarm() {
        stubMetrics(datapoints(80, 85, 90));
        when(handlers.iterator()).thenAnswer(inv -> List.of(handler).iterator());
        when(handler.supports(POLICY_ARN)).thenReturn(true);

        MetricAlarm alarm = alarm();
        alarm.setStateValue("ALARM");
        evaluator.evaluate(alarm);

        verify(handler).handle(eq(POLICY_ARN), eq(alarm), eq(90.0), eq(REGION));
    }

    /**
     * AWS reaches {@code INSUFFICIENT_DATA} only when every period in the evaluation range is
     * empty; a range holding fewer real datapoints than {@code EvaluationPeriods} is still
     * evaluated on the real data it does have.
     */
    @Test
    void insufficientDataOnlyWhenTheWholeEvaluationRangeIsEmpty() {
        stubMetrics(List.of());

        MetricAlarm alarm = alarm();
        alarm.setStateValue("OK");
        evaluator.evaluate(alarm);

        verify(metricsService).setAlarmState(
                eq(alarm.getAlarmName()), eq("INSUFFICIENT_DATA"), anyString(), any(), eq(REGION));
    }

    @Test
    void transitionsBackToOkWhenNoLongerBreaching() {
        stubMetrics(datapoints(10, 15, 20));

        MetricAlarm alarm = alarm();
        alarm.setStateValue("ALARM");
        evaluator.evaluate(alarm);

        verify(metricsService).setAlarmState(
                eq(alarm.getAlarmName()), eq("OK"), anyString(), any(), eq(REGION));
        verify(handler, never()).handle(anyString(), any(), anyDouble(), anyString());
    }

    @Test
    void skipsAlarmsMissingEvaluableConfig() {
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName("hand-created-no-metric-config");
        alarm.setRegion(REGION);

        evaluator.evaluate(alarm);

        verify(metricsService, never()).getMetricStatistics(
                any(), any(), any(), any(), any(), anyInt(), any(), any(), anyString());
    }

    @Test
    void evaluateAllSurvivesAllAlarmsThrowing() {
        when(metricsService.allAlarms()).thenThrow(new RuntimeException("storage hiccup"));

        assertDoesNotThrow(evaluator::evaluateAll);
    }

    @Test
    void actionsDisabledSuppressesDispatchEvenOnBreach() {
        stubMetrics(datapoints(80, 85, 90));

        MetricAlarm alarm = alarm();
        alarm.setActionsEnabled(false);
        evaluator.evaluate(alarm);

        verify(handler, never()).handle(anyString(), any(), anyDouble(), anyString());
    }

    @Test
    void dispatchesTheMostRecentBreachingValueEvenWhenTheLatestDatapointDoesNot() {
        stubMetrics(datapoints(90, 85, 20));
        when(handlers.iterator()).thenAnswer(inv -> List.of(handler).iterator());
        when(handler.supports(POLICY_ARN)).thenReturn(true);

        MetricAlarm alarm = alarm();
        alarm.setDatapointsToAlarm(2);
        evaluator.evaluate(alarm);

        verify(handler).handle(eq(POLICY_ARN), eq(alarm), eq(85.0), eq(REGION));
    }

    @Test
    void treatMissingDataBreachingCountsGapsAsBreaching() {
        stubMetrics(datapoints(80));
        when(handlers.iterator()).thenAnswer(inv -> List.<AlarmActionHandler>of().iterator());

        MetricAlarm alarm = alarm();
        alarm.setStateValue("OK");
        alarm.setTreatMissingData("breaching");
        evaluator.evaluate(alarm);

        verify(metricsService).setAlarmState(
                eq(alarm.getAlarmName()), eq("ALARM"), anyString(), any(), eq(REGION));
    }

    @Test
    void missingDataAlarmWithNoRealBreachingDatapointDispatchesNaN() {
        stubMetrics(datapoints(20));
        when(handlers.iterator()).thenAnswer(inv -> List.of(handler).iterator());
        when(handler.supports(POLICY_ARN)).thenReturn(true);

        MetricAlarm alarm = alarm();
        alarm.setStateValue("OK");
        alarm.setTreatMissingData("breaching");
        alarm.setDatapointsToAlarm(2);
        evaluator.evaluate(alarm);

        verify(metricsService).setAlarmState(
                eq(alarm.getAlarmName()), eq("ALARM"), anyString(), any(), eq(REGION));
        verify(handler).handle(eq(POLICY_ARN), eq(alarm), eq(Double.NaN), eq(REGION));
    }

    @Test
    void treatMissingDataNotBreachingDoesNotCountGapsTowardAlarm() {
        stubMetrics(datapoints(80));

        MetricAlarm alarm = alarm();
        alarm.setStateValue("ALARM");
        alarm.setTreatMissingData("notBreaching");
        evaluator.evaluate(alarm);

        verify(metricsService).setAlarmState(
                eq(alarm.getAlarmName()), eq("OK"), anyString(), any(), eq(REGION));
    }

    @Test
    void queriesAPeriodAlignedWindowRegardlessOfWhenTheTickFires() {
        stubMetrics(datapoints(80, 85, 90));
        when(handlers.iterator()).thenAnswer(inv -> List.<AlarmActionHandler>of().iterator());

        evaluator.evaluate(alarm());

        ArgumentCaptor<Instant> startCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(metricsService).getMetricStatistics(
                any(), any(), any(), startCaptor.capture(), any(), anyInt(), any(), any(), anyString());
        assertEquals(0, startCaptor.getValue().getEpochSecond() % 60);
    }

    @Test
    void treatMissingDataIgnoreLeavesStateUntouched() {
        stubMetrics(datapoints(80));

        MetricAlarm alarm = alarm();
        alarm.setStateValue("OK");
        alarm.setTreatMissingData("ignore");
        evaluator.evaluate(alarm);

        verify(metricsService, never()).setAlarmState(
                anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void treatMissingDataIgnoreStillRetriesDispatchWhileAlreadyInAlarm() {
        stubMetrics(datapoints(80));
        when(handlers.iterator()).thenAnswer(inv -> List.of(handler).iterator());
        when(handler.supports(POLICY_ARN)).thenReturn(true);

        MetricAlarm alarm = alarm();
        alarm.setStateValue("ALARM");
        alarm.setTreatMissingData("ignore");
        evaluator.evaluate(alarm);

        verify(metricsService, never()).setAlarmState(
                anyString(), anyString(), anyString(), any(), anyString());
        verify(handler).handle(eq(POLICY_ARN), eq(alarm), eq(80.0), eq(REGION));
    }
}
