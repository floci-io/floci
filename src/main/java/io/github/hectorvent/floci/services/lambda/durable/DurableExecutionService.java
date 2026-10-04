package io.github.hectorvent.floci.services.lambda.durable;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend.AccountEntry;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.core.storage.WriteProfile;
import io.github.hectorvent.floci.services.lambda.LambdaArnUtils;
import io.github.hectorvent.floci.services.lambda.durable.DurableFunctionInvoker.DurableInvocationResult;
import io.github.hectorvent.floci.services.lambda.durable.DurableFunctionInvoker.ResolvedDurableTarget;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableCheckpointReplay;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableErrorObject;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableExecution;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableExecutionStatus;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableHistoryEvent;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableOperation;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableOperationStatus;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableOperationType;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableOperationUpdate;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;

/**
 * The durable execution engine behind Lambda Durable Functions. It stores checkpointed operations,
 * re-invokes the function when something it waited for is ready, and serves the execution APIs.
 *
 * <p>Timers are persisted deadlines fired by {@link DurableExecutionSweeper}, as SWF does, so a
 * wait survives a restart. One execution runs one invocation at a time. Every change collects
 * effects, such as launching an invocation or waking sync callers, that run after the
 * per-execution lock is released. Lambda is never invoked while a lock is held.
 */
@ApplicationScoped
public class DurableExecutionService implements Resettable {

    private static final Logger LOG = Logger.getLogger(DurableExecutionService.class);

    /**
     * AWS retries a crashing invocation with growing delays for a few minutes, then fails the
     * execution. Floci keeps that shape over a shorter run with 1, 2, 4 and 8 second delays.
     */
    static final int INVOCATION_RETRY_MAX_ATTEMPTS = 5;
    static final Duration INVOCATION_RETRY_DELAY = Duration.ofSeconds(1);
    static final int ASYNC_PAYLOAD_LIMIT = 1024 * 1024;
    static final int SYNC_PAYLOAD_LIMIT = 6 * 1024 * 1024;
    static final int DEFAULT_PAGE_SIZE = 100;
    private static final int LOCK_STRIPES = 256;
    static final int MAX_PAGE_SIZE = 1000;
    static final String INVALID_TOKEN = "Invalid checkpoint token";
    static final String TOKEN_FOR_OTHER_EXECUTION = "Checkpoint token is not valid for the durable execution ARN";
    static final String NOT_FOUND = "Durable Execution does not exist";

    private final AccountAwareStorageBackend<DurableExecution> store;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final DurableFunctionInvoker invoker;
    private final Executor launchExecutor;
    private final long invocationRetryDelayMillis;
    /** Striped, so a lookup of an unknown ARN never grows a map. Two executions may share a lock, never nest one. */
    private final Object[] executionLocks = newLocks(LOCK_STRIPES);
    private final ConcurrentHashMap<String, Object> startLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<DurableExecution>> completions = new ConcurrentHashMap<>();
    /** Moves on reset and shutdown so an invocation result from before cannot be written afterwards. */
    private final AtomicLong generation = new AtomicLong();

    @Inject
    public DurableExecutionService(StorageFactory storageFactory, ObjectMapper objectMapper, Clock clock,
                                   DurableFunctionInvoker invoker) {
        this(storageFactory, objectMapper, clock, invoker,
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("lambda-durable-", 0).factory()),
                INVOCATION_RETRY_DELAY);
    }

    /** For tests. An inline executor and a zero delay make every invocation chain synchronous. */
    DurableExecutionService(StorageFactory storageFactory, ObjectMapper objectMapper, Clock clock,
                            DurableFunctionInvoker invoker, Executor launchExecutor, Duration invocationRetryDelay) {
        this.store = storageFactory.create("lambda", "lambda-durable-executions.json",
                new TypeReference<Map<String, DurableExecution>>() {}, WriteProfile.APPEND_HEAVY);
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.invoker = invoker;
        this.launchExecutor = launchExecutor;
        this.invocationRetryDelayMillis = invocationRetryDelay.toMillis();
    }

    public record StartRequest(String accountId, String region, String functionName, String qualifier,
                               String executionName, String inputPayload, boolean synchronous) {
    }

    public record ListRequest(String accountId, String region, String functionName, String version,
                              String executionName, Set<DurableExecutionStatus> statuses, Long startedAfter,
                              Long startedBefore, boolean reverseOrder, Integer maxItems, String marker) {
    }

    /** The token is null when the batch closed the execution. The SDK reads that as "completed". */
    public record CheckpointResult(String checkpointToken, List<DurableOperation> newExecutionState) {
    }

    public record StatePage(List<DurableOperation> operations, String nextMarker) {
    }

    public DurableExecution start(StartRequest request) {
        ResolvedDurableTarget target = invoker.resolve(request.accountId(), request.region(),
                request.functionName(), request.qualifier());
        if (!target.durable()) {
            throw new AwsException("InvalidParameterValueException",
                    "Function " + target.functionName() + " is not a durable function", 400);
        }
        String name = request.executionName() != null ? request.executionName() : UUID.randomUUID().toString();
        List<Runnable> effects = new ArrayList<>();
        DurableExecution execution;
        synchronized (startLock(request.accountId(), request.region())) {
            Optional<DurableExecution> existing = request.executionName() == null ? Optional.empty() : store
                    .scanForAccount(request.accountId(), key -> key.startsWith(request.region() + "/"))
                    .stream()
                    .filter(candidate -> name.equals(candidate.getName())
                            && target.functionName().equals(candidate.getFunctionName()))
                    .findFirst();
            if (existing.isPresent()) {
                // Names are unique per function across versions. Only the same version and payload re-attaches.
                if (!existing.get().getVersion().equals(target.version())
                        || !Objects.equals(existing.get().getInputPayload(), request.inputPayload())) {
                    throw new AwsException("DurableExecutionAlreadyStartedException",
                            "Execution already started: " + existing.get().getExecutionArn(), 409);
                }
                return existing.get();
            }
            long now = clock.millis();
            execution = newExecution(request, target, name, now);
            trigger(execution, effects);
            store.putForAccount(request.accountId(), storeKey(execution), execution);
        }
        runEffects(effects);
        return execution;
    }

    public CompletableFuture<DurableExecution> awaitCompletion(String executionArn) {
        ArnParts arn = parseArn(executionArn);
        synchronized (lockFor(arn)) {
            DurableExecution execution = load(arn);
            if (execution.isClosed()) {
                return CompletableFuture.completedFuture(execution);
            }
            return completions.computeIfAbsent(arn.lockKey(), ignored -> new CompletableFuture<>());
        }
    }

    public DurableExecution get(String executionArn) {
        ArnParts arn = parseArn(executionArn);
        synchronized (lockFor(arn)) {
            return load(arn);
        }
    }

    /** A non-null but empty {@code statuses} matches nothing, and the page parameters are still checked. */
    public PaginatedResult<DurableExecution> list(ListRequest request) {
        // Pagination orders by the cursor string, so the cursor carries the direction. Newest first is the default.
        boolean oldestFirst = request.reverseOrder();
        List<Cursored<DurableExecution>> matching = new ArrayList<>();
        boolean matchesNothing = request.statuses() != null && request.statuses().isEmpty();
        List<DurableExecution> candidates = matchesNothing ? List.of() : store.scanForAccount(request.accountId(),
                key -> key.startsWith(request.region() + "/"));
        for (DurableExecution execution : candidates) {
            if (!request.functionName().equals(execution.getFunctionName())) {
                continue;
            }
            if (request.version() != null && !request.version().equals(execution.getVersion())) {
                continue;
            }
            if (request.executionName() != null && !request.executionName().equals(execution.getName())) {
                continue;
            }
            if (request.statuses() != null && !request.statuses().contains(execution.getStatus())) {
                continue;
            }
            if (request.startedAfter() != null && execution.getStartTimestamp() <= request.startedAfter()) {
                continue;
            }
            if (request.startedBefore() != null && request.startedBefore() <= execution.getStartTimestamp()) {
                continue;
            }
            matching.add(new Cursored<>(orderedCursor(execution.getStartTimestamp(), oldestFirst) + ":"
                    + execution.getExecutionId(), execution));
        }
        return paginate(matching, pageSize(request.maxItems()), request.marker(),
                oldestFirst ? "durable-executions-asc" : "durable-executions-desc");
    }

    public PaginatedResult<DurableHistoryEvent> history(String executionArn, Integer maxItems, String marker,
                                                        boolean reverseOrder) {
        ArnParts arn = parseArn(executionArn);
        List<Cursored<DurableHistoryEvent>> events = new ArrayList<>();
        synchronized (lockFor(arn)) {
            for (DurableHistoryEvent event : load(arn).getHistory()) {
                events.add(new Cursored<>(orderedCursor(event.getEventId(), !reverseOrder), event));
            }
        }
        return paginate(events, pageSize(maxItems), marker,
                reverseOrder ? "durable-history-desc" : "durable-history-asc");
    }

    /** A fixed-width cursor that sorts ascending, or descending when the page runs newest first. */
    private static String orderedCursor(long value, boolean ascending) {
        return String.format("%019d", ascending ? value : Long.MAX_VALUE - value);
    }

    /** A retry of the last checkpoint with the same {@code clientToken} gets the same answer again. */
    public CheckpointResult checkpoint(String executionArn, String checkpointToken, String clientToken,
                                       List<DurableOperationUpdate> updates) {
        ArnParts arn = parseArn(executionArn);
        List<Runnable> effects = new ArrayList<>();
        CheckpointResult result;
        synchronized (lockFor(arn)) {
            CheckpointResult replayed = replayLastCheckpoint(arn, checkpointToken, clientToken);
            if (replayed != null) {
                return replayed;
            }
            DurableExecution execution = loadWithCurrentToken(arn, checkpointToken);
            long now = clock.millis();
            DurableCheckpointApplier.Outcome outcome = DurableCheckpointApplier.apply(execution, updates, now);
            if (!outcome.closed()) {
                DurableCheckpointApplier.fireDueTimers(execution, now);
            }
            List<DurableOperation> changed = unseenOperations(execution);
            String nextToken = null;
            if (outcome.closed()) {
                closeBookkeeping(execution, now, effects);
            } else {
                execution.setCheckpointSequence(execution.getCheckpointSequence() + 1);
                nextToken = DurableTokens.checkpointToken(execution.getExecutionArn(),
                        execution.getCurrentInvocationId(), execution.getCheckpointSequence());
            }
            // AWS does not replay the checkpoint that closed the execution.
            execution.setLastCheckpoint(clientToken == null || nextToken == null ? null
                    : new DurableCheckpointReplay(checkpointToken, clientToken, nextToken, changed));
            execution.setSeenSequence(execution.getChangeSequence());
            save(execution);
            result = new CheckpointResult(nextToken, changed);
        }
        runEffects(effects);
        return result;
    }

    public StatePage getState(String executionArn, String checkpointToken, String marker, Integer maxItems) {
        ArnParts arn = parseArn(executionArn);
        List<DurableOperation> operations;
        synchronized (lockFor(arn)) {
            DurableExecution execution = loadWithCurrentToken(arn, checkpointToken);
            operations = new ArrayList<>(execution.getOperations().values());
        }
        List<Cursored<DurableOperation>> indexed = new ArrayList<>(operations.size());
        for (int i = 0; i < operations.size(); i++) {
            indexed.add(new Cursored<>(String.format("%010d", i), operations.get(i)));
        }
        PaginatedResult<DurableOperation> page = paginate(indexed, pageSize(maxItems), marker, "durable-state");
        return new StatePage(page.items(), page.nextToken() != null ? page.nextToken() : "");
    }

    /** Stopping a closed execution changes nothing and reports the time it closed. */
    public DurableExecution stop(String executionArn, DurableErrorObject error) {
        ArnParts arn = parseArn(executionArn);
        List<Runnable> effects = new ArrayList<>();
        DurableExecution execution;
        synchronized (lockFor(arn)) {
            execution = load(arn);
            if (!execution.isClosed()) {
                // Without a body AWS records an empty error object, not a message.
                DurableErrorObject stopError = error != null ? error : new DurableErrorObject();
                close(execution, DurableExecutionStatus.STOPPED, null, stopError, clock.millis(), effects);
                save(execution);
            }
        }
        runEffects(effects);
        return execution;
    }

    /** One tick of {@link DurableExecutionSweeper}. It fires due timers, times out and expires executions. */
    public void sweep() {
        long now = clock.millis();
        for (AccountEntry<DurableExecution> entry : store.scanAllAccountEntries(key -> true)) {
            List<Runnable> effects = new ArrayList<>();
            synchronized (lockFor(entry.accountId(), entry.key())) {
                DurableExecution execution = store.getForAccount(entry.accountId(), entry.key()).orElse(null);
                if (execution == null) {
                    continue;
                }
                if (execution.isClosed()) {
                    if (execution.getRetentionDeadline() != null && execution.getRetentionDeadline() <= now) {
                        store.deleteForAccount(entry.accountId(), entry.key());
                    }
                    continue;
                }
                boolean changed = false;
                if (execution.getExecutionDeadline() != null && execution.getExecutionDeadline() <= now) {
                    DurableErrorObject error = DurableErrorObject.of("Execution timed out after "
                            + execution.getExecutionTimeoutSeconds() + " seconds.", null);
                    close(execution, DurableExecutionStatus.TIMED_OUT, null, error, now, effects);
                    changed = true;
                } else {
                    if (DurableCheckpointApplier.fireDueTimers(execution, now)) {
                        trigger(execution, effects);
                        changed = true;
                    }
                    if (execution.getCurrentInvocationId() == null && execution.getNextInvocationAttemptAt() != null
                            && execution.getNextInvocationAttemptAt() <= now) {
                        execution.setNextInvocationAttemptAt(null);
                        trigger(execution, effects);
                        changed = true;
                    }
                }
                if (changed) {
                    save(execution);
                }
            }
            runEffects(effects);
        }
    }

    /**
     * Re-invokes every RUNNING execution after a restart. The invocation that was in flight is lost
     * with the process, and the SDK replays from the checkpoints, so starting over is safe.
     */
    public void recoverAfterRestart() {
        int recovered = 0;
        for (AccountEntry<DurableExecution> entry : store.scanAllAccountEntries(key -> true)) {
            List<Runnable> effects = new ArrayList<>();
            synchronized (lockFor(entry.accountId(), entry.key())) {
                DurableExecution execution = store.getForAccount(entry.accountId(), entry.key()).orElse(null);
                if (execution == null || execution.isClosed()) {
                    continue;
                }
                execution.setCurrentInvocationId(null);
                execution.setReinvokeRequested(false);
                // A crash retry keeps its backoff, and the sweeper relaunches it when it is due.
                if (execution.getNextInvocationAttemptAt() == null) {
                    trigger(execution, effects);
                }
                save(execution);
                recovered++;
            }
            runEffects(effects);
        }
        if (recovered > 0) {
            LOG.infov("Resumed {0} durable execution(s) after restart", recovered);
        }
    }

    @Override
    public void beforeReset() {
        generation.incrementAndGet();
    }

    @Override
    public void clear() {
        generation.incrementAndGet();
        startLocks.clear();
        for (CompletableFuture<DurableExecution> waiter : completions.values()) {
            waiter.completeExceptionally(new AwsException("ResourceNotFoundException", NOT_FOUND, 404));
        }
        completions.clear();
    }

    @PreDestroy
    void shutdown() {
        generation.incrementAndGet();
    }

    // ──────────────────────────── invocation lane ────────────────────────────

    private void trigger(DurableExecution execution, List<Runnable> effects) {
        if (execution.getCurrentInvocationId() != null) {
            execution.setReinvokeRequested(true);
            return;
        }
        String invocationId = UUID.randomUUID().toString();
        execution.setCurrentInvocationId(invocationId);
        execution.setNextInvocationAttemptAt(null);
        String accountId = execution.getAccountId();
        String key = storeKey(execution);
        long launchGeneration = generation.get();
        effects.add(() -> launchExecutor.execute(() -> runInvocation(accountId, key, invocationId, launchGeneration)));
    }

    private void runInvocation(String accountId, String key, String invocationId, long launchGeneration) {
        ResolvedDurableTarget target;
        byte[] payload;
        long startedAt;
        synchronized (lockFor(accountId, key)) {
            DurableExecution execution = store.getForAccount(accountId, key).orElse(null);
            if (execution == null || execution.isClosed() || launchGeneration != generation.get()
                    || !invocationId.equals(execution.getCurrentInvocationId())) {
                return;
            }
            startedAt = clock.millis();
            String token = DurableTokens.checkpointToken(execution.getExecutionArn(), invocationId,
                    execution.getCheckpointSequence());
            List<String> updatedOperationIds = unseenOperations(execution).stream().map(DurableOperation::getId).toList();
            try {
                target = invoker.resolve(accountId, execution.getRegion(), execution.getFunctionName(),
                        execution.getVersion());
                payload = objectMapper.writeValueAsBytes(
                        DurableWire.invocationEvent(execution, token, updatedOperationIds));
            } catch (AwsException | IOException e) {
                target = null;
                payload = null;
                LOG.warnv("Durable execution {0} cannot be invoked: {1}", execution.getExecutionArn(), e.getMessage());
            }
            // The event carries every operation, so the handler has now seen everything up to here.
            execution.setSeenSequence(execution.getChangeSequence());
        }
        DurableInvocationResult result;
        if (target == null) {
            result = new DurableInvocationResult(null, null, "ResourceNotFoundException");
        } else {
            try {
                result = invoker.invoke(target, payload);
            } catch (AwsException e) {
                result = new DurableInvocationResult(null, DurableWire.functionErrorPayload(
                        DurableErrorObject.of(e.getMessage(), e.getErrorCode())),
                        e.getErrorCode());
            } catch (RuntimeException e) {
                LOG.warnv("Durable invocation failed: {0}", e.getMessage());
                result = new DurableInvocationResult(null, DurableWire.functionErrorPayload(
                        DurableErrorObject.of(e.getMessage(), "InvocationError")),
                        "InvocationError");
            }
        }
        List<Runnable> effects = new ArrayList<>();
        synchronized (lockFor(accountId, key)) {
            DurableExecution execution = store.getForAccount(accountId, key).orElse(null);
            if (execution == null || launchGeneration != generation.get()
                    || !invocationId.equals(execution.getCurrentInvocationId())) {
                return;
            }
            long endedAt = clock.millis();
            String requestId = result.requestId() != null ? result.requestId() : UUID.randomUUID().toString();
            execution.setCurrentInvocationId(null);
            if (execution.isClosed()) {
                DurableHistory.invocationCompleted(execution, startedAt, endedAt, requestId, null);
            } else if (result.functionError() != null) {
                // The sweeper relaunches the invocation once its backoff has passed.
                recordInvocationFailure(execution, result, startedAt, endedAt, requestId, effects);
            } else {
                recordHandlerResponse(execution, result.payload(), startedAt, endedAt, requestId, effects);
                if (!execution.isClosed() && (execution.isReinvokeRequested() || execution.hasUnseenChanges())) {
                    execution.setReinvokeRequested(false);
                    trigger(execution, effects);
                }
            }
            save(execution);
        }
        runEffects(effects);
    }

    private void recordInvocationFailure(DurableExecution execution, DurableInvocationResult result, long startedAt,
                                         long endedAt, String requestId, List<Runnable> effects) {
        DurableErrorObject error = errorFromLambdaPayload(result.payload(), result.functionError());
        int failures = execution.getConsecutiveInvocationFailures() + 1;
        execution.setConsecutiveInvocationFailures(failures);
        DurableHistory.invocationCompleted(execution, startedAt, endedAt, requestId, error);
        if (failures >= INVOCATION_RETRY_MAX_ATTEMPTS) {
            close(execution, DurableExecutionStatus.FAILED, null, error, endedAt, effects);
        } else {
            execution.setNextInvocationAttemptAt(endedAt + (invocationRetryDelayMillis << (failures - 1)));
            // The retry sees every change, so a wake-up that arrived during this invocation is spent.
            execution.setReinvokeRequested(false);
        }
    }

    private void recordHandlerResponse(DurableExecution execution, byte[] payload, long startedAt, long endedAt,
                                       String requestId, List<Runnable> effects) {
        execution.setConsecutiveInvocationFailures(0);
        DurableWire.HandlerResponse response = parseHandlerResponse(payload);
        String protocolError = protocolError(execution, response);
        if (protocolError != null) {
            DurableErrorObject error = DurableErrorObject.of(protocolError, "InvalidParameterValueException");
            DurableHistory.invocationCompleted(execution, startedAt, endedAt, requestId, error);
            close(execution, DurableExecutionStatus.FAILED, null, error, endedAt, effects);
            return;
        }
        boolean failed = "FAILED".equals(response.status());
        DurableHistory.invocationCompleted(execution, startedAt, endedAt, requestId, failed ? response.error() : null);
        switch (response.status()) {
            case "SUCCEEDED" -> close(execution, DurableExecutionStatus.SUCCEEDED, response.result(), null, endedAt,
                    effects);
            case "FAILED" -> close(execution, DurableExecutionStatus.FAILED, null, response.error(), endedAt, effects);
            default -> {
            }
        }
    }

    /** Null when the handler response is acceptable, else the error message AWS gives. */
    private static String protocolError(DurableExecution execution, DurableWire.HandlerResponse response) {
        if (response == null) {
            return "Invalid Status in invocation output.";
        }
        return switch (response.status()) {
            case "SUCCEEDED" -> response.error() != null ? "Cannot provide an Error for SUCCEEDED status."
                    : DurableCheckpointApplier.utf8Length(response.result()) > execution.getMaxResultBytes()
                    ? "Execution output payload size must be less than or equal to "
                            + execution.getMaxResultBytes() + " bytes." : null;
            case "FAILED" -> response.result() != null ? "Cannot provide a Result for FAILED status." : null;
            case "PENDING" -> execution.hasPendingOperations() || execution.isReinvokeRequested()
                    || execution.hasUnseenChanges() ? null
                    : "Cannot return PENDING status with no pending operations.";
            default -> "Invalid Status in invocation output.";
        };
    }

    private DurableWire.HandlerResponse parseHandlerResponse(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return null;
        }
        try {
            JsonNode body = objectMapper.readTree(payload);
            return DurableWire.parseHandlerResponse(body);
        } catch (IOException e) {
            LOG.debugv("Durable handler returned a body that is not JSON: {0}", e.getMessage());
            return null;
        }
    }

    // ──────────────────────────── state changes ────────────────────────────

    private DurableExecution newExecution(StartRequest request, ResolvedDurableTarget target, String name, long now) {
        DurableExecution execution = new DurableExecution();
        String executionId = UUID.randomUUID().toString();
        execution.setExecutionId(executionId);
        execution.setName(name);
        execution.setAccountId(request.accountId());
        execution.setRegion(request.region());
        execution.setFunctionName(target.functionName());
        execution.setFunctionArn(target.functionArn());
        execution.setVersion(target.version());
        execution.setExecutionArn(target.functionArn() + "/durable-execution/" + name + "/" + executionId);
        execution.setStartTimestamp(now);
        execution.setInputPayload(request.inputPayload());
        execution.setExecutionTimeoutSeconds(target.executionTimeoutSeconds());
        execution.setRetentionPeriodInDays(target.retentionPeriodInDays());
        execution.setExecutionDeadline(now + target.executionTimeoutSeconds() * 1000L);
        execution.setMaxResultBytes(request.synchronous() ? SYNC_PAYLOAD_LIMIT : ASYNC_PAYLOAD_LIMIT);

        DurableOperation root = new DurableOperation();
        root.setId(executionId);
        root.setName(name);
        root.setType(DurableOperationType.EXECUTION);
        root.setStatus(DurableOperationStatus.STARTED);
        root.setStartTimestamp(now);
        root.setInputPayload(request.inputPayload());
        root.setChangeSequence(execution.nextChangeSequence());
        execution.getOperations().put(executionId, root);
        DurableHistory.executionStarted(execution, now);
        return execution;
    }

    private void close(DurableExecution execution, DurableExecutionStatus status, String result,
                       DurableErrorObject error, long now, List<Runnable> effects) {
        execution.setStatus(status);
        execution.setResult(result);
        execution.setError(error);
        execution.setEndTimestamp(now);
        DurableOperation root = execution.getOperations().get(execution.getExecutionId());
        if (root != null) {
            root.setStatus(switch (status) {
                case SUCCEEDED -> DurableOperationStatus.SUCCEEDED;
                case TIMED_OUT -> DurableOperationStatus.TIMED_OUT;
                case STOPPED -> DurableOperationStatus.STOPPED;
                default -> DurableOperationStatus.FAILED;
            });
            root.setEndTimestamp(now);
            root.setChangeSequence(execution.nextChangeSequence());
        }
        switch (status) {
            case SUCCEEDED -> DurableHistory.executionSucceeded(execution, now);
            case TIMED_OUT -> DurableHistory.executionTimedOut(execution, now);
            case STOPPED -> DurableHistory.executionEnded(execution, "ExecutionStopped", now);
            default -> DurableHistory.executionEnded(execution, "ExecutionFailed", now);
        }
        closeBookkeeping(execution, now, effects);
    }

    /** What every close shares, whether the applier or the service decided it. */
    private void closeBookkeeping(DurableExecution execution, long now, List<Runnable> effects) {
        execution.setRetentionDeadline(now + execution.getRetentionPeriodInDays() * 86_400_000L);
        execution.setNextInvocationAttemptAt(null);
        execution.setReinvokeRequested(false);
        String lockKey = lockKey(execution.getAccountId(), storeKey(execution));
        effects.add(() -> {
            CompletableFuture<DurableExecution> waiter = completions.remove(lockKey);
            if (waiter != null) {
                waiter.complete(execution);
            }
        });
    }

    /** Null unless this is the last checkpoint again, with the same ClientToken, and its token is still current. */
    private CheckpointResult replayLastCheckpoint(ArnParts arn, String checkpointToken, String clientToken) {
        if (clientToken == null) {
            return null;
        }
        DurableExecution execution = store.getForAccount(arn.accountId(), arn.storeKey())
                .filter(candidate -> candidate.getExecutionArn().equals(arn.arn()))
                .orElse(null);
        DurableCheckpointReplay last = execution == null ? null : execution.getLastCheckpoint();
        if (last == null || execution.isClosed() || execution.getCurrentInvocationId() == null
                || !clientToken.equals(last.getClientToken()) || !last.getRequestToken().equals(checkpointToken)
                || !last.getResponseToken().equals(DurableTokens.checkpointToken(execution.getExecutionArn(),
                        execution.getCurrentInvocationId(), execution.getCheckpointSequence()))) {
            return null;
        }
        return new CheckpointResult(last.getResponseToken(), last.getOperations());
    }

    /** Checkpoint and state calls answer an unknown execution as a bad token, not as ResourceNotFoundException. */
    private DurableExecution loadWithCurrentToken(ArnParts arn, String checkpointToken) {
        Optional<DurableTokens.CheckpointToken> token = DurableTokens.parseCheckpointToken(checkpointToken);
        if (token.isPresent() && !token.get().executionArn().equals(arn.arn())) {
            throw new AwsException("InvalidParameterValueException", TOKEN_FOR_OTHER_EXECUTION, 400);
        }
        DurableExecution execution = store.getForAccount(arn.accountId(), arn.storeKey())
                .filter(candidate -> candidate.getExecutionArn().equals(arn.arn()))
                .orElse(null);
        if (token.isEmpty() || execution == null || execution.isClosed() || execution.getCurrentInvocationId() == null
                || !token.get().invocationId().equals(execution.getCurrentInvocationId())
                || token.get().sequence() != execution.getCheckpointSequence()) {
            throw new AwsException("InvalidParameterValueException", INVALID_TOKEN, 400);
        }
        return execution;
    }

    private void save(DurableExecution execution) {
        store.putForAccount(execution.getAccountId(), storeKey(execution), execution);
    }

    private DurableExecution load(ArnParts arn) {
        return store.getForAccount(arn.accountId(), arn.storeKey())
                .filter(execution -> execution.getExecutionArn().equals(arn.arn()))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", NOT_FOUND, 404));
    }

    private static List<DurableOperation> unseenOperations(DurableExecution execution) {
        return execution.getOperations().values().stream()
                .filter(operation -> operation.getChangeSequence() > execution.getSeenSequence())
                .sorted(Comparator.comparingLong(DurableOperation::getChangeSequence))
                .toList();
    }

    private static void runEffects(List<Runnable> effects) {
        for (Runnable effect : effects) {
            effect.run();
        }
    }

    // ──────────────────────────── helpers ────────────────────────────

    record ArnParts(String arn, String accountId, String region, String functionName, String version, String name,
                    String executionId) {
        String storeKey() {
            return region + "/" + executionId;
        }

        String lockKey() {
            return DurableExecutionService.lockKey(accountId, storeKey());
        }
    }

    static ArnParts parseArn(String executionArn) {
        Matcher matcher = executionArn == null ? null : LambdaArnUtils.DURABLE_EXECUTION_ARN.matcher(executionArn);
        if (matcher == null || !matcher.matches()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + executionArn + "' at 'durableExecutionArn' failed to "
                            + "satisfy constraint: Member must satisfy regular expression pattern: "
                            + LambdaArnUtils.DURABLE_EXECUTION_ARN.pattern(), 400);
        }
        return new ArnParts(executionArn, matcher.group(3), matcher.group(2), matcher.group(4), matcher.group(5),
                matcher.group(6), matcher.group(7));
    }

    private static String storeKey(DurableExecution execution) {
        return execution.getRegion() + "/" + execution.getExecutionId();
    }

    private static String lockKey(String accountId, String storeKey) {
        return accountId + "/" + storeKey;
    }

    private Object lockFor(ArnParts arn) {
        return lockFor(arn.accountId(), arn.storeKey());
    }

    private Object lockFor(String accountId, String storeKey) {
        return executionLocks[Math.floorMod(lockKey(accountId, storeKey).hashCode(), executionLocks.length)];
    }

    private static Object[] newLocks(int count) {
        Object[] locks = new Object[count];
        for (int i = 0; i < count; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    private Object startLock(String accountId, String region) {
        return startLocks.computeIfAbsent(accountId + ":" + region, ignored -> new Object());
    }

    private static int pageSize(Integer maxItems) {
        String violation = maxItemsViolation(maxItems);
        if (violation != null) {
            throw validationError(List.of(violation));
        }
        return maxItems == null || maxItems == 0 ? DEFAULT_PAGE_SIZE : maxItems;
    }

    /** Null when MaxItems is in range. Zero asks for the default page. */
    static String maxItemsViolation(Integer maxItems) {
        if (maxItems == null) {
            return null;
        }
        String bound = maxItems < 0 ? "greater than or equal to 0"
                : MAX_PAGE_SIZE < maxItems ? "less than or equal to " + MAX_PAGE_SIZE : null;
        return bound == null ? null
                : "Value '" + maxItems + "' at 'maxItems' failed to satisfy constraint: Member must have value " + bound;
    }

    /** AWS reports every invalid member of a request in one ValidationException. */
    static AwsException validationError(List<String> violations) {
        String count = violations.size() == 1 ? "1 validation error detected: "
                : violations.size() + " validation errors detected: ";
        return new AwsException("ValidationException", count + String.join("; ", violations), 400);
    }

    private static AwsException invalidMarker() {
        return new AwsException("InvalidParameterValueException", "Invalid Marker", 400);
    }

    private DurableErrorObject errorFromLambdaPayload(byte[] payload, String functionError) {
        if (payload != null && payload.length > 0) {
            try {
                JsonNode body = objectMapper.readTree(payload);
                if (body.isObject() && body.hasNonNull("errorMessage")) {
                    DurableErrorObject error = DurableErrorObject.of(body.get("errorMessage").asText(),
                            body.path("errorType").isTextual() ? body.get("errorType").asText() : functionError);
                    JsonNode stackTrace = body.get("stackTrace");
                    if (stackTrace != null && stackTrace.isArray()) {
                        List<String> lines = new ArrayList<>();
                        stackTrace.forEach(line -> lines.add(line.isTextual() ? line.asText() : line.toString()));
                        error.setStackTrace(lines);
                    }
                    return error;
                }
                return DurableErrorObject.of(body.toString(), functionError);
            } catch (IOException expected) {
                // A runtime may report a plain-text error. Its text becomes the message.
                return DurableErrorObject.of(new String(payload, StandardCharsets.UTF_8), functionError);
            }
        }
        return DurableErrorObject.of("Function invocation failed", functionError);
    }

    private static <T> PaginatedResult<T> paginate(List<Cursored<T>> items, int pageSize, String marker,
                                                   String namespace) {
        PaginatedResult<Cursored<T>> page = Pagination.paginate(items, Cursored::cursor, pageSize, marker, namespace,
                ignored -> invalidMarker());
        return new PaginatedResult<>(page.items().stream().map(Cursored::item).toList(), page.nextToken());
    }

    private record Cursored<T>(String cursor, T item) {
    }
}
