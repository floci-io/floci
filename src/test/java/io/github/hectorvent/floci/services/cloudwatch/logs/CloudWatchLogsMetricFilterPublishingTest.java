package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogEvent;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricFilter;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricTransformation;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The metrics a filter publishes for a stored batch: one value per matching event at the event's
 * time, the literal value or the field it names, dimensions from the event's fields, the default
 * value once when nothing matched, and a failing filter never stopping the others.
 */
class CloudWatchLogsMetricFilterPublishingTest {

    private static final String REGION = "us-east-1";
    private static final String GROUP = "/app/api";
    private static final String OTHER_GROUP = "/app/worker";

    private CloudWatchMetricsService metrics;
    private CloudWatchLogsMetricFilterService service;

    @BeforeEach
    void setUp() {
        RegionResolver resolver = new RegionResolver(REGION, "000000000000");
        CloudWatchLogsService logs = new CloudWatchLogsService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), 10_000, resolver);
        logs.createLogGroup(GROUP, null, null, REGION);
        logs.createLogGroup(OTHER_GROUP, null, null, REGION);
        metrics = mock(CloudWatchMetricsService.class);
        service = new CloudWatchLogsMetricFilterService(new InMemoryStorage<>(), logs, metrics, resolver);
    }

    private static MetricTransformation transformation(String name, String namespace, String value) {
        MetricTransformation t = new MetricTransformation();
        t.setMetricName(name);
        t.setMetricNamespace(namespace);
        t.setMetricValue(value);
        return t;
    }

    private static MetricFilter filter(String group, String name, String pattern, MetricTransformation transformation) {
        MetricFilter filter = new MetricFilter();
        filter.setLogGroupName(group);
        filter.setFilterName(name);
        filter.setFilterPattern(pattern);
        filter.setMetricTransformations(List.of(transformation));
        return filter;
    }

    private static MetricFilter errorCounter(String group, String name) {
        return filter(group, name, "ERROR", transformation("ErrorCount", "App", "1"));
    }

    private static LogEvent event(long timestamp, String message) {
        LogEvent event = new LogEvent();
        event.setEventId(String.valueOf(timestamp));
        event.setTimestamp(timestamp);
        event.setMessage(message);
        event.setIngestionTime(timestamp);
        return event;
    }

    private List<MetricDatum> published(String namespace) {
        return published(null, namespace);
    }

    /** Every datum published across the calls a test made, for the tests that send several batches. */
    private List<MetricDatum> allPublished(String namespace) {
        ArgumentCaptor<List<MetricDatum>> datums = ArgumentCaptor.captor();
        verify(metrics, atLeastOnce()).putMetricDataForAccount(isNull(), eq(namespace), datums.capture(), eq(REGION));
        return datums.getAllValues().stream().flatMap(List::stream).toList();
    }

    private List<MetricDatum> published(String accountId, String namespace) {
        ArgumentCaptor<List<MetricDatum>> datums = ArgumentCaptor.captor();
        verify(metrics).putMetricDataForAccount(accountId == null ? isNull() : eq(accountId), eq(namespace),
                datums.capture(), eq(REGION));
        return datums.getValue();
    }


    @Test
    void aMatchingEventPublishesTheLiteralValueAtTheEventTime() {
        MetricTransformation t = transformation("ErrorCount", "App", "1");
        t.setUnit("Count");
        service.putMetricFilter(filter(GROUP, "errors", "ERROR", t), REGION);

        service.onLogEventsIngested(new LogEventsIngested(null, REGION, GROUP, "s1", List.of(
                event(1_700_000_000_000L, "[ERROR] one"),
                event(1_700_000_001_000L, "[INFO] fine"),
                event(1_700_000_002_000L, "[ERROR] two"))));

        List<MetricDatum> datums = published("App");
        assertEquals(2, datums.size());
        assertEquals("ErrorCount", datums.get(0).getMetricName());
        assertEquals(1.0, datums.get(0).getValue());
        assertEquals("Count", datums.get(0).getUnit());
        assertEquals(1_700_000_000L, datums.get(0).getTimestamp(), "the datum sits at the event's second");
        assertEquals(1_700_000_002L, datums.get(1).getTimestamp());
        assertEquals(List.of(), datums.get(0).getDimensions());
    }

    @Test
    void theValueCanComeFromAFieldOfTheEvent() {
        service.putMetricFilter(filter(GROUP, "latency", "{ $.latency = * }", transformation("Latency", "App", "$.latency")),
                REGION);

        service.onLogEventsIngested(new LogEventsIngested(null, REGION, GROUP, "s1", List.of(
                event(1_700_000_000_000L, "{\"latency\": 50, \"requestType\": \"GET\"}"),
                event(1_700_000_001_000L, "{\"latency\": \"slow\"}"),
                event(1_700_000_002_000L, "{\"latency\": 12.5}"))));

        List<MetricDatum> datums = published("App");
        assertEquals(2, datums.size(), "a matching event whose field is not a number publishes nothing");
        assertEquals(50.0, datums.get(0).getValue());
        assertEquals(12.5, datums.get(1).getValue());
        assertEquals("None", datums.get(0).getUnit(), "no unit means None");
    }

    @Test
    void spaceDelimitedFieldsFeedTheValueAndDimensions() {
        MetricTransformation t = transformation("Volume", "Web", "$size");
        t.setDimensions(Map.of("Status", "$status_code", "Method", "$1"));
        service.putMetricFilter(filter(GROUP, "bytes", "[..., status_code, size]", t), REGION);

        service.onLogEventsIngested(new LogEventsIngested(null, REGION, GROUP, "s1", List.of(
                event(1_700_000_000_000L, "GET /index.html 200 1534"))));

        List<MetricDatum> datums = published("Web");
        assertEquals(1, datums.size());
        assertEquals(1534.0, datums.getFirst().getValue());
        assertEquals(List.of(new Dimension("Method", "GET"), new Dimension("Status", "200")),
                datums.getFirst().getDimensions().stream().sorted((a, b) -> a.name().compareTo(b.name())).toList());
    }

    @Test
    void aDimensionWhoseFieldTheEventLacksIsLeftOut() {
        MetricTransformation t = transformation("Events", "App", "1");
        t.setDimensions(Map.of("eventType", "$.eventType", "user", "$.user"));
        service.putMetricFilter(filter(GROUP, "events", "{ $.eventType = \"*\" }", t), REGION);

        service.onLogEventsIngested(new LogEventsIngested(null, REGION, GROUP, "s1", List.of(
                event(1_700_000_000_000L, "{\"eventType\": \"UpdateTrail\"}"))));

        List<MetricDatum> datums = published("App");
        assertEquals(List.of(new Dimension("eventType", "UpdateTrail")), datums.getFirst().getDimensions());
    }

    /**
     * AWS reports metric filter values every minute and reports the default value for a minute that
     * ingested logs without a match, so two calls inside one minute still produce one default. It
     * is published once the next minute arrives, which is what settles the one before it.
     */
    @Test
    void theDefaultValueIsPublishedOncePerMinuteRatherThanOncePerBatch() {
        MetricTransformation t = transformation("ErrorCount", "App", "1");
        t.setDefaultValue(0.0);
        service.putMetricFilter(filter(GROUP, "errors", "ERROR", t), REGION);

        service.onLogEventsIngested(new LogEventsIngested(null, REGION, GROUP, "s1",
                List.of(event(1_700_000_000_000L, "[INFO] one"))));
        service.onLogEventsIngested(new LogEventsIngested(null, REGION, GROUP, "s1",
                List.of(event(1_700_000_005_000L, "[INFO] two"))));
        verify(metrics, never()).putMetricDataForAccount(any(), anyString(), anyList(), anyString());

        service.onLogEventsIngested(new LogEventsIngested(null, REGION, GROUP, "s1",
                List.of(event(1_700_000_065_000L, "[INFO] the next minute"))));

        List<MetricDatum> datums = allPublished("App");
        assertEquals(1, datums.size(), "one default value for the minute, not one per batch");
        assertEquals(0.0, datums.getFirst().getValue());
        assertEquals(1_700_000_005L, datums.getFirst().getTimestamp(), "at that minute's last event");
    }

    /** A minute holding one miss and one match reports the match and no default value. */
    @Test
    void aMinuteThatMatchedSomethingReportsNoDefaultValue() {
        MetricTransformation t = transformation("ErrorCount", "App", "1");
        t.setDefaultValue(0.0);
        service.putMetricFilter(filter(GROUP, "errors", "ERROR", t), REGION);

        service.onLogEventsIngested(new LogEventsIngested(null, REGION, GROUP, "s1",
                List.of(event(1_700_000_000_000L, "[INFO] one"))));
        service.onLogEventsIngested(new LogEventsIngested(null, REGION, GROUP, "s1",
                List.of(event(1_700_000_005_000L, "[ERROR] two"))));
        service.onLogEventsIngested(new LogEventsIngested(null, REGION, GROUP, "s1",
                List.of(event(1_700_000_065_000L, "[INFO] the next minute"))));

        List<MetricDatum> datums = allPublished("App");
        assertEquals(1, datums.size(), "the match alone");
        assertEquals(1.0, datums.getFirst().getValue());
    }

    /** One batch spanning two minutes settles the earlier one on the spot. */
    @Test
    void aBatchSpanningTwoMinutesSettlesTheEarlierOne() {
        MetricTransformation t = transformation("ErrorCount", "App", "1");
        t.setDefaultValue(0.0);
        service.putMetricFilter(filter(GROUP, "errors", "ERROR", t), REGION);

        service.onLogEventsIngested(new LogEventsIngested(null, REGION, GROUP, "s1", List.of(
                event(1_700_000_000_000L, "[INFO] first minute"),
                event(1_700_000_065_000L, "[INFO] second minute"))));

        List<MetricDatum> datums = allPublished("App");
        assertEquals(1, datums.size(), "only the minute the batch moved past is settled");
        assertEquals(1_700_000_000L, datums.getFirst().getTimestamp());
    }

    /** A match whose field is missing or not a number is still a match: no default for that batch. */
    @Test
    void theDefaultValueIsNotPublishedWhenAMatchHadNoNumericValue() {
        MetricTransformation t = transformation("Latency", "App", "$.latency");
        t.setDefaultValue(0.0);
        service.putMetricFilter(filter(GROUP, "latency", "{ $.latency = * }", t), REGION);

        service.onLogEventsIngested(new LogEventsIngested(null, REGION, GROUP, "s1", List.of(
                event(1_700_000_000_000L, "{\"latency\": \"slow\"}"))));

        verify(metrics, never()).putMetricDataForAccount(any(), anyString(), anyList(), anyString());
    }

    /**
     * A batch a container streamed for another account, outside any request, runs that account's
     * filters and publishes into that account's metrics, not the default account's.
     */
    @Test
    void aBatchWrittenForAnotherAccountUsesThatAccountsFiltersAndMetrics() {
        AccountAwareStorageBackend<MetricFilter> store = AccountAwareStorageBackend.inMemory("000000000000");
        RegionResolver resolver = new RegionResolver(REGION, "000000000000");
        CloudWatchLogsService logs = new CloudWatchLogsService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), 10_000, resolver);
        logs.createLogGroup(GROUP, null, null, REGION);
        service = new CloudWatchLogsMetricFilterService(store, logs, metrics, resolver);
        service.putMetricFilter(filter(GROUP, "errors", "ERROR", transformation("Errors", "Default", "1")), REGION);
        store.putForAccount("111111111111", REGION + "::" + GROUP + "::errors",
                filter(GROUP, "errors", "ERROR", transformation("Errors", "Other", "1")));

        service.onLogEventsIngested(new LogEventsIngested("111111111111", REGION, GROUP, "s1",
                List.of(event(1_700_000_000_000L, "[ERROR] boom"))));

        assertEquals(1, published("111111111111", "Other").size());
        verify(metrics, never()).putMetricDataForAccount(any(), eq("Default"), anyList(), anyString());
    }

    @Test
    void theDefaultValueIsNotPublishedNextToMatches() {
        MetricTransformation t = transformation("ErrorCount", "App", "1");
        t.setDefaultValue(0.0);
        service.putMetricFilter(filter(GROUP, "errors", "ERROR", t), REGION);

        service.onLogEventsIngested(new LogEventsIngested(null, REGION, GROUP, "s1", List.of(
                event(1_700_000_000_000L, "[ERROR] one"), event(1_700_000_001_000L, "[INFO] two"))));

        assertEquals(1, published("App").size());
    }

    @Test
    void nothingIsPublishedWithoutADefaultWhenNothingMatchedOrForOtherGroups() {
        service.putMetricFilter(errorCounter(GROUP, "errors"), REGION);

        service.onLogEventsIngested(new LogEventsIngested(null, REGION, GROUP, "s1", List.of(event(1L, "[INFO] fine"))));
        service.onLogEventsIngested(new LogEventsIngested(null, REGION, OTHER_GROUP, "s1", List.of(event(1L, "[ERROR] boom"))));
        service.onLogEventsIngested(new LogEventsIngested(null, "eu-west-1", GROUP, "s1", List.of(event(1L, "[ERROR] boom"))));

        verify(metrics, never()).putMetricDataForAccount(any(), anyString(), anyList(), anyString());
    }

    @Test
    void everyFilterOfTheGroupPublishesAndAFailingOneDoesNotStopTheOthers() {
        service.putMetricFilter(filter(GROUP, "a", "ERROR", transformation("Errors", "Broken", "1")), REGION);
        service.putMetricFilter(filter(GROUP, "b", "ERROR", transformation("Errors", "Fine", "1")), REGION);
        doThrow(new IllegalStateException("store closed")).when(metrics)
                .putMetricDataForAccount(isNull(), eq("Broken"), any(), eq(REGION));

        service.onLogEventsIngested(new LogEventsIngested(null, REGION, GROUP, "s1", List.of(event(1L, "[ERROR] boom"))));

        assertEquals(1, published("Fine").size());
    }

    /**
     * The system fields a filter emits become dimensions on the datums it publishes, naming the
     * account the batch was written for and its Region.
     */
    @Test
    void theEmittedSystemFieldsBecomeDimensions() {
        MetricFilter filter = errorCounter(GROUP, "errors");
        filter.setEmitSystemFieldDimensions(List.of("@aws.account", "@aws.region"));
        service.putMetricFilter(filter, REGION);

        service.onLogEventsIngested(new LogEventsIngested("111111111111", REGION, GROUP, "s1",
                List.of(event(1_700_000_000_000L, "[ERROR] one"))));

        List<MetricDatum> datums = published("111111111111", "App");
        assertEquals(1, datums.size());
        assertEquals(List.of("@aws.account", "@aws.region"),
                datums.getFirst().getDimensions().stream().map(Dimension::name).toList());
        assertEquals(List.of("111111111111", REGION),
                datums.getFirst().getDimensions().stream().map(Dimension::value).toList());
    }

    /** A batch written without an explicit account carries the caller's own account. */
    @Test
    void theAccountDimensionNamesTheCallersOwnAccountWhenTheBatchNamesNone() {
        MetricFilter filter = errorCounter(GROUP, "errors");
        filter.setEmitSystemFieldDimensions(List.of("@aws.account"));
        service.putMetricFilter(filter, REGION);

        service.onLogEventsIngested(new LogEventsIngested(null, REGION, GROUP, "s1",
                List.of(event(1_700_000_000_000L, "[ERROR] one"))));

        assertEquals("000000000000", published("App").getFirst().getDimensions().getFirst().value());
    }

    /**
     * Selection criteria decide from the batch's account and Region whether the filter runs at all,
     * so a batch they exclude publishes nothing, not even a default value.
     */
    @Test
    void aBatchTheSelectionCriteriaExcludePublishesNothing() {
        MetricTransformation t = transformation("ErrorCount", "App", "1");
        t.setDefaultValue(0.0);
        MetricFilter filter = filter(GROUP, "errors", "ERROR", t);
        filter.setFieldSelectionCriteria("@aws.account IN [\"222222222222\"]");
        service.putMetricFilter(filter, REGION);

        service.onLogEventsIngested(new LogEventsIngested("111111111111", REGION, GROUP, "s1",
                List.of(event(1_700_000_000_000L, "[ERROR] one"))));

        verify(metrics, never()).putMetricDataForAccount(anyString(), anyString(), anyList(), anyString());
    }

    /** A batch the criteria admit publishes exactly as an unfiltered one does. */
    @Test
    void aBatchTheSelectionCriteriaAdmitPublishesAsUsual() {
        MetricFilter filter = errorCounter(GROUP, "errors");
        filter.setFieldSelectionCriteria("@aws.account = \"111111111111\" && @aws.region = \"" + REGION + "\"");
        service.putMetricFilter(filter, REGION);

        service.onLogEventsIngested(new LogEventsIngested("111111111111", REGION, GROUP, "s1",
                List.of(event(1_700_000_000_000L, "[ERROR] one"))));

        assertEquals(1, published("111111111111", "App").size());
    }
}
