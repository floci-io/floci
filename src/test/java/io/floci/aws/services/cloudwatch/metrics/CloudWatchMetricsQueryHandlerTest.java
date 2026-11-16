package io.floci.aws.services.cloudwatch.metrics;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudwatch.dashboards.CloudWatchDashboardsService;
import io.floci.aws.services.cloudwatch.metrics.model.MetricAlarm;
import io.floci.aws.services.cloudwatch.metrics.model.MetricDatum;
import io.floci.aws.services.cloudwatch.metricstreams.CloudWatchMetricStreamsService;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
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
    void aLargeMixedBatchDoesNotRescanEveryFormFieldForEachDatum() {
        MultivaluedMap<String, String> params = spy(new MultivaluedHashMap<String, String>());
        params.putSingle("Namespace", "LargeBatch");
        for (String list : List.of("MetricData", "EntityMetricData.member.2.MetricData")) {
            for (int index = 1; index <= 500; index++) {
                String prefix = list + ".member." + index + ".";
                params.putSingle(prefix + "MetricName", "Metric" + index);
                params.putSingle(prefix + "Value", Integer.toString(index));
            }
        }

        handler.handle("PutMetricData", params, REGION);

        ArgumentCaptor<List<MetricDatum>> datums = ArgumentCaptor.forClass(List.class);
        verify(metricsService).putMetricData(eq("LargeBatch"), datums.capture(), eq(REGION));
        assertEquals(1000, datums.getValue().size());
        assertEquals(500.0, datums.getValue().getLast().getValue());
        // Bound whole-map scans independently of the number of datums; no timing assertion.
        verify(params, atMost(3)).keySet();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MetricData", "EntityMetricData.member.2.MetricData"})
    void anUnknownStatisticMemberStillRequiresTheCompleteStatisticSet(String list) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        String prefix = list + ".member.1.";
        params.putSingle("Namespace", "InvalidValues");
        params.putSingle(prefix + "MetricName", "Partial");
        params.putSingle(prefix + "StatisticValues.Unknown", "1");

        AwsException error = assertThrows(AwsException.class,
                () -> handler.handle("PutMetricData", params, REGION));

        assertEquals("MissingParameter", error.getErrorCode());
        assertEquals("The parameter " + prefix + "StatisticValues.SampleCount is required.", error.getMessage());
        verify(metricsService, never()).putMetricData(anyString(), any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MetricData", "EntityMetricData.member.2.MetricData"})
    void sparseValidMembersAreParsedInNumericOrder(String list) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("Namespace", "SparseBatch");
        for (int index : new int[]{999999999, 3, 1}) {
            params.putSingle(list + ".member." + index + ".MetricName", "Metric" + index);
            params.putSingle(list + ".member." + index + ".Value", "1");
        }

        handler.handle("PutMetricData", params, REGION);

        ArgumentCaptor<List<MetricDatum>> datums = ArgumentCaptor.forClass(List.class);
        verify(metricsService).putMetricData(eq("SparseBatch"), datums.capture(), eq(REGION));
        assertEquals(List.of("Metric1", "Metric3", "Metric999999999"),
                datums.getValue().stream().map(MetricDatum::getMetricName).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MetricData", "EntityMetricData.member.2.MetricData"})
    void invalidStatisticsAfterAGapRejectTheWholeBatch(String list) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("Namespace", "SparseBatch");
        params.putSingle(list + ".member.1.MetricName", "Good");
        params.putSingle(list + ".member.1.Value", "1");
        String prefix = list + ".member.3.";
        params.putSingle(prefix + "MetricName", "Invalid");
        params.putSingle(prefix + "StatisticValues.SampleCount", "2");
        params.putSingle(prefix + "StatisticValues.Sum", "six");
        params.putSingle(prefix + "StatisticValues.Minimum", "1");
        params.putSingle(prefix + "StatisticValues.Maximum", "2");

        AwsException error = assertThrows(AwsException.class,
                () -> handler.handle("PutMetricData", params, REGION));

        assertEquals("InvalidParameterValue", error.getErrorCode());
        verify(metricsService, never()).putMetricData(anyString(), any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MetricData", "EntityMetricData.member.2.MetricData"})
    void aMissingMetricNameAfterAGapRejectsTheWholeBatch(String list) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("Namespace", "SparseBatch");
        params.putSingle(list + ".member.1.MetricName", "Good");
        params.putSingle(list + ".member.1.Value", "1");
        params.putSingle(list + ".member.3.Value", "2");

        AwsException error = assertThrows(AwsException.class,
                () -> handler.handle("PutMetricData", params, REGION));

        assertEquals("MissingParameter", error.getErrorCode());
        assertEquals("The parameter " + list + ".member.3.MetricName is required.", error.getMessage());
        verify(metricsService, never()).putMetricData(anyString(), any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MetricData.member.9999999999", "EntityMetricData.member.2.MetricData.member.9999999999",
            "EntityMetricData.member.9999999999.MetricData.member.1"})
    void oversizedMemberIndexesRejectTheWholeBatchWithoutIntegerOverflow(String prefix) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("Namespace", "SparseBatch");
        params.putSingle("MetricData.member.1.MetricName", "Good");
        params.putSingle("MetricData.member.1.Value", "1");
        params.putSingle(prefix + ".MetricName", "TooLarge");
        params.putSingle(prefix + ".Value", "2");

        AwsException error = assertThrows(AwsException.class,
                () -> handler.handle("PutMetricData", params, REGION));

        assertEquals("InvalidParameterValue", error.getErrorCode());
        verify(metricsService, never()).putMetricData(anyString(), any(), anyString());
    }

    private static MultivaluedMap<String, String> statisticRequest() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("Namespace", "InvalidValues");
        params.putSingle("MetricData.member.1.MetricName", "First");
        params.putSingle("MetricData.member.1.Value", "1");
        params.putSingle("MetricData.member.2.MetricName", "Invalid");
        params.putSingle("MetricData.member.2.StatisticValues.SampleCount", "2");
        params.putSingle("MetricData.member.2.StatisticValues.Sum", "6");
        params.putSingle("MetricData.member.2.StatisticValues.Minimum", "2");
        params.putSingle("MetricData.member.2.StatisticValues.Maximum", "4");
        return params;
    }

    @ParameterizedTest
    @ValueSource(strings = {"SampleCount", "Sum", "Minimum", "Maximum"})
    void incompleteStatisticsRejectTheWholeBatch(String missing) {
        MultivaluedMap<String, String> params = statisticRequest();
        String path = "MetricData.member.2.StatisticValues." + missing;
        params.remove(path);

        AwsException error = assertThrows(AwsException.class,
                () -> handler.handle("PutMetricData", params, REGION));

        assertEquals("MissingParameter", error.getErrorCode());
        assertEquals("The parameter " + path + " is required.", error.getMessage());
        verify(metricsService, never()).putMetricData(anyString(), any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "not-a-number", "NaN", "Infinity", "1e109", "-1e109"})
    void malformedNumericValuesAreNotStoredAsZero(String value) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("Namespace", "InvalidValues");
        params.putSingle("MetricData.member.1.MetricName", "Invalid");
        params.putSingle("MetricData.member.1.Value", value);

        AwsException error = assertThrows(AwsException.class,
                () -> handler.handle("PutMetricData", params, REGION));

        assertEquals("InvalidParameterValue", error.getErrorCode());
        verify(metricsService, never()).putMetricData(anyString(), any(), anyString());
    }

    @Test
    void scalarAndStatisticsAreNotSilentlyReducedToStatistics() {
        MultivaluedMap<String, String> params = statisticRequest();
        params.putSingle("MetricData.member.2.Value", "7");

        AwsException error = assertThrows(AwsException.class,
                () -> handler.handle("PutMetricData", params, REGION));

        assertEquals("InvalidParameterCombination", error.getErrorCode());
        assertEquals("InvalidParameterCombinationException", error.jsonType());
        verify(metricsService, never()).putMetricData(anyString(), any(), anyString());
    }

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

    // The Query serialization of EntityMetricData nests each datum two lists deep;
    // floci-io/floci#6066 is the JSON-side report of the same dropped datums.
    @Test
    void putMetricDataStoresTopLevelAndEntityMetricDataUnderTheRequestNamespace() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("Namespace", "CWAgent");
        params.putSingle("MetricData.member.1.MetricName", "TopLevel");
        params.putSingle("MetricData.member.1.Value", "1");
        params.putSingle("EntityMetricData.member.1.Entity.KeyAttributes.entry.1.key", "Type");
        params.putSingle("EntityMetricData.member.1.Entity.KeyAttributes.entry.1.value", "AWS::Resource");
        params.putSingle("EntityMetricData.member.1.MetricData.member.1.MetricName", "mem_used_percent");
        params.putSingle("EntityMetricData.member.1.MetricData.member.1.Value", "6.5");
        params.putSingle("EntityMetricData.member.1.MetricData.member.1.Unit", "Percent");
        params.putSingle("EntityMetricData.member.1.MetricData.member.1.Dimensions.member.1.Name", "host");
        params.putSingle("EntityMetricData.member.1.MetricData.member.1.Dimensions.member.1.Value", "h1");
        params.putSingle("EntityMetricData.member.2.MetricData.member.1.MetricName", "disk_used_percent");
        params.putSingle("EntityMetricData.member.2.MetricData.member.1.Value", "0.75");
        params.putSingle("StrictEntityValidation", "false");

        handler.handle("PutMetricData", params, REGION);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MetricDatum>> captor = ArgumentCaptor.forClass(List.class);
        verify(metricsService).putMetricData(eq("CWAgent"), captor.capture(), eq(REGION));
        List<MetricDatum> datums = captor.getValue();
        assertEquals(List.of("TopLevel", "mem_used_percent", "disk_used_percent"),
                datums.stream().map(MetricDatum::getMetricName).toList());
        MetricDatum entityDatum = datums.get(1);
        assertEquals(6.5, entityDatum.getValue());
        assertEquals("Percent", entityDatum.getUnit());
        assertEquals("host", entityDatum.getDimensions().get(0).name());
        assertEquals("h1", entityDatum.getDimensions().get(0).value());
    }

    @Test
    void putMetricDataStoresEntityMetricDataAfterAnEmptyEntityEntry() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("Namespace", "CWAgent");
        params.putSingle("EntityMetricData.member.2.MetricData.member.1.MetricName", "M");
        params.putSingle("EntityMetricData.member.2.MetricData.member.1.Value", "1");
        params.putSingle("EntityMetricData.member.10.MetricData.member.1.MetricName", "N");
        params.putSingle("EntityMetricData.member.10.MetricData.member.1.Value", "2");
        params.putSingle("StrictEntityValidation", "false");

        handler.handle("PutMetricData", params, REGION);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MetricDatum>> captor = ArgumentCaptor.forClass(List.class);
        verify(metricsService).putMetricData(eq("CWAgent"), captor.capture(), eq(REGION));
        List<MetricDatum> datums = captor.getValue();
        assertEquals(List.of("M", "N"), datums.stream().map(MetricDatum::getMetricName).toList());
        assertEquals(1.0, datums.get(0).getValue());
        assertEquals(2.0, datums.get(1).getValue());
    }

    @Test
    void putMetricDataRejectsAMemberWithoutMetricNameBeforeStoringAnything() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("Namespace", "NS");
        params.putSingle("MetricData.member.1.Value", "1");
        params.putSingle("MetricData.member.2.MetricName", "Valid");
        params.putSingle("MetricData.member.2.Value", "2");

        AwsException e = assertThrows(AwsException.class,
                () -> handler.handle("PutMetricData", params, REGION));
        assertEquals("MissingParameter", e.getErrorCode());
        assertEquals("MissingRequiredParameterException", e.jsonType());
        assertEquals(400, e.getHttpStatus());
        assertEquals("The parameter MetricData.member.1.MetricName is required.", e.getMessage());
        verify(metricsService, never()).putMetricData(any(), any(), any());
    }

    @Test
    void putMetricDataRejectsANestedMemberWithoutMetricNameBeforeStoringAnything() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("Namespace", "NS");
        params.putSingle("MetricData.member.1.MetricName", "TopLevel");
        params.putSingle("MetricData.member.1.Value", "1");
        params.putSingle("EntityMetricData.member.1.MetricData.member.1.Unit", "Percent");
        params.putSingle("EntityMetricData.member.1.MetricData.member.2.MetricName", "Valid");

        AwsException e = assertThrows(AwsException.class,
                () -> handler.handle("PutMetricData", params, REGION));
        assertEquals("The parameter EntityMetricData.member.1.MetricData.member.1.MetricName is required.",
                e.getMessage());
        verify(metricsService, never()).putMetricData(any(), any(), any());
    }

    @Test
    void putMetricDataRejectsAnEmptyMetricNameButAcceptsWhitespace() {
        MultivaluedMap<String, String> empty = new MultivaluedHashMap<>();
        empty.putSingle("Namespace", "NS");
        empty.putSingle("MetricData.member.1.MetricName", "");
        empty.putSingle("MetricData.member.1.Value", "1");

        AwsException e = assertThrows(AwsException.class,
                () -> handler.handle("PutMetricData", empty, REGION));
        assertEquals("The parameter MetricData.member.1.MetricName is required.", e.getMessage());
        verify(metricsService, never()).putMetricData(any(), any(), any());

        MultivaluedMap<String, String> whitespace = new MultivaluedHashMap<>();
        whitespace.putSingle("Namespace", "NS");
        whitespace.putSingle("MetricData.member.1.MetricName", " ");
        whitespace.putSingle("MetricData.member.1.Value", "1");

        handler.handle("PutMetricData", whitespace, REGION);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MetricDatum>> captor = ArgumentCaptor.forClass(List.class);
        verify(metricsService).putMetricData(eq("NS"), captor.capture(), eq(REGION));
        assertEquals(List.of(" "), captor.getValue().stream().map(MetricDatum::getMetricName).toList());
    }

    @Test
    void describeAlarmsReturnsEmptyCompositeAndLogAlarmCollections() {
        when(metricsService.describeAlarms(any(CloudWatchMetricsService.AlarmFilter.class), eq(REGION)))
                .thenReturn(new CloudWatchMetricsService.DescribedAlarms(List.of(), List.of()));

        String xml = (String) handler.handle("DescribeAlarms", new MultivaluedHashMap<>(), REGION).getEntity();

        assertTrue(xml.contains("<MetricAlarms></MetricAlarms>"), xml);
        assertTrue(xml.contains("<CompositeAlarms></CompositeAlarms>"), xml);
        assertTrue(xml.contains("<LogAlarms></LogAlarms>"), xml);
    }
}
