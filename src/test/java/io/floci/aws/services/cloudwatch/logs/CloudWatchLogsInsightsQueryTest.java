package io.floci.aws.services.cloudwatch.logs;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
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
    void startQueryDeduplicatesRepeatedLogGroups() {
        String group = "reporting/dedup";
        String stream = "s-1";
        createGroupStream(service, group, stream);
        put(group, stream, BASE_MS + 1000, "INFO", "JOB-1", "once");

        // The same group passed twice must be scanned once. A non-dedup query is used so a double
        // scan would surface as duplicate rows and an inflated recordsScanned.
        String query = "fields @message | filter params.job_id = 'JOB-1'";
        long startSec = BASE_MS / 1000 - 10;
        String queryId = service.startQuery(List.of(group, group), startSec, startSec + 86400, query, null, REGION);

        CloudWatchLogsService.QueryState state = service.getQueryResults(queryId);
        assertEquals("Complete", state.status());
        assertEquals(1, state.rows().size(), "a repeated group is scanned once, not duplicated");
        assertEquals(1, state.recordsScanned(), "scan count is not inflated by the repeated group");
    }

    @Test
    void startQueryUnknownLogGroupThrowsResourceNotFound() {
        // The group is never created.
        long startSec = BASE_MS / 1000 - 10;
        AwsException ex = assertThrows(AwsException.class, () ->
                service.startQuery(List.of("/app/missing"), startSec, startSec + 86400, APP_QUERY, null, REGION));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    @Test
    void filterValueContainingPipeIsNotSplit() {
        String group = "connectors/webhook";
        String stream = "s-1";
        createGroupStream(service, group, stream);
        putRaw(service, group, stream, BASE_MS + 1000, idLog("REQ|42", "piped"));
        putRaw(service, group, stream, BASE_MS + 2000, idLog("REQ-99", "other"));

        // The '|' inside the quoted value must not split the query; the filter matches the literal id.
        String query = "fields @message, params.id | filter params.id = 'REQ|42'";
        long startSec = BASE_MS / 1000 - 10;
        String queryId = service.startQuery(List.of(group), startSec, startSec + 86400, query, null, REGION);

        CloudWatchLogsService.QueryState state = service.getQueryResults(queryId);
        assertEquals("Complete", state.status());
        assertEquals(1, state.rows().size(), "the pipe-containing value matched exactly one event");
        assertEquals("REQ|42", state.rows().get(0).get("params.id"));
    }

    @Test
    void startQueryWithNoLogGroupThrowsInvalidParameter() {
        long startSec = BASE_MS / 1000 - 10;
        // No usable selector (empty list, or only blank names) is an invalid request, not an empty success.
        AwsException empty = assertThrows(AwsException.class, () ->
                service.startQuery(List.of(), startSec, startSec + 86400, APP_QUERY, null, REGION));
        assertEquals("InvalidParameterException", empty.getErrorCode());

        AwsException blank = assertThrows(AwsException.class, () ->
                service.startQuery(List.of("  ", ""), startSec, startSec + 86400, APP_QUERY, null, REGION));
        assertEquals("InvalidParameterException", blank.getErrorCode());
    }

    @Test
    void queryStringLongerThanTheAwsLimitIsRejected() {
        String group = "search/query-length";
        String stream = "s-1";
        createGroupStream(service, group, stream);
        putRaw(service, group, stream, BASE_MS + 1000, "{\"type\":\"B\",\"id\":\"b1\"}");
        putRaw(service, group, stream, BASE_MS + 2000, "{\"type\":\"C\",\"id\":\"c1\"}");

        // The deepest nesting and the longest dotted field that fit in AWS's 10000-character limit still parse.
        String nested = "fields id | filter " + "a=1 or (".repeat(1100) + "type = 'B'" + ")".repeat(1100);
        assertEquals(List.of("b1"), rowsFor(group, nested).stream().map(row -> row.get("id")).toList());
        assertEquals(List.of(), idsFor(group, "a" + ".a".repeat(4900) + " = 1"));
        // AWS counts code points: 4990 emoji are 9980 UTF-16 units but leave the query under the limit.
        assertEquals(List.of(), idsFor(group, "id = '" + "\uD83D\uDE00".repeat(4990) + "'"));

        long startSec = BASE_MS / 1000 - 10;
        String tooLong = "fields id" + " ".repeat(10_000 - 8);
        AwsException ex = assertThrows(AwsException.class, () ->
                service.startQuery(List.of(group), startSec, startSec + 86400, tooLong, null, REGION));
        assertEquals("InvalidParameterException", ex.getErrorCode());
        assertEquals("1 validation error detected: Value at 'queryString' failed to satisfy constraint: "
                + "Member must have length less than or equal to 10000", ex.getMessage());
    }

    @Test
    void filterValueContainingEqualsIsNotMisparsed() {
        String group = "connectors/equals";
        String stream = "s-1";
        createGroupStream(service, group, stream);
        putRaw(service, group, stream, BASE_MS + 1000, idLog("REQ==42", "eq"));
        putRaw(service, group, stream, BASE_MS + 2000, idLog("REQ-1", "other"));

        // '==' inside the quoted value must not be taken as the filter operator; the real '=' wins.
        String query = "fields @message, params.id | filter params.id = 'REQ==42'";
        long startSec = BASE_MS / 1000 - 10;
        String queryId = service.startQuery(List.of(group), startSec, startSec + 86400, query, null, REGION);

        CloudWatchLogsService.QueryState state = service.getQueryResults(queryId);
        assertEquals("Complete", state.status());
        assertEquals(1, state.rows().size(), "the value-embedded '==' did not break operator parsing");
        assertEquals("REQ==42", state.rows().get(0).get("params.id"));
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
    void limitTruncatesResultsAfterSort() {
        String group = "analytics/dashboard";
        String stream = "s-1";
        createGroupStream(service, group, stream);
        put(group, stream, BASE_MS + 1000, "INFO", "JOB-1", "one");
        put(group, stream, BASE_MS + 2000, "INFO", "JOB-1", "two");
        put(group, stream, BASE_MS + 3000, "INFO", "JOB-1", "three");
        put(group, stream, BASE_MS + 4000, "INFO", "JOB-1", "four");

        // 'limit 2' applies after sort, keeping only the two newest.
        String query = """
                fields @timestamp, @message
                | filter params.job_id = 'JOB-1'
                | sort @timestamp desc
                | limit 2
                """;
        long startSec = BASE_MS / 1000 - 10;
        String queryId = service.startQuery(List.of(group), startSec, startSec + 86400, query, null, REGION);

        CloudWatchLogsService.QueryState state = service.getQueryResults(queryId);
        assertEquals("Complete", state.status());
        assertEquals(2, state.rows().size(), "limit 2 keeps only the top two after sort");
        assertTrue(state.rows().get(0).get("@message").contains("four"));
        assertTrue(state.rows().get(1).get("@message").contains("three"));
    }

    @Test
    void sortAscendingByJsonField() {
        String group = "query-builder/explorer";
        String stream = "s-1";
        createGroupStream(service, group, stream);
        putRaw(service, group, stream, BASE_MS + 1000, idLog("REQ-C", "third"));
        putRaw(service, group, stream, BASE_MS + 2000, idLog("REQ-A", "first"));
        putRaw(service, group, stream, BASE_MS + 3000, idLog("REQ-B", "second"));

        // Ascending sort on a nested JSON field (lexicographic via the string comparator).
        String query = """
                fields @message, params.id
                | sort params.id asc
                """;
        long startSec = BASE_MS / 1000 - 10;
        String queryId = service.startQuery(List.of(group), startSec, startSec + 86400, query, null, REGION);

        CloudWatchLogsService.QueryState state = service.getQueryResults(queryId);
        assertEquals("Complete", state.status());
        assertEquals(3, state.rows().size());
        assertEquals("REQ-A", state.rows().get(0).get("params.id"));
        assertEquals("REQ-B", state.rows().get(1).get("params.id"));
        assertEquals("REQ-C", state.rows().get(2).get("params.id"));
    }

    @Test
    void sameMillisecondEventsSortByIngestionSequence() {
        String group = "analytics/pipeline";
        String stream = "s-1";
        createGroupStream(service, group, stream);

        // Five events sharing ONE millisecond, ingested in a known order. Pre-fix the @timestamp
        // comparator has no tie-break, so same-ms rows keep arbitrary hash-scan order; post-fix they
        // are ordered by ingestion sequence. Five events => 120 permutations, so a pre-fix pass by luck
        // is ~1/120.
        long ts = BASE_MS + 7000;
        putRaw(service, group, stream, ts, idLog("REQ-1", "first"));
        putRaw(service, group, stream, ts, idLog("REQ-2", "second"));
        putRaw(service, group, stream, ts, idLog("REQ-3", "third"));
        putRaw(service, group, stream, ts, idLog("REQ-4", "fourth"));
        putRaw(service, group, stream, ts, idLog("REQ-5", "fifth"));

        String query = """
                fields @message, params.id
                | sort @timestamp asc
                """;
        long startSec = BASE_MS / 1000 - 10;
        String queryId = service.startQuery(List.of(group), startSec, startSec + 86400, query, null, REGION);

        CloudWatchLogsService.QueryState state = service.getQueryResults(queryId);
        assertEquals("Complete", state.status());
        assertEquals(5, state.rows().size());
        // Ascending @timestamp with a sequence tie-break => strict ingestion order.
        assertEquals("REQ-1", state.rows().get(0).get("params.id"));
        assertEquals("REQ-2", state.rows().get(1).get("params.id"));
        assertEquals("REQ-3", state.rows().get(2).get("params.id"));
        assertEquals("REQ-4", state.rows().get(3).get("params.id"));
        assertEquals("REQ-5", state.rows().get(4).get("params.id"));
    }

    @Test
    void sameMillisecondEventsSortDescendingReverseIngestion() {
        String group = "metrics/collector";
        String stream = "s-1";
        createGroupStream(service, group, stream);

        // Same-ms events; descending sort must reverse the ingestion-order tie-break (newest-ingested
        // first), consistent with reversing the whole @timestamp comparator.
        long ts = BASE_MS + 8000;
        putRaw(service, group, stream, ts, idLog("REQ-1", "first"));
        putRaw(service, group, stream, ts, idLog("REQ-2", "second"));
        putRaw(service, group, stream, ts, idLog("REQ-3", "third"));
        putRaw(service, group, stream, ts, idLog("REQ-4", "fourth"));
        putRaw(service, group, stream, ts, idLog("REQ-5", "fifth"));

        String query = """
                fields @message, params.id
                | sort @timestamp desc
                """;
        long startSec = BASE_MS / 1000 - 10;
        String queryId = service.startQuery(List.of(group), startSec, startSec + 86400, query, null, REGION);

        CloudWatchLogsService.QueryState state = service.getQueryResults(queryId);
        assertEquals("Complete", state.status());
        assertEquals(5, state.rows().size());
        // Descending: reverse of ingestion order.
        assertEquals("REQ-5", state.rows().get(0).get("params.id"));
        assertEquals("REQ-4", state.rows().get(1).get("params.id"));
        assertEquals("REQ-3", state.rows().get(2).get("params.id"));
        assertEquals("REQ-2", state.rows().get(3).get("params.id"));
        assertEquals("REQ-1", state.rows().get(4).get("params.id"));
    }

    @Test
    void dedupWithoutSortKeepsNewestPerTupleByDefault() {
        String group = "reporting/emitter";
        String stream = "s-1";
        createGroupStream(service, group, stream);

        // Two hosts, three events each at distinct timestamps. With NO explicit sort, AWS applies a
        // default `sort @timestamp desc` before dedup, so the newest event per host survives. Ingestion
        // order below is deliberately NOT newest-last, so a pre-fix run (dedup over raw hash-scan order)
        // would keep an arbitrary — usually older — event and fail.
        putRaw(service, group, stream, BASE_MS + 2000, hostLog("host-a", "a-old"));
        putRaw(service, group, stream, BASE_MS + 6000, hostLog("host-a", "a-new"));
        putRaw(service, group, stream, BASE_MS + 4000, hostLog("host-a", "a-mid"));
        putRaw(service, group, stream, BASE_MS + 1000, hostLog("host-b", "b-old"));
        putRaw(service, group, stream, BASE_MS + 5000, hostLog("host-b", "b-new"));
        putRaw(service, group, stream, BASE_MS + 3000, hostLog("host-b", "b-mid"));

        // dedup with no preceding sort => default @timestamp desc, keep newest per host.
        String query = """
                fields @message, params.host
                | dedup params.host
                """;
        long startSec = BASE_MS / 1000 - 10;
        String queryId = service.startQuery(List.of(group), startSec, startSec + 86400, query, null, REGION);

        CloudWatchLogsService.QueryState state = service.getQueryResults(queryId);
        assertEquals("Complete", state.status());
        assertEquals(2, state.rows().size(), "one surviving row per unique host");

        // Final order also follows the default desc sort: host-a's newest (6000) before host-b's newest (5000).
        // @message resolves to the raw JSON line, so match by substring (the file's convention).
        assertTrue(state.rows().get(0).get("@message").contains("a-new"), "newest host-a event survives dedup");
        assertEquals("host-a", state.rows().get(0).get("params.host"));
        assertTrue(state.rows().get(1).get("@message").contains("b-new"), "newest host-b event survives dedup");
        assertEquals("host-b", state.rows().get(1).get("params.host"));
    }

    @Test
    void defaultProjectionReturnsTimestampAndMessage() {
        String group = "metrics/kpi-center";
        String stream = "s-1";
        createGroupStream(service, group, stream);
        put(group, stream, BASE_MS + 1000, "INFO", "JOB-1", "hello");

        // No 'fields' stage → the engine defaults to @timestamp, @message.
        String query = "filter params.job_id = 'JOB-1'";
        long startSec = BASE_MS / 1000 - 10;
        String queryId = service.startQuery(List.of(group), startSec, startSec + 86400, query, null, REGION);

        CloudWatchLogsService.QueryState state = service.getQueryResults(queryId);
        assertEquals("Complete", state.status());
        assertEquals(1, state.rows().size());
        LinkedHashMap<String, String> row = state.rows().get(0);
        assertTrue(row.containsKey("@timestamp"), "default projection includes @timestamp");
        assertTrue(row.containsKey("@message"), "default projection includes @message");
        assertNotNull(row.get("@ptr"));
        assertTrue(row.get("@message").contains("hello"));
    }

    @Test
    void missingFieldProjectsEmptyAndKeepsEventsOnInequality() {
        String group = "alerts/notifications";
        String stream = "s-1";
        createGroupStream(service, group, stream);
        put(group, stream, BASE_MS + 1000, "INFO", "JOB-1", "present");

        // 'params.absent' is not in the log: projecting it yields "", and '!=' keeps the event (null != 'x').
        String query = """
                fields @message, params.absent
                | filter params.absent != 'x'
                """;
        long startSec = BASE_MS / 1000 - 10;
        String queryId = service.startQuery(List.of(group), startSec, startSec + 86400, query, null, REGION);

        CloudWatchLogsService.QueryState state = service.getQueryResults(queryId);
        assertEquals("Complete", state.status());
        assertEquals(1, state.rows().size(), "an event lacking the field survives a '!=' filter on it");
        assertEquals("", state.rows().get(0).get("params.absent"), "an absent field projects as an empty string");
    }

    @Test
    void unsupportedCommandsFailTheQueryInsteadOfBeingIgnored() {
        String group = "data-sources/connectors";
        String stream = "s-1";
        createGroupStream(service, group, stream);
        put(group, stream, BASE_MS + 1000, "INFO", "JOB-1", "kept");
        put(group, stream, BASE_MS + 2000, "TRACE", "JOB-1", "dropped");

        // 'parse' / 'stats' are unsupported: the query must fail rather than silently apply only the
        // stages it understands and return a result set that does not correspond to the query asked.
        String query = """
                fields @message
                | filter level != 'TRACE'
                | parse @message '*' as x
                | stats count(*) by level
                """;
        long startSec = BASE_MS / 1000 - 10;
        String queryId = service.startQuery(List.of(group), startSec, startSec + 86400, query, null, REGION);

        CloudWatchLogsService.QueryState state = service.getQueryResults(queryId);
        assertEquals("Failed", state.status());
        assertTrue(state.rows().isEmpty(), "a failed query exposes no rows");
        assertNotNull(state.failureReason());
        assertTrue(state.failureReason().contains("parse"), "failure reason names the unsupported command: "
                + state.failureReason());
    }

    @Test
    void unsupportedFilterOperatorsFailTheQueryInsteadOfMisparsingOrDropping() {
        String group = "search/query-operators";
        String stream = "s-1";
        createGroupStream(service, group, stream);
        put(group, stream, BASE_MS + 1000, "INFO", "JOB-1", "info-line");
        put(group, stream, BASE_MS + 2000, "ERROR", "JOB-1", "error-line");

        // '>=', '<=' and '=~' used to lose their comparison character to the equality scan, leaving a
        // dead field ("level >") that silently matched nothing. None of these is supported: each one
        // must now fail the whole query rather than silently drop the filter and return every row.
        // The same holds for '||', 'and', 'not' and leftover text after a value: each fails rather
        // than degrading silently into a different comparison.
        for (String expr : List.of("level >= 'INFO'", "level <= 'INFO'", "level =~ /INFO/", ">= 'INFO'",
                "level > 'INFO'", "level < 'INFO'", "level like /INFO/", "level not like /INFO/",
                "level in ['INFO']",
                "level = 'INFO' || level = 'ERROR'",
                "level = 'INFO' || level = 'ERROR' | sort @timestamp desc",
                "(level = 'INFO' || level = 'ERROR')",
                "level = 'INFO' or level = 'ERROR' ||",
                "level = 'INFO' || sort @timestamp desc",
                "level = 'INFO' or",
                "level = 'INFO' and level = 'ERROR'",
                "not level = 'INFO'",
                "level = 'INFO' or level like /E/",
                "level like /a=b/",
                "level != INFO=oops",
                "level = INFO=oops",
                "level = 'INFO\\' or level = 'ERROR'",
                "`level` = 'INFO'",
                "1+1 = 2 or level = 'ERROR'",
                "level. = 'INFO'",
                "level = 'INFO' x",
                "(level = 'INFO'",
                "level = 'INFO",
                "level = INFO",
                "level = web-db",
                "level = 1+1",
                "level != 1+1 or level = 'ERROR'")) {
            CloudWatchLogsService.QueryState state = startQueryFor(group, "fields @message | filter " + expr);
            assertEquals("Failed", state.status(), "unsupported operator must fail the query: " + expr);
            assertTrue(state.rows().isEmpty(), "a failed query exposes no rows: " + expr);
            assertNotNull(state.failureReason(), "failure reason must be set: " + expr);
        }
    }

    @Test
    void startQuerySucceedsAndReportsFailureOnlyViaGetQueryResults() {
        // A query the engine cannot evaluate must still be accepted by StartQuery (it is a valid
        // request shape, just unsupported syntax); the failure surfaces asynchronously through
        // GetQueryResults, exactly like a real Insights query failing partway through execution.
        String group = "search/unsupported-syntax";
        String stream = "s-1";
        createGroupStream(service, group, stream);
        put(group, stream, BASE_MS + 1000, "INFO", "JOB-1", "hello");

        long startSec = BASE_MS / 1000 - 10;
        String queryId = service.startQuery(
                List.of(group), startSec, startSec + 86400, "filter @message like /ERROR/", null, REGION);
        assertNotNull(queryId);

        CloudWatchLogsService.QueryState state = service.getQueryResults(queryId);
        assertEquals("Failed", state.status());
        assertTrue(state.rows().isEmpty());
        assertNotNull(state.failureReason());
    }

    @Test
    void invalidArgumentsToRecognizedCommandsFailTheQuery() {
        // A recognized command with an argument it cannot parse must fail the query too, not just an
        // unrecognized command or a fully-unsupported filter operator: silently keeping the default
        // limit, or silently running with no sort/dedup, would be just as misleading to the caller.
        String group = "search/invalid-command-arguments";
        String stream = "s-1";
        createGroupStream(service, group, stream);
        put(group, stream, BASE_MS + 1000, "INFO", "JOB-1", "one");
        put(group, stream, BASE_MS + 2000, "INFO", "JOB-1", "two");

        for (String query : List.of(
                "fields @message | limit abc",
                "fields @message | sort",
                "fields @message | dedup")) {
            CloudWatchLogsService.QueryState state = startQueryFor(group, query);
            assertEquals("Failed", state.status(), "invalid command argument must fail the query: " + query);
            assertTrue(state.rows().isEmpty(), "a failed query exposes no rows: " + query);
            assertNotNull(state.failureReason(), "failure reason must be set: " + query);
        }
    }

    @Test
    void emptyStageFailsTheQuery() {
        String group = "search/empty-stage";
        String stream = "s-1";
        createGroupStream(service, group, stream);
        put(group, stream, BASE_MS + 1000, "INFO", "JOB-1", "one");

        for (String query : List.of("", "   ", "fields @message |", "fields @message | | sort @timestamp desc")) {
            CloudWatchLogsService.QueryState state = startQueryFor(group, query);
            assertEquals("Failed", state.status(), "an empty stage must fail the query: '" + query + "'");
            assertTrue(state.rows().isEmpty(), "a failed query exposes no rows: '" + query + "'");
        }
    }

    @Test
    void supportedFilterOperatorsStillParse() {
        String group = "search/query-equality";
        String stream = "s-1";
        createGroupStream(service, group, stream);
        put(group, stream, BASE_MS + 1000, "INFO", "JOB-1", "info-line");
        put(group, stream, BASE_MS + 2000, "ERROR", "JOB-1", "error-line");
        putRaw(service, group, stream, BASE_MS + 3000, idLog("REQ>=42", "quoted"));

        assertEquals(1, rowsFor(group, "fields @message | filter level = 'ERROR'").size());
        assertEquals(1, rowsFor(group, "fields @message | filter level == 'ERROR'").size());
        assertEquals(2, rowsFor(group, "fields @message | filter level != 'ERROR'").size());

        // A '>=' inside the quoted value is not an operator — the real '=' still wins.
        assertEquals("REQ>=42",
                rowsFor(group, "fields params.id | filter params.id = 'REQ>=42'").get(0).get("params.id"));
    }

    @Test
    void bareNumberComparesAsANumber() {
        String group = "search/query-number";
        String stream = "s-1";
        createGroupStream(service, group, stream);
        // These events and the expected ids below were observed on AWS with the same queries.
        putRaw(service, group, stream, BASE_MS + 1000, "{\"n\":2,\"id\":\"a1\"}");
        putRaw(service, group, stream, BASE_MS + 2000, "{\"n\":3,\"id\":\"b1\"}");
        putRaw(service, group, stream, BASE_MS + 3000, "{\"n\":2.0,\"id\":\"d1\"}");

        for (String filter : List.of("n = 2", "n = 2.0", "n = 02", "n == 2")) {
            assertEquals(List.of("a1", "d1"), idsFor(group, filter), filter);
        }
        assertEquals(List.of(), idsFor(group, "n = -2"), "n = -2");
        assertEquals(List.of("b1"), idsFor(group, "n != 2"), "n != 2");
    }

    @Test
    void orFilterKeepsRowsMatchingEitherComparison() {
        String group = "search/query-or";
        String stream = "s-1";
        createGroupStream(service, group, stream);
        // These events and the expected ids below were observed on AWS with the same queries.
        putRaw(service, group, stream, BASE_MS + 1000, "{\"type\":\"A\",\"level\":\"INFO\",\"id\":\"a1\"}");
        putRaw(service, group, stream, BASE_MS + 2000, "{\"type\":\"B\",\"level\":\"INFO\",\"id\":\"b1\"}");
        putRaw(service, group, stream, BASE_MS + 3000, "{\"type\":\"C\",\"level\":\"INFO\",\"id\":\"c1\"}");
        putRaw(service, group, stream, BASE_MS + 4000, "{\"type\":\"A|B\",\"level\":\"INFO\",\"id\":\"p1\"}");
        putRaw(service, group, stream, BASE_MS + 5000, "{\"type\":\"A\",\"level\":\"WARN\",\"id\":\"a2\"}");
        putRaw(service, group, stream, BASE_MS + 6000,
                "{\"type\":\"A or B\",\"color\":\"red\",\"order\":\"first\",\"id\":\"o1\"}");

        assertEquals(List.of("a1", "a2"), idsFor(group, "type = 'A'"), "type = 'A'");
        assertEquals(List.of("b1"), idsFor(group, "type = 'B'"), "type = 'B'");
        assertEquals(List.of("p1"), idsFor(group, "type = 'A|B'"), "type = 'A|B'");
        assertEquals(List.of("o1"), idsFor(group, "type = 'A or B'"), "type = 'A or B'");
        assertEquals(List.of("c1", "o1"), idsFor(group, "color = 'red' or type = 'C'"),
                "color = 'red' or type = 'C'");
        assertEquals(List.of("c1", "o1"), idsFor(group, "order = 'first' or type = 'C'"),
                "order = 'first' or type = 'C'");

        for (String filter : List.of(
                "type = 'A' or type = 'B'",
                "type = 'A' OR type = 'B'",
                "type = 'A' Or type = 'B'",
                "type='A'or type='B'",
                "type = \"A\" or type = \"B\"",
                "type == 'A' or type == 'B'",
                "(type = 'A' or type = 'B')",
                "(type = 'A') or (type = 'B')",
                "((type = 'A') or type = 'B')",
                "(type='A')or(type='B')")) {
            assertEquals(List.of("a1", "a2", "b1"), idsFor(group, filter), filter);
        }

        assertEquals(List.of("a1", "a2", "b1", "c1"), idsFor(group, "type = 'A' or type = 'B' or type = 'C'"),
                "type = 'A' or type = 'B' or type = 'C'");
        assertEquals(List.of("a1", "a2", "b1", "c1"), idsFor(group, "type = 'A' or (type = 'B' or type = 'C')"),
                "type = 'A' or (type = 'B' or type = 'C')");
        assertEquals(List.of("a2", "b1", "c1", "o1", "p1"), idsFor(group, "type != 'A' or level = 'WARN'"),
                "type != 'A' or level = 'WARN'");
        assertEquals(List.of("b1"), idsFor(group, "(type = 'A)' or type = 'B')"), "(type = 'A)' or type = 'B')");

        List<String> sortedDesc = rowsFor(group, "fields id | filter type = 'A' or type = 'B' | sort @timestamp desc")
                .stream().map(row -> row.get("id")).toList();
        assertEquals(List.of("a2", "b1", "a1"), sortedDesc, "type = 'A' or type = 'B' | sort @timestamp desc");
        assertEquals(List.of("a1", "b1"), idsFor(group, "type = 'A' or type = 'B' | filter level = 'INFO'"),
                "type = 'A' or type = 'B' | filter level = 'INFO'");
    }

    @Test
    void statisticsReportScannedAndMatched() {
        String group = "data-studio/insights";
        String stream = "s-1";
        createGroupStream(service, group, stream);
        put(group, stream, BASE_MS + 1000, "INFO", "JOB-1", "a");
        put(group, stream, BASE_MS + 2000, "TRACE", "JOB-1", "b"); // dropped: level
        put(group, stream, BASE_MS + 3000, "INFO", "JOB-2", "c");  // dropped: job_id
        put(group, stream, BASE_MS + 4000, "INFO", "JOB-1", "d");

        long startSec = BASE_MS / 1000 - 10;
        String queryId = service.startQuery(List.of(group), startSec, startSec + 86400, APP_QUERY, null, REGION);

        CloudWatchLogsService.QueryState state = service.getQueryResults(queryId);
        assertEquals("Complete", state.status());
        assertEquals(4, state.recordsScanned(), "recordsScanned counts every event in the window");
        assertEquals(2, state.recordsMatched(), "recordsMatched counts only the rows returned after filtering");
        assertEquals(2, state.rows().size());
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
                new InMemoryStorage<>(), 10000, new RegionResolver(REGION, "000000000000"), delayMs, clockMs::get);
        createGroupStream(svc, group, stream);
        putRaw(svc, group, stream, BASE_MS + 2000, jobLog("INFO", "x", "JOB-1"));
        return svc;
    }

    /** Run {@code query} over the full window on the default service and return its completed rows. */
    private List<LinkedHashMap<String, String>> rowsFor(String group, String query) {
        long startSec = BASE_MS / 1000 - 10;
        CloudWatchLogsService.QueryState state = service.getQueryResults(
                service.startQuery(List.of(group), startSec, startSec + 86400, query, null, REGION));
        assertEquals("Complete", state.status());
        return state.rows();
    }

    /** The {@code id} of each row {@code fields id | filter <filter>} returns, sorted. */
    private List<String> idsFor(String group, String filter) {
        return rowsFor(group, "fields id | filter " + filter).stream().map(row -> row.get("id")).sorted().toList();
    }

    /** Run {@code query} over the full window on the default service and return its final state. */
    private CloudWatchLogsService.QueryState startQueryFor(String group, String query) {
        long startSec = BASE_MS / 1000 - 10;
        return service.getQueryResults(
                service.startQuery(List.of(group), startSec, startSec + 86400, query, null, REGION));
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

    private static String hostLog(String host, String text) {
        return String.format(
                "{\"level\":\"INFO\",\"message\":\"%s\",\"params\":{\"host\":\"%s\"}}",
                text, host);
    }

    private static String artifactLog(String jobId, String group, String artifact, String version) {
        return String.format(
                "{\"level\":\"INFO\",\"message\":\"published\",\"params\":{\"job_id\":\"%s\","
                        + "\"artifact_coordinates\":{\"group\":\"%s\",\"artifact\":\"%s\",\"version\":\"%s\"}}}",
                jobId, group, artifact, version);
    }
}
