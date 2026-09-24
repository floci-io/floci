package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbStreamService;
import io.github.hectorvent.floci.services.dynamodb.model.DynamoDbStreamRecord;
import io.github.hectorvent.floci.services.lambda.model.EventSourceMapping;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.pipes.PipesFilterMatcher;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.vertx.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

@ApplicationScoped
public class DynamoDbStreamsEventSourcePoller implements Resettable {

    private static final Logger LOG = Logger.getLogger(DynamoDbStreamsEventSourcePoller.class);
    private static final DateTimeFormatter S3_ON_FAILURE_PATH_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter S3_ON_FAILURE_FILE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH.mm.ss").withZone(ZoneOffset.UTC);

    /** Raised by the stream when a stored checkpoint has aged out of the retained window. */
    private static final String TRIMMED_DATA_ACCESS_EXCEPTION = "TrimmedDataAccessException";
    static final long MAX_RETRY_BACKOFF_MS = 60_000;

    private final Vertx vertx;
    private final DynamoDbStreamService streamService;
    private final LambdaExecutorService executorService;
    private final LambdaTargetResolver targetResolver;
    private final EsmStore esmStore;
    private final ObjectMapper objectMapper;
    private final PipesFilterMatcher filterMatcher;
    private final SqsService sqsService;
    private final SnsService snsService;
    private final S3Service s3Service;
    private final String baseUrl;
    private final long pollIntervalMs;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Long> timerIds = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, Boolean> activePolls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> retryCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> retryNotBefore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> bisectLimits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingFailure> pendingFailures = new ConcurrentHashMap<>();
    final Set<String> stopped = ConcurrentHashMap.newKeySet();
    private volatile boolean resetting;
    private final ExecutorService pollExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "dynamodb-streams-esm-poller");
        t.setDaemon(true);
        return t;
    });

    @Inject
    public DynamoDbStreamsEventSourcePoller(Vertx vertx, DynamoDbStreamService streamService,
                                            LambdaExecutorService executorService,
                                            LambdaTargetResolver targetResolver,
                                            EsmStore esmStore,
                                            ObjectMapper objectMapper,
                                            EmulatorConfig config,
                                            PipesFilterMatcher filterMatcher,
                                            SqsService sqsService,
                                            SnsService snsService,
                                            S3Service s3Service) {
        this(vertx, streamService, executorService, targetResolver, esmStore, objectMapper, config,
                filterMatcher, sqsService, snsService, s3Service, System::currentTimeMillis);
    }

    DynamoDbStreamsEventSourcePoller(Vertx vertx, DynamoDbStreamService streamService,
                                     LambdaExecutorService executorService,
                                     LambdaTargetResolver targetResolver,
                                     EsmStore esmStore,
                                     ObjectMapper objectMapper,
                                     EmulatorConfig config,
                                     PipesFilterMatcher filterMatcher,
                                     SqsService sqsService,
                                     SnsService snsService,
                                     S3Service s3Service,
                                     LongSupplier clock) {
        this.vertx = vertx;
        this.streamService = streamService;
        this.executorService = executorService;
        this.targetResolver = targetResolver;
        this.esmStore = esmStore;
        this.objectMapper = objectMapper;
        this.pollIntervalMs = config.services().lambda().pollIntervalMs();
        this.baseUrl = config.effectiveBaseUrl();
        this.filterMatcher = filterMatcher;
        this.sqsService = sqsService;
        this.snsService = snsService;
        this.s3Service = s3Service;
        this.clock = clock;
    }

    public void startPersistedPollers() {
        for (EventSourceMapping esm : esmStore.listAll()) {
            if (esm.getEventSourceArn() != null && esm.getEventSourceArn().contains(":dynamodb:")) {
                discardStaleShardCheckpoints(esm);
                if (esm.isEnabled()) {
                    startPolling(esm);
                }
            }
        }
        LOG.infov("DynamoDbStreamsEventSourcePoller initialized");
    }

    /**
     * Discards any shard checkpoints a DynamoDB Streams ESM persisted during a previous run,
     * before its poller is (re)started at startup.
     *
     * <p>A DynamoDB stream is volatile: its record buffer and its {@code AtomicLong} sequence
     * counter live only in memory (see {@link DynamoDbStreamService}) and are never persisted, so a
     * restart recreates the stream empty and its sequence numbers start over from
     * {@code 000000000000000000001}. A {@code shardSequenceNumbers} checkpoint saved during the
     * previous run therefore points <em>past</em> every record in the new stream epoch: resuming
     * from it with an {@code AFTER_SEQUENCE_NUMBER} iterator silently skips every freshly written
     * record — no invoke, no error, no log — until the new sequence numbers climb back above the
     * stale value. Clearing the checkpoint lets the poller resume from {@code TRIM_HORIZON}, which
     * matches the volatility of the stream itself. See issue #2076.
     */
    private void discardStaleShardCheckpoints(EventSourceMapping esm) {
        if (esm.getShardSequenceNumbers().isEmpty()) {
            return;
        }
        LOG.infov("DynamoDB Streams ESM {0}: discarding {1} stale shard checkpoint(s) persisted by a "
                        + "previous run (stream sequence numbers reset on restart); resuming from TRIM_HORIZON",
                esm.getUuid(), esm.getShardSequenceNumbers().size());
        esm.getShardSequenceNumbers().clear();
        esmStore.saveForAccount(esm.getAccountId(), esm);
    }

    /** Pins a new LATEST mapping after the stream's newest record, so only later writes are delivered. */
    public void initializeStartingPosition(EventSourceMapping esm) {
        if (!"LATEST".equals(esm.getStartingPosition()) || !esm.getShardSequenceNumbers().isEmpty()) {
            return;
        }
        String latest = streamService.latestSequenceNumber(esm.getEventSourceArn());
        if (latest != null) {
            esm.getShardSequenceNumbers().put(DynamoDbStreamService.SHARD_ID, latest);
        }
    }

    @PreDestroy
    void shutdown() {
        pollExecutor.shutdownNow();
        timerIds.values().forEach(vertx::cancelTimer);
        timerIds.clear();
    }

    @Override
    public synchronized void beforeReset() {
        // Taking the monitor drains an in-flight advanceCheckpoint save; it is released before the wipe.
        resetting = true;
    }

    @Override
    public synchronized void afterReset() {
        resetting = false;
    }

    public void clear() {
        timerIds.values().forEach(vertx::cancelTimer);
        timerIds.clear();
        activePolls.clear();
        stopped.clear();
        retryCounts.clear();
        retryNotBefore.clear();
        bisectLimits.clear();
        pendingFailures.clear();
    }

    public synchronized void startPolling(EventSourceMapping esm) {
        stopped.remove(esm.getUuid());
        if (timerIds.containsKey(esm.getUuid())) {
            return;
        }
        String uuid = esm.getUuid();
        String accountId = esm.getAccountId();
        long timerId = vertx.setPeriodic(pollIntervalMs, id ->
                esmStore.getForAccount(accountId, uuid).ifPresent(latest -> {
                    if (latest.isEnabled()) {
                        pollAndInvoke(latest);
                    }
                }));
        timerIds.put(uuid, timerId);
        LOG.infov("Started DynamoDB Streams polling for ESM {0} → {1}", uuid, esm.getEventSourceArn());
    }

    /**
     * Drops the stop tombstone. Safe once the store delete has run, because exists() in the
     * synchronized advanceCheckpoint then refuses any write-back on its own.
     */
    void mappingDeleted(String uuid) {
        stopped.remove(uuid);
    }

    public synchronized void stopPolling(String uuid) {
        stopped.add(uuid);
        Long timerId = timerIds.remove(uuid);
        if (timerId != null) {
            vertx.cancelTimer(timerId);
            LOG.debugv("Stopped DynamoDB Streams polling for ESM {0}", uuid);
        }
        retryCounts.keySet().removeIf(k -> k.startsWith(uuid + ":"));
        retryNotBefore.keySet().removeIf(k -> k.startsWith(uuid + ":"));
        bisectLimits.keySet().removeIf(k -> k.startsWith(uuid + ":"));
        pendingFailures.keySet().removeIf(k -> k.startsWith(uuid + ":"));
    }


    void pollAndInvoke(EventSourceMapping esm) {
        if (activePolls.putIfAbsent(esm.getUuid(), Boolean.TRUE) != null) {
            return;
        }
        pollExecutor.submit(() -> {
            try {
                String streamArn = esm.getEventSourceArn();
                String shardId = DynamoDbStreamService.SHARD_ID;
                String lastSeq = esm.getShardSequenceNumbers().get(shardId);
                String persistedKey = batchStateKey(esm.getUuid(), shardId, lastSeq);
                PendingFailure pending = pendingFailures.get(persistedKey);
                if (pending != null) {
                    long now = clock.getAsLong();
                    if (now >= retryNotBefore.getOrDefault(persistedKey, 0L)) {
                        dispose(esm, shardId, persistedKey, pending, now);
                    }
                    return;
                }

                LambdaFunction fn = targetResolver.resolveMappingTarget(esm).orElse(null);
                if (fn == null) {
                    LOG.warnv("DynamoDB Streams ESM {0}: function {1} not found, skipping",
                            esm.getUuid(), esm.getFunctionName());
                    return;
                }

                boolean checkpointTrimmed = false;
                DynamoDbStreamService.GetRecordsResult result;
                try {
                    String iterator = lastSeq == null
                            ? streamService.getShardIterator(streamArn, shardId, "TRIM_HORIZON", null)
                            : streamService.getShardIterator(streamArn, shardId, "AFTER_SEQUENCE_NUMBER", lastSeq);
                    result = streamService.getRecords(iterator,
                            Math.min(bisectLimit(esm, persistedKey), esm.getBatchSize()));
                } catch (AwsException e) {
                    if (!TRIMMED_DATA_ACCESS_EXCEPTION.equals(e.getErrorCode())) {
                        throw e;
                    }
                    checkpointTrimmed = true;
                    clearBatchState(persistedKey);
                    // The checkpoint fell outside the retained window, so the cursor it names can
                    // never succeed again. Retrying it wedges the ESM permanently: every later
                    // write reaches the stream and none is ever delivered. Resume from the oldest
                    // record still held instead, which is what AWS does when a consumer is
                    // overtaken by the trim horizon. Records written between the lost checkpoint
                    // and that record are gone from the stream and are not delivered.
                    LOG.warnv("DynamoDB Streams ESM {0}: checkpoint {1} was trimmed, resuming from the "
                                    + "trim horizon; records between were dropped from the stream",
                            esm.getUuid(), lastSeq);
                    String horizon = streamService.getShardIterator(streamArn, shardId, "TRIM_HORIZON", null);
                    result = streamService.getRecords(horizon, Math.min(esm.getBatchSize(),
                            bisectLimit(esm, batchStateKey(esm.getUuid(), shardId, null))));
                }
                List<DynamoDbStreamRecord> records = result.records();

                if (records.isEmpty()) {
                    return;
                }
                // ponytail: a stop or reset racing this check does not cancel an in-flight invoke; the stop checks in
                // advanceCheckpoint and dispose drop its checkpoint and OnFailure send. Only a stop landing
                // between dispose's check and the send itself still delivers; a lock around the send would close it.
                boolean enabled = esmStore.getForAccount(esm.getAccountId(), esm.getUuid())
                        .map(EventSourceMapping::isEnabled)
                        .orElse(false);
                if (resetting || !enabled) {
                    return;
                }

                // Advance to the newest FETCHED record whenever the batch is disposed of, invoked or
                // fully filtered out, so filtered-out records are consumed, not re-read forever. Leave
                // the checkpoint unmoved only when an attempted invoke fails (the window retries).
                String newestFetchedSeq = records.get(records.size() - 1).getSequenceNumber();

                List<DynamoDbStreamRecord> matched = records;
                JsonNode filterParams = EsmFilterCriteriaUtils.matcherSourceParameters(objectMapper, esm.getFilterCriteria());
                if (filterParams != null) {
                    List<JsonNode> filterNodes = new ArrayList<>(records.size());
                    for (DynamoDbStreamRecord rec : records) {
                        filterNodes.add(buildDynamoDbRecordNode(rec, esm));
                    }
                    matched = EsmFilterCriteriaUtils.selectMatched(
                            records, filterNodes, filterMatcher.applyFilterCriteria(filterNodes, filterParams));
                }

                String batchKey = batchStateKey(esm.getUuid(), shardId, checkpointTrimmed ? null : lastSeq);
                if (matched.isEmpty()) {
                    clearBatchState(batchKey);
                    advanceCheckpoint(esm, shardId, newestFetchedSeq);
                    return;
                }

                long now = clock.getAsLong();
                if (now < retryNotBefore.getOrDefault(batchKey, 0L)) {
                    return;
                }

                LOG.infov("DynamoDB Streams ESM {0}: delivering {1} of {2} record(s) to {3}",
                        esm.getUuid(), matched.size(), records.size(), esm.getFunctionName());

                String eventJson = buildDynamoDbEvent(matched, esm);
                InvokeResult invokeResult;
                try {
                    invokeResult = executorService.invoke(fn, eventJson.getBytes(), InvocationType.RequestResponse);
                } catch (AwsException e) {
                    if ("TooManyRequestsException".equals(e.getErrorCode())) {
                        LOG.infov("DynamoDB Streams ESM {0}: function {1} throttled, shard iterator not advanced",
                                esm.getUuid(), fn.getFunctionName());
                        return;
                    }
                    throw e;
                }
                if (!exists(esm)) {
                    LOG.debugv("DynamoDB Streams ESM {0} was deleted during the invocation, dropping its result",
                            esm.getUuid());
                    return;
                }

                CheckpointOutcome outcome = invokeResult.getFunctionError() == null
                        ? successfulInvocationCheckpoint(esm, invokeResult, lastSeq, records, matched)
                        : new CheckpointOutcome(null, 0);

                if (outcome.lowestFailedIndex() == records.size()) {
                    clearBatchState(batchKey);
                    advanceCheckpoint(esm, shardId, outcome.checkpoint());
                } else if (outcome.lowestFailedIndex() > 0) {
                    clearBatchState(batchKey);

                    String nextCheckpoint = outcome.checkpoint();
                    String nextBatchKey = batchStateKey(esm.getUuid(), shardId, nextCheckpoint);
                    Integer maxRetries = esm.getMaximumRetryAttempts();
                    int currentRetries = retryCounts.merge(nextBatchKey, 1, Integer::sum);
                    Set<String> deliveredSeqs = new HashSet<>();
                    for (DynamoDbStreamRecord rec : matched) {
                        deliveredSeqs.add(rec.getSequenceNumber());
                    }
                    List<DynamoDbStreamRecord> failedRecords = new ArrayList<>();
                    for (int i = outcome.lowestFailedIndex(); i < records.size(); i++) {
                        DynamoDbStreamRecord rec = records.get(i);
                        if (deliveredSeqs.contains(rec.getSequenceNumber())) {
                            failedRecords.add(rec);
                        }
                    }
                    if (failedRecords.isEmpty()) {
                        failedRecords = records.subList(outcome.lowestFailedIndex(), records.size());
                    }

                    if (hasExceededMaximumRecordAge(esm, failedRecords, now)) {
                        LOG.warnv("DynamoDB Streams ESM {0}: maximum record age exceeded for batch ending at {1}",
                                esm.getUuid(), newestFetchedSeq);
                        if (!dispose(esm, shardId, nextBatchKey, new PendingFailure(failedRecords, invokeResult,
                                currentRetries, "MaximumRecordAgeExceeded", newestFetchedSeq), now)) {
                            advanceCheckpoint(esm, shardId, nextCheckpoint);
                        }
                    } else if (maxRetries != null && maxRetries >= 0 && currentRetries > maxRetries) {
                        LOG.warnv("DynamoDB Streams ESM {0}: maximum retry attempts ({1}) exhausted for batch ending at {2}",
                                esm.getUuid(), maxRetries, newestFetchedSeq);
                        if (!dispose(esm, shardId, nextBatchKey, new PendingFailure(failedRecords, invokeResult,
                                currentRetries, "RetryAttemptsExhausted", newestFetchedSeq), now)) {
                            advanceCheckpoint(esm, shardId, nextCheckpoint);
                        }
                    } else {
                        retryNotBefore.put(nextBatchKey, now + retryBackoffMs(currentRetries));
                        LOG.warnv("DynamoDB Streams ESM {0}: Lambda returned error [batchItemFailures], retry {1}, records will be retried",
                                esm.getUuid(), currentRetries);
                        advanceCheckpoint(esm, shardId, nextCheckpoint);
                    }
                } else {
                    Integer maxRetries = esm.getMaximumRetryAttempts();
                    int currentRetries = retryCounts.getOrDefault(batchKey, 0) + 1;
                    if (hasExceededMaximumRecordAge(esm, matched, now)) {
                        LOG.warnv("DynamoDB Streams ESM {0}: maximum record age exceeded for batch ending at {1}",
                                esm.getUuid(), newestFetchedSeq);
                        clearBatchState(batchKey);
                        dispose(esm, shardId, persistedKey, new PendingFailure(matched, invokeResult,
                                currentRetries, "MaximumRecordAgeExceeded", newestFetchedSeq), now);
                    } else if (invokeResult.getFunctionError() != null
                            && Boolean.TRUE.equals(esm.getBisectBatchOnFunctionError()) && matched.size() > 1) {
                        // ponytail: the halved limit is keyed by checkpoint, so once a good half advances the
                        // rest is refetched at full batch size and split again. One window converges on the
                        // poison record in O(log n) invocations, a busy stream in O(log^2 n) worst case;
                        // carrying the right half's end sequence forward would match AWS exactly.
                        int limit = (records.size() + 1) / 2;
                        bisectLimits.put(batchKey, limit);
                        LOG.infov("DynamoDB Streams ESM {0}: function error on {1} record(s), bisecting to {2}",
                                esm.getUuid(), records.size(), limit);
                    } else if (maxRetries != null && maxRetries >= 0 && currentRetries > maxRetries) {
                        LOG.warnv("DynamoDB Streams ESM {0}: maximum retry attempts ({1}) exhausted for batch ending at {2}",
                                esm.getUuid(), maxRetries, newestFetchedSeq);
                        clearBatchState(batchKey);
                        dispose(esm, shardId, persistedKey, new PendingFailure(matched, invokeResult,
                                currentRetries, "RetryAttemptsExhausted", newestFetchedSeq), now);
                    } else {
                        retryCounts.put(batchKey, currentRetries);
                        retryNotBefore.put(batchKey, now + retryBackoffMs(currentRetries));
                        String error = invokeResult.getFunctionError() != null
                                ? invokeResult.getFunctionError()
                                : "batchItemFailures";
                        LOG.warnv("DynamoDB Streams ESM {0}: Lambda returned error [{1}], retry {2}, records will be retried",
                                esm.getUuid(), error, currentRetries);
                    }
                }
            } catch (Exception e) {
                LOG.warnv("DynamoDB Streams ESM {0} poll error: {1}", esm.getUuid(), e.getMessage());
            } finally {
                activePolls.remove(esm.getUuid());
            }
        });
    }

    private record CheckpointOutcome(String checkpoint, int lowestFailedIndex) {}

    private record PendingFailure(List<DynamoDbStreamRecord> records, InvokeResult invokeResult, int invokeCount,
                                  String condition, String advanceTo) {}

    /**
     * Sends a discarded batch to the OnFailure destination and checkpoints past it. A refused send
     * parks the batch under {@code key}, the checkpoint actually persisted, and retries only the send
     * after a backoff. Returns false only when the batch was parked; a stopped mapping sends and
     * parks nothing.
     */
    private boolean dispose(EventSourceMapping esm, String shardId, String key, PendingFailure failure, long now) {
        if (isStopped(esm)) {
            clearBatchState(key);
            return true;
        }
        if (sendToOnFailureDestination(esm, shardId, failure.records(), failure.invokeResult(),
                failure.invokeCount(), failure.condition())) {
            clearBatchState(key);
            advanceCheckpoint(esm, shardId, failure.advanceTo());
            return true;
        }
        if (pendingFailures.put(key, failure) == null) {
            retryCounts.remove(key);
        }
        retryNotBefore.put(key, now + retryBackoffMs(retryCounts.merge(key, 1, Integer::sum)));
        return false;
    }

    private void clearBatchState(String key) {
        retryCounts.remove(key);
        retryNotBefore.remove(key);
        bisectLimits.remove(key);
        pendingFailures.remove(key);
    }

    private int bisectLimit(EventSourceMapping esm, String key) {
        return Boolean.TRUE.equals(esm.getBisectBatchOnFunctionError())
                ? bisectLimits.getOrDefault(key, Integer.MAX_VALUE)
                : Integer.MAX_VALUE;
    }

    private static String batchStateKey(String uuid, String shardId, String sequence) {
        return uuid + ":" + shardId + ":" + (sequence == null ? "TRIM_HORIZON" : sequence);
    }

    /**
     * Returns the last record that can be consumed after a successful invocation. Floci stores the
     * last consumed sequence and resumes with {@code AFTER_SEQUENCE_NUMBER}, so a partial failure
     * checkpoints the record immediately before AWS's lowest reported failed sequence.
     */
    private CheckpointOutcome successfulInvocationCheckpoint(EventSourceMapping esm, InvokeResult invokeResult,
                                                              String previousCheckpoint,
                                                              List<DynamoDbStreamRecord> fetched,
                                                              List<DynamoDbStreamRecord> delivered) {
        String newestFetchedSeq = fetched.get(fetched.size() - 1).getSequenceNumber();
        byte[] payload = invokeResult.getPayload();
        if (!esm.isReportBatchItemFailures() || payload == null || payload.length == 0) {
            return new CheckpointOutcome(newestFetchedSeq, fetched.size());
        }

        try {
            JsonNode response = objectMapper.readTree(payload);
            JsonNode failures = response.get("batchItemFailures");
            if (failures == null || failures.isNull()) {
                return new CheckpointOutcome(newestFetchedSeq, fetched.size());
            }
            if (!failures.isArray()) {
                return new CheckpointOutcome(
                        retryWholeBatch(esm, previousCheckpoint, "batchItemFailures is not an array"), 0);
            }

            Map<String, Integer> fetchedIndexes = new HashMap<>();
            for (int i = 0; i < fetched.size(); i++) {
                fetchedIndexes.put(fetched.get(i).getSequenceNumber(), i);
            }
            Set<String> deliveredSequences = new HashSet<>();
            for (DynamoDbStreamRecord record : delivered) {
                deliveredSequences.add(record.getSequenceNumber());
            }

            int lowestFailedIndex = fetched.size();
            for (JsonNode item : failures) {
                JsonNode identifier = item.get("itemIdentifier");
                if (identifier == null || identifier.isNull() || identifier.asText().isEmpty()) {
                    return new CheckpointOutcome(
                            retryWholeBatch(esm, previousCheckpoint,
                                    "entry has a missing, null or empty itemIdentifier"), 0);
                }
                String sequenceNumber = identifier.asText();
                Integer index = fetchedIndexes.get(sequenceNumber);
                if (index == null || !deliveredSequences.contains(sequenceNumber)) {
                    return new CheckpointOutcome(
                            retryWholeBatch(esm, previousCheckpoint,
                                    "itemIdentifier " + sequenceNumber + " is not in the delivered batch"), 0);
                }
                lowestFailedIndex = Math.min(lowestFailedIndex, index);
            }

            if (lowestFailedIndex == fetched.size()) {
                return new CheckpointOutcome(newestFetchedSeq, fetched.size());
            }
            String checkpoint = lowestFailedIndex == 0
                    ? previousCheckpoint
                    : fetched.get(lowestFailedIndex - 1).getSequenceNumber();
            return new CheckpointOutcome(checkpoint, lowestFailedIndex);
        } catch (Exception e) {
            return new CheckpointOutcome(
                    retryWholeBatch(esm, previousCheckpoint, "response is not valid JSON: " + e.getMessage()), 0);
        }
    }

    private String retryWholeBatch(EventSourceMapping esm, String previousCheckpoint, String reason) {
        LOG.warnv("DynamoDB Streams ESM {0}: malformed batchItemFailures response ({1}), "
                        + "retrying the whole batch",
                esm.getUuid(), reason);
        return previousCheckpoint;
    }

    private boolean hasExceededMaximumRecordAge(EventSourceMapping esm, List<DynamoDbStreamRecord> records,
                                                long now) {
        Integer configuredAge = esm.getMaximumRecordAgeInSeconds();
        if (configuredAge == null || configuredAge <= 0) {
            return false;
        }

        long oldestRecordSeconds = records.stream()
                .mapToLong(DynamoDbStreamRecord::getApproximateCreationDateTime)
                .filter(seconds -> seconds > 0)
                .min()
                .orElse(0);
        if (oldestRecordSeconds == 0) {
            return false;
        }
        return now - oldestRecordSeconds * 1_000 >= configuredAge * 1_000L;
    }

    long retryBackoffMs(int retries) {
        int doublings = Math.min(retries, 16);
        return Math.min(pollIntervalMs * (1L << doublings), MAX_RETRY_BACKOFF_MS);
    }

    /** Returns false only when the configured destination refused the batch. */
    private boolean sendToOnFailureDestination(EventSourceMapping esm, String shardId,
                                               List<DynamoDbStreamRecord> records,
                                               InvokeResult invokeResult,
                                               int invokeCount,
                                               String condition) {
        if (esm.getDestinationConfig() == null || esm.getDestinationConfig().getOnFailure() == null) {
            return true;
        }
        String destinationArn = esm.getDestinationConfig().getOnFailure().getDestination();
        if (destinationArn == null || destinationArn.isBlank()) {
            return true;
        }

        try {
            if (destinationArn.contains(":sqs:")) {
                String region = AwsArnUtils.regionOrDefault(destinationArn, esm.getRegion());
                String queueUrl = AwsArnUtils.arnToQueueUrl(destinationArn, baseUrl);
                String payload = buildOnFailurePayload(esm, shardId, records, invokeResult, invokeCount, condition);
                sqsService.sendMessage(queueUrl, payload, 0, region);
                LOG.infov("DynamoDB Streams ESM {0}: sent failed batch to SQS DLQ {1}", esm.getUuid(), destinationArn);
            } else if (destinationArn.contains(":sns:")) {
                String region = AwsArnUtils.regionOrDefault(destinationArn, esm.getRegion());
                String payload = buildOnFailurePayload(esm, shardId, records, invokeResult, invokeCount, condition);
                snsService.publish(destinationArn, null, payload, "ESM OnFailure", region);
                LOG.infov("DynamoDB Streams ESM {0}: sent failed batch to SNS DLQ {1}", esm.getUuid(), destinationArn);
            } else if (destinationArn.contains(":s3:")) {
                AwsArnUtils.Arn arn = AwsArnUtils.parse(destinationArn);
                if (!"s3".equals(arn.service()) || arn.resource().isBlank()) {
                    throw new IllegalArgumentException("Invalid S3 destination ARN: " + destinationArn);
                }
                String key = buildS3OnFailureKey(esm.getUuid(), shardId, Instant.now(), UUID.randomUUID());
                String s3Payload = buildS3OnFailurePayload(esm, shardId, records, invokeResult, invokeCount, condition);
                RequestScopes.runAs(esm.getAccountId(), () ->
                        s3Service.putObject(arn.resource(), key,
                                s3Payload.getBytes(StandardCharsets.UTF_8),
                                "application/json", Map.of()));
                LOG.infov("DynamoDB Streams ESM {0}: sent failed batch to S3 bucket {1} with key {2}",
                        esm.getUuid(), arn.resource(), key);
            } else {
                LOG.warnv("DynamoDB Streams ESM {0}: unsupported OnFailure destination ARN {1}",
                        esm.getUuid(), destinationArn);
            }
        } catch (Exception e) {
            LOG.errorv("DynamoDB Streams ESM {0}: OnFailure destination {1} refused the batch, retrying the send: {2}",
                    esm.getUuid(), destinationArn, e.getMessage());
            return false;
        }
        return true;
    }

    private String buildS3OnFailurePayload(EventSourceMapping esm, String shardId,
                                            List<DynamoDbStreamRecord> records,
                                            InvokeResult invokeResult,
                                            int invokeCount,
                                            String condition) {
        try {
            ObjectNode root = (ObjectNode) objectMapper.readTree(
                    buildOnFailurePayload(esm, shardId, records, invokeResult, invokeCount, condition));
            root.put("payload", buildDynamoDbEvent(records, esm));
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize S3 OnFailure payload", e);
        }
    }

    static String buildS3OnFailureKey(String esmUuid, String shardId, Instant now, UUID randomId) {
        return "aws/lambda/" + esmUuid + "/" + shardId + "/"
                + S3_ON_FAILURE_PATH_DATE_FORMATTER.format(now) + "/"
                + S3_ON_FAILURE_FILE_TIME_FORMATTER.format(now) + "-" + randomId;
    }

    private String buildOnFailurePayload(EventSourceMapping esm, String shardId,
                                         List<DynamoDbStreamRecord> records,
                                         InvokeResult invokeResult,
                                         int invokeCount,
                                         String condition) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("version", "1.0");
            root.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));

            ObjectNode requestContext = root.putObject("requestContext");
            requestContext.put("requestId", invokeResult.getRequestId() != null ? invokeResult.getRequestId() : "");
            requestContext.put("functionArn", esm.getFunctionArn() != null ? esm.getFunctionArn() : "");
            requestContext.put("condition", condition);
            requestContext.put("approximateInvokeCount", invokeCount);

            ObjectNode responseContext = root.putObject("responseContext");
            responseContext.put("statusCode", invokeResult.getStatusCode() != 0 ? invokeResult.getStatusCode() : 200);
            responseContext.put("executedVersion", invokeResult.getExecutedVersion() != null ? invokeResult.getExecutedVersion() : "$LATEST");
            if (invokeResult.getFunctionError() != null) {
                responseContext.put("functionError", invokeResult.getFunctionError());
            }

            ObjectNode batchInfo = root.putObject("DDBStreamBatchInfo");
            batchInfo.put("shardId", shardId);
            String startSeq = records.isEmpty() ? "" : records.get(0).getSequenceNumber();
            String endSeq = records.isEmpty() ? "" : records.get(records.size() - 1).getSequenceNumber();
            batchInfo.put("startSequenceNumber", startSeq);
            batchInfo.put("endSequenceNumber", endSeq);

            if (!records.isEmpty()) {
                long firstArrival = records.get(0).getApproximateCreationDateTime();
                long lastArrival = records.get(records.size() - 1).getApproximateCreationDateTime();
                batchInfo.put("approximateArrivalOfFirstRecord",
                        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(firstArrival)));
                batchInfo.put("approximateArrivalOfLastRecord",
                        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(lastArrival)));
            }
            batchInfo.put("batchSize", records.size());
            batchInfo.put("streamArn", esm.getEventSourceArn());

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            LOG.warnv("Failed to serialize OnFailure payload: {0}", e.getMessage());
            return "{}";
        }
    }

    private String buildDynamoDbEvent(List<DynamoDbStreamRecord> records, EventSourceMapping esm) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            ArrayNode array = root.putArray("Records");
            for (DynamoDbStreamRecord rec : records) {
                array.add(buildDynamoDbRecordNode(rec, esm));
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize DynamoDB Streams event", e);
        }
    }

    /**
     * Builds the single-record node: top-level {@code eventName}/metadata plus the {@code dynamodb} map
     * with AttributeValue-wrapped images. This is both the delivery record shape and the exact structure a
     * DynamoDB filter pattern matches against, so it serves the matcher unchanged. (Numeric operators
     * naturally never match here because AttributeValue numbers are JSON strings, AWS parity for free.)
     */
    private ObjectNode buildDynamoDbRecordNode(DynamoDbStreamRecord rec, EventSourceMapping esm) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("eventID", rec.getEventId());
        item.put("eventVersion", rec.getEventVersion());
        item.put("awsRegion", rec.getAwsRegion());
        item.put("eventName", rec.getEventName());
        item.put("eventSourceARN", esm.getEventSourceArn());
        item.put("eventSource", rec.getEventSource());

        ObjectNode dynamodb = item.putObject("dynamodb");
        dynamodb.put("StreamViewType", rec.getStreamViewType());
        dynamodb.put("SequenceNumber", rec.getSequenceNumber());
        dynamodb.put("SizeBytes", 100);
        dynamodb.put("ApproximateCreationDateTime", (double) rec.getApproximateCreationDateTime());
        if (rec.getKeys() != null) {
            dynamodb.set("Keys", rec.getKeys());
        }
        if (rec.getNewImage() != null) {
            dynamodb.set("NewImage", rec.getNewImage());
        }
        if (rec.getOldImage() != null) {
            dynamodb.set("OldImage", rec.getOldImage());
        }
        return item;
    }

    private synchronized void advanceCheckpoint(EventSourceMapping esm, String shardId, String newestSeq) {
        if (isStopped(esm)) {
            return;
        }
        esm.getShardSequenceNumbers().put(shardId, newestSeq);
        esmStore.saveForAccount(esm.getAccountId(), esm);
    }

    private boolean isStopped(EventSourceMapping esm) {
        return resetting || stopped.contains(esm.getUuid()) || !exists(esm);
    }

    private boolean exists(EventSourceMapping esm) {
        return esmStore.getForAccount(esm.getAccountId(), esm.getUuid()).isPresent();
    }
}
