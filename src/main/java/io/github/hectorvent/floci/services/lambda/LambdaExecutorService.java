package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.lambda.launcher.ContainerHandle;
import io.github.hectorvent.floci.services.lambda.model.FunctionEventInvokeConfig;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.model.PendingInvocation;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
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
 *
 * <p>An Event invocation answers 202 straight away and, once the function has finished on the
 * pool, hands its result to {@link AsyncInvokeDestinationRouter}, which delivers it to the
 * function's configured destination when it has one.
 */
@ApplicationScoped
public class LambdaExecutorService {

    private static final Logger LOG = Logger.getLogger(LambdaExecutorService.class);
    /** Extra time for a newly started runtime to request its first invocation. */
    private static final int RUNTIME_DISPATCH_GRACE_SECONDS = 2;

    private final WarmPool warmPool;
    private final ObjectMapper objectMapper;
    private final LambdaConcurrencyLimiter concurrencyLimiter;
    /** Null in the constructor tests use, which exercise execution rather than delivery. */
    private final AsyncInvokeDestinationRouter destinationRouter;
    private final Instance<LambdaService> lambdaServiceInstance;
    private final LambdaService directLambdaService;
    private final Clock clock;
    private final ExecutorService asyncExecutor = new ThreadPoolExecutor(
            Math.max(4, Runtime.getRuntime().availableProcessors() * 2),
            Math.max(8, Runtime.getRuntime().availableProcessors() * 4),
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(500),
            new ThreadPoolExecutor.CallerRunsPolicy());

    @Inject
    public LambdaExecutorService(WarmPool warmPool,
                                 ObjectMapper objectMapper,
                                 LambdaConcurrencyLimiter concurrencyLimiter,
                                 AsyncInvokeDestinationRouter destinationRouter,
                                 Instance<LambdaService> lambdaServiceInstance,
                                 Clock clock) {
        this(warmPool, objectMapper, concurrencyLimiter, destinationRouter, lambdaServiceInstance, null, clock);
    }

    LambdaExecutorService(WarmPool warmPool,
                          ObjectMapper objectMapper,
                          LambdaConcurrencyLimiter concurrencyLimiter,
                          AsyncInvokeDestinationRouter destinationRouter,
                          LambdaService directLambdaService) {
        this(warmPool, objectMapper, concurrencyLimiter, destinationRouter, null, directLambdaService,
                Clock.systemUTC());
    }

    LambdaExecutorService(WarmPool warmPool,
                          ObjectMapper objectMapper,
                          LambdaConcurrencyLimiter concurrencyLimiter,
                          AsyncInvokeDestinationRouter destinationRouter,
                          LambdaService directLambdaService,
                          Clock clock) {
        this(warmPool, objectMapper, concurrencyLimiter, destinationRouter, null, directLambdaService, clock);
    }

    private LambdaExecutorService(WarmPool warmPool,
                                  ObjectMapper objectMapper,
                                  LambdaConcurrencyLimiter concurrencyLimiter,
                                  AsyncInvokeDestinationRouter destinationRouter,
                                  Instance<LambdaService> lambdaServiceInstance,
                                  LambdaService directLambdaService,
                                  Clock clock) {
        this.warmPool = warmPool;
        this.objectMapper = objectMapper;
        this.concurrencyLimiter = concurrencyLimiter;
        this.destinationRouter = destinationRouter;
        this.lambdaServiceInstance = lambdaServiceInstance;
        this.directLambdaService = directLambdaService;
        this.clock = clock;
    }

    /** Package-private constructor for testing without CDI, leaving destinations unrouted. */
    LambdaExecutorService(WarmPool warmPool,
                          ObjectMapper objectMapper,
                          LambdaConcurrencyLimiter concurrencyLimiter) {
        this(warmPool, objectMapper, concurrencyLimiter, null, (Instance<LambdaService>) null, null,
                Clock.systemUTC());
    }

    LambdaExecutorService(WarmPool warmPool,
                          ObjectMapper objectMapper,
                          LambdaConcurrencyLimiter concurrencyLimiter,
                          AsyncInvokeDestinationRouter destinationRouter) {
        this(warmPool, objectMapper, concurrencyLimiter, destinationRouter,
                (Instance<LambdaService>) null, null, Clock.systemUTC());
    }

    public InvokeResult invoke(LambdaFunction fn, byte[] payload, InvocationType type) {
        return invoke(fn, payload, type, 0);
    }

    /**
     * Invokes {@code fn}, carrying the number of invocations that the same originating event has
     * already caused. A direct invoke starts at zero; each destination delivery adds one, whether
     * it names the next function outright or reaches it back through SNS or EventBridge.
     *
     * <p>An asynchronous invocation past the bound is dropped rather than run, which is how AWS
     * breaks a recursive loop: the event goes no further and the caller, which was answered with
     * 202 long before, sees nothing. Only the asynchronous path is guarded because it is the only
     * one a destination chain can re-enter through.
     */
    InvokeResult invoke(LambdaFunction fn, byte[] payload, InvocationType type, int chainDepth) {
        return invoke(fn, payload, type, chainDepth, null);
    }

    InvokeResult invoke(LambdaFunction fn, byte[] payload, InvocationType type, int chainDepth,
                        String invokedQualifier) {
        String requestId = UUID.randomUUID().toString();

        if (type == InvocationType.DryRun) {
            return new InvokeResult(204, null, new byte[0], null, requestId);
        }

        if (type == InvocationType.Event && LambdaInvocationChain.exhausted(chainDepth)) {
            LOG.warnv("Dropping the asynchronous invocation of {0}: the same event already caused "
                    + "{1} invocations, so something in the chain is feeding itself",
                    fn.getFunctionArn(), chainDepth);
            return new InvokeResult(202, null, new byte[0], null, requestId);
        }

        LambdaConcurrencyLimiter.Permit permit = concurrencyLimiter.acquire(fn);

        if (type == InvocationType.Event) {
            LambdaService lambdaService = resolveLambdaService();
            FunctionEventInvokeConfig eventInvokeConfig = null;
            if (lambdaService != null) {
                try {
                    eventInvokeConfig = lambdaService.findEventInvokeConfig(fn, invokedQualifier).orElse(null);
                } catch (Exception e) {
                    LOG.warnv("Could not read event invoke configuration for {0}: {1}",
                            fn.getFunctionArn(), e.getMessage());
                }
            }

            int maxRetries = eventInvokeConfig != null && eventInvokeConfig.getMaximumRetryAttempts() != null
                    ? eventInvokeConfig.getMaximumRetryAttempts() : 2;
            int maxEventAgeSeconds = eventInvokeConfig != null
                    && eventInvokeConfig.getMaximumEventAgeInSeconds() != null
                    ? eventInvokeConfig.getMaximumEventAgeInSeconds() : 21600;
            long submitTimeMs = clock.millis();

            try {
                asyncExecutor.submit(() -> {
                    int attempt = 0;
                    InvokeResult asyncResult = null;
                    try {
                        while (attempt <= maxRetries) {
                            long elapsedSeconds = (clock.millis() - submitTimeMs) / 1000;
                            if (elapsedSeconds >= maxEventAgeSeconds) {
                                break;
                            }

                            attempt++;
                            asyncResult = executeSync(fn, payload, requestId);
                            if (asyncResult.getFunctionError() == null && asyncResult.getStatusCode() < 300) {
                                break;
                            }
                        }
                    } catch (Exception e) {
                        LOG.warnv("Error in async Lambda execution for {0}: {1}", fn.getFunctionName(), e.getMessage());
                        if (asyncResult == null) {
                            asyncResult = new InvokeResult(500, "Unhandled",
                                    buildErrorPayload("Error executing Lambda: " + e.getMessage(), "Lambda.UnknownError"),
                                    null, requestId);
                        }
                    } finally {
                        permit.close();
                    }
                    if (destinationRouter != null) {
                        if (asyncResult == null) {
                            // This Floci-only placeholder covers expiry before any attempt; AWS documents no payload.
                            asyncResult = new InvokeResult(200, "Unhandled",
                                    buildErrorPayload("Event age exceeded", "EventAgeExceeded"),
                                    null, requestId);
                        }
                        destinationRouter.route(fn, payload, asyncResult, attempt,
                                chainDepth, invokedQualifier);
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

    private LambdaService resolveLambdaService() {
        if (directLambdaService != null) {
            return directLambdaService;
        }
        if (lambdaServiceInstance != null && lambdaServiceInstance.isResolvable()) {
            return lambdaServiceInstance.get();
        }
        return null;
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
