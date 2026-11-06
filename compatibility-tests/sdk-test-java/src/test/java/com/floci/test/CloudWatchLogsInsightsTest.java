package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.*;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * CloudWatch Logs Insights compatibility: StartQuery / GetQueryResults / StopQuery.
 *
 * <p>Seeds a log group with structured JSON events and exercises the same query shape real
 * clients send — filter on a nested JSON field ({@code params.job_id}), drop a log level,
 * sort by {@code @timestamp}, and dedup. StartQuery times are epoch <em>seconds</em> while
 * event timestamps are millis.
 */
@DisplayName("CloudWatch Logs Insights")
class CloudWatchLogsInsightsTest {

    private static CloudWatchLogsClient logs;

    private static final String GROUP = "/test/" + TestFixtures.uniqueName("insights");
    private static final String STREAM = "stream-01";
    private static String jobId;
    private static long baseMs;

    @BeforeAll
    static void setUp() {
        logs = TestFixtures.cloudWatchLogsClient();
        logs.createLogGroup(b -> b.logGroupName(GROUP));
        logs.createLogStream(b -> b.logGroupName(GROUP).logStreamName(STREAM));

        baseMs = System.currentTimeMillis();
        jobId = TestFixtures.uniqueName("job");
        logs.putLogEvents(b -> b
                .logGroupName(GROUP)
                .logStreamName(STREAM)
                .logEvents(
                        InputLogEvent.builder().timestamp(baseMs - 3000).message(json("INFO", "job started", jobId)).build(),
                        InputLogEvent.builder().timestamp(baseMs - 2000).message(json("TRACE", "verbose noise", jobId)).build(),
                        InputLogEvent.builder().timestamp(baseMs - 1000).message(json("INFO", "unrelated", "other-job")).build(),
                        InputLogEvent.builder().timestamp(baseMs).message(json("ERROR", "job boom", jobId)).build()
                ));
    }

    @AfterAll
    static void tearDown() {
        if (logs != null) {
            try {
                logs.deleteLogGroup(b -> b.logGroupName(GROUP));
            } catch (RuntimeException ignored) {
                // best-effort cleanup
            }
            logs.close();
        }
    }

    @Test
    @DisplayName("StartQuery returns a queryId")
    void startQueryReturnsQueryId() {
        StartQueryResponse start = startTestQuery();
        assertThat(start.queryId()).isNotBlank();
    }

    @Test
    @DisplayName("GetQueryResults filters by JSON field, drops TRACE, sorts desc and dedups")
    void getQueryResultsFiltersSortsDedups() {
        // On real AWS, events are not queryable for several seconds after ingestion; retry the
        // full start+poll cycle until rows appear or the ~60s deadline elapses.
        GetQueryResultsResponse res = pollUntilComplete(startTestQuery().queryId());
        long deadline = System.currentTimeMillis() + 60_000;
        while (res.results().isEmpty() && System.currentTimeMillis() < deadline) {
            res = pollUntilComplete(startTestQuery().queryId());
        }

        assertThat(res.status()).isEqualTo(QueryStatus.COMPLETE);

        // job_id filter keeps 3 events; level != TRACE drops 1 -> 2 rows.
        assertThat(res.results()).hasSize(2);

        // Every row projects @timestamp and @message.
        assertThat(res.results()).allSatisfy(row -> {
            assertThat(row).anySatisfy(f -> assertThat(f.field()).isEqualTo("@timestamp"));
            assertThat(row).anySatisfy(f -> assertThat(f.field()).isEqualTo("@message"));
        });

        // sort @timestamp desc -> newest (ERROR "job boom") first, then INFO "job started".
        assertThat(messageOf(res.results().get(0))).contains("job boom");
        assertThat(messageOf(res.results().get(1))).contains("job started");

        // The TRACE line and the unrelated job must be excluded.
        List<String> messages = res.results().stream()
                .map(CloudWatchLogsInsightsTest::messageOf)
                .toList();
        assertThat(messages).noneMatch(m -> m.contains("verbose noise") || m.contains("unrelated"));
    }

    @Test
    @DisplayName("StopQuery on a finished query fails with InvalidParameterException")
    void stopFinishedQueryFailsNotRunning() {
        StartQueryResponse start = startTestQuery();
        pollUntilComplete(start.queryId()); // ensure the query has ended

        // AWS: stopping a query that is not running returns an error ("...is not running").
        assertThatThrownBy(() -> logs.stopQuery(b -> b.queryId(start.queryId())))
                .isInstanceOfSatisfying(CloudWatchLogsException.class,
                        e -> assertThat(e.awsErrorDetails().errorCode()).isEqualTo("InvalidParameterException"));
    }

    @Test
    @DisplayName("StopQuery on a running query returns success")
    void stopRunningQuerySucceeds() {
        StartQueryResponse start = startTestQuery();

        // Only meaningful where the server models an async Running window: real AWS, or Floci with a
        // non-zero query-completion-delay (FLOCI_SERVICES_CLOUDWATCHLOGS_QUERY_COMPLETION_DELAY_MS=2000).
        // With Floci's default (instant completion) there is no Running phase, so this case is skipped
        // rather than asserted.
        String status = logs.getQueryResults(b -> b.queryId(start.queryId())).statusAsString();
        Assumptions.assumeTrue("Running".equals(status) || "Scheduled".equals(status),
                "no Running window (instant completion) — nothing to stop");

        // On real AWS the query can complete in the gap between the status check and the stop call;
        // treat InvalidParameterException from stopQuery as a valid outcome rather than a failure.
        try {
            StopQueryResponse stop = logs.stopQuery(b -> b.queryId(start.queryId()));
            assertThat(stop.success()).isTrue();
        } catch (CloudWatchLogsException e) {
            if ("InvalidParameterException".equals(e.awsErrorDetails().errorCode())) {
                Assumptions.abort("query completed before stop");
            }
            throw e;
        }
    }

    // ──────────────────────────── helpers ────────────────────────────

    private static StartQueryResponse startTestQuery() {
        long nowSec = baseMs / 1000;
        return logs.startQuery(b -> b
                .logGroupNames(GROUP)
                .startTime(nowSec - 300)
                .endTime(nowSec + 300)
                .queryString(query()));
    }

    private static String query() {
        return "fields @timestamp, @message"
                + " | filter params.job_id = '" + jobId + "'"
                + " | filter level != 'TRACE'"
                + " | sort @timestamp desc"
                + " | dedup @timestamp, @message";
    }

    private static GetQueryResultsResponse pollUntilComplete(String queryId) {
        GetQueryResultsResponse res = null;
        for (int i = 0; i < 75; i++) {   // ~15s budget; real AWS Insights queries run asynchronously
            res = logs.getQueryResults(b -> b.queryId(queryId));
            QueryStatus status = res.status();
            if (status == QueryStatus.COMPLETE || status == QueryStatus.FAILED || status == QueryStatus.CANCELLED) {
                break;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return res;
    }

    private static String messageOf(List<ResultField> row) {
        return row.stream()
                .filter(f -> "@message".equals(f.field()))
                .map(ResultField::value)
                .findFirst()
                .orElse("");
    }

    private static String json(String level, String message, String job) {
        return "{\"level\":\"" + level + "\",\"message\":\"" + message + "\",\"params\":{\"job_id\":\"" + job + "\"}}";
    }
}
