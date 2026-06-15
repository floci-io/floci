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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the CloudWatch Logs Insights subset ({@code StartQuery}/{@code GetQueryResults}/{@code StopQuery})
 * against the exact query shape the reporting-studio web backend sends.
 */
class CloudWatchLogsInsightsQueryTest {

    private static final String REGION = "us-east-1";
    private static final String GROUP = "rs-reporting-studio-spark";
    private static final String STREAM = "job-stream";
    private static final long BASE_MS = 1_700_000_000_000L;

    // The literal query the web backend builds (filter on a nested JSON field, drop TRACE, sort, dedup).
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
        seedGroupAndStream(service);
    }

    private void put(long timestampMs, String level, String jobId, String text) {
        String message = String.format(
                "{\"level\":\"%s\",\"message\":\"%s\",\"params\":{\"job_id\":\"%s\"}}",
                level, text, jobId);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("timestamp", timestampMs);
        event.put("message", message);
        service.putLogEvents(GROUP, STREAM, List.of(event), REGION);
    }

    @Test
    void startQueryFiltersSortsAndDedups() {
        put(BASE_MS + 2000, "INFO", "JOB-1", "alpha");
        put(BASE_MS + 3000, "TRACE", "JOB-1", "trace-line"); // excluded: level == TRACE
        put(BASE_MS + 4000, "INFO", "JOB-2", "other-job");   // excluded: different job_id
        put(BASE_MS + 2000, "INFO", "JOB-1", "alpha");        // duplicate @timestamp + @message
        put(BASE_MS + 5000, "DEBUG", "JOB-1", "delta");

        long startSec = BASE_MS / 1000 - 10;
        long endSec = startSec + 86400;
        String queryId = service.startQuery(List.of(GROUP), startSec, endSec, APP_QUERY, null, REGION);

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
        put(BASE_MS + 2000, "INFO", "JOB-1", "in-window");

        // window entirely before the event → nothing matches
        long startSec = BASE_MS / 1000 - 1000;
        long endSec = BASE_MS / 1000 - 100;
        String queryId = service.startQuery(List.of(GROUP), startSec, endSec, APP_QUERY, null, REGION);

        assertTrue(service.getQueryResults(queryId).rows().isEmpty());
    }

    @Test
    void getQueryResultsUnknownIdThrowsResourceNotFound() {
        AwsException ex = assertThrows(AwsException.class, () -> service.getQueryResults("does-not-exist"));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    @Test
    void stopCompletedQueryThrowsInvalidParameter() {
        // The default service has zero completion delay, so the query is Complete immediately.
        put(BASE_MS + 2000, "INFO", "JOB-1", "alpha");
        long startSec = BASE_MS / 1000 - 10;
        String queryId = service.startQuery(List.of(GROUP), startSec, startSec + 86400, APP_QUERY, null, REGION);
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
        AtomicLong now = new AtomicLong(BASE_MS);
        CloudWatchLogsService async = newAsyncService(now, 1000L);
        String queryId = async.startQuery(List.of(GROUP), BASE_MS / 1000 - 10, BASE_MS / 1000 + 86400, APP_QUERY, null, REGION);

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
        AtomicLong now = new AtomicLong(BASE_MS);
        CloudWatchLogsService async = newAsyncService(now, 1000L);
        String queryId = async.startQuery(List.of(GROUP), BASE_MS / 1000 - 10, BASE_MS / 1000 + 86400, APP_QUERY, null, REGION);
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
        AtomicLong now = new AtomicLong(BASE_MS);
        CloudWatchLogsService async = newAsyncService(now, -5000L);
        String queryId = async.startQuery(List.of(GROUP), BASE_MS / 1000 - 10, BASE_MS / 1000 + 86400, APP_QUERY, null, REGION);

        // A negative delay must not leave the query stuck Running — it clamps to instant completion.
        CloudWatchLogsService.QueryState state = async.getQueryResults(queryId);
        assertEquals("Complete", state.status());
        assertEquals(1, state.rows().size());
    }

    private CloudWatchLogsService newAsyncService(AtomicLong clockMs, long delayMs) {
        CloudWatchLogsService svc = new CloudWatchLogsService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                10000, new RegionResolver(REGION, "000000000000"), delayMs, clockMs::get);
        seedGroupAndStream(svc);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("timestamp", BASE_MS + 2000);
        event.put("message", "{\"level\":\"INFO\",\"message\":\"x\",\"params\":{\"job_id\":\"JOB-1\"}}");
        svc.putLogEvents(GROUP, STREAM, List.of(event), REGION);
        return svc;
    }

    private static void seedGroupAndStream(CloudWatchLogsService svc) {
        svc.createLogGroup(GROUP, null, null, REGION);
        svc.createLogStream(GROUP, STREAM, REGION);
    }
}
