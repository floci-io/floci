package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.lambda.launcher.ContainerHandle;
import io.github.hectorvent.floci.services.lambda.model.ContainerState;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.model.PendingInvocation;
import io.github.hectorvent.floci.services.lambda.runtime.RuntimeApiServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LambdaExecutorServiceTest {

    @Mock WarmPool warmPool;
    @Mock LambdaConcurrencyLimiter concurrencyLimiter;

    private LambdaExecutorService executor;
    private LambdaFunction fn;

    @BeforeEach
    void setUp() {
        executor = new LambdaExecutorService(warmPool, new ObjectMapper(), concurrencyLimiter);

        fn = new LambdaFunction();
        fn.setFunctionName("test-fn");
        fn.setFunctionArn("arn:aws:lambda:us-east-1:000000000000:function:test-fn");
        fn.setTimeout(1);

        when(concurrencyLimiter.acquire(any())).thenReturn(() -> {});
    }

    @Test
    void timeoutInvocation_destroysHandle_doesNotRelease() {
        RuntimeApiServer rtas = mock(RuntimeApiServer.class);
        ContainerHandle handle = new ContainerHandle("cid-1", "test-fn", rtas, ContainerState.WARM);

        when(warmPool.acquire(any())).thenReturn(handle);
        when(rtas.enqueue(any())).thenReturn(new CompletableFuture<>());

        InvokeResult result = executor.invoke(fn, "{}".getBytes(), InvocationType.RequestResponse);

        verify(warmPool).destroyHandle(handle);
        verify(warmPool, never()).release(handle);
        assertEquals(200, result.getStatusCode());
        assertEquals("Unhandled", result.getFunctionError());
        String payload = new String(result.getPayload());
        assertTrue(payload.contains("Function.TimedOut"), "payload should contain error type");
        assertTrue(payload.contains("1 seconds"), "payload should contain timeout value");
    }

    @Test
    void successfulInvocation_releasesHandle_doesNotDestroy() {
        RuntimeApiServer rtas = mock(RuntimeApiServer.class);
        ContainerHandle handle = new ContainerHandle("cid-2", "test-fn", rtas, ContainerState.WARM);

        when(warmPool.acquire(any())).thenReturn(handle);
        InvokeResult expected = new InvokeResult(200, null, "{\"ok\":true}".getBytes(), null, "req-1");
        doAnswer(inv -> {
            PendingInvocation pi = inv.getArgument(0);
            pi.getResultFuture().complete(expected);
            return pi.getResultFuture();
        }).when(rtas).enqueue(any(PendingInvocation.class));

        InvokeResult result = executor.invoke(fn, "{}".getBytes(), InvocationType.RequestResponse);

        verify(warmPool).release(handle);
        verify(warmPool, never()).destroyHandle(handle);
        assertEquals(200, result.getStatusCode());
        assertNull(result.getFunctionError());
    }

    @Test
    void timeoutResponse_containsCorrectErrorPayload() {
        fn.setTimeout(2);
        RuntimeApiServer rtas = mock(RuntimeApiServer.class);
        ContainerHandle handle = new ContainerHandle("cid-3", "test-fn", rtas, ContainerState.WARM);

        when(warmPool.acquire(any())).thenReturn(handle);
        when(rtas.enqueue(any())).thenReturn(new CompletableFuture<>());

        InvokeResult result = executor.invoke(fn, "{}".getBytes(), InvocationType.RequestResponse);

        assertNotNull(result.getPayload());
        String payload = new String(result.getPayload());
        assertTrue(payload.contains("\"errorType\":\"Function.TimedOut\""));
        assertTrue(payload.contains("Task timed out after 2 seconds"));
        assertNotNull(result.getRequestId());
    }

    @Test
    void dryRunInvocation_doesNotAcquireContainer() {
        InvokeResult result = executor.invoke(fn, "{}".getBytes(), InvocationType.DryRun);

        verify(warmPool, never()).acquire(any());
        verify(warmPool, never()).release(any());
        verify(warmPool, never()).destroyHandle(any());
        assertEquals(204, result.getStatusCode());
    }
}
