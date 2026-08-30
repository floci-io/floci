package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two worked examples from AWS's "Configuring how CloudWatch alarms treat missing data",
 * transcribed as executable cases. Each test is one row of a published table and asserts the
 * documented state for all four {@code TreatMissingData} settings at once.
 *
 * <p>Patterns read oldest-to-newest, as in the AWS tables: {@code 0} is a non-breaching datapoint,
 * {@code X} a breaching one and {@code -} a missing period. Both tables use
 * {@code EvaluationPeriods = 3}, giving an evaluation range of 5.</p>
 */
class AlarmEvaluatorMissingDataTablesTest {

    private static final String REGION = "us-east-1";
    private static final double THRESHOLD = 50.0;
    private static final String RETAIN = "<retain>";

    @Test
    void enoughRealDatapointsIgnoresTheMissingDataSetting() {
        assertRow("0 - X - X", 3, "OK", "OK", "OK", "OK");
    }

    @Test
    void oneOldNonBreachingDatapointStaysOkUnderEverySetting() {
        assertRow("0 - - - -", 3, "OK", "OK", "OK", "OK");
    }

    @Test
    void anEntirelyEmptyRangeIsTheOnlyInsufficientDataCase() {
        assertRow("- - - - -", 3, "INSUFFICIENT_DATA", RETAIN, "ALARM", "OK");
    }

    @Test
    void threeRecentBreachesAlarmUnderEverySetting() {
        assertRow("0 X X - X", 3, "ALARM", "ALARM", "ALARM", "ALARM");
    }

    @Test
    void aBreachAgedPastDatapointsToAlarmAlarmsEvenWithTooFewRealDatapoints() {
        assertRow("- - X - -", 3, "ALARM", RETAIN, "ALARM", "OK");
    }

    @Test
    void twoOfThreeBreachesAlarmWhenEnoughRealDatapointsExist() {
        assertRow("0 - X - X", 2, "ALARM", "ALARM", "ALARM", "ALARM");
    }

    @Test
    void aFullRangeUsesOnlyTheMostRecentEvaluationPeriods() {
        assertRow("0 0 X 0 X", 2, "ALARM", "ALARM", "ALARM", "ALARM");
    }

    @Test
    void aNonBreachingReadingAnywhereInRangeBlocksThePrematureShortcut() {
        assertRow("0 - X - -", 2, "OK", "OK", "ALARM", "OK");
    }

    @Test
    void trailingGapsOnlyAlarmWhenMissingDataIsTreatedAsBreaching() {
        assertRow("- - - - 0", 2, "OK", "OK", "ALARM", "OK");
    }

    @Test
    void theShortcutAppliesToMOutOfNAlarmsToo() {
        assertRow("- - - X -", 2, "ALARM", RETAIN, "ALARM", "OK");
    }

    /**
     * @param expected states for {@code missing}, {@code ignore}, {@code breaching} and
     *                 {@code notBreaching}, in the column order of the AWS tables. {@link #RETAIN}
     *                 means the alarm must keep the state it already had.
     */
    private void assertRow(String pattern, int datapointsToAlarm, String... expected) {
        String[] treatments = {"missing", "ignore", "breaching", "notBreaching"};
        for (int i = 0; i < treatments.length; i++) {
            assertState(pattern, datapointsToAlarm, treatments[i], expected[i]);
        }
    }

    private void assertState(String pattern, int datapointsToAlarm, String treatment, String expected) {
        CloudWatchMetricsService service = mock(CloudWatchMetricsService.class);
        @SuppressWarnings("unchecked")
        Instance<AlarmActionHandler> noHandlers = mock(Instance.class);
        AlarmEvaluator subject = new AlarmEvaluator(service, noHandlers);

        when(service.getMetricStatistics(any(), any(), any(), any(), any(), anyInt(), any(), any(), anyString()))
                .thenAnswer(invocation -> datapointsFor(pattern, invocation.getArgument(3)));

        MetricAlarm alarm = alarm(datapointsToAlarm, treatment);
        String before = alarm.getStateValue();
        subject.evaluate(alarm);

        String context = pattern + " / DatapointsToAlarm=" + datapointsToAlarm + " / " + treatment;
        if (RETAIN.equals(expected)) {
            verify(service, never().description(context + " must retain " + before))
                    .setAlarmState(anyString(), anyString(), anyString(), any(), anyString());
        } else if (expected.equals(before)) {
            verify(service, never().description(context + " must stay " + expected))
                    .setAlarmState(anyString(), anyString(), anyString(), any(), anyString());
        } else {
            verify(service, times(1).description(context + " must become " + expected))
                    .setAlarmState(anyString(), eq(expected), anyString(), any(), anyString());
        }
    }

    /**
     * Builds the pattern's datapoints against the window the evaluator actually asked for, so the
     * mapping from slot to period bucket cannot drift if the clock crosses a period boundary
     * between building the alarm and evaluating it.
     */
    private static List<CloudWatchMetricsService.Datapoint> datapointsFor(String pattern, Instant start) {
        List<CloudWatchMetricsService.Datapoint> points = new ArrayList<>();
        String[] slots = pattern.trim().split("\\s+");
        for (int i = 0; i < slots.length; i++) {
            if ("-".equals(slots[i])) {
                continue;
            }
            double value = "X".equals(slots[i]) ? 80.0 : 20.0;
            Instant timestamp = start.plusSeconds(60L * i);
            points.add(new CloudWatchMetricsService.Datapoint(
                    timestamp, 1, value, value, value, value, "Percent"));
        }
        return points;
    }

    private static MetricAlarm alarm(int datapointsToAlarm, String treatMissingData) {
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName("doc-table");
        alarm.setRegion(REGION);
        alarm.setNamespace("AWS/ECS");
        alarm.setMetricName("CPUUtilization");
        alarm.setStatistic("Average");
        alarm.setPeriod(60);
        alarm.setEvaluationPeriods(3);
        alarm.setDatapointsToAlarm(datapointsToAlarm);
        alarm.setThreshold(THRESHOLD);
        alarm.setComparisonOperator("GreaterThanThreshold");
        alarm.setTreatMissingData(treatMissingData);
        alarm.setActionsEnabled(false);
        alarm.setStateValue("OK");
        return alarm;
    }
}
