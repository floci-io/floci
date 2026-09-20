package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricFilter;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.hectorvent.floci.services.cloudwatch.logs.PublicationTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** AWS observations are input data, never regenerated from emulator output. */
class CloudWatchLogsMetricFilterPublishingTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private PublicationTestSupport f;

    @BeforeEach
    void setUp() { f = new PublicationTestSupport(); }

    private static JsonNode evidence(String section) throws IOException {
        return JSON.readTree(CloudWatchLogsMetricFilterPublishingTest.class.getResourceAsStream(
                "/cloudwatchlogs/metric-filter-publishing-aws.json")).get(section);
    }

    @Test
    void recordedMixedAndSeparateBatchesContributePerEvent() throws IOException {
        JsonNode data = evidence("metricDefaults");
        int i = 0;
        for (JsonNode scenario : data.get("cases")) {
            String name = "case" + i;
            f.put(name, data.get("filterPattern").asText(), data.get("metricValue").asText(),
                    data.get("defaultValue").asDouble());
            for (JsonNode batch : scenario.get("batches")) {
                List<String> messages = new ArrayList<>();
                batch.forEach(message -> messages.add(message.asText()));
                f.ingest(TIME + i * 60_000L, messages.toArray(String[]::new));
            }
            JsonNode expected = scenario.get("expected");
            f.stats(name, TIME + i * 60_000L, expected.get("Sum").asDouble(), expected.get("SampleCount").asDouble());
            assertEquals(expected.get("Minimum").asDouble(), f.points(name, List.of(), TIME + i * 60_000L, REGION)
                    .getFirst().minimum());
            assertEquals(expected.get("Maximum").asDouble(), f.points(name, List.of(), TIME + i * 60_000L, REGION)
                    .getFirst().maximum());
            i++;
        }
    }

    @Test
    void threeQuietNonmatchesPublishImmediatelyWithoutClosingTraffic() {
        f.put("quiet", "ERROR", "3", 7.0);
        f.ingest(TIME, "INFO", "INFO", "INFO");
        f.stats("quiet", TIME, 21, 3);
        f.stats("quiet", TIME, 21, 3);
        f.stats("quiet", TIME + 60_000, 0, 0);
    }

    @Test
    void noEventsProduceNoPointsEvenWithANonzeroDefault() {
        f.put("empty", "ERROR", "3", 7.0);
        f.ingest(TIME);
        assertTrue(f.metrics.listMetrics(null, null, null, REGION).isEmpty());
    }

    @Test
    void lateMatchAppendsWithoutRetractingAlreadyPublishedDefault() {
        f.put("late", "ERROR", "3", 11.0);
        f.ingest(TIME, "INFO");
        f.stats("late", TIME, 11, 1);
        f.ingest(TIME + 1_000, "ERROR");
        f.stats("late", TIME, 14, 2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void recordedExtractionDistinguishesMissingNullAndRawNonnumeric(boolean fallback) throws IOException {
        JsonNode data = evidence("extraction");
        f.put("extraction", data.get("filterPattern").asText(), data.get("metricValue").asText(),
                fallback ? data.get("defaultValue").asDouble() : null);
        int i = 0;
        for (JsonNode scenario : data.get("cases")) {
            long time = TIME + i++ * 60_000L;
            f.ingest(time, scenario.get("message").toString());
            JsonNode expected = scenario.get("expected");
            boolean numeric = scenario.get("message").path("value").isNumber();
            boolean present = expected.has("Sum") && (fallback || numeric);
            f.stats("extraction", time, present ? expected.get("Sum").asDouble() : 0, present ? 1 : 0);
        }
        f.ingest(TIME + i * 60_000L, "INFO");
        f.stats("extraction", TIME + i * 60_000L, fallback ? 7 : 0, fallback ? 1 : 0);
    }

    @Test
    void ordinaryDimensionsAreAllOrNoneAsRecorded() throws IOException {
        JsonNode data = evidence("ordinaryDimensions");
        MetricFilter filter = definition("dimensions", data.get("filterPattern").asText(), "1", null);
        filter.getMetricTransformations().getFirst().setDimensions(Map.of("A", "$.a", "B", "$.b"));
        f.service.putMetricFilter(filter, REGION);
        for (JsonNode message : data.get("messages")) { f.ingest(TIME, message.toString()); }
        for (JsonNode series : data.get("expectedSeries")) {
            List<Dimension> dims = new ArrayList<>();
            series.get("dimensions").properties().forEach(entry -> dims.add(new Dimension(entry.getKey(), entry.getValue().asText())));
            f.stats("dimensions", dims, TIME, series.get("Sum").asDouble(), series.get("SampleCount").asDouble());
        }
        f.stats("dimensions", List.of(new Dimension("A", "alpha")), TIME, 0, 0);
        f.stats("dimensions", List.of(new Dimension("B", "beta")), TIME, 0, 0);
    }

    @Test
    void recordedSystemDimensionsApplyToMatchesButNotPatternNonmatchDefaults() throws IOException {
        JsonNode data = evidence("systemDimensions");
        int i = 0;
        for (JsonNode scenario : data.get("cases")) {
            String name = "system" + i++;
            MetricFilter filter = definition(name, "ERROR", "3", 7.0);
            List<String> fields = new ArrayList<>();
            scenario.get("emitSystemFieldDimensions").forEach(field -> fields.add(field.asText()));
            filter.setEmitSystemFieldDimensions(fields);
            f.service.putMetricFilter(filter, REGION);
            f.ingest(TIME, "ERROR", "INFO");
            for (JsonNode series : scenario.get("expectedSeries")) {
                List<Dimension> dims = new ArrayList<>();
                series.get("dimensions").properties().forEach(entry -> dims.add(new Dimension(entry.getKey(),
                        entry.getValue().asText().replace("<CALLER_ACCOUNT>", ACCOUNT))));
                f.stats(name, dims, TIME, series.get("Sum").asDouble(), series.get("SampleCount").asDouble());
            }
            f.service.deleteMetricFilter(GROUP, name, REGION);
        }
    }

    @Test
    void inferredPolicyRetainsSystemDimensionsOnMatchingExtractionFallback() {
        MetricFilter filter = definition("fallback", "{ $.probe = \"value\" || $.value = * }", "$.value", 7.0);
        filter.setEmitSystemFieldDimensions(List.of("@aws.account"));
        f.service.putMetricFilter(filter, REGION);
        f.ingest(TIME, "{\"probe\":\"value\"}", "{\"probe\":\"value\",\"value\":null}");
        f.stats("fallback", List.of(new Dimension("@aws.account", ACCOUNT)), TIME, 14, 2);
        f.stats("fallback", TIME, 0, 0);
    }

    @Test
    void inferredPolicyDropsIncompleteOrdinarySetButRetainsSystemDimensions() {
        MetricFilter filter = definition("incomplete", "{ $.a = * || $.b = * }", "1", null);
        filter.getMetricTransformations().getFirst().setDimensions(Map.of("A", "$.a", "B", "$.b"));
        filter.setEmitSystemFieldDimensions(List.of("@aws.region"));
        f.service.putMetricFilter(filter, REGION);
        f.ingest(TIME, "{\"a\":\"alpha\"}");
        f.stats("incomplete", List.of(new Dimension("@aws.region", REGION)), TIME, 1, 1);
        f.stats("incomplete", List.of(new Dimension("A", "alpha"), new Dimension("@aws.region", REGION)), TIME, 0, 0);
    }

    @Test
    void timestampsComeFromEventsIncludingBeforeCreationAndFutureNotIngestionTime() {
        f.put("time", "ERROR", "3", 7.0);
        long future = (System.currentTimeMillis() / 60_000 + 10) * 60_000;
        f.ingest(TIME, "ERROR", "INFO");
        f.ingest(future, "ERROR", "INFO");
        f.stats("time", TIME, 10, 2);
        f.stats("time", future, 10, 2);
        assertEquals("Count", f.points("time", List.of(), TIME, REGION).getFirst().unit());
    }

    @Test
    void spaceDelimitedFieldsStillFeedValuesAndDimensions() {
        MetricFilter filter = definition("space", "[..., status_code, size]", "$size", null);
        filter.getMetricTransformations().getFirst().setDimensions(Map.of("Status", "$status_code", "Method", "$1"));
        f.service.putMetricFilter(filter, REGION);
        f.ingest(TIME, "GET /index.html 200 1534");
        f.stats("space", List.of(new Dimension("Status", "200"), new Dimension("Method", "GET")), TIME, 1534, 1);
    }

    @Test
    void selectionRunsBeforeBothMatchesAndDefaults() {
        MetricFilter filter = definition("selection", "ERROR", "3", 7.0);
        filter.setFieldSelectionCriteria("@aws.account = \"" + OTHER_ACCOUNT + "\" AND @aws.region = \"" + REGION + "\"");
        f.service.putMetricFilter(filter, REGION);
        f.ingest(TIME, "ERROR", "INFO");
        f.stats("selection", TIME, 0, 0);
        f.account.set(OTHER_ACCOUNT);
        f.group(GROUP, REGION);
        f.service.putMetricFilter(filter, REGION);
        f.account.set(ACCOUNT);
        f.ingest(OTHER_ACCOUNT, GROUP, REGION, TIME, List.of("ERROR", "INFO"));
        f.stats("selection", TIME, 0, 0);
        f.account.set(OTHER_ACCOUNT);
        f.stats("selection", TIME, 10, 2);
    }

    @Test
    void selectionRegionExcludesBothDefaultAndMatchBeforePublication() {
        MetricFilter filter = definition("region", "ERROR", "3", 7.0);
        filter.setFieldSelectionCriteria("@aws.region = \"us-east-1\"");
        f.service.putMetricFilter(filter, REGION);
        f.ingest(TIME, "ERROR", "INFO");
        f.stats("region", TIME, 0, 0);
        filter.setFieldSelectionCriteria("@aws.region = \"" + REGION + "\"");
        f.service.putMetricFilter(filter, REGION);
        f.ingest(TIME, "ERROR", "INFO");
        f.stats("region", TIME, 10, 2);
    }

    @Test
    void otherGroupsRegionsAndAccountsDoNotUseTheCallersFilters() {
        f.put("isolation", "ERROR", "3", 7.0);
        f.group("/other", REGION);
        f.group(GROUP, "us-east-1");
        f.ingest(null, "/other", REGION, TIME, List.of("ERROR", "INFO"));
        f.ingest(null, GROUP, "us-east-1", TIME, List.of("ERROR", "INFO"));
        f.account.set(OTHER_ACCOUNT);
        f.group(GROUP, REGION);
        f.ingest(TIME, "ERROR", "INFO");
        f.stats("isolation", TIME, 0, 0);
        f.account.set(ACCOUNT);
        f.stats("isolation", TIME, 0, 0);
        f.ingest(TIME, "ERROR");
        f.stats("isolation", TIME, 3, 1);
    }
}
