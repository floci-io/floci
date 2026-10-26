package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import io.github.hectorvent.floci.services.kinesis.model.KinesisRecord;
import io.github.hectorvent.floci.services.kinesis.model.KinesisShard;
import io.github.hectorvent.floci.services.kinesis.model.KinesisStream;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

@ApplicationScoped
public class KinesisEventSourcePoller implements Resettable {

    private static final Logger LOG = Logger.getLogger(KinesisEventSourcePoller.class);
    private static final String BATCH_INFO_NAME = "KinesisBatchInfo";

    private final Vertx vertx;
    private final KinesisService kinesisService;
    private final LambdaExecutorService executorService;
    private final LambdaTargetResolver targetResolver;
    private final EsmStore esmStore;
    private final long pollIntervalMs;
    private final ObjectMapper objectMapper;
    private final PipesFilterMatcher filterMatcher;
    private final StreamBatchFailures batchFailures;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Long> timerIds = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, Boolean> activePolls = new ConcurrentHashMap<>();
    /** Keyed by {@link StreamBatchFailures#batchStateKey}. */
    private final ConcurrentHashMap<String, Integer> retryCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> retryNotBefore = new ConcurrentHashMap<>();
    /** The batch size a split batch is refetched at, keyed like {@link #retryCounts}. */
    private final ConcurrentHashMap<String, Integer> bisectLimits = new ConcurrentHashMap<>();
    /** Set during a reset and after shutdown: a failed invoke then counts for nothing. */
    private volatile boolean quiesced;
    /** Mappings stopped since their last start; a delete stops the mapping before the store forgets it. */
    final Set<String> stopped = ConcurrentHashMap.newKeySet();
    private final ExecutorService pollExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "kinesis-esm-poller");
        t.setDaemon(true);
        return t;
    });

    @Inject
    public KinesisEventSourcePoller(Vertx vertx, KinesisService kinesisService,
                                     LambdaExecutorService executorService,
                                     LambdaTargetResolver targetResolver,
                                     EsmStore esmStore, EmulatorConfig config,
                                     ObjectMapper objectMapper,
                                     PipesFilterMatcher filterMatcher,
                                     StreamBatchFailures batchFailures) {
        this(vertx, kinesisService, executorService, targetResolver, esmStore, config, objectMapper,
                filterMatcher, batchFailures, System::currentTimeMillis);
    }

    KinesisEventSourcePoller(Vertx vertx, KinesisService kinesisService,
                             LambdaExecutorService executorService,
                             LambdaTargetResolver targetResolver,
                             EsmStore esmStore, EmulatorConfig config,
                             ObjectMapper objectMapper,
                             PipesFilterMatcher filterMatcher,
                             StreamBatchFailures batchFailures,
                             LongSupplier clock) {
        this.vertx = vertx;
        this.kinesisService = kinesisService;
        this.executorService = executorService;
        this.targetResolver = targetResolver;
        this.esmStore = esmStore;
        this.pollIntervalMs = config.services().lambda().pollIntervalMs();
        this.objectMapper = objectMapper;
        this.filterMatcher = filterMatcher;
        this.batchFailures = batchFailures;
        this.clock = clock;
    }

    public void startPersistedPollers() {
        List<EventSourceMapping> esms = esmStore.listAll();
        for (EventSourceMapping esm : esms) {
            if (esm.isEnabled() && esm.getEventSourceArn().contains(":kinesis:")) {
                startPolling(esm);
            }
        }
    }

    /** Runs before the Lambda containers are stopped, so an invoke cut short by them is not taken for a failure. */
    @PreDestroy
    public synchronized void shutdown() {
        quiesced = true;
        pollExecutor.shutdownNow();
        timerIds.values().forEach(vertx::cancelTimer);
        timerIds.clear();
    }

    @Override
    public synchronized void beforeReset() {
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
        retryCounts.clear();
        retryNotBefore.clear();
        bisectLimits.clear();
        stopped.clear();
    }

    public synchronized void startPolling(EventSourceMapping esm) {
        stopped.remove(esm.getUuid());
        if (timerIds.containsKey(esm.getUuid())) return;
        String uuid = esm.getUuid();
        String accountId = esm.getAccountId();
        long timerId = vertx.setPeriodic(pollIntervalMs, id -> {
            esmStore.getForAccount(accountId, uuid).ifPresent(latest -> {
                if (latest.isEnabled()) pollAndInvoke(latest);
            });
        });
        timerIds.put(uuid, timerId);
        LOG.infov("Started Kinesis polling for ESM {0} → {1}", uuid, esm.getEventSourceArn());
    }

    public synchronized void stopPolling(String uuid) {
        stopped.add(uuid);
        Long timerId = timerIds.remove(uuid);
        if (timerId != null) {
            vertx.cancelTimer(timerId);
        }
        retryCounts.keySet().removeIf(k -> k.startsWith(uuid + ":"));
        retryNotBefore.keySet().removeIf(k -> k.startsWith(uuid + ":"));
        bisectLimits.keySet().removeIf(k -> k.startsWith(uuid + ":"));
    }

    /** Drops the stop tombstone once the store has forgotten the mapping, which stillActive then sees. */
    void mappingDeleted(String uuid) {
        stopped.remove(uuid);
    }

    /** Package-private (not private) only so unit tests can drive a single poll directly. */
    void pollAndInvoke(EventSourceMapping esm) {
        if (activePolls.putIfAbsent(esm.getUuid(), Boolean.TRUE) != null) return;
        pollExecutor.submit(() -> {
            try {
                LambdaFunction fn = targetResolver.resolveMappingTarget(esm).orElse(null);
                if (fn == null) return;

                String streamName = streamNameFromArn(esm.getEventSourceArn());
                KinesisStream stream = kinesisService.describeStreamForAccount(
                        esm.getAccountId(), streamName, esm.getRegion());

                for (KinesisShard shard : stream.getShards()) {
                    pollShard(esm, fn, streamName, shard.getShardId());
                }
            } catch (Exception e) {
                LOG.warnv("Kinesis ESM {0} error: {1}", esm.getUuid(), e.getMessage());
            } finally {
                activePolls.remove(esm.getUuid());
            }
        });
    }

    private void pollShard(EventSourceMapping esm, LambdaFunction fn, String streamName, String shardId) {
        String lastSeq = esm.getShardSequenceNumbers().get(shardId);
        String batchKey = StreamBatchFailures.batchStateKey(esm.getUuid(), shardId, lastSeq);
        if (clock.getAsLong() < retryNotBefore.getOrDefault(batchKey, 0L)) {
            return;
        }

        String iterator;
        if (lastSeq == null) {
            iterator = kinesisService.getShardIteratorForAccount(esm.getAccountId(),
                    streamName, shardId, "TRIM_HORIZON", null, esm.getRegion());
        } else {
            iterator = kinesisService.getShardIteratorForAccount(esm.getAccountId(),
                    streamName, shardId, "AFTER_SEQUENCE_NUMBER", lastSeq, esm.getRegion());
        }

        Map<String, Object> result = kinesisService.getRecordsForAccount(
                esm.getAccountId(), iterator, Math.min(esm.getBatchSize(), StreamBatchFailures.bisectLimit(esm, bisectLimits, batchKey)),
                esm.getRegion());
        List<KinesisRecord> records = (List<KinesisRecord>) result.get("Records");

        if (records.isEmpty()) {
            return;
        }

        // The checkpoint must advance to the newest FETCHED record whenever the batch is
        // disposed of: by a successful invoke, because a filter matched nothing, or because the
        // batch was discarded after its retries or its record age ran out. Filtered-out records are
        // consumed, not re-read forever. A failed invoke that may still be retried leaves the
        // checkpoint unmoved, and reported batch item failures move it only up to the lowest failed
        // record.
        String newestFetchedSeq = records.get(records.size() - 1).getSequenceNumber();

        List<KinesisRecord> matched = records;
        JsonNode filterParams = EsmFilterCriteriaUtils.matcherSourceParameters(objectMapper, esm.getFilterCriteria());
        if (filterParams != null) {
            List<JsonNode> filterNodes = new ArrayList<>(records.size());
            for (KinesisRecord rec : records) {
                filterNodes.add(buildKinesisFilterNode(rec));
            }
            matched = EsmFilterCriteriaUtils.selectMatched(
                    records, filterNodes, filterMatcher.applyFilterCriteria(filterNodes, filterParams));
        }

        if (matched.isEmpty()) {
            // Whole batch filtered out: consume it (advance past), do not invoke, do not retry.
            clearBatchState(batchKey);
            advanceCheckpoint(esm, shardId, newestFetchedSeq);
            return;
        }

        String eventJson = buildKinesisEvent(matched, esm, shardId);
        InvokeResult invokeResult;
        try {
            String qualifier = LambdaTargetResolver.mappingQualifier(esm);
            invokeResult = qualifier == null
                    ? executorService.invoke(fn, eventJson.getBytes(), InvocationType.RequestResponse)
                    : executorService.invoke(fn, eventJson.getBytes(), InvocationType.RequestResponse, 0, qualifier);
        } catch (AwsException e) {
            if (!"TooManyRequestsException".equals(e.getErrorCode())) {
                throw e;
            }
            // A throttled batch is retried until it is older than MaximumRecordAgeInSeconds, as on AWS.
            if (stillActive(esm) && StreamBatchFailures.maximumRecordAgeExceeded(
                    esm, matched.get(0).getApproximateArrivalTimestamp(), clock.getAsLong())) {
                discard(esm, shardId, batchKey, matched, new InvokeResult(429, null, null, null, null),
                        retryCounts.getOrDefault(batchKey, 0), StreamBatchFailures.MAXIMUM_RECORD_AGE_EXCEEDED,
                        newestFetchedSeq);
                return;
            }
            LOG.infov("Kinesis ESM {0}: function {1} throttled, shard iterator not advanced",
                    esm.getUuid(), fn.getFunctionName());
            return;
        }

        CheckpointOutcome outcome = invokeResult.getFunctionError() == null
                ? successfulInvocationCheckpoint(esm, invokeResult.getPayload(), lastSeq, records, matched)
                : new CheckpointOutcome(lastSeq, 0);
        if (outcome.lowestFailedIndex() == records.size()) {
            clearBatchState(batchKey);
            advanceCheckpoint(esm, shardId, outcome.checkpoint());
            return;
        }
        // Records before the lowest reported failure are done: the checkpoint moves up to them, as it
        // always did, and the attempts of the retried batch are counted under the new checkpoint.
        String retryKey = StreamBatchFailures.batchStateKey(esm.getUuid(), shardId, outcome.checkpoint());
        if (!retryKey.equals(batchKey)) {
            clearBatchState(batchKey);
            advanceCheckpoint(esm, shardId, outcome.checkpoint());
        }
        // A failure is counted, and may discard the batch, only for a mapping that is still polled.
        if (!stillActive(esm)) {
            LOG.debugv("Kinesis ESM {0} was stopped during a failed invocation, dropping its result", esm.getUuid());
            return;
        }

        // The backoff and the record age count from the end of the failed invoke, so a slow
        // function is not retried at once.
        long now = clock.getAsLong();
        List<KinesisRecord> failed = failedRecords(records, matched, outcome.lowestFailedIndex());
        if (StreamBatchFailures.splitsOnFunctionError(esm, invokeResult, failed.size())
                && !StreamBatchFailures.maximumRecordAgeExceeded(esm, failed.get(0).getApproximateArrivalTimestamp(), now)) {
            bisect(esm, shardId, retryKey, records.size());
            return;
        }
        int invokeCount = retryCounts.getOrDefault(retryKey, 0) + 1;
        Optional<String> discardCondition = discardCondition(esm, failed, invokeCount, now);
        if (discardCondition.isPresent()) {
            discard(esm, shardId, retryKey, failed, invokeResult, invokeCount, discardCondition.get(),
                    newestFetchedSeq);
        } else {
            scheduleRetry(esm, shardId, retryKey, failed.size(), invokeResult, invokeCount, now);
        }
    }

    /**
     * Sends the discarded records to the OnFailure destination, then checkpoints past the batch. The
     * send can be slow, so the mapping is checked again under the monitor that stopPolling, a reset
     * and shutdown take: a mapping stopped meanwhile keeps no state and is not written back.
     */
    private void discard(EventSourceMapping esm, String shardId, String stateKey, List<KinesisRecord> discarded,
                         InvokeResult invokeResult, int invokeCount, String condition, String newestFetchedSeq) {
        LOG.warnv("Kinesis ESM {0}: {1} after {2} invocation(s), discarding {3} record(s) of shard {4}",
                esm.getUuid(), condition, invokeCount, discarded.size(), shardId);
        batchFailures.sendToOnFailureDestination(esm, discardedBatch(esm, shardId, discarded), invokeResult,
                invokeCount, condition);
        synchronized (this) {
            if (stillActive(esm)) {
                clearBatchState(stateKey);
                advanceCheckpoint(esm, shardId, newestFetchedSeq);
            }
        }
    }

    /** Records the attempt and its backoff, unless stopPolling, a reset or shutdown came first. */
    private synchronized void scheduleRetry(EventSourceMapping esm, String shardId, String retryKey, int records,
                                            InvokeResult invokeResult, int invokeCount, long now) {
        if (!stillActive(esm)) {
            return;
        }
        long backoffMs = retryBackoffMs(invokeCount);
        retryCounts.put(retryKey, invokeCount);
        retryNotBefore.put(retryKey, now + backoffMs);
        LOG.warnv("Kinesis ESM {0}: invocation {1} failed [{2}], retrying {3} record(s) of shard {4} in {5,number,#} ms",
                esm.getUuid(), invokeCount,
                invokeResult.getFunctionError() != null ? invokeResult.getFunctionError() : "batchItemFailures",
                records, shardId, backoffMs);
    }

    /**
     * Refetches the failed batch at half its size, unless stopPolling, a reset or shutdown came first. The
     * limit is keyed by checkpoint, so once the first half succeeds the rest is fetched at the full batch
     * size and split again if it fails.
     */
    private synchronized void bisect(EventSourceMapping esm, String shardId, String retryKey, int fetched) {
        if (!stillActive(esm)) {
            return;
        }
        int limit = StreamBatchFailures.splitBatchSize(fetched);
        bisectLimits.put(retryKey, limit);
        LOG.infov("Kinesis ESM {0}: function error on {1} record(s) of shard {2}, bisecting to {3}",
                esm.getUuid(), fetched, shardId, limit);
    }

    private record CheckpointOutcome(String checkpoint, int lowestFailedIndex) {}

    long retryBackoffMs(int retries) {
        return StreamBatchFailures.retryBackoffMs(pollIntervalMs, retries);
    }

    private static Optional<String> discardCondition(EventSourceMapping esm, List<KinesisRecord> failed,
                                                     int invokeCount, long now) {
        if (StreamBatchFailures.maximumRecordAgeExceeded(esm, failed.get(0).getApproximateArrivalTimestamp(), now)) {
            return Optional.of(StreamBatchFailures.MAXIMUM_RECORD_AGE_EXCEEDED);
        }
        if (StreamBatchFailures.retryAttemptsExhausted(esm, invokeCount)) {
            return Optional.of(StreamBatchFailures.RETRY_ATTEMPTS_EXHAUSTED);
        }
        return Optional.empty();
    }

    /** The delivered records from the lowest failed one on. A filtered-out record was never sent, so it did not fail. */
    private static List<KinesisRecord> failedRecords(List<KinesisRecord> fetched, List<KinesisRecord> delivered,
                                                     int lowestFailedIndex) {
        Set<String> retried = new HashSet<>();
        for (KinesisRecord rec : fetched.subList(lowestFailedIndex, fetched.size())) {
            retried.add(rec.getSequenceNumber());
        }
        return delivered.stream().filter(rec -> retried.contains(rec.getSequenceNumber())).toList();
    }

    private StreamBatchFailures.DiscardedBatch discardedBatch(EventSourceMapping esm, String shardId,
                                                              List<KinesisRecord> records) {
        KinesisRecord first = records.get(0);
        KinesisRecord last = records.get(records.size() - 1);
        return new StreamBatchFailures.DiscardedBatch(BATCH_INFO_NAME, shardId,
                first.getSequenceNumber(), last.getSequenceNumber(),
                AsyncInvokeDestinationRouter.formatRecordTimestamp(first.getApproximateArrivalTimestamp()),
                AsyncInvokeDestinationRouter.formatRecordTimestamp(last.getApproximateArrivalTimestamp()),
                records.size(), streamArnFromArn(esm.getEventSourceArn()),
                () -> buildKinesisEvent(records, esm, shardId));
    }

    /** False once the mapping is stopped, deleted or disabled, or the poller is reset or shut down. */
    private boolean stillActive(EventSourceMapping esm) {
        return !quiesced && !stopped.contains(esm.getUuid())
                && esmStore.getForAccount(esm.getAccountId(), esm.getUuid())
                .map(EventSourceMapping::isEnabled)
                .orElse(false);
    }

    private void clearBatchState(String key) {
        retryCounts.remove(key);
        retryNotBefore.remove(key);
        bisectLimits.remove(key);
    }

    private String buildKinesisEvent(List<KinesisRecord> records, EventSourceMapping esm, String shardId) {
        try {
            ArrayNode recordsArray = objectMapper.createArrayNode();
            for (KinesisRecord rec : records) {
                ObjectNode kinesisNode = objectMapper.createObjectNode();
                kinesisNode.put("kinesisSchemaVersion", "1.0");
                kinesisNode.put("partitionKey", rec.getPartitionKey());
                kinesisNode.put("sequenceNumber", rec.getSequenceNumber());
                kinesisNode.put("data", Base64.getEncoder().encodeToString(rec.getData()));
                kinesisNode.put("approximateArrivalTimestamp",
                        rec.getApproximateArrivalTimestamp().toEpochMilli() / 1000.0);
                ObjectNode record = objectMapper.createObjectNode();
                record.set("kinesis", kinesisNode);
                record.put("eventSource", "aws:kinesis");
                record.put("eventVersion", "1.0");
                record.put("eventID", shardId + ":" + rec.getSequenceNumber());
                record.put("eventName", "aws:kinesis:record");
                record.put("invokeIdentityArn", AwsArnUtils.Arn.global(AwsRegions.partitionFor(esm.getRegion()), "iam",
                        esm.getAccountId(), "role/lambda-role").toString());
                record.put("awsRegion", esm.getRegion());
                record.put("eventSourceARN", esm.getEventSourceArn());
                recordsArray.add(record);
            }
            ObjectNode root = objectMapper.createObjectNode();
            root.set("Records", recordsArray);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"Records\":[]}";
        }
    }

    /**
     * The record view a Kinesis filter pattern matches against. AWS decodes the base64 {@code data} and
     * filters the <em>top-level</em> {@code data} key (plus metadata such as {@code partitionKey}), not the
     * nested/base64 invocation envelope produced by {@link #buildKinesisEvent}. This is a separate,
     * filter-only view: {@code data} is the base64-decoded payload, parsed as JSON when it parses, else kept
     * as the decoded string (an object pattern then won't match it: AWS drops on format mismatch).
     */
    private JsonNode buildKinesisFilterNode(KinesisRecord rec) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("partitionKey", rec.getPartitionKey());
        String decoded = new String(rec.getData(), StandardCharsets.UTF_8);
        try {
            JsonNode parsed = objectMapper.readTree(decoded);
            if (parsed == null || parsed.isMissingNode()) {
                node.put("data", decoded);
            } else {
                node.set("data", parsed);
            }
        } catch (JsonProcessingException e) {
            node.put("data", decoded);
        }
        return node;
    }

    /**
     * The sequence number to checkpoint after a successful invocation, and the fetched index of the
     * lowest failed record ({@code fetched.size()} when none failed). With
     * {@code ReportBatchItemFailures} the shard resumes at the lowest reported failure; Floci
     * resumes {@code AFTER_SEQUENCE_NUMBER}, so that is the fetched record just before it. A
     * malformed {@code batchItemFailures} response retries the whole batch, as on AWS.
     */
    private CheckpointOutcome successfulInvocationCheckpoint(EventSourceMapping esm, byte[] payload,
                                                             String previousCheckpoint,
                                                             List<KinesisRecord> fetched,
                                                             List<KinesisRecord> delivered) {
        String newestFetchedSeq = fetched.get(fetched.size() - 1).getSequenceNumber();
        if (!esm.isReportBatchItemFailures() || payload == null || payload.length == 0) {
            return new CheckpointOutcome(newestFetchedSeq, fetched.size());
        }

        try {
            JsonNode failures = objectMapper.readTree(payload).get("batchItemFailures");
            if (failures == null || failures.isNull()) {
                return new CheckpointOutcome(newestFetchedSeq, fetched.size());
            }
            if (!failures.isArray()) {
                return retryWholeBatch(esm, previousCheckpoint, "batchItemFailures is not an array");
            }

            Map<String, Integer> fetchedIndexes = new HashMap<>();
            for (int i = 0; i < fetched.size(); i++) {
                fetchedIndexes.put(fetched.get(i).getSequenceNumber(), i);
            }
            Set<String> deliveredSequences = new HashSet<>();
            for (KinesisRecord rec : delivered) {
                deliveredSequences.add(rec.getSequenceNumber());
            }

            int lowestFailedIndex = fetched.size();
            for (JsonNode item : failures) {
                JsonNode identifier = item.get("itemIdentifier");
                if (identifier == null || identifier.isNull() || identifier.asText().isEmpty()) {
                    return retryWholeBatch(esm, previousCheckpoint,
                            "entry has a missing, null or empty itemIdentifier");
                }
                String sequenceNumber = identifier.asText();
                Integer index = fetchedIndexes.get(sequenceNumber);
                if (index == null || !deliveredSequences.contains(sequenceNumber)) {
                    return retryWholeBatch(esm, previousCheckpoint,
                            "itemIdentifier " + sequenceNumber + " is not in the delivered batch");
                }
                lowestFailedIndex = Math.min(lowestFailedIndex, index);
            }

            if (lowestFailedIndex == fetched.size()) {
                return new CheckpointOutcome(newestFetchedSeq, fetched.size());
            }
            LOG.warnv("Kinesis ESM {0}: function reported batch item failures, resuming at {1}",
                    esm.getUuid(), fetched.get(lowestFailedIndex).getSequenceNumber());
            String checkpoint = lowestFailedIndex == 0
                    ? previousCheckpoint
                    : fetched.get(lowestFailedIndex - 1).getSequenceNumber();
            return new CheckpointOutcome(checkpoint, lowestFailedIndex);
        } catch (Exception e) {
            return retryWholeBatch(esm, previousCheckpoint, "response is not valid JSON: " + e.getMessage());
        }
    }

    private CheckpointOutcome retryWholeBatch(EventSourceMapping esm, String previousCheckpoint, String reason) {
        LOG.warnv("Kinesis ESM {0}: malformed batchItemFailures response ({1}), retrying the whole batch",
                esm.getUuid(), reason);
        return new CheckpointOutcome(previousCheckpoint, 0);
    }

    private void advanceCheckpoint(EventSourceMapping esm, String shardId, String newestSeq) {
        esm.getShardSequenceNumbers().put(shardId, newestSeq);
        esmStore.saveForAccount(esm.getAccountId(), esm);
    }

    /** The stream ARN of a stream or stream-consumer ARN: an enhanced fan-out mapping reads its stream. */
    static String streamArnFromArn(String arn) {
        int consumer = arn.indexOf("/consumer/");
        return consumer >= 0 ? arn.substring(0, consumer) : arn;
    }

    /**
     * The stream name from a stream ARN ({@code .../stream/S}) or a stream-consumer ARN
     * ({@code .../stream/S/consumer/C:123}): the segment right after {@code stream/}. Only a
     * {@code /consumer/} suffix is dropped; any other suffix stays part of the name, so a
     * malformed ARN names no stream rather than a real one.
     */
    static String streamNameFromArn(String arn) {
        int streamIdx = arn.indexOf(":stream/");
        if (streamIdx < 0) {
            return arn.substring(arn.lastIndexOf("/") + 1);
        }
        String after = arn.substring(streamIdx + ":stream/".length());
        int consumer = after.indexOf("/consumer/");
        return consumer >= 0 ? after.substring(0, consumer) : after;
    }
}
