package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.lambda.launcher.ContainerHandle;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.model.PendingInvocation;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Orchestrates Lambda function invocations.
 * Handles RequestResponse (sync), Event (async fire-and-forget), and DryRun modes.
 */
@ApplicationScoped
public class LambdaExecutorService {

    private static final Logger LOG = Logger.getLogger(LambdaExecutorService.class);
    /** Extra time for a newly started runtime to request its first invocation. */
    private static final int RUNTIME_DISPATCH_GRACE_SECONDS = 2;

    private final WarmPool warmPool;
    private final ObjectMapper objectMapper;
    private final LambdaConcurrencyLimiter concurrencyLimiter;
    private final ExecutorService asyncExecutor = new ThreadPoolExecutor(
            Math.max(4, Runtime.getRuntime().availableProcessors() * 2),
            Math.max(8, Runtime.getRuntime().availableProcessors() * 4),
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(500),
            new ThreadPoolExecutor.CallerRunsPolicy());

    @Inject
    public LambdaExecutorService(WarmPool warmPool,
                                 ObjectMapper objectMapper,
                                 LambdaConcurrencyLimiter concurrencyLimiter) {
        this.warmPool = warmPool;
        this.objectMapper = objectMapper;
        this.concurrencyLimiter = concurrencyLimiter;
    }

    public InvokeResult invoke(LambdaFunction fn, byte[] payload, InvocationType type) {
        String requestId = UUID.randomUUID().toString();

        if (type == InvocationType.DryRun) {
            return new InvokeResult(204, null, new byte[0], null, requestId);
        }

        LambdaConcurrencyLimiter.Permit permit = concurrencyLimiter.acquire(fn);

        if (type == InvocationType.Event) {
            try {
                asyncExecutor.submit(() -> {
                    try {
                        executeSync(fn, payload, requestId);
                    } finally {
                        permit.close();
                    }
                });
            } catch (RuntimeException e) {
                permit.close();
                throw e;
            }
            return new InvokeResult(202, null, new byte[0], null, requestId);
        }

        try {
            return executeSync(fn, payload, requestId);
        } finally {
            permit.close();
        }
    }

    private InvokeResult executeSync(LambdaFunction fn, byte[] payload, String requestId) {
        ContainerHandle handle;
        try {
            handle = warmPool.acquire(fn);
        } catch (Exception e) {
            LOG.warnv("Failed to acquire container for function {0}: {1}", fn.getFunctionName(), e.getMessage());
            return new InvokeResult(200, "Unhandled",
                    buildErrorPayload("Failed to start Lambda container: " + e.getMessage(), "Lambda.InitError"),
                    null, requestId);
        }
        try {
            long deadlineMs = System.currentTimeMillis() + (long) fn.getTimeout() * 1000;
            PendingInvocation invocation = new PendingInvocation(
                    requestId, payload, deadlineMs, fn.getFunctionArn(),
                    new java.util.concurrent.CompletableFuture<>());

            handle.getRuntimeApiServer().enqueue(invocation);

            java.util.concurrent.CompletableFuture.anyOf(
                            invocation.getDispatchedFuture(), invocation.getResultFuture())
                    .get(fn.getTimeout() + RUNTIME_DISPATCH_GRACE_SECONDS, TimeUnit.SECONDS);
            InvokeResult result = invocation.getResultFuture().get();

            warmPool.release(handle);
            return result;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            warmPool.destroyHandle(handle);
            return new InvokeResult(200, "Unhandled", buildErrorPayload("Invocation interrupted", "Interrupted"), null, requestId);
        } catch (Exception e) {
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            if (cause instanceof TimeoutException) {
                LOG.warnv("Function {0} timed out after {1}s", fn.getFunctionName(), fn.getTimeout());
                warmPool.destroyHandle(handle);
                return new InvokeResult(200, "Unhandled",
                        buildErrorPayload("Task timed out after " + fn.getTimeout() + " seconds", "Function.TimedOut"),
                        null, requestId);
            }
            LOG.warnv("Invocation error for function {0}: {1}", fn.getFunctionName(), cause.getMessage());
            warmPool.destroyHandle(handle);
            return new InvokeResult(200, "Unhandled",
                    buildErrorPayload(cause.getMessage(), "InvocationError"), null, requestId);
        }
    }

    @PreDestroy
    public void shutdown() {
        asyncExecutor.shutdownNow();
    }

    private byte[] buildErrorPayload(String message, String errorType) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("errorMessage", message);
            node.put("errorType", errorType);
            return objectMapper.writeValueAsBytes(node);
        } catch (Exception e) {
            return ("{\"errorMessage\":\"unknown\",\"errorType\":\"" + errorType + "\"}").getBytes();
        }
    }
}
