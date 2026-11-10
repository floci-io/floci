package io.floci.aws.services.lambdaweb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * The {@code platform.*} events and the environment metrics of one environment. AWS reports the CPU
 * time and the peak memory of the whole environment, which Floci reads from the container's cgroup. A
 * reading that fails keeps the last one, so CPU time after the last reading is lost when the process
 * exits on its own.
 */
final class LambdaWebPlatformLog {

    static final String INIT_TYPE = "native-http";
    private static final String RUNTIME_VERSION = "nodejs.web24.v30";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MIB = 1024 * 1024;
    private static final DateTimeFormatter CONSOLE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** The CPU time and the peak memory of the environment so far. */
    record Usage(long cpuMicros, long memoryBytes) {
    }

    private final LambdaWebLogs.Sink sink;
    private final String region;
    private final String functionName;
    private final String revisionId;
    private final String runtime;
    private final String applicationLogLevel;
    private final LambdaWebMetrics.EnvironmentMetrics environmentMetrics;
    private final String instanceId = UUID.randomUUID().toString();
    private long initStarted;
    private long invokeStarted;
    private Instant windowStart;
    private int windowRequests;
    private int windowConcurrency;
    private long windowBytes;
    private int totalRequests;
    private int totalConcurrency;
    private long cpuMicros;
    private long cpuAtInvokeStart;
    private long cpuAtWindowStart;
    private long peakMemoryMb;
    private boolean serving;
    private boolean ended;

    LambdaWebPlatformLog(LambdaWebLogs.Sink sink, String region, String functionName, String revisionId,
                         String runtime, String applicationLogLevel,
                         LambdaWebMetrics.EnvironmentMetrics environmentMetrics) {
        this.sink = sink;
        this.region = region;
        this.functionName = functionName;
        this.revisionId = revisionId;
        this.runtime = runtime;
        this.applicationLogLevel = applicationLogLevel;
        this.environmentMetrics = environmentMetrics;
    }

    synchronized void initStart() {
        initStarted = System.nanoTime();
        ObjectNode record = JSON.createObjectNode();
        record.put("runtimeVersion", runtime + ":" + RUNTIME_VERSION);
        record.put("runtimeVersionArn", AwsArnUtils.Arn.of("lambda", region, "",
                "runtime/" + runtime + "/version/" + RUNTIME_VERSION).toString());
        record.put("functionName", functionName);
        record.put("revisionId", revisionId);
        record.put("instanceId", instanceId);
        record.put("initializationType", INIT_TYPE);
        sink.platform(Instant.now(), "INFO", "platform.initStart", record);
    }

    /** {@code errorType} is null when the app listens. */
    synchronized void initReport(String errorType, Usage usage) {
        read(usage);
        long initNanos = System.nanoTime() - initStarted;
        ObjectNode record = status(errorType);
        record.put("instanceId", instanceId);
        record.put("initializationType", INIT_TYPE);
        ObjectNode metrics = record.putObject("metrics");
        metrics.put("totalDurationMs", wholeMillis(initNanos));
        metrics.put("totalCpuTimeMs", wholeMillis(cpuMicros * 1000));
        metrics.put("maxMemoryUsageMb", peakMemoryMb);
        sink.platform(Instant.now(), errorType == null ? "INFO" : "WARN", "platform.initReport", record);
        environmentMetrics.publish(millis(initNanos), millis(cpuMicros * 1000), peakMemoryMb, 0, 0);
    }

    /** A failure AWS writes in the shape of console output, with the time as Node prints it, and drops at FATAL. */
    void applicationError(String message) {
        if ("FATAL".equals(applicationLogLevel)) {
            return;
        }
        ObjectNode event = JSON.createObjectNode();
        Instant now = Instant.now();
        event.put("timestamp", CONSOLE_TIME.format(now));
        event.put("level", "ERROR");
        event.put("message", message);
        event.put("region", region);
        sink.accept(now, event.toString());
    }

    synchronized void invokeStart(int inFlight) {
        serving = true;
        invokeStarted = System.nanoTime();
        windowStart = Instant.now();
        cpuAtInvokeStart = cpuMicros;
        cpuAtWindowStart = cpuMicros;
        windowConcurrency = inFlight;
        totalConcurrency = inFlight;
        ObjectNode record = JSON.createObjectNode();
        record.put("functionName", functionName);
        record.put("revisionId", revisionId);
        record.put("instanceId", instanceId);
        sink.platform(windowStart, "INFO", "platform.invokeStart", record);
    }

    synchronized boolean serving() {
        return serving;
    }

    synchronized void concurrency(int inFlight) {
        windowConcurrency = Math.max(windowConcurrency, inFlight);
        totalConcurrency = Math.max(totalConcurrency, inFlight);
    }

    /** {@code errorType} names the page the platform answered in place of the app. */
    synchronized void requestComplete(String requestId, String endpointName, int result, String errorType,
                                      long durationNanos, long responseBytes) {
        windowRequests++;
        totalRequests++;
        windowBytes += responseBytes;
        ObjectNode record = JSON.createObjectNode();
        record.put("requestId", requestId);
        record.put("endpointName", endpointName);
        record.put("instanceId", instanceId);
        record.put("result", result);
        if (errorType != null) {
            record.put("errorType", errorType);
        }
        ObjectNode metrics = record.putObject("metrics");
        metrics.put("durationMs", BigDecimal.valueOf(durationNanos).movePointLeft(6).setScale(3, RoundingMode.HALF_UP));
        metrics.put("responseBytes", responseBytes);
        sink.platform(Instant.now(), "DEBUG", "platform.requestComplete", record);
    }

    /** Closes the window that started at the last summary, or at {@code invokeStart}, unless the environment ended. */
    synchronized void invokeSummary(Usage usage, int inFlight) {
        if (ended) {
            return;
        }
        read(usage);
        Instant now = Instant.now();
        ObjectNode record = JSON.createObjectNode();
        record.put("startTime", LambdaWebLogs.timestamp(windowStart, 9));
        record.put("instanceId", instanceId);
        ObjectNode metrics = record.putObject("metrics");
        metrics.put("requests", windowRequests);
        metrics.put("concurrentRequests", windowConcurrency);
        metrics.put("cpuTimeMs", wholeMillis((cpuMicros - cpuAtWindowStart) * 1000));
        metrics.put("maxMemoryUsageMb", peakMemoryMb);
        sink.platform(now, "INFO", "platform.invokeSummary", record);
        environmentMetrics.publish(Duration.between(windowStart, now).toMillis(),
                millis((cpuMicros - cpuAtWindowStart) * 1000), peakMemoryMb, windowConcurrency, windowBytes);
        windowStart = now;
        windowRequests = 0;
        windowBytes = 0;
        windowConcurrency = inFlight;
        cpuAtWindowStart = cpuMicros;
    }

    /**
     * The last summary and {@code invokeReport}. {@code errorType} is null when Floci stops the environment, and names
     * the exit when the process ends.
     */
    synchronized void end(Usage usage, String errorType) {
        invokeSummary(usage, 0);
        ended = true;
        ObjectNode record = status(errorType);
        record.put("instanceId", instanceId);
        ObjectNode metrics = record.putObject("metrics");
        metrics.put("totalCpuTimeMs", wholeMillis((cpuMicros - cpuAtInvokeStart) * 1000));
        metrics.put("totalDurationMs", wholeMillis(System.nanoTime() - invokeStarted));
        metrics.put("totalRequests", totalRequests);
        metrics.put("concurrentRequests", totalConcurrency);
        metrics.put("maxMemoryUsageMb", peakMemoryMb);
        sink.platform(Instant.now(), errorType == null ? "INFO" : "WARN", "platform.invokeReport", record);
    }

    synchronized void spinDownStart() {
        ObjectNode record = JSON.createObjectNode();
        record.put("functionName", functionName);
        record.put("revisionId", revisionId);
        record.put("instanceId", instanceId);
        sink.platform(Instant.now(), "INFO", "platform.spinDownStart", record);
    }

    /** The cgroup is gone once the process has stopped, so the CPU time of the shutdown is not known. */
    synchronized void spinDownReport(String errorType, long durationNanos) {
        ObjectNode record = status(errorType);
        record.put("instanceId", instanceId);
        ObjectNode metrics = record.putObject("metrics");
        metrics.put("totalDurationMs", wholeMillis(durationNanos));
        metrics.put("totalCpuTimeMs", wholeMillis(0));
        metrics.put("maxMemoryUsageMb", peakMemoryMb);
        sink.platform(Instant.now(), errorType == null ? "INFO" : "WARN", "platform.spinDownReport", record);
    }

    private void read(Usage usage) {
        if (usage != null) {
            cpuMicros = Math.max(cpuMicros, usage.cpuMicros());
            peakMemoryMb = Math.max(peakMemoryMb, usage.memoryBytes() / MIB);
        }
    }

    private static ObjectNode status(String errorType) {
        ObjectNode record = JSON.createObjectNode();
        record.put("status", errorType == null ? "success" : "failure");
        if (errorType != null) {
            record.put("errorType", errorType);
        }
        return record;
    }

    private static long millis(long nanos) {
        return Math.round(nanos / 1_000_000.0);
    }

    /** AWS writes these durations in whole milliseconds with three decimals. */
    private static BigDecimal wholeMillis(long nanos) {
        return BigDecimal.valueOf(millis(nanos)).setScale(3);
    }
}
