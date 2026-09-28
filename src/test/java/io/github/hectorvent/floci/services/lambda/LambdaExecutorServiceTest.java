package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.lambda.launcher.ContainerHandle;
import io.github.hectorvent.floci.services.lambda.model.ContainerState;
import io.github.hectorvent.floci.services.lambda.model.FunctionEventInvokeConfig;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.model.PendingInvocation;
import io.github.hectorvent.floci.services.lambda.runtime.RuntimeApiServer;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
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
    void asyncInvocationPastTheChainBound_isDroppedWithoutRunningTheFunction() {
        // AWS stops the next invocation in a chain of requests once the same originating event has
        // caused about this many, which is what keeps a destination cycle from running forever.
        InvokeResult result = executor.invoke(fn, "{}".getBytes(), InvocationType.Event,
                LambdaInvocationChain.MAX_DEPTH);

        assertEquals(202, result.getStatusCode());
        assertNotNull(result.getRequestId());
        verify(concurrencyLimiter, never()).acquire(any());
        verify(warmPool, never()).acquire(any());
    }

    @Test
    void asyncInvocationShortOfTheChainBound_stillRuns() {
        executor.invoke(fn, "{}".getBytes(), InvocationType.Event, LambdaInvocationChain.MAX_DEPTH - 1);

        verify(warmPool, timeout(5000).atLeastOnce()).acquire(fn);
    }

    @Test
    void asyncInvocationPassesInvokedAliasToDestinationRouter() {
        AsyncInvokeDestinationRouter router = mock(AsyncInvokeDestinationRouter.class);
        executor.shutdown();
        executor = new LambdaExecutorService(warmPool, new ObjectMapper(), concurrencyLimiter, router);
        RuntimeApiServer rtas = mock(RuntimeApiServer.class);
        ContainerHandle handle = new ContainerHandle("cid-alias", "test-fn", rtas, ContainerState.WARM);
        when(warmPool.acquire(fn)).thenReturn(handle);
        doAnswer(inv -> {
            PendingInvocation invocation = inv.getArgument(0);
            invocation.getResultFuture().complete(
                    new InvokeResult(200, null, "{}".getBytes(), null, invocation.getRequestId()));
            return invocation.getResultFuture();
        }).when(rtas).enqueue(any(PendingInvocation.class));

        byte[] payload = "{}".getBytes();
        executor.invoke(fn, payload, InvocationType.Event, 0, "prod");

        verify(router, timeout(5000)).route(eq(fn), eq(payload), any(InvokeResult.class), eq(1), eq(0), eq("prod"));
    }

    @Test
    void syncInvocationPastTheChainBound_isNotAffected() {
        // Only the asynchronous path can be re-entered by a destination chain, and refusing a
        // RequestResponse invoke would need an error shape AWS does not define here.
        RuntimeApiServer rtas = mock(RuntimeApiServer.class);
        ContainerHandle handle = new ContainerHandle("cid-bound", "test-fn", rtas, ContainerState.WARM);
        when(warmPool.acquire(any())).thenReturn(handle);
        when(rtas.enqueue(any())).thenReturn(new CompletableFuture<>());

        executor.invoke(fn, "{}".getBytes(), InvocationType.RequestResponse, LambdaInvocationChain.MAX_DEPTH);

        verify(warmPool).acquire(fn);
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
    void resultAfterConfiguredDeadline_timesOutAndDestroysHandle() {
        RuntimeApiServer rtas = mock(RuntimeApiServer.class);
        ContainerHandle handle = new ContainerHandle("cid-late", "test-fn", rtas, ContainerState.WARM);

        when(warmPool.acquire(any())).thenReturn(handle);
        doAnswer(inv -> {
            PendingInvocation pi = inv.getArgument(0);
            pi.prepareForDispatch();
            pi.markDispatched();
            CompletableFuture.delayedExecutor(1200, TimeUnit.MILLISECONDS)
                    .execute(() -> pi.getResultFuture().complete(
                            new InvokeResult(200, null, "{\"ok\":true}".getBytes(), null, "req-late")));
            return pi.getResultFuture();
        }).when(rtas).enqueue(any(PendingInvocation.class));

        InvokeResult result = executor.invoke(fn, "{}".getBytes(), InvocationType.RequestResponse);

        verify(warmPool).destroyHandle(handle);
        verify(warmPool, never()).release(handle);
        assertEquals("Unhandled", result.getFunctionError());
        assertTrue(new String(result.getPayload()).contains("Function.TimedOut"));
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

    @Test
    void interruptedInvocation_destroysHandle_doesNotRelease() throws Exception {
        fn.setTimeout(30);
        RuntimeApiServer rtas = mock(RuntimeApiServer.class);
        ContainerHandle handle = new ContainerHandle("cid-int", "test-fn", rtas, ContainerState.WARM);

        when(warmPool.acquire(any())).thenReturn(handle);
        CountDownLatch enqueued = new CountDownLatch(1);
        doAnswer(inv -> {
            PendingInvocation pi = inv.getArgument(0);
            enqueued.countDown();
            return pi.getResultFuture();
        }).when(rtas).enqueue(any(PendingInvocation.class));

        AtomicReference<InvokeResult> resultRef = new AtomicReference<>();
        Thread worker = new Thread(() -> resultRef.set(
                executor.invoke(fn, "{}".getBytes(), InvocationType.RequestResponse)));
        worker.start();

        assertTrue(enqueued.await(5, TimeUnit.SECONDS), "enqueue never called");
        worker.interrupt();
        worker.join(5_000);

        InvokeResult result = resultRef.get();
        assertNotNull(result, "invoke did not return");
        verify(warmPool).destroyHandle(handle);
        verify(warmPool, never()).release(handle);
        assertEquals(200, result.getStatusCode());
        assertEquals("Unhandled", result.getFunctionError());
        assertTrue(new String(result.getPayload()).contains("Interrupted"));
    }

    @Test
    void eventInvocation_answers202AndRoutesTheResultToDestinations() throws Exception {
        AsyncInvokeDestinationRouter router = mock(AsyncInvokeDestinationRouter.class);
        LambdaExecutorService routingExecutor =
                new LambdaExecutorService(warmPool, new ObjectMapper(), concurrencyLimiter, router);

        RuntimeApiServer rtas = mock(RuntimeApiServer.class);
        ContainerHandle handle = new ContainerHandle("cid-async", "test-fn", rtas, ContainerState.WARM);
        when(warmPool.acquire(any())).thenReturn(handle);
        InvokeResult expected = new InvokeResult(200, null, "{\"ok\":true}".getBytes(), null, "req-async");
        doAnswer(inv -> {
            PendingInvocation pi = inv.getArgument(0);
            pi.getResultFuture().complete(expected);
            return pi.getResultFuture();
        }).when(rtas).enqueue(any(PendingInvocation.class));

        CountDownLatch routed = new CountDownLatch(1);
        doAnswer(inv -> {
            routed.countDown();
            return null;
        }).when(router).route(any(), any(), any(), anyInt(), anyInt(), isNull());

        byte[] payload = "{}".getBytes();
        InvokeResult result = routingExecutor.invoke(fn, payload, InvocationType.Event);

        assertEquals(202, result.getStatusCode());
        assertTrue(routed.await(5, TimeUnit.SECONDS), "destination routing never ran");
        verify(router).route(fn, payload, expected, 1, 0, null);
    }

    @Test
    void eventInvocation_retriesFailedInvocationUpToMaximumRetryAttempts() throws Exception {
        AsyncInvokeDestinationRouter router = mock(AsyncInvokeDestinationRouter.class);
        LambdaService lambdaService = mock(LambdaService.class);
        FunctionEventInvokeConfig config = new FunctionEventInvokeConfig();
        config.setMaximumRetryAttempts(2);
        config.setMaximumEventAgeInSeconds(21600);
        when(lambdaService.findEventInvokeConfig(fn, null)).thenReturn(Optional.of(config));

        LambdaExecutorService retryExecutor =
                new LambdaExecutorService(warmPool, new ObjectMapper(), concurrencyLimiter, router, lambdaService);
        RuntimeApiServer rtas = mock(RuntimeApiServer.class);
        ContainerHandle handle = new ContainerHandle("cid-retry", "test-fn", rtas, ContainerState.WARM);
        when(warmPool.acquire(any())).thenReturn(handle);
        InvokeResult failureResult = new InvokeResult(200, "Unhandled",
                "{\"errorMessage\":\"fails\"}".getBytes(), null, "req-retry");
        doAnswer(invocation -> {
            PendingInvocation pendingInvocation = invocation.getArgument(0);
            pendingInvocation.getResultFuture().complete(failureResult);
            return pendingInvocation.getResultFuture();
        }).when(rtas).enqueue(any(PendingInvocation.class));

        CountDownLatch routed = new CountDownLatch(1);
        doAnswer(invocation -> {
            routed.countDown();
            return null;
        }).when(router).route(any(), any(), any(), anyInt(), anyInt(), any());

        byte[] payload = "{}".getBytes();
        InvokeResult result = retryExecutor.invoke(fn, payload, InvocationType.Event);

        assertEquals(202, result.getStatusCode());
        assertTrue(routed.await(5, TimeUnit.SECONDS), "destination routing never ran");
        verify(rtas, times(3)).enqueue(any(PendingInvocation.class));
        verify(router).route(eq(fn), eq(payload), eq(failureResult), eq(3), eq(0), isNull());
    }

    @Test
    void eventInvocation_stopsRetryingWhenMaxEventAgeExceeded() throws Exception {
        AsyncInvokeDestinationRouter router = mock(AsyncInvokeDestinationRouter.class);
        LambdaService lambdaService = mock(LambdaService.class);
        FunctionEventInvokeConfig config = new FunctionEventInvokeConfig();
        config.setMaximumRetryAttempts(2);
        config.setMaximumEventAgeInSeconds(0);
        when(lambdaService.findEventInvokeConfig(fn, null)).thenReturn(Optional.of(config));
        LambdaExecutorService retryExecutor =
                new LambdaExecutorService(warmPool, new ObjectMapper(), concurrencyLimiter, router, lambdaService);

        CountDownLatch routed = new CountDownLatch(1);
        doAnswer(invocation -> {
            routed.countDown();
            return null;
        }).when(router).route(any(), any(), any(), anyInt(), anyInt(), any());

        byte[] payload = "{}".getBytes();
        InvokeResult result = retryExecutor.invoke(fn, payload, InvocationType.Event);

        assertEquals(202, result.getStatusCode());
        assertTrue(routed.await(5, TimeUnit.SECONDS), "destination routing never ran");
        verify(warmPool, never()).acquire(any());
        ArgumentCaptor<InvokeResult> resultCaptor = ArgumentCaptor.forClass(InvokeResult.class);
        verify(router).route(eq(fn), eq(payload), resultCaptor.capture(), eq(0), eq(0), isNull());
        assertTrue(new String(resultCaptor.getValue().getPayload()).contains("EventAgeExceeded"),
                "an event that expires before execution needs an expiration result");
    }

    @Test
    void eventInvocation_preservesLastAttemptWhenAgeExpiresBetweenAttempts() throws Exception {
        AsyncInvokeDestinationRouter router = mock(AsyncInvokeDestinationRouter.class);
        LambdaService lambdaService = mock(LambdaService.class);
        FunctionEventInvokeConfig config = new FunctionEventInvokeConfig();
        config.setMaximumRetryAttempts(2);
        config.setMaximumEventAgeInSeconds(1);
        when(lambdaService.findEventInvokeConfig(fn, null)).thenReturn(Optional.of(config));
        MutableClock clock = new MutableClock();
        LambdaExecutorService retryExecutor =
                new LambdaExecutorService(warmPool, new ObjectMapper(), concurrencyLimiter, router, lambdaService, clock);

        RuntimeApiServer rtas = mock(RuntimeApiServer.class);
        ContainerHandle handle = new ContainerHandle("cid-age-after-failure", "test-fn", rtas, ContainerState.WARM);
        when(warmPool.acquire(any())).thenReturn(handle);
        InvokeResult failedAttempt = new InvokeResult(200, "Unhandled",
                "{\"errorMessage\":\"fails\"}".getBytes(), null, "req-age");
        doAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(1));
            PendingInvocation pendingInvocation = invocation.getArgument(0);
            pendingInvocation.getResultFuture().complete(failedAttempt);
            return pendingInvocation.getResultFuture();
        }).when(rtas).enqueue(any(PendingInvocation.class));

        CountDownLatch routed = new CountDownLatch(1);
        doAnswer(invocation -> {
            routed.countDown();
            return null;
        }).when(router).route(any(), any(), any(), anyInt(), anyInt(), any());

        byte[] payload = "{}".getBytes();
        InvokeResult result = retryExecutor.invoke(fn, payload, InvocationType.Event);

        assertEquals(202, result.getStatusCode());
        assertTrue(routed.await(5, TimeUnit.SECONDS), "destination routing never ran");
        ArgumentCaptor<InvokeResult> resultCaptor = ArgumentCaptor.forClass(InvokeResult.class);
        verify(router).route(eq(fn), eq(payload), resultCaptor.capture(), eq(1), eq(0), isNull());
        assertSame(failedAttempt, resultCaptor.getValue(),
                "age expiration should preserve the last invocation result");
    }

    @Test
    void exceptionDuringInvocation_destroysHandle_doesNotRelease() {
        RuntimeApiServer rtas = mock(RuntimeApiServer.class);
        ContainerHandle handle = new ContainerHandle("cid-exc", "test-fn", rtas, ContainerState.WARM);

        when(warmPool.acquire(any())).thenReturn(handle);
        doAnswer(inv -> {
            PendingInvocation pi = inv.getArgument(0);
            pi.getResultFuture().completeExceptionally(new RuntimeException("runtime crash"));
            return pi.getResultFuture();
        }).when(rtas).enqueue(any(PendingInvocation.class));

        InvokeResult result = executor.invoke(fn, "{}".getBytes(), InvocationType.RequestResponse);

        verify(warmPool).destroyHandle(handle);
        verify(warmPool, never()).release(handle);
        assertEquals(200, result.getStatusCode());
        assertEquals("Unhandled", result.getFunctionError());
        assertTrue(new String(result.getPayload()).contains("InvocationError"));
    }
}
