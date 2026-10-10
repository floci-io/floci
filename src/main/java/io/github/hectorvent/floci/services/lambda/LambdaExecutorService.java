package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.lambda.launcher.ContainerHandle;
import io.github.hectorvent.floci.services.lambda.model.FunctionEventInvokeConfig;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.model.PendingInvocation;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p>An Event invocation answers 202 straight away and hands the final outcome to
 * {@link AsyncInvokeDestinationRouter}, which delivers it to the function's configured destination
 * when it has one. The first attempt runs on the pool; a failed attempt is retried on a virtual
 * thread after the configured delay, as AWS waits one minute and then two.
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
    private final Map<String, Long> deletions = new ConcurrentHashMap<>();
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

    public InvokeResult invoke(LambdaFunction fn, byte[] payload, InvocationType type, String requestId) {
        return invoke(fn, payload, type, 0, null, null, requestId);
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
        return invoke(fn, payload, type, chainDepth, invokedQualifier, null);
    }

    InvokeResult invoke(LambdaFunction fn, byte[] payload, InvocationType type, int chainDepth,
                        String invokedQualifier, String clientContext) {
        return invoke(fn, payload, type, chainDepth, invokedQualifier, clientContext, UUID.randomUUID().toString());
    }

    private InvokeResult invoke(LambdaFunction fn, byte[] payload, InvocationType type, int chainDepth,
                                String invokedQualifier, String clientContext, String requestId) {
        if (type == InvocationType.DryRun) {
            return new InvokeResult(204, null, new byte[0], null, requestId);
        }

        if (type == InvocationType.Event && LambdaInvocationChain.exhausted(chainDepth)) {
            LOG.warnv("Dropping the asynchronous invocation of {0}: the same event already caused "
                    + "{1} invocations, so something in the chain is feeding itself",
                    fn.getFunctionArn(), chainDepth);
            return new InvokeResult(202, null, new byte[0], null, requestId);
        }

        LambdaService lambdaService = resolveLambdaService();
        LambdaConcurrencyLimiter.Permit permit = acquire(lambdaService, fn);

        if (type == InvocationType.Event) {
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
                    maxRetries, clock.millis() + maxEventAgeSeconds * 1000L, generation.get(),
                    deletions.getOrDefault(functionKey(fn), 0L));
            // The deletion count is read above, before this store check, and DeleteFunction moves the count
            // again after it removes the function from the store. So either this check sees the function
            // gone, or the count moves after the event's snapshot and stale() drops the event.
            if (lambdaService != null && !lambdaService.isLive(fn)) {
                permit.close();
                return new InvokeResult(202, null, new byte[0], null, requestId);
            }
            try {
                asyncExecutor.submit(() -> attempt(event, 1, null, permit));
            } catch (RuntimeException e) {
                permit.close();
                throw e;
            }
            return new InvokeResult(202, null, new byte[0], null, requestId);
        }

        try {
            return executeSync(fn, payload, requestId, clientContext);
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
     * Reserved concurrency is function-wide, so the permit is taken against the function's {@code $LATEST}
     * record, which holds the reservation, rather than a version's snapshot, which has none.
     */
    private LambdaConcurrencyLimiter.Permit acquire(LambdaService lambdaService, LambdaFunction fn) {
        LambdaFunction function = lambdaService != null ? lambdaService.findLatest(fn).orElse(fn) : fn;
        return concurrencyLimiter.acquire(function);
    }

    /**
     * Runs one attempt of an asynchronous event and either routes the outcome or schedules the next
     * attempt. Retry n waits n times the configured delay, never past the event's maximum age, and
     * the pending event of a function deleted meanwhile is dropped, whether it was waiting to retry
     * or to expire. The concurrency permit covers only the attempt; {@code held} is the one the
     * invoke itself took.
     */
    private void attempt(AsyncEvent event, int attempt, InvokeResult previous,
                         LambdaConcurrencyLimiter.Permit held) {
        if (stale(event) || clock.millis() >= event.expiresAtMs()) {
            if (held != null) {
                held.close();
            }
            route(event, previous, attempt - 1);
            return;
        }
        LambdaConcurrencyLimiter.Permit permit;
        if (held != null) {
            permit = held;
        } else {
            try {
                permit = acquire(resolveLambdaService(), event.fn());
            } catch (AwsException throttled) {
                // ponytail: AWS backs a throttled retry off exponentially, up to five minutes; one
                // fixed poll is enough locally, and the event still expires on time.
                schedule(event, THROTTLED_RETRY_POLL_MS, attempt, previous);
                return;
            }
        }
        InvokeResult result;
        try (permit) {
            result = executeSync(event.fn(), event.payload(), event.requestId(), null);
        } catch (RuntimeException e) {
            LOG.warnv("Error in async Lambda execution for {0}: {1}", event.fn().getFunctionName(), e.getMessage());
            // Stops retrying, as upstream did: routes the previous attempt's result, or UnknownError when
            // there is none, counting this attempt.
            route(event, previous != null ? previous : new InvokeResult(500, "Unhandled",
                    buildErrorPayload("Error executing Lambda: " + e.getMessage(), "Lambda.UnknownError"),
                    null, event.requestId()), attempt);
            return;
        }
        if ((result.getFunctionError() == null && result.getStatusCode() < 300) || attempt > event.maxRetries()) {
            route(event, result, attempt);
            return;
        }
        schedule(event, asyncRetryDelayMs * attempt, attempt + 1, result);
    }

    /**
     * Decides expiry up front, on the injected clock: the delay thread keeps its own time and may
     * wake a hair early.
     */
    private void schedule(AsyncEvent event, long delayMs, int attempt, InvokeResult previous) {
        long remainingMs = event.expiresAtMs() - clock.millis();
        Runnable next = delayMs < remainingMs
                ? () -> attempt(event, attempt, previous, null)
                : () -> route(event, previous, attempt - 1);
        CompletableFuture.delayedExecutor(Math.max(0, Math.min(delayMs, remainingMs)), TimeUnit.MILLISECONDS,
                RETRY_THREADS).execute(next);
    }

    private void route(AsyncEvent event, InvokeResult result, int attempts) {
        if (destinationRouter == null || stale(event)) {
            return;
        }
        // This Floci-only placeholder covers expiry before any attempt; AWS documents no payload.
        InvokeResult outcome = result != null ? result : new InvokeResult(200, "Unhandled",
                buildErrorPayload("Event age exceeded", "EventAgeExceeded"), null, event.requestId());
        destinationRouter.route(event.fn(), event.payload(), outcome, attempts, event.chainDepth(),
                event.invokedQualifier());
    }

    private InvokeResult executeSync(LambdaFunction fn, byte[] payload, String requestId,
                                     String clientContext) {
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
            invocation.setClientContext(clientContext);

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

    /**
     * Drops the pending asynchronous events of a deleted function: AWS runs no further attempt and
     * delivers no record for them. DeleteFunction calls it twice. The call before it drains the warm pool
     * and deletes anything stops the events already queued or running, so none starts, or reports a failure
     * for, a function whose code is being removed. The call after the function has left the store stops an
     * Event invoke that read the count between the two calls and still found the function stored.
     */
    void dropPending(LambdaFunction fn) {
        // ponytail: deleting a single published version does not drop its pending events; they fail
        // to start and are reported as usual, which is rare locally.
        deletions.merge(functionKey(fn), 1L, Long::sum);
    }

    /** Account, region and name, so every version of a function shares one deletion count. */
    private static String functionKey(LambdaFunction fn) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(fn.getFunctionArn());
        return arn.accountId() + ":" + arn.region() + ":" + fn.getFunctionName();
    }

    private boolean stale(AsyncEvent event) {
        return event.generation() != generation.get()
                || event.deletions() != deletions.getOrDefault(functionKey(event.fn()), 0L);
    }

    @PreDestroy
    public void shutdown() {
        generation.incrementAndGet();
        asyncExecutor.shutdownNow();
    }

    /**
     * Drops pending events before storage is wiped, so a retry whose timer fires during the wipe neither
     * runs nor delivers a record. {@link #clear()} moves the generation again for events accepted in between.
     */
    @Override
    public void beforeReset() {
        generation.incrementAndGet();
    }

    @Override
    public void clear() {
        // Only the generation: the deletion counts still fence events from before the reset, so they are
        // kept, one per deleted function, as LambdaService keeps its per-function concurrency locks.
        generation.incrementAndGet();
    }

    private record AsyncEvent(LambdaFunction fn, byte[] payload, String requestId, int chainDepth,
                              String invokedQualifier, int maxRetries, long expiresAtMs, long generation,
                              long deletions) {}

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
