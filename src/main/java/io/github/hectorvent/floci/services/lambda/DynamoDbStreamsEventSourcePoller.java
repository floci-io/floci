package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbTtlIdentity;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbStreamReader;
import io.github.hectorvent.floci.services.lambda.model.EventSourceMapping;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.pipes.PipesFilterMatcher;
import io.vertx.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

@ApplicationScoped
public class DynamoDbStreamsEventSourcePoller implements Resettable {

    private static final Logger LOG = Logger.getLogger(DynamoDbStreamsEventSourcePoller.class);
    private static final String BATCH_INFO_NAME = "DDBStreamBatchInfo";

    /** Raised by the stream when a stored checkpoint has aged out of the retained window. */
    private static final String TRIMMED_DATA_ACCESS_EXCEPTION = "TrimmedDataAccessException";

    private final Vertx vertx;
    private final DynamoDbStreamReader streamReader;
    private final LambdaExecutorService executorService;
    private final LambdaTargetResolver targetResolver;
    private final EsmStore esmStore;
    private final ObjectMapper objectMapper;
    private final PipesFilterMatcher filterMatcher;
    private final StreamBatchFailures batchFailures;
    private final long pollIntervalMs;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Long> timerIds = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, Boolean> activePolls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> retryCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> retryNotBefore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> bisectLimits = new ConcurrentHashMap<>();
    final Set<String> stopped = ConcurrentHashMap.newKeySet();
    /** {@code uuid:shardId} of shards a mapping read to their end; re-derived from committed progress. */
    private final Set<String> finishedShards = ConcurrentHashMap.newKeySet();
    /** Set during a reset and after shutdown: no invocation starts and no checkpoint is saved. */
    private volatile boolean quiesced;
    private final ExecutorService pollExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "dynamodb-streams-esm-poller");
        t.setDaemon(true);
        return t;
    });

    @Inject
    public DynamoDbStreamsEventSourcePoller(Vertx vertx, DynamoDbStreamReader streamReader,
                                            LambdaExecutorService executorService,
                                            LambdaTargetResolver targetResolver,
                                            EsmStore esmStore,
                                            ObjectMapper objectMapper,
                                            EmulatorConfig config,
                                            PipesFilterMatcher filterMatcher,
                                            StreamBatchFailures batchFailures) {
        this(vertx, streamReader, executorService, targetResolver, esmStore, objectMapper, config,
                filterMatcher, batchFailures, System::currentTimeMillis);
    }

    DynamoDbStreamsEventSourcePoller(Vertx vertx, DynamoDbStreamReader streamReader,
                                     LambdaExecutorService executorService,
                                     LambdaTargetResolver targetResolver,
                                     EsmStore esmStore,
                                     ObjectMapper objectMapper,
                                     EmulatorConfig config,
                                     PipesFilterMatcher filterMatcher,
                                     StreamBatchFailures batchFailures,
                                     LongSupplier clock) {
        this.vertx = vertx;
        this.streamReader = streamReader;
        this.executorService = executorService;
        this.targetResolver = targetResolver;
        this.esmStore = esmStore;
        this.objectMapper = objectMapper;
        this.pollIntervalMs = config.services().lambda().pollIntervalMs();
        this.filterMatcher = filterMatcher;
        this.batchFailures = batchFailures;
        this.clock = clock;
    }

    public void startPersistedPollers() {
        for (EventSourceMapping esm : esmStore.listAll()) {
            if (esm.getEventSourceArn() != null && esm.getEventSourceArn().contains(":dynamodb:")) {
                if (streamReader.checkpointLifetime() == DynamoDbStreamReader.CheckpointLifetime.PROCESS) {
                    discardStaleShardCheckpoints(esm);
                }
                if (esm.isEnabled()) {
                    startPolling(esm);
                }
            }
        }
        LOG.infov("DynamoDbStreamsEventSourcePoller initialized");
    }

    /**
     * Discards any shard checkpoints a DynamoDB Streams ESM persisted during a previous run, before
     * its poller is (re)started at startup, when the engine's checkpoints last only for the process
     * ({@link DynamoDbStreamReader.CheckpointLifetime#PROCESS}).
     *
     * <p>Such a stream's history is volatile: the native engine keeps its records and sequence
     * counter only in memory, so a restart recreates the stream empty and its sequence numbers start
     * over from {@code 000000000000000000001}. A sequence number from a previous process is therefore
     * meaningless, and a {@code shardSequenceNumbers} checkpoint saved then usually points
     * <em>past</em> every record in the new stream epoch: resuming from it with an
     * {@code AFTER_SEQUENCE_NUMBER} iterator silently skips every freshly written record (no invoke,
     * no error, no log) until the new sequence numbers climb back above the stale value. Clearing the
     * checkpoint lets the poller resume from {@code TRIM_HORIZON}, which matches the volatility of the
     * stream itself. An engine whose checkpoints last as long as the stream
     * ({@link DynamoDbStreamReader.CheckpointLifetime#STREAM}) keeps its history across restarts, so
     * its progress is kept. See issue #2076.
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

    /**
     * Pins a new LATEST mapping after each shard's newest record, so only later writes are delivered.
     * A stream that does not exist yet has nothing to skip.
     */
    public void initializeStartingPosition(EventSourceMapping esm) {
        if (!"LATEST".equals(esm.getStartingPosition()) || !esm.getShardSequenceNumbers().isEmpty()) {
            return;
        }
        try {
            esm.getShardSequenceNumbers().putAll(
                    streamReader.newestSequenceNumbers(DynamoDbStreamReader.Stream.of(esm.getEventSourceArn())));
        } catch (AwsException e) {
            if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("DynamoDB Streams ESM {0}: stream {1} does not exist yet, so LATEST is not pinned",
                    esm.getUuid(), esm.getEventSourceArn());
        }
    }

    /**
     * Stops polling for good. Taking the monitor drains an in-flight advanceCheckpoint save, and no
     * checkpoint is saved after this returns. Idempotent, so it may run again from {@code @PreDestroy}.
     */
    @PreDestroy
    public synchronized void shutdown() {
        quiesced = true;
        pollExecutor.shutdownNow();
        timerIds.values().forEach(vertx::cancelTimer);
        timerIds.clear();
    }

    @Override
    public synchronized void beforeReset() {
        // Taking the monitor drains an in-flight advanceCheckpoint save; it is released before the wipe.
        quiesced = true;
    }

    @Override
    public synchronized void afterReset() {
        quiesced = pollExecutor.isShutdown();
    }

    public void clear() {
        timerIds.values().forEach(vertx::cancelTimer);
        timerIds.clear();
        activePolls.clear();
        stopped.clear();
        retryCounts.clear();
        retryNotBefore.clear();
        bisectLimits.clear();
        finishedShards.clear();
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
        finishedShards.removeIf(k -> k.startsWith(uuid + ":"));
    }


    void pollAndInvoke(EventSourceMapping esm) {
        if (activePolls.putIfAbsent(esm.getUuid(), Boolean.TRUE) != null) {
            return;
        }
        pollExecutor.submit(() -> {
            try {
                LambdaFunction fn = targetResolver.resolveMappingTarget(esm).orElse(null);
                if (fn == null) {
                    LOG.warnv("DynamoDB Streams ESM {0}: function {1} not found, skipping",
                            esm.getUuid(), esm.getFunctionName());
                    return;
                }
                DynamoDbStreamReader.Stream stream = DynamoDbStreamReader.Stream.of(esm.getEventSourceArn());
                List<DynamoDbStreamReader.Shard> shards = streamReader.shards(stream);
                Set<String> finished = shards.stream()
                        .map(DynamoDbStreamReader.Shard::shardId)
                        .filter(shardId -> finishedShards.contains(finishedShardKey(esm.getUuid(), shardId)))
                        .collect(Collectors.toSet());
                // ponytail: a shard whose read or invoke throws ends the tick for the shards after it; the next
                // tick starts over from committed progress, so it only delays them.
                for (DynamoDbStreamReader.Shard shard : DynamoDbStreamReader.readable(shards, finished)) {
                    pollShard(esm, fn, stream, shard.shardId());
                }
            } catch (Exception e) {
                LOG.warnv("DynamoDB Streams ESM {0} poll error: {1}", esm.getUuid(), e.getMessage());
            } finally {
                activePolls.remove(esm.getUuid());
            }
        });
    }

    private void pollShard(EventSourceMapping esm, LambdaFunction fn, DynamoDbStreamReader.Stream stream,
                           String shardId) {
        String lastSeq = esm.getShardSequenceNumbers().get(shardId);
        String persistedKey = StreamBatchFailures.batchStateKey(esm.getUuid(), shardId, lastSeq);

        boolean checkpointTrimmed = false;
        DynamoDbStreamReader.RecordsPage page;
        try {
            page = streamReader.readAfter(stream, shardId, lastSeq,
                    Math.min(StreamBatchFailures.bisectLimit(esm, bisectLimits, persistedKey), esm.getBatchSize()));
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
            page = streamReader.readAfter(stream, shardId, null, Math.min(esm.getBatchSize(),
                    StreamBatchFailures.bisectLimit(esm, bisectLimits,
                            StreamBatchFailures.batchStateKey(esm.getUuid(), shardId, null))));
        }
        List<DynamoDbStreamReader.Record> records = page.records();

        if (records.isEmpty()) {
            if (page.closed()) {
                finishedShards.add(finishedShardKey(esm.getUuid(), shardId));
            }
            return;
        }
        // ponytail: a stop or reset racing this check does not cancel an in-flight invoke; the stop checks in
        // advanceCheckpoint and dispose drop its checkpoint and OnFailure send. Only a stop landing
        // between dispose's check and the send itself still delivers; a lock around the send would close it.
        boolean enabled = esmStore.getForAccount(esm.getAccountId(), esm.getUuid())
                .map(EventSourceMapping::isEnabled)
                .orElse(false);
        if (quiesced || !enabled) {
            return;
        }

        // Advance to the newest FETCHED record whenever the batch is disposed of, invoked or
        // fully filtered out, so filtered-out records are consumed, not re-read forever. Leave
        // the checkpoint unmoved only when an attempted invoke fails (the window retries).
        String newestFetchedSeq = records.get(records.size() - 1).sequenceNumber();

        List<DynamoDbStreamReader.Record> matched = records;
        JsonNode filterParams = EsmFilterCriteriaUtils.matcherSourceParameters(objectMapper, esm.getFilterCriteria());
        if (filterParams != null) {
            List<JsonNode> filterNodes = new ArrayList<>(records.size());
            for (DynamoDbStreamReader.Record rec : records) {
                filterNodes.add(buildDynamoDbRecordNode(rec, esm));
            }
            matched = EsmFilterCriteriaUtils.selectMatched(
                    records, filterNodes, filterMatcher.applyFilterCriteria(filterNodes, filterParams));
        }

        String batchKey = StreamBatchFailures.batchStateKey(esm.getUuid(), shardId, checkpointTrimmed ? null : lastSeq);
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
            String qualifier = LambdaTargetResolver.mappingQualifier(esm);
            invokeResult = qualifier == null
                    ? executorService.invoke(fn, eventJson.getBytes(), InvocationType.RequestResponse)
                    : executorService.invoke(fn, eventJson.getBytes(), InvocationType.RequestResponse, 0, qualifier);
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
            String nextBatchKey = StreamBatchFailures.batchStateKey(esm.getUuid(), shardId, nextCheckpoint);
            int currentRetries = retryCounts.merge(nextBatchKey, 1, Integer::sum);
            Set<String> deliveredSeqs = new HashSet<>();
            for (DynamoDbStreamReader.Record rec : matched) {
                deliveredSeqs.add(rec.sequenceNumber());
            }
            List<DynamoDbStreamReader.Record> failedRecords = new ArrayList<>();
            for (int i = outcome.lowestFailedIndex(); i < records.size(); i++) {
                DynamoDbStreamReader.Record rec = records.get(i);
                if (deliveredSeqs.contains(rec.sequenceNumber())) {
                    failedRecords.add(rec);
                }
            }
            if (failedRecords.isEmpty()) {
                failedRecords = records.subList(outcome.lowestFailedIndex(), records.size());
            }

            if (hasExceededMaximumRecordAge(esm, failedRecords, now)) {
                LOG.warnv("DynamoDB Streams ESM {0}: maximum record age exceeded for batch ending at {1}",
                        esm.getUuid(), newestFetchedSeq);
                dispose(esm, shardId, nextBatchKey, new PendingFailure(failedRecords, invokeResult,
                        currentRetries, StreamBatchFailures.MAXIMUM_RECORD_AGE_EXCEEDED, newestFetchedSeq));
            } else if (StreamBatchFailures.retryAttemptsExhausted(esm, currentRetries)) {
                LOG.warnv("DynamoDB Streams ESM {0}: maximum retry attempts ({1}) exhausted for batch ending at {2}",
                        esm.getUuid(), esm.getMaximumRetryAttempts(), newestFetchedSeq);
                dispose(esm, shardId, nextBatchKey, new PendingFailure(failedRecords, invokeResult,
                        currentRetries, StreamBatchFailures.RETRY_ATTEMPTS_EXHAUSTED, newestFetchedSeq));
            } else {
                retryNotBefore.put(nextBatchKey, now + retryBackoffMs(currentRetries));
                LOG.warnv("DynamoDB Streams ESM {0}: Lambda returned error [batchItemFailures], retry {1}, records will be retried",
                        esm.getUuid(), currentRetries);
                advanceCheckpoint(esm, shardId, nextCheckpoint);
            }
        } else {
            int currentRetries = retryCounts.getOrDefault(batchKey, 0) + 1;
            if (hasExceededMaximumRecordAge(esm, matched, now)) {
                LOG.warnv("DynamoDB Streams ESM {0}: maximum record age exceeded for batch ending at {1}",
                        esm.getUuid(), newestFetchedSeq);
                clearBatchState(batchKey);
                dispose(esm, shardId, persistedKey, new PendingFailure(matched, invokeResult,
                        currentRetries, StreamBatchFailures.MAXIMUM_RECORD_AGE_EXCEEDED, newestFetchedSeq));
            } else if (StreamBatchFailures.splitsOnFunctionError(esm, invokeResult, matched.size())) {
                // ponytail: the halved limit is keyed by checkpoint, so once a good half advances the
                // rest is refetched at full batch size and split again. One window converges on the
                // poison record in O(log n) invocations, a busy stream in O(log^2 n) worst case;
                // carrying the right half's end sequence forward would match AWS exactly.
                int limit = StreamBatchFailures.splitBatchSize(records.size());
                bisectLimits.put(batchKey, limit);
                LOG.infov("DynamoDB Streams ESM {0}: function error on {1} record(s), bisecting to {2}",
                        esm.getUuid(), records.size(), limit);
            } else if (StreamBatchFailures.retryAttemptsExhausted(esm, currentRetries)) {
                LOG.warnv("DynamoDB Streams ESM {0}: maximum retry attempts ({1}) exhausted for batch ending at {2}",
                        esm.getUuid(), esm.getMaximumRetryAttempts(), newestFetchedSeq);
                clearBatchState(batchKey);
                dispose(esm, shardId, persistedKey, new PendingFailure(matched, invokeResult,
                        currentRetries, StreamBatchFailures.RETRY_ATTEMPTS_EXHAUSTED, newestFetchedSeq));
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
    }

    private record CheckpointOutcome(String checkpoint, int lowestFailedIndex) {}

    private record PendingFailure(List<DynamoDbStreamReader.Record> records, InvokeResult invokeResult, int invokeCount,
                                  String condition, String advanceTo) {}

    /** Sends a discarded batch to its OnFailure destination and checkpoints past it. */
    private void dispose(EventSourceMapping esm, String shardId, String key, PendingFailure failure) {
        if (isStopped(esm)) {
            clearBatchState(key);
            return;
        }
        batchFailures.sendToOnFailureDestination(esm, discardedBatch(esm, shardId, failure.records()),
                failure.invokeResult(), failure.invokeCount(), failure.condition());
        clearBatchState(key);
        advanceCheckpoint(esm, shardId, failure.advanceTo());
    }

    private void clearBatchState(String key) {
        retryCounts.remove(key);
        retryNotBefore.remove(key);
        bisectLimits.remove(key);
    }

    private static String finishedShardKey(String uuid, String shardId) {
        return uuid + ":" + shardId;
    }

    /**
     * Returns the last record that can be consumed after a successful invocation. Floci stores the
     * last consumed sequence and resumes with {@code AFTER_SEQUENCE_NUMBER}, so a partial failure
     * checkpoints the record immediately before AWS's lowest reported failed sequence.
     */
    private CheckpointOutcome successfulInvocationCheckpoint(EventSourceMapping esm, InvokeResult invokeResult,
                                                              String previousCheckpoint,
                                                              List<DynamoDbStreamReader.Record> fetched,
                                                              List<DynamoDbStreamReader.Record> delivered) {
        String newestFetchedSeq = fetched.get(fetched.size() - 1).sequenceNumber();
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
                fetchedIndexes.put(fetched.get(i).sequenceNumber(), i);
            }
            Set<String> deliveredSequences = new HashSet<>();
            for (DynamoDbStreamReader.Record record : delivered) {
                deliveredSequences.add(record.sequenceNumber());
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
                    : fetched.get(lowestFailedIndex - 1).sequenceNumber();
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

    private boolean hasExceededMaximumRecordAge(EventSourceMapping esm, List<DynamoDbStreamReader.Record> records,
                                                long now) {
        OptionalLong oldestRecordSeconds = records.stream()
                .mapToLong(DynamoDbStreamsEventSourcePoller::approximateCreationSeconds)
                .filter(seconds -> seconds > 0)
                .min();
        return oldestRecordSeconds.isPresent() && StreamBatchFailures.maximumRecordAgeExceeded(
                esm, Instant.ofEpochSecond(oldestRecordSeconds.getAsLong()), now);
    }

    private static long approximateCreationSeconds(DynamoDbStreamReader.Record rec) {
        return rec.awsRecord().path("dynamodb").path("ApproximateCreationDateTime").asLong();
    }

    long retryBackoffMs(int retries) {
        return StreamBatchFailures.retryBackoffMs(pollIntervalMs, retries);
    }

    private StreamBatchFailures.DiscardedBatch discardedBatch(EventSourceMapping esm, String shardId,
                                                              List<DynamoDbStreamReader.Record> records) {
        DynamoDbStreamReader.Record first = records.get(0);
        DynamoDbStreamReader.Record last = records.get(records.size() - 1);
        return new StreamBatchFailures.DiscardedBatch(BATCH_INFO_NAME, shardId,
                first.sequenceNumber(), last.sequenceNumber(),
                creationTime(first), creationTime(last),
                records.size(), esm.getEventSourceArn(), () -> buildDynamoDbEvent(records, esm));
    }

    /** A stream record's creation time is whole seconds, and AWS writes it without a fraction. */
    private static String creationTime(DynamoDbStreamReader.Record rec) {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(approximateCreationSeconds(rec)));
    }

    private String buildDynamoDbEvent(List<DynamoDbStreamReader.Record> records, EventSourceMapping esm) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            ArrayNode array = root.putArray("Records");
            for (DynamoDbStreamReader.Record rec : records) {
                array.add(buildDynamoDbRecordNode(rec, esm));
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize DynamoDB Streams event", e);
        }
    }

    /**
     * Builds the single-record node: a copy of the AWS stream record, whose top-level {@code eventName}
     * and metadata and {@code dynamodb} map with AttributeValue-wrapped images Lambda delivers as they
     * are, plus {@code eventSourceARN}, with a {@code userIdentity} renamed from the Streams API's
     * capitalised members to the event record's. {@code ApproximateCreationDateTime} is written as a
     * double, as Floci has always delivered it. This is both the delivery record shape and the exact
     * structure a DynamoDB filter pattern matches against, so it serves the matcher unchanged. (Numeric
     * operators naturally never match here because AttributeValue numbers are JSON strings, AWS parity
     * for free.)
     */
    private ObjectNode buildDynamoDbRecordNode(DynamoDbStreamReader.Record rec, EventSourceMapping esm) {
        ObjectNode item = DynamoDbTtlIdentity.toEventRecord(rec.awsRecord());
        item.put("eventSourceARN", esm.getEventSourceArn());
        JsonNode created = item.path("dynamodb").path("ApproximateCreationDateTime");
        if (created.isNumber()) {
            ((ObjectNode) item.get("dynamodb")).put("ApproximateCreationDateTime", created.asDouble());
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
        return quiesced || stopped.contains(esm.getUuid()) || !exists(esm);
    }

    private boolean exists(EventSourceMapping esm) {
        return esmStore.getForAccount(esm.getAccountId(), esm.getUuid()).isPresent();
    }
}
