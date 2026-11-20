package io.floci.aws.services.lambda.durable;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurableInvocationLogsTest {

    @Test
    void resumingAnExecutionKeepsOnlyTheLastFourKilobytesAcrossHandlers() {
        DurableInvocationLogs logs = new DurableInvocationLogs();
        logs.acquire();
        byte[] first = "first handler\n".getBytes(StandardCharsets.UTF_8);
        logs.beginInvocation();
        logs.complete(Base64.getEncoder().encodeToString(first));
        byte[] second = ("界".repeat(3000) + " second handler\n").getBytes(StandardCharsets.UTF_8);
        logs.beginInvocation();
        logs.complete(Base64.getEncoder().encodeToString(second));

        assertArrayEquals(Arrays.copyOfRange(second, second.length - 4096, second.length),
                Base64.getDecoder().decode(logs.snapshot().join()));
        logs.release();
        assertTrue(logs.unused());
    }

    @Test
    void attachedCallersShareTheCaptureUntilTheLastHandlerAndCallerFinish() {
        DurableInvocationLogs logs = new DurableInvocationLogs();
        logs.acquire();
        logs.acquire();
        logs.beginInvocation();
        CompletableFuture<String> snapshot = logs.snapshot();
        logs.release();
        assertFalse(logs.unused());
        logs.release();
        assertFalse(logs.unused(), "an in-flight handler still owns the capture");
        assertFalse(snapshot.isDone());

        logs.complete("aGFuZGxlcgo=");

        assertArrayEquals("handler\n".getBytes(StandardCharsets.UTF_8), Base64.getDecoder().decode(snapshot.join()));
        assertTrue(logs.unused());
    }

    @Test
    void resetCancelsPendingLogWaiters() {
        DurableInvocationLogs logs = new DurableInvocationLogs();
        logs.beginInvocation();
        CompletableFuture<String> snapshot = logs.snapshot();

        logs.cancel();

        assertTrue(snapshot.isCompletedExceptionally());
        assertTrue(logs.unused());
    }
}
