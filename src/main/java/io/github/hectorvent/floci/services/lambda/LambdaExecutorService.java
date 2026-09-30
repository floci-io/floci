package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Resettable;
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
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Orchestrates Lambda function invocations.
 * Handles RequestResponse (sync), Event (async fire-and-forget), and DryRun modes.
 *
 * <p>An Event invocation answers 202 straight away and, once the function has finished on the
 * pool, hands its result to {@link AsyncInvokeDestinationRouter}, which delivers it to the
 * function's configured destination when it has one. A failed attempt is retried after the
 * configured delay, as AWS waits one minute and then two.
 */
@ApplicationScoped
public class LambdaExecutorService implements Resettable {

    private static final Logger LOG = Logger.getLogger(LambdaExecutorService.class);
    /** Extra time for a newly started runtime to request its first invocation. */
    private static final int RUNTIME_DISPATCH_GRACE_SECONDS = 2;
    /** How long a retry that found the function's concurrency in use waits before asking again. */
    private static final long THROTTLED_RETRY_POLL_MS = 1000;
    /**
     * Retries run on virtual threads rather than the pool: once the pool's queue is full its
     * caller-runs fallback would run a whole attempt on the JDK's shared delay thread, which also
     * fires the invocation timeouts that attempt may be waiting on.
     */
    private static final Executor RETRY_THREADS = task -> Thread.ofVirtual().name("lambda-async-retry").start(task);

    private final WarmPool warmPool;
    private final ObjectMapper objectMapper;
    private final LambdaConcurrencyLimiter concurrencyLimiter;
    /** Null in the constructor tests use, which exercise execution rather than delivery. */
    private final AsyncInvokeDestinationRouter destinationRouter;
    private final Instance<LambdaService> lambdaServiceInstance;
    private final LambdaService directLambdaService;
    private final Clock clock;
    private final long asyncRetryDelayMs;
    /** Moved on by a reset so that retries still waiting from before it are dropped. */
    private final AtomicLong generation = new AtomicLong();
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
                                 Clock clock,
                                 EmulatorConfig config) {
        this(warmPool, objectMapper, concurrencyLimiter, destinationRouter, lambdaServiceInstance, null, clock,
                Duration.ofSeconds(config.services().lambda().asyncRetryDelaySeconds()));
    }

    LambdaExecutorService(WarmPool warmPool,
                          ObjectMapper objectMapper,
                          LambdaConcurrencyLimiter concurrencyLimiter,
                          AsyncInvokeDestinationRouter destinationRouter,
                          LambdaService directLambdaService) {
        this(warmPool, objectMapper, concurrencyLimiter, destinationRouter, null, directLambdaService,
                Clock.systemUTC(), Duration.ZERO);
    }

    LambdaExecutorService(WarmPool warmPool,
                          ObjectMapper objectMapper,
                          LambdaConcurrencyLimiter concurrencyLimiter,
                          AsyncInvokeDestinationRouter destinationRouter,
                          LambdaService directLambdaService,
                          Clock clock,
                          Duration asyncRetryDelay) {
        this(warmPool, objectMapper, concurrencyLimiter, destinationRouter, null, directLambdaService, clock,
                asyncRetryDelay);
    }

    private LambdaExecutorService(WarmPool warmPool,
                                  ObjectMapper objectMapper,
                                  LambdaConcurrencyLimiter concurrencyLimiter,
                                  AsyncInvokeDestinationRouter destinationRouter,
                                  Instance<LambdaService> lambdaServiceInstance,
                                  LambdaService directLambdaService,
                                  Clock clock,
                                  Duration asyncRetryDelay) {
        this.warmPool = warmPool;
        this.objectMapper = objectMapper;
        this.concurrencyLimiter = concurrencyLimiter;
        this.destinationRouter = destinationRouter;
        this.lambdaServiceInstance = lambdaServiceInstance;
        this.directLambdaService = directLambdaService;
        this.clock = clock;
        this.asyncRetryDelayMs = asyncRetryDelay.toMillis();
    }

    /** Package-private constructor for testing without CDI, leaving destinations unrouted. */
    LambdaExecutorService(WarmPool warmPool,
                          ObjectMapper objectMapper,
                          LambdaConcurrencyLimiter concurrencyLimiter) {
        this(warmPool, objectMapper, concurrencyLimiter, null, (Instance<LambdaService>) null, null,
                Clock.systemUTC(), Duration.ZERO);
    }

    LambdaExecutorService(WarmPool warmPool,
                          ObjectMapper objectMapper,
                          LambdaConcurrencyLimiter concurrencyLimiter,
                          AsyncInvokeDestinationRouter destinationRouter) {
        this(warmPool, objectMapper, concurrencyLimiter, destinationRouter,
                (Instance<LambdaService>) null, null, Clock.systemUTC(), Duration.ZERO);
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

            AsyncEvent event = new AsyncEvent(fn, payload, requestId, chainDepth, invokedQualifier,
                    maxRetries, clock.millis() + maxEventAgeSeconds * 1000L, generation.get());
            try {
                asyncExecutor.submit(() -> attempt(event, 1, null, permit));
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

    /**
     * Runs one attempt of an asynchronous event and either routes the outcome or schedules the next
     * attempt. Retry n waits n times the configured delay, never past the event's maximum age. The
     * concurrency permit covers only the attempt; {@code held} is the one the invoke itself took.
     */
    private void attempt(AsyncEvent event, int attempt, InvokeResult previous,
                         LambdaConcurrencyLimiter.Permit held) {
        if (event.generation() != generation.get() || clock.millis() >= event.expiresAtMs()) {
            if (held != null) {
                held.close();
            }
            if (event.generation() == generation.get()) {
                route(event, previous, attempt - 1);
            }
            return;
        }
        LambdaConcurrencyLimiter.Permit permit = held;
        if (permit == null) {
            try {
                permit = concurrencyLimiter.acquire(event.fn());
            } catch (AwsException throttled) {
                // ponytail: AWS backs a throttled retry off exponentially, up to five minutes; one
                // fixed poll is enough locally, and the event still expires on time.
                schedule(event, THROTTLED_RETRY_POLL_MS, () -> attempt(event, attempt, previous, null));
                return;
            }
        }
        InvokeResult result;
        LambdaConcurrencyLimiter.Permit acquired = permit;
        try (acquired) {
            result = executeSync(event.fn(), event.payload(), event.requestId());
        } catch (RuntimeException e) {
            LOG.warnv("Error in async Lambda execution for {0}: {1}", event.fn().getFunctionName(), e.getMessage());
            result = new InvokeResult(500, "Unhandled",
                    buildErrorPayload("Error executing Lambda: " + e.getMessage(), "Lambda.UnknownError"),
                    null, event.requestId());
        }
        if ((result.getFunctionError() == null && result.getStatusCode() < 300) || attempt > event.maxRetries()) {
            route(event, result, attempt);
            return;
        }
        InvokeResult failed = result;
        schedule(event, asyncRetryDelayMs * attempt, () -> attempt(event, attempt + 1, failed, null));
    }

    private void schedule(AsyncEvent event, long delayMs, Runnable next) {
        long waitMs = Math.max(0, Math.min(delayMs, event.expiresAtMs() - clock.millis()));
        CompletableFuture.delayedExecutor(waitMs, TimeUnit.MILLISECONDS, RETRY_THREADS).execute(next);
    }

    private void route(AsyncEvent event, InvokeResult result, int attempts) {
        if (destinationRouter == null) {
            return;
        }
        InvokeResult outcome = result;
        if (outcome == null) {
            // This Floci-only placeholder covers expiry before any attempt; AWS documents no payload.
            outcome = new InvokeResult(200, "Unhandled",
                    buildErrorPayload("Event age exceeded", "EventAgeExceeded"), null, event.requestId());
        }
        destinationRouter.route(event.fn(), event.payload(), outcome, attempts,
                event.chainDepth(), event.invokedQualifier());
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
                    new CompletableFuture<>());

            handle.getRuntimeApiServer().enqueue(invocation);

            CompletableFuture.anyOf(
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
        generation.incrementAndGet();
        asyncExecutor.shutdownNow();
    }

    @Override
    public void clear() {
        generation.incrementAndGet();
    }

    private record AsyncEvent(LambdaFunction fn, byte[] payload, String requestId, int chainDepth,
                              String invokedQualifier, int maxRetries, long expiresAtMs, long generation) {}

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
