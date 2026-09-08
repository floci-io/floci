package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricFilter;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricTransformation;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The metric filter operations against the rules the Logs API documents. What a filter publishes
 * for a stored batch is in {@link CloudWatchLogsMetricFilterPublishingTest}.
 */
class CloudWatchLogsMetricFilterServiceTest {

    private static final String REGION = "us-east-1";
    private static final String GROUP = "/app/api";
    private static final String OTHER_GROUP = "/app/worker";

    private CloudWatchLogsMetricFilterService service;

    @BeforeEach
    void setUp() {
        CloudWatchLogsService logs = new CloudWatchLogsService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), 10_000, new RegionResolver(REGION, "000000000000"));
        logs.createLogGroup(GROUP, null, null, REGION);
        logs.createLogGroup(OTHER_GROUP, null, null, REGION);
        service = new CloudWatchLogsMetricFilterService(new InMemoryStorage<>(), logs, mock(CloudWatchMetricsService.class));
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
        filter.setMetricTransformations(transformation == null ? List.of() : List.of(transformation));
        return filter;
    }

    private static MetricFilter errorCounter(String group, String name) {
        return filter(group, name, "ERROR", transformation("ErrorCount", "App", "1"));
    }

    private static AwsException rejected(Runnable call, String code) {
        AwsException e = assertThrows(AwsException.class, call::run);
        assertEquals(code, e.getErrorCode(), e.getMessage());
        return e;
    }

    // ──────────────────────────── PutMetricFilter ────────────────────────────

    @Test
    void putStoresTheFilterAndDescribeReturnsIt() {
        MetricTransformation t = transformation("ErrorCount", "App", "1");
        t.setDefaultValue(0.0);
        t.setUnit("Count");
        MetricFilter definition = filter(GROUP, "errors", "ERROR", t);
        definition.setApplyOnTransformedLogs(false);
        definition.setFieldSelectionCriteria("@aws.region = \"us-east-1\"");
        definition.setEmitSystemFieldDimensions(List.of("@aws.account"));

        MetricFilter stored = service.putMetricFilter(definition, REGION);

        List<MetricFilter> described = service.describeMetricFilters(GROUP, null, null, null, null, null, REGION)
                .metricFilters();
        assertEquals(1, described.size());
        MetricFilter f = described.getFirst();
        assertEquals("errors", f.getFilterName());
        assertEquals(GROUP, f.getLogGroupName());
        assertEquals("ERROR", f.getFilterPattern());
        assertEquals("ErrorCount", f.getMetricTransformations().getFirst().getMetricName());
        assertEquals("App", f.getMetricTransformations().getFirst().getMetricNamespace());
        assertEquals("1", f.getMetricTransformations().getFirst().getMetricValue());
        assertEquals(0.0, f.getMetricTransformations().getFirst().getDefaultValue());
        assertEquals("Count", f.getMetricTransformations().getFirst().getUnit());
        assertEquals(Boolean.FALSE, f.getApplyOnTransformedLogs());
        assertEquals("@aws.region = \"us-east-1\"", f.getFieldSelectionCriteria());
        assertEquals(List.of("@aws.account"), f.getEmitSystemFieldDimensions());
        assertTrue(f.getCreationTime() > 0);
        assertEquals(stored.getCreationTime(), f.getCreationTime());
        assertEquals(1, service.countMetricFilters(GROUP, REGION));
        assertEquals(0, service.countMetricFilters(OTHER_GROUP, REGION));
    }

    @Test
    void putReplacesAFilterOfTheSameNameAndKeepsItsCreationTime() throws InterruptedException {
        long created = service.putMetricFilter(errorCounter(GROUP, "errors"), REGION).getCreationTime();
        Thread.sleep(2);

        service.putMetricFilter(filter(GROUP, "errors", "FATAL", transformation("FatalCount", "App", "2")), REGION);

        List<MetricFilter> described = service.describeMetricFilters(GROUP, null, null, null, null, null, REGION)
                .metricFilters();
        assertEquals(1, described.size());
        assertEquals("FATAL", described.getFirst().getFilterPattern());
        assertEquals("FatalCount", described.getFirst().getMetricTransformations().getFirst().getMetricName());
        assertEquals(created, described.getFirst().getCreationTime(), "the creation time is the first put's");
    }

    @Test
    void putNeedsAnExistingLogGroup() {
        rejected(() -> service.putMetricFilter(errorCounter("/missing", "errors"), REGION), "ResourceNotFoundException");
        rejected(() -> service.putMetricFilter(errorCounter(GROUP, "errors"), "eu-west-1"), "ResourceNotFoundException");
        rejected(() -> service.putMetricFilter(errorCounter(null, "errors"), REGION), "InvalidParameterException");
    }

    @Test
    void filterNameFollowsTheApiPattern() {
        rejected(() -> service.putMetricFilter(errorCounter(GROUP, null), REGION), "InvalidParameterException");
        rejected(() -> service.putMetricFilter(errorCounter(GROUP, "a:b"), REGION), "InvalidParameterException");
        rejected(() -> service.putMetricFilter(errorCounter(GROUP, "a*"), REGION), "InvalidParameterException");
        rejected(() -> service.putMetricFilter(errorCounter(GROUP, "n".repeat(513)), REGION), "InvalidParameterException");
        service.putMetricFilter(errorCounter(GROUP, "spaces and | pipes are fine"), REGION);
    }

    @Test
    void filterPatternMustParse() {
        AwsException e = rejected(() -> service.putMetricFilter(
                filter(GROUP, "bad", "{ $.a = }", transformation("M", "N", "1")), REGION), "InvalidParameterException");
        assertTrue(e.getMessage().startsWith("Invalid filter pattern"), e.getMessage());
        rejected(() -> service.putMetricFilter(filter(GROUP, "none", null, transformation("M", "N", "1")), REGION),
                "InvalidParameterException");
        rejected(() -> service.putMetricFilter(filter(GROUP, "long", "x".repeat(1025), transformation("M", "N", "1")),
                REGION), "InvalidParameterException");
        service.putMetricFilter(filter(GROUP, "everything", "", transformation("All", "App", "1")), REGION);
    }

    @Test
    void exactlyOneTransformationIsRequired() {
        rejected(() -> service.putMetricFilter(filter(GROUP, "none", "ERROR", null), REGION), "InvalidParameterException");
        MetricFilter two = filter(GROUP, "two", "ERROR", transformation("A", "N", "1"));
        two.setMetricTransformations(List.of(transformation("A", "N", "1"), transformation("B", "N", "1")));
        rejected(() -> service.putMetricFilter(two, REGION), "InvalidParameterException");
        rejected(() -> service.putMetricFilter(filter(GROUP, "noname", "ERROR", transformation(null, "N", "1")), REGION),
                "InvalidParameterException");
        rejected(() -> service.putMetricFilter(filter(GROUP, "badname", "ERROR", transformation("a:b", "N", "1")), REGION),
                "InvalidParameterException");
        rejected(() -> service.putMetricFilter(filter(GROUP, "nons", "ERROR", transformation("A", null, "1")), REGION),
                "InvalidParameterException");
        rejected(() -> service.putMetricFilter(filter(GROUP, "badns", "ERROR", transformation("A", "n$s", "1")), REGION),
                "InvalidParameterException");
    }

    @Test
    void metricValueIsANumberOrAFieldThePatternCanSupply() {
        service.putMetricFilter(filter(GROUP, "literal", "ERROR", transformation("A", "N", "1")), REGION);
        service.putMetricFilter(filter(GROUP, "decimal", "ERROR", transformation("A", "N", "0.5")), REGION);
        service.putMetricFilter(filter(GROUP, "json", "{ $.latency = * }", transformation("A", "N", "$.latency")), REGION);
        service.putMetricFilter(filter(GROUP, "delimited", "[..., size]", transformation("A", "N", "$size")), REGION);
        service.putMetricFilter(filter(GROUP, "position", "[..., size]", transformation("A", "N", "$3")), REGION);

        rejected(() -> service.putMetricFilter(filter(GROUP, "x", "ERROR", transformation("A", "N", null)), REGION),
                "InvalidParameterException");
        rejected(() -> service.putMetricFilter(filter(GROUP, "x", "ERROR", transformation("A", "N", "abc")), REGION),
                "InvalidParameterException");
        rejected(() -> service.putMetricFilter(filter(GROUP, "x", "ERROR", transformation("A", "N", "$.latency")), REGION),
                "InvalidParameterException");
        rejected(() -> service.putMetricFilter(filter(GROUP, "x", "[..., size]", transformation("A", "N", "$bytes")), REGION),
                "InvalidParameterException");
        rejected(() -> service.putMetricFilter(filter(GROUP, "x", "[..., size]", transformation("A", "N", "$.size")), REGION),
                "InvalidParameterException");
        rejected(() -> service.putMetricFilter(filter(GROUP, "x", "ERROR", transformation("A", "N", "1".repeat(101))), REGION),
                "InvalidParameterException");
    }

    @Test
    void dimensionsFollowTheReferenceRules() {
        MetricTransformation ok = transformation("A", "N", "1");
        ok.setDimensions(Map.of("eventType", "$.eventType", "ip", "$.sourceIPAddress"));
        service.putMetricFilter(filter(GROUP, "ok", "{ $.eventType = \"*\" }", ok), REGION);

        MetricTransformation four = transformation("A", "N", "1");
        four.setDimensions(Map.of("a", "$.a", "b", "$.b", "c", "$.c", "d", "$.d"));
        rejected(() -> service.putMetricFilter(filter(GROUP, "four", "{ $.a = 1 }", four), REGION), "InvalidParameterException");

        MetricTransformation onTerms = transformation("A", "N", "1");
        onTerms.setDimensions(Map.of("a", "$.a"));
        rejected(() -> service.putMetricFilter(filter(GROUP, "terms", "ERROR", onTerms), REGION), "InvalidParameterException");

        MetricTransformation withDefault = transformation("A", "N", "1");
        withDefault.setDimensions(Map.of("a", "$.a"));
        withDefault.setDefaultValue(0.0);
        rejected(() -> service.putMetricFilter(filter(GROUP, "default", "{ $.a = 1 }", withDefault), REGION),
                "InvalidParameterException");

        MetricTransformation badReference = transformation("A", "N", "1");
        badReference.setDimensions(Map.of("a", "$bytes"));
        rejected(() -> service.putMetricFilter(filter(GROUP, "ref", "[..., size]", badReference), REGION),
                "InvalidParameterException");

        MetricTransformation badName = transformation("A", "N", "1");
        badName.setDimensions(Map.of(":a", "$.a"));
        rejected(() -> service.putMetricFilter(filter(GROUP, "name", "{ $.a = 1 }", badName), REGION),
                "InvalidParameterException");
    }

    @Test
    void unitMustBeAStandardUnit() {
        MetricTransformation t = transformation("A", "N", "1");
        t.setUnit("Furlongs");
        rejected(() -> service.putMetricFilter(filter(GROUP, "unit", "ERROR", t), REGION), "InvalidParameterException");
        t.setUnit("Bytes/Second");
        service.putMetricFilter(filter(GROUP, "unit", "ERROR", t), REGION);
    }

    @Test
    void systemFieldDimensionsAreTheTwoDocumentedOnesAndCountTowardTheLimit() {
        MetricFilter bad = errorCounter(GROUP, "sys");
        bad.setEmitSystemFieldDimensions(List.of("@aws.host"));
        rejected(() -> service.putMetricFilter(bad, REGION), "InvalidParameterException");

        MetricTransformation two = transformation("A", "N", "1");
        two.setDimensions(Map.of("a", "$.a", "b", "$.b"));
        MetricFilter crowded = filter(GROUP, "sys", "{ $.a = 1 }", two);
        crowded.setEmitSystemFieldDimensions(List.of("@aws.account", "@aws.region"));
        rejected(() -> service.putMetricFilter(crowded, REGION), "InvalidParameterException");

        crowded.setEmitSystemFieldDimensions(List.of("@aws.region"));
        service.putMetricFilter(crowded, REGION);
    }

    @Test
    void aLogGroupHoldsAtMostOneHundredFilters() {
        for (int i = 0; i < 100; i++) {
            service.putMetricFilter(errorCounter(GROUP, "f" + i), REGION);
        }
        rejected(() -> service.putMetricFilter(errorCounter(GROUP, "f100"), REGION), "LimitExceededException");
        service.putMetricFilter(errorCounter(GROUP, "f0"), REGION);
        service.putMetricFilter(errorCounter(OTHER_GROUP, "f100"), REGION);
        assertEquals(100, service.countMetricFilters(GROUP, REGION));
    }

    /** Two creates racing for the last slot must not both get it. */
    @Test
    void theLimitHoldsUnderConcurrentPuts() throws Exception {
        for (int i = 0; i < 99; i++) {
            service.putMetricFilter(errorCounter(GROUP, "f" + i), REGION);
        }
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String name = "race" + i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    service.putMetricFilter(errorCounter(GROUP, name), REGION);
                    accepted.incrementAndGet();
                } catch (AwsException e) {
                    assertEquals("LimitExceededException", e.getErrorCode());
                    refused.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdown();

        assertEquals(1, accepted.get());
        assertEquals(threads - 1, refused.get());
        assertEquals(100, service.countMetricFilters(GROUP, REGION));
    }

    // ──────────────────────────── DescribeMetricFilters ────────────────────────────

    @Test
    void describeSortsByNameAndFiltersByPrefixMetricAndNamespace() {
        service.putMetricFilter(filter(GROUP, "beta", "B", transformation("Bees", "App", "1")), REGION);
        service.putMetricFilter(filter(GROUP, "alpha", "A", transformation("Ants", "App", "1")), REGION);
        service.putMetricFilter(filter(GROUP, "other", "O", transformation("Owls", "Zoo", "1")), REGION);
        service.putMetricFilter(filter(OTHER_GROUP, "alpha", "A", transformation("Ants", "App", "1")), REGION);

        assertEquals(List.of("alpha", "beta", "other"), names(service.describeMetricFilters(GROUP, null, null, null,
                null, null, REGION).metricFilters()));
        assertEquals(List.of("alpha"), names(service.describeMetricFilters(GROUP, "al", null, null, null, null, REGION)
                .metricFilters()));
        assertEquals(List.of("alpha", "alpha", "beta"), names(service.describeMetricFilters(null, null, null, "App",
                null, null, REGION).metricFilters()), "a namespace search spans the Region's groups");
        assertEquals(List.of("beta"), names(service.describeMetricFilters(null, null, "Bees", "App", null, null, REGION)
                .metricFilters()));
        assertEquals(List.of("alpha", "alpha", "beta", "other"), names(service.describeMetricFilters(null, null, null,
                null, null, null, REGION).metricFilters()));
        assertEquals(List.of(), names(service.describeMetricFilters(null, null, null, null, null, null, "eu-west-1")
                .metricFilters()));
        assertEquals(List.of("alpha", "alpha", "beta", "other"), names(service.describeMetricFilters(null, "al", null,
                null, null, null, REGION).metricFilters()), "a prefix without a group is ignored, as on AWS");
    }

    @Test
    void describeRejectsWhatTheApiRejects() {
        rejected(() -> service.describeMetricFilters("/missing", null, null, null, null, null, REGION),
                "ResourceNotFoundException");
        rejected(() -> service.describeMetricFilters(null, null, "Bees", null, null, null, REGION),
                "InvalidParameterException");
        rejected(() -> service.describeMetricFilters(GROUP, null, null, null, null, 0, REGION), "InvalidParameterException");
        rejected(() -> service.describeMetricFilters(GROUP, null, null, null, null, 51, REGION), "InvalidParameterException");
        rejected(() -> service.describeMetricFilters(GROUP, null, null, null, "garbage", null, REGION),
                "InvalidParameterException");
        rejected(() -> service.describeMetricFilters(GROUP, null, null, null, "99", null, REGION),
                "InvalidParameterException");
    }

    @Test
    void describePaginates() {
        for (int i = 0; i < 5; i++) {
            service.putMetricFilter(errorCounter(GROUP, "f" + i), REGION);
        }

        CloudWatchLogsMetricFilterService.DescribeMetricFiltersResult first =
                service.describeMetricFilters(GROUP, null, null, null, null, 2, REGION);
        assertEquals(List.of("f0", "f1"), names(first.metricFilters()));
        assertNotNull(first.nextToken());
        CloudWatchLogsMetricFilterService.DescribeMetricFiltersResult second =
                service.describeMetricFilters(GROUP, null, null, null, first.nextToken(), 2, REGION);
        assertEquals(List.of("f2", "f3"), names(second.metricFilters()));
        CloudWatchLogsMetricFilterService.DescribeMetricFiltersResult last =
                service.describeMetricFilters(GROUP, null, null, null, second.nextToken(), 2, REGION);
        assertEquals(List.of("f4"), names(last.metricFilters()));
        assertNull(last.nextToken());
    }

    private static List<String> names(List<MetricFilter> filters) {
        return filters.stream().map(MetricFilter::getFilterName).toList();
    }

    // ──────────────────────────── DeleteMetricFilter ────────────────────────────

    @Test
    void deleteRemovesTheFilterAndRejectsWhatIsNotThere() {
        service.putMetricFilter(errorCounter(GROUP, "errors"), REGION);

        service.deleteMetricFilter(GROUP, "errors", REGION);

        assertEquals(0, service.countMetricFilters(GROUP, REGION));
        rejected(() -> service.deleteMetricFilter(GROUP, "errors", REGION), "ResourceNotFoundException");
        rejected(() -> service.deleteMetricFilter("/missing", "errors", REGION), "ResourceNotFoundException");
        rejected(() -> service.deleteMetricFilter(GROUP, null, REGION), "InvalidParameterException");
    }

    @Test
    void deletingTheLogGroupTakesItsFiltersAlong() {
        service.putMetricFilter(errorCounter(GROUP, "errors"), REGION);
        service.putMetricFilter(errorCounter(OTHER_GROUP, "errors"), REGION);

        service.onLogGroupDeleted(new LogGroupDeleted(REGION, GROUP));

        assertEquals(0, service.countMetricFilters(GROUP, REGION));
        assertEquals(1, service.countMetricFilters(OTHER_GROUP, REGION));
    }

    // ──────────────────────────── TestMetricFilter ────────────────────────────

    @Test
    void testMetricFilterReportsMatchesWithZeroBasedNumbersAndExtractedValues() {
        List<CloudWatchLogsMetricFilterService.MetricFilterMatchRecord> matches = service.testMetricFilter(
                "[..., status_code=200, size]", List.of(
                        "127.0.0.1 - frank [10/Oct/2000:13:25:15 -0700] \"GET /apache_pb.gif HTTP/1.0\" 200 1534",
                        "127.0.0.1 - frank [10/Oct/2000:13:35:22 -0700] \"GET /apache_pb.gif HTTP/1.0\" 500 5324",
                        "127.0.0.1 - frank [10/Oct/2000:13:50:35 -0700] \"GET /apache_pb.gif HTTP/1.0\" 200 4355"));

        assertEquals(2, matches.size());
        assertEquals(0, matches.get(0).eventNumber());
        assertEquals(2, matches.get(1).eventNumber());
        assertEquals("1534", matches.get(0).extractedValues().get("$size"));
        assertEquals("200", matches.get(0).extractedValues().get("$status_code"));
        assertEquals("frank", matches.get(0).extractedValues().get("$3"));
        assertTrue(matches.get(0).eventMessage().endsWith("200 1534"));

        List<CloudWatchLogsMetricFilterService.MetricFilterMatchRecord> terms = service.testMetricFilter(
                "\"[ERROR]\"", List.of("[INFO] up", "[ERROR] down"));
        assertEquals(1, terms.size());
        assertEquals(1, terms.getFirst().eventNumber());
        assertEquals(Map.of(), terms.getFirst().extractedValues());
    }

    @Test
    void testMetricFilterRejectsWhatTheApiRejects() {
        rejected(() -> service.testMetricFilter("{ $.a = }", List.of("x")), "InvalidParameterException");
        rejected(() -> service.testMetricFilter(null, List.of("x")), "InvalidParameterException");
        rejected(() -> service.testMetricFilter("ERROR", List.of()), "InvalidParameterException");
        rejected(() -> service.testMetricFilter("ERROR", List.of("")), "InvalidParameterException");
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            tooMany.add("m");
        }
        rejected(() -> service.testMetricFilter("ERROR", tooMany), "InvalidParameterException");
    }
}
