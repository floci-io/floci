package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbStreamService;
import io.github.hectorvent.floci.services.dynamodb.model.DynamoDbStreamRecord;
import io.github.hectorvent.floci.services.lambda.model.EventSourceMapping;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.pipes.PipesFilterMatcher;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.vertx.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@ApplicationScoped
public class DynamoDbStreamsEventSourcePoller implements Resettable {

    private static final Logger LOG = Logger.getLogger(DynamoDbStreamsEventSourcePoller.class);

    /** Raised by the stream when a stored checkpoint has aged out of the retained window. */
    private static final String TRIMMED_DATA_ACCESS_EXCEPTION = "TrimmedDataAccessException";

    private final Vertx vertx;
    private final DynamoDbStreamService streamService;
    private final LambdaExecutorService executorService;
    private final LambdaFunctionStore functionStore;
    private final EsmStore esmStore;
    private final ObjectMapper objectMapper;
    private final PipesFilterMatcher filterMatcher;
    private final SqsService sqsService;
    private final SnsService snsService;
    private final String baseUrl;
    private final long pollIntervalMs;
    private final ConcurrentHashMap<String, Long> timerIds = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, Boolean> activePolls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> retryCounts = new ConcurrentHashMap<>();
    private final ExecutorService pollExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "dynamodb-streams-esm-poller");
        t.setDaemon(true);
        return t;
    });

    @Inject
    public DynamoDbStreamsEventSourcePoller(Vertx vertx, DynamoDbStreamService streamService,
                                            LambdaExecutorService executorService,
                                            LambdaFunctionStore functionStore,
                                            EsmStore esmStore,
                                            ObjectMapper objectMapper,
                                            EmulatorConfig config,
                                            PipesFilterMatcher filterMatcher,
                                            SqsService sqsService,
                                            SnsService snsService) {
        this.vertx = vertx;
        this.streamService = streamService;
        this.executorService = executorService;
        this.functionStore = functionStore;
        this.esmStore = esmStore;
        this.objectMapper = objectMapper;
        this.pollIntervalMs = config.services().lambda().pollIntervalMs();
        this.baseUrl = config.effectiveBaseUrl();
        this.filterMatcher = filterMatcher;
        this.sqsService = sqsService;
        this.snsService = snsService;
    }

    public void startPersistedPollers() {
        for (EventSourceMapping esm : esmStore.listAll()) {
            if (esm.isEnabled() && esm.getEventSourceArn().contains(":dynamodb:")) {
                discardStaleShardCheckpoints(esm);
                startPolling(esm);
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

    @PreDestroy
    void shutdown() {
        pollExecutor.shutdownNow();
        timerIds.values().forEach(vertx::cancelTimer);
        timerIds.clear();
    }

    public void clear() {
        timerIds.values().forEach(vertx::cancelTimer);
        timerIds.clear();
        activePolls.clear();
        retryCounts.clear();
    }

    public void startPolling(EventSourceMapping esm) {
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

    public void stopPolling(String uuid) {
        Long timerId = timerIds.remove(uuid);
        if (timerId != null) {
            vertx.cancelTimer(timerId);
            LOG.debugv("Stopped DynamoDB Streams polling for ESM {0}", uuid);
        }
        retryCounts.keySet().removeIf(k -> k.startsWith(uuid + ":"));
    }


    void pollAndInvoke(EventSourceMapping esm) {
        if (activePolls.putIfAbsent(esm.getUuid(), Boolean.TRUE) != null) {
            return;
        }
        pollExecutor.submit(() -> {
            try {
                LambdaFunction fn = functionStore.getForAccount(esm.getAccountId(), esm.getRegion(), esm.getFunctionName()).orElse(null);
                if (fn == null) {
                    LOG.warnv("DynamoDB Streams ESM {0}: function {1} not found, skipping",
                            esm.getUuid(), esm.getFunctionName());
                    return;
                }

                String streamArn = esm.getEventSourceArn();
                String shardId = DynamoDbStreamService.SHARD_ID;
                String lastSeq = esm.getShardSequenceNumbers().get(shardId);

                boolean checkpointTrimmed = false;
                DynamoDbStreamService.GetRecordsResult result;
                try {
                    String iterator = lastSeq == null
                            ? streamService.getShardIterator(streamArn, shardId, "TRIM_HORIZON", null)
                            : streamService.getShardIterator(streamArn, shardId, "AFTER_SEQUENCE_NUMBER", lastSeq);
                    result = streamService.getRecords(iterator, esm.getBatchSize());
                } catch (AwsException e) {
                    if (!TRIMMED_DATA_ACCESS_EXCEPTION.equals(e.getErrorCode())) {
                        throw e;
                    }
                    checkpointTrimmed = true;
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
                    result = streamService.getRecords(horizon, esm.getBatchSize());
                }
                List<DynamoDbStreamRecord> records = result.records();

                if (records.isEmpty()) {
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

                if (matched.isEmpty()) {
                    advanceCheckpoint(esm, shardId, newestFetchedSeq);
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

                String checkpointSeq = (lastSeq == null || checkpointTrimmed) ? "TRIM_HORIZON" : lastSeq;
                String batchKey = esm.getUuid() + ":" + shardId + ":" + checkpointSeq;
                if (invokeResult.getFunctionError() == null) {
                    retryCounts.remove(batchKey);
                    advanceCheckpoint(esm, shardId, newestFetchedSeq);
                } else {
                    Integer maxRetries = esm.getMaximumRetryAttempts();
                    int currentRetries = retryCounts.merge(batchKey, 1, Integer::sum);
                    if (maxRetries != null && maxRetries >= 0 && currentRetries > maxRetries) {
                        LOG.warnv("DynamoDB Streams ESM {0}: maximum retry attempts ({1}) exhausted for batch ending at {2}",
                                esm.getUuid(), maxRetries, newestFetchedSeq);
                        sendToOnFailureDestination(esm, shardId, matched, invokeResult, currentRetries);
                        retryCounts.remove(batchKey);
                        advanceCheckpoint(esm, shardId, newestFetchedSeq);
                    } else {
                        LOG.warnv("DynamoDB Streams ESM {0}: Lambda returned error [{1}], retry {2}, records will be retried",
                                esm.getUuid(), invokeResult.getFunctionError(), currentRetries);
                    }
                }
            } catch (Exception e) {
                LOG.warnv("DynamoDB Streams ESM {0} poll error: {1}", esm.getUuid(), e.getMessage());
            } finally {
                activePolls.remove(esm.getUuid());
            }
        });
    }

    private void sendToOnFailureDestination(EventSourceMapping esm, String shardId,
                                           List<DynamoDbStreamRecord> records,
                                           InvokeResult invokeResult,
                                           int invokeCount) {
        if (esm.getDestinationConfig() == null || esm.getDestinationConfig().getOnFailure() == null) {
            return;
        }
        String destinationArn = esm.getDestinationConfig().getOnFailure().getDestination();
        if (destinationArn == null || destinationArn.isBlank()) {
            return;
        }

        try {
            String payload = buildOnFailurePayload(esm, shardId, records, invokeResult, invokeCount);
            String region = AwsArnUtils.regionOrDefault(destinationArn, esm.getRegion());

            if (destinationArn.contains(":sqs:")) {
                String queueUrl = AwsArnUtils.arnToQueueUrl(destinationArn, baseUrl);
                sqsService.sendMessage(queueUrl, payload, 0, region);
                LOG.infov("DynamoDB Streams ESM {0}: sent failed batch to SQS DLQ {1}", esm.getUuid(), destinationArn);
            } else if (destinationArn.contains(":sns:")) {
                snsService.publish(destinationArn, null, payload, "ESM OnFailure", region);
                LOG.infov("DynamoDB Streams ESM {0}: sent failed batch to SNS DLQ {1}", esm.getUuid(), destinationArn);
            } else {
                LOG.warnv("DynamoDB Streams ESM {0}: unsupported OnFailure destination ARN {1}",
                        esm.getUuid(), destinationArn);
            }
        } catch (Exception e) {
            LOG.errorv("DynamoDB Streams ESM {0}: failed to send to OnFailure destination {1}: {2}",
                    esm.getUuid(), destinationArn, e.getMessage());
        }
    }

    private String buildOnFailurePayload(EventSourceMapping esm, String shardId,
                                         List<DynamoDbStreamRecord> records,
                                         InvokeResult invokeResult,
                                         int invokeCount) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("version", "1.0");
            root.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));

            ObjectNode requestContext = root.putObject("requestContext");
            requestContext.put("requestId", invokeResult.getRequestId() != null ? invokeResult.getRequestId() : "");
            requestContext.put("functionArn", esm.getFunctionArn() != null ? esm.getFunctionArn() : "");
            requestContext.put("condition", "RetryAttemptsExhausted");
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
                        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(firstArrival)));
                batchInfo.put("approximateArrivalOfLastRecord",
                        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(lastArrival)));
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

    private void advanceCheckpoint(EventSourceMapping esm, String shardId, String newestSeq) {
        esm.getShardSequenceNumbers().put(shardId, newestSeq);
        esmStore.saveForAccount(esm.getAccountId(), esm);
    }
}
