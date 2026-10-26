package io.github.hectorvent.floci.services.lambdaweb;

import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LambdaWebPlatformLogTest {

    private static final String GROUP = "/aws/lambda/web/web";
    private static final String STREAM = "2026/10/18/web[rev-abcde]0123456789abcdef0123456789abcdef";

    @Mock
    CloudWatchLogsService cloudWatchLogs;

    private LambdaWebLogs logs;
    private final List<List<Long>> datapoints = new ArrayList<>();

    @BeforeEach
    void start() {
        logs = new LambdaWebLogs(cloudWatchLogs);
    }

    @AfterEach
    void stop() {
        logs.shutdown();
    }

    @Test
    void timesDropTheTrailingZerosOfTheFraction() {
        assertEquals("2026-10-18T13:49:46.67Z", LambdaWebLogs.timestamp(Instant.parse("2026-10-18T13:49:46.670Z"), 3));
        assertEquals("2026-10-18T13:49:33.4Z", LambdaWebLogs.timestamp(Instant.parse("2026-10-18T13:49:33.400999Z"), 3));
        assertEquals("2026-10-18T13:49:33Z", LambdaWebLogs.timestamp(Instant.parse("2026-10-18T13:49:33.000Z"), 3));
        assertEquals("2026-10-18T13:50:09.0405147Z",
                LambdaWebLogs.timestamp(Instant.parse("2026-10-18T13:50:09.040514700Z"), 9));
    }

    @Test
    void aRequestIsReportedWithItsDurationInMillisecondsToThreeDecimals() {
        LambdaWebPlatformLog platform = new LambdaWebPlatformLog(
                logs.open("111122223333", "eu-west-1", "eu-west-1", GROUP, STREAM, "DEBUG"), "eu-west-1", "web",
                "rev-abcde", "nodejs24.x", "INFO", this::datapoint);

        platform.requestComplete("KXH25FBDZ33GP4TQ4ARAUYSTSA", "dev", 504, "Runtime.RequestTimeout", 3_000_716_400L, 0);

        JsonPath event = JsonPath.from(firstMessage());
        assertEquals("DEBUG", event.getString("level"));
        assertEquals("platform.requestComplete", event.getString("type"));
        assertEquals("eu-west-1", event.getString("region"));
        assertTrue(firstMessage().endsWith("\"result\":504,\"errorType\":\"Runtime.RequestTimeout\","
                + "\"metrics\":{\"durationMs\":3000.716,\"responseBytes\":0}}}"), firstMessage());
    }

    @Test
    void eventsBelowTheSystemLogLevelAreDroppedAndAFailedReportIsWarn() {
        LambdaWebPlatformLog platform = new LambdaWebPlatformLog(
                logs.open("111122223333", "eu-west-1", "eu-west-1", GROUP, STREAM, "WARN"), "eu-west-1", "web",
                "rev-abcde", "nodejs24.x", "INFO", this::datapoint);
        platform.initStart();
        platform.initReport(null, new LambdaWebPlatformLog.Usage(178_400, 142L * 1024 * 1024 + 5));
        platform.invokeStart(1);
        platform.concurrency(3);

        platform.end(null, "Runtime.ExitError");

        JsonPath event = JsonPath.from(firstMessage());
        assertEquals("WARN", event.getString("level"));
        assertEquals("platform.invokeReport", event.getString("type"));
        assertEquals("failure", event.getString("record.status"));
        assertEquals(3, event.getInt("record.metrics.concurrentRequests"));
        assertEquals(142, event.getInt("record.metrics.maxMemoryUsageMb"));
        assertTrue(firstMessage().contains("\"totalCpuTimeMs\":0.000,"), firstMessage());
    }

    @Test
    void theListenFailureIsDroppedAtFatal() {
        LambdaWebPlatformLog platform = new LambdaWebPlatformLog(
                logs.open("111122223333", "eu-west-1", "eu-west-1", GROUP, STREAM, "DEBUG"), "eu-west-1", "web",
                "rev-abcde", "nodejs24.x", "FATAL", this::datapoint);

        platform.applicationError("Application.TimeoutListenError: nothing was listening");
        platform.spinDownStart();

        assertEquals("platform.spinDownStart", JsonPath.from(firstMessage()).getString("type"));
    }

    @Test
    void anEnvironmentPublishesItsMetricsAtInitAndForEachWindowWhateverTheSystemLogLevel() {
        LambdaWebPlatformLog platform = new LambdaWebPlatformLog(
                logs.open("111122223333", "eu-west-1", "eu-west-1", GROUP, STREAM, "WARN"), "eu-west-1", "web",
                "rev-abcde", "nodejs24.x", "INFO", this::datapoint);
        platform.initStart();
        platform.initReport(null, new LambdaWebPlatformLog.Usage(178_400, 142L * 1024 * 1024 + 5));
        platform.invokeStart(1);
        platform.concurrency(2);
        platform.requestComplete("KXH25FBDZ33GP4TQ4ARAUYSTSA", "dev", 200, null, 1_000_000, 18);
        platform.requestComplete("Q2TRUVQ6VJ5RQHBA4ZKSBWQO7A", "dev", 503, "Runtime.OriginRequestError", 1_000_000, 0);
        platform.invokeSummary(new LambdaWebPlatformLog.Usage(250_000, 150L * 1024 * 1024), 1);
        platform.end(null, "Runtime.ExitError");
        platform.invokeSummary(new LambdaWebPlatformLog.Usage(300_000, 150L * 1024 * 1024), 0);

        assertEquals(3, datapoints.size(), datapoints.toString());
        assertEquals(List.of(178L, 142L, 0L, 0L), datapoints.get(0).subList(1, 5));
        assertEquals(List.of(72L, 150L, 2L, 18L), datapoints.get(1).subList(1, 5));
        assertEquals(List.of(0L, 150L, 1L, 0L), datapoints.get(2).subList(1, 5));
    }

    @Test
    void aFailedInitPublishesOneDatapoint() {
        LambdaWebPlatformLog platform = new LambdaWebPlatformLog(
                logs.open("111122223333", "eu-west-1", "eu-west-1", GROUP, STREAM, "INFO"), "eu-west-1", "web",
                "rev-abcde", "nodejs24.x", "INFO", this::datapoint);
        platform.initStart();
        platform.initReport("Runtime.ExitError", null);

        assertEquals(1, datapoints.size(), datapoints.toString());
        assertEquals(List.of(0L, 0L, 0L, 0L), datapoints.getFirst().subList(1, 5));
    }

    private void datapoint(long activeMillis, long cpuMillis, long maxMemoryMb, int concurrentRequests,
                           long responseBytes) {
        datapoints.add(List.of(activeMillis, cpuMillis, maxMemoryMb, (long) concurrentRequests, responseBytes));
    }

    @SuppressWarnings("unchecked")
    private String firstMessage() {
        ArgumentCaptor<List<Map<String, Object>>> batch = ArgumentCaptor.forClass(List.class);
        verify(cloudWatchLogs, timeout(5_000).atLeastOnce()).putLogEventsForAccount(eq("111122223333"),
                eq(GROUP + ".local"), eq(STREAM), batch.capture(), eq("eu-west-1"));
        return (String) batch.getAllValues().getFirst().getFirst().get("message");
    }
}
