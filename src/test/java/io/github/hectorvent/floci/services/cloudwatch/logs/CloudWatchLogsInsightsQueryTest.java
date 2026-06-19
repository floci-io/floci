package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the CloudWatch Logs Insights subset ({@code StartQuery}/{@code GetQueryResults}/{@code StopQuery})
 * against representative query shapes: filtering on nested JSON fields (including deep paths and
 * whole-object resolution), level exclusion, sort, and dedup. Each test uses its own log group name,
 * confirming the engine is not coupled to any particular group.
 */
class CloudWatchLogsInsightsQueryTest {

    private static final String REGION = "us-east-1";
    private static final long BASE_MS = 1_700_000_000_000L;

    // A representative Logs Insights query: filter on a nested JSON field, drop a level, sort, dedup.
    private static final String APP_QUERY = """
            fields @timestamp, @message
            | filter params.job_id = 'JOB-1'
            | filter level != 'TRACE'
            | sort @timestamp desc
            | dedup @timestamp, @message
            """;

    private CloudWatchLogsService service;

    @BeforeEach
    void setUp() {
        service = new CloudWatchLogsService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                10000,
                new RegionResolver(REGION, "000000000000"));
    }

    @Test
    void startQueryFiltersSortsAndDedups() {
        String group = "/app/etl/worker-logs";
        String stream = "worker-1";
        createGroupStream(service, group, stream);

        put(group, stream, BASE_MS + 2000, "INFO", "JOB-1", "alpha");
        put(group, stream, BASE_MS + 3000, "TRACE", "JOB-1", "trace-line"); // excluded: level == TRACE
        put(group, stream, BASE_MS + 4000, "INFO", "JOB-2", "other-job");   // excluded: different job_id
        put(group, stream, BASE_MS + 2000, "INFO", "JOB-1", "alpha");        // duplicate @timestamp + @message
        put(group, stream, BASE_MS + 5000, "DEBUG", "JOB-1", "delta");

        long startSec = BASE_MS / 1000 - 10;
        long endSec = startSec + 86400;
        String queryId = service.startQuery(List.of(group), startSec, endSec, APP_QUERY, null, REGION);

        CloudWatchLogsService.QueryState state = service.getQueryResults(queryId);
        assertEquals("Complete", state.status());
        assertEquals(2, state.rows().size(), "delta + alpha survive filter/dedup");

        // sorted @timestamp desc: delta (BASE+5000) before alpha (BASE+2000)
        assertTrue(state.rows().get(0).get("@message").contains("delta"));
        assertTrue(state.rows().get(1).get("@message").contains("alpha"));

        for (LinkedHashMap<String, String> row : state.rows()) {
            assertTrue(row.containsKey("@timestamp"), "row has @timestamp");
            assertTrue(row.containsKey("@message"), "row has @message");
            assertNotNull(row.get("@ptr"), "row has @ptr");
        }
    }

    @Test
    void startQueryRespectsTimeWindow() {
        String group = "/svc/api/access-logs";
        String stream = "node-1";
        createGroupStream(service, group, stream);
        put(group, stream, BASE_MS + 2000, "INFO", "JOB-1", "in-window");

        // window entirely before the event → nothing matches
        long startSec = BASE_MS / 1000 - 1000;
        long endSec = BASE_MS / 1000 - 100;
        String queryId = service.startQuery(List.of(group), startSec, endSec, APP_QUERY, null, REGION);

        assertTrue(service.getQueryResults(queryId).rows().isEmpty());
    }

    @Test
    void startQueryFindsParamAcrossMultipleGroups() {
        // The same params.id is logged from several service/engine groups.
        String reportingSpark = "reporting/spark";
        String reportingMysql = "reporting/mysql";
        String dashboardMysql = "dashboard/mysql";
        String invoiceSpark = "invoice/spark"; // matching event, but NOT queried — must be excluded
        createGroupStream(service, reportingSpark, "s-1");
        createGroupStream(service, reportingMysql, "s-1");
        createGroupStream(service, dashboardMysql, "s-1");
        createGroupStream(service, invoiceSpark, "s-1");

        putRaw(service, reportingSpark, "s-1", BASE_MS + 2000, idLog("REQ-42", "spark-stage-done"));
        putRaw(service, reportingMysql, "s-1", BASE_MS + 5000, idLog("REQ-42", "mysql-commit"));
        putRaw(service, dashboardMysql, "s-1", BASE_MS + 3000, idLog("REQ-42", "dashboard-render"));
        putRaw(service, reportingMysql, "s-1", BASE_MS + 1000, idLog("REQ-99", "unrelated-id"));   // different id → filtered
        putRaw(service, invoiceSpark, "s-1", BASE_MS + 6000, idLog("REQ-42", "invoice-export"));   // matches, group not queried

        String query = """
                fields @timestamp, @message, params.id
                | filter params.id = 'REQ-42'
                | sort @timestamp desc
                """;
        long startSec = BASE_MS / 1000 - 10;
        long endSec = startSec + 86400;
        // Query spans three groups; the params.id filter finds REQ-42 wherever it was logged among them.
        String queryId = service.startQuery(
                List.of(reportingSpark, reportingMysql, dashboardMysql), startSec, endSec, query, null, REGION);

        CloudWatchLogsService.QueryState state = service.getQueryResults(queryId);
        assertEquals("Complete", state.status());
        assertEquals(3, state.rows().size(), "REQ-42 is found across all three queried groups");

        // Every matched row carries the requested param, regardless of which group it came from.
        for (LinkedHashMap<String, String> row : state.rows()) {
            assertEquals("REQ-42", row.get("params.id"));
        }

        // Merged + globally sorted desc: mysql-commit(5000), dashboard-render(3000), spark-stage-done(2000).
        assertTrue(state.rows().get(0).get("@message").contains("mysql-commit"));
        assertTrue(state.rows().get(1).get("@message").contains("dashboard-render"));
        assertTrue(state.rows().get(2).get("@message").contains("spark-stage-done"));

        // A different params.id is filtered out; a matching REQ-42 in the un-queried invoice/spark is excluded.
        for (LinkedHashMap<String, String> row : state.rows()) {
            assertFalse(row.get("@message").contains("unrelated-id"));
            assertFalse(row.get("@message").contains("invoice-export"));
        }
    }

    @Test
    void queryResolvesNestedJsonObjectsAndDeepPaths() {
        String group = "/app/build/publish-logs";
        String stream = "publisher-1";
        createGroupStream(service, group, stream);

        // Structured events whose params carry a nested 'artifact_coordinates' JSON object.
        putRaw(service, group, stream, BASE_MS + 1000,
                artifactLog("JOB-7", "com.example.tools", "widget-core", "1.2.3"));
        putRaw(service, group, stream, BASE_MS + 2000,
                artifactLog("JOB-8", "com.example.other", "gizmo", "4.5.6"));

        // Filter on a DEEP path; project a deep scalar and the whole nested object.
        String query = """
                fields @timestamp, params.artifact_coordinates.version, params.artifact_coordinates
                | filter params.artifact_coordinates.group = 'com.example.tools'
                """;
        long startSec = BASE_MS / 1000 - 10;
        String queryId = service.startQuery(List.of(group), startSec, startSec + 86400, query, null, REGION);

        CloudWatchLogsService.QueryState state = service.getQueryResults(queryId);
        assertEquals("Complete", state.status());
        assertEquals(1, state.rows().size(), "only the event whose nested 'group' matches survives the deep-path filter");

        LinkedHashMap<String, String> row = state.rows().get(0);
        // A deep dotted path resolves to the leaf scalar.
        assertEquals("1.2.3", row.get("params.artifact_coordinates.version"));
        // A path to a nested object resolves to its JSON serialization.
        String coords = row.get("params.artifact_coordinates");
        assertTrue(coords.contains("\"artifact\":\"widget-core\""), "nested object projected as JSON: " + coords);
        assertTrue(coords.contains("\"version\":\"1.2.3\""), "nested object projected as JSON: " + coords);
    }

    @Test
    void getQueryResultsUnknownIdThrowsResourceNotFound() {
        AwsException ex = assertThrows(AwsException.class, () -> service.getQueryResults("does-not-exist"));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    @Test
    void stopCompletedQueryThrowsInvalidParameter() {
        String group = "/app/batch/spark-logs";
        String stream = "batch-1";
        createGroupStream(service, group, stream);
        // The default service has zero completion delay, so the query is Complete immediately.
        put(group, stream, BASE_MS + 2000, "INFO", "JOB-1", "alpha");
        long startSec = BASE_MS / 1000 - 10;
        String queryId = service.startQuery(List.of(group), startSec, startSec + 86400, APP_QUERY, null, REGION);
        assertEquals("Complete", service.getQueryResults(queryId).status());

        AwsException ex = assertThrows(AwsException.class, () -> service.stopQuery(queryId));
        assertEquals("InvalidParameterException", ex.getErrorCode());
    }

    @Test
    void stopUnknownQueryThrowsResourceNotFound() {
        AwsException ex = assertThrows(AwsException.class, () -> service.stopQuery("does-not-exist"));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    @Test
    void asyncQueryReportsRunningThenCompleteAfterDelay() {
        String group = "/app/stream/ingest-logs";
        String stream = "shard-1";
        AtomicLong now = new AtomicLong(BASE_MS);
        CloudWatchLogsService async = newAsyncService(now, 1000L, group, stream);
        String queryId = async.startQuery(List.of(group), BASE_MS / 1000 - 10, BASE_MS / 1000 + 86400, APP_QUERY, null, REGION);

        // Within the delay window: Running, with no rows exposed yet.
        assertEquals("Running", async.getQueryResults(queryId).status());
        assertTrue(async.getQueryResults(queryId).rows().isEmpty());
        // recordsMatched reports the full match count even while Running (rows masked, statistics not).
        assertEquals(1, async.getQueryResults(queryId).recordsMatched());

        // Advance the clock past the completion delay: Complete, rows exposed.
        now.addAndGet(1000);
        CloudWatchLogsService.QueryState complete = async.getQueryResults(queryId);
        assertEquals("Complete", complete.status());
        assertEquals(1, complete.rows().size());
    }

    @Test
    void stopRunningQueryCancelsIt() {
        String group = "/app/stream/replay-logs";
        String stream = "shard-2";
        AtomicLong now = new AtomicLong(BASE_MS);
        CloudWatchLogsService async = newAsyncService(now, 1000L, group, stream);
        String queryId = async.startQuery(List.of(group), BASE_MS / 1000 - 10, BASE_MS / 1000 + 86400, APP_QUERY, null, REGION);
        assertEquals("Running", async.getQueryResults(queryId).status());

        // Stopping a running query succeeds and cancels it.
        assertTrue(async.stopQuery(queryId));
        CloudWatchLogsService.QueryState cancelled = async.getQueryResults(queryId);
        assertEquals("Cancelled", cancelled.status());
        assertTrue(cancelled.rows().isEmpty());

        // Stopping again (already ended) throws InvalidParameterException.
        AwsException ex = assertThrows(AwsException.class, () -> async.stopQuery(queryId));
        assertEquals("InvalidParameterException", ex.getErrorCode());
    }

    @Test
    void negativeCompletionDelayClampsToImmediateComplete() {
        String group = "/app/cron/scheduler-logs";
        String stream = "tick-1";
        AtomicLong now = new AtomicLong(BASE_MS);
        CloudWatchLogsService async = newAsyncService(now, -5000L, group, stream);
        String queryId = async.startQuery(List.of(group), BASE_MS / 1000 - 10, BASE_MS / 1000 + 86400, APP_QUERY, null, REGION);

        // A negative delay must not leave the query stuck Running — it clamps to instant completion.
        CloudWatchLogsService.QueryState state = async.getQueryResults(queryId);
        assertEquals("Complete", state.status());
        assertEquals(1, state.rows().size());
    }

    // ──────────────────────────── helpers ────────────────────────────

    private CloudWatchLogsService newAsyncService(AtomicLong clockMs, long delayMs, String group, String stream) {
        CloudWatchLogsService svc = new CloudWatchLogsService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                10000, new RegionResolver(REGION, "000000000000"), delayMs, clockMs::get);
        createGroupStream(svc, group, stream);
        putRaw(svc, group, stream, BASE_MS + 2000, jobLog("INFO", "x", "JOB-1"));
        return svc;
    }

    private void createGroupStream(CloudWatchLogsService svc, String group, String stream) {
        svc.createLogGroup(group, null, null, REGION);
        svc.createLogStream(group, stream, REGION);
    }

    /** Append one structured-JSON line ({@code level} / {@code message} / {@code params.job_id}) on the default service. */
    private void put(String group, String stream, long timestampMs, String level, String jobId, String text) {
        putRaw(service, group, stream, timestampMs, jobLog(level, text, jobId));
    }

    /** Append one event with a verbatim JSON message to (group, stream) on the given service. */
    private void putRaw(CloudWatchLogsService svc, String group, String stream, long timestampMs, String message) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("timestamp", timestampMs);
        event.put("message", message);
        svc.putLogEvents(group, stream, List.of(event), REGION);
    }

    private static String jobLog(String level, String text, String jobId) {
        return String.format(
                "{\"level\":\"%s\",\"message\":\"%s\",\"params\":{\"job_id\":\"%s\"}}",
                level, text, jobId);
    }

    private static String idLog(String id, String text) {
        return String.format(
                "{\"level\":\"INFO\",\"message\":\"%s\",\"params\":{\"id\":\"%s\"}}",
                text, id);
    }

    private static String artifactLog(String jobId, String group, String artifact, String version) {
        return String.format(
                "{\"level\":\"INFO\",\"message\":\"published\",\"params\":{\"job_id\":\"%s\","
                        + "\"artifact_coordinates\":{\"group\":\"%s\",\"artifact\":\"%s\",\"version\":\"%s\"}}}",
                jobId, group, artifact, version);
    }
}
