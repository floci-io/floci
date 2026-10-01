package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@ApplicationScoped
public class KinesisEventSourcePoller implements Resettable {

    private static final Logger LOG = Logger.getLogger(KinesisEventSourcePoller.class);

    private final Vertx vertx;
    private final KinesisService kinesisService;
    private final LambdaExecutorService executorService;
    private final LambdaTargetResolver targetResolver;
    private final EsmStore esmStore;
    private final long pollIntervalMs;
    private final ObjectMapper objectMapper;
    private final PipesFilterMatcher filterMatcher;
    private final ConcurrentHashMap<String, Long> timerIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> activePolls = new ConcurrentHashMap<>();
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
                                     PipesFilterMatcher filterMatcher) {
        this.vertx = vertx;
        this.kinesisService = kinesisService;
        this.executorService = executorService;
        this.targetResolver = targetResolver;
        this.esmStore = esmStore;
        this.pollIntervalMs = config.services().lambda().pollIntervalMs();
        this.objectMapper = objectMapper;
        this.filterMatcher = filterMatcher;
    }

    public void startPersistedPollers() {
        List<EventSourceMapping> esms = esmStore.listAll();
        for (EventSourceMapping esm : esms) {
            if (esm.isEnabled() && esm.getEventSourceArn().contains(":kinesis:")) {
                startPolling(esm);
            }
        }
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
    }

    public void startPolling(EventSourceMapping esm) {
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

    public void stopPolling(String uuid) {
        Long timerId = timerIds.remove(uuid);
        if (timerId != null) vertx.cancelTimer(timerId);
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
                    String lastSeq = esm.getShardSequenceNumbers().get(shard.getShardId());
                    String iterator;
                    if (lastSeq == null) {
                        iterator = kinesisService.getShardIteratorForAccount(esm.getAccountId(),
                                streamName, shard.getShardId(), "TRIM_HORIZON", null, esm.getRegion());
                    } else {
                        iterator = kinesisService.getShardIteratorForAccount(esm.getAccountId(),
                                streamName, shard.getShardId(), "AFTER_SEQUENCE_NUMBER", lastSeq, esm.getRegion());
                    }

                    Map<String, Object> result = kinesisService.getRecordsForAccount(
                            esm.getAccountId(), iterator, esm.getBatchSize(), esm.getRegion());
                    List<KinesisRecord> records = (List<KinesisRecord>) result.get("Records");

                    if (records.isEmpty()) {
                        continue;
                    }

                    // The checkpoint must advance to the newest FETCHED record whenever the batch is
                    // disposed of, whether by a successful invoke or because a filter matched nothing,
                    // so filtered-out records are consumed, not re-read forever. Only an invoke that was
                    // attempted and failed leaves the checkpoint unmoved (the whole window retries), and
                    // reported batch item failures move it only up to the lowest failed record.
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
                        advanceCheckpoint(esm, shard.getShardId(), newestFetchedSeq);
                        continue;
                    }

                    String eventJson = buildKinesisEvent(matched, esm, shard.getShardId());
                    InvokeResult invokeResult;
                    try {
                        invokeResult = executorService.invoke(fn, eventJson.getBytes(), InvocationType.RequestResponse);
                    } catch (AwsException e) {
                        if ("TooManyRequestsException".equals(e.getErrorCode())) {
                            LOG.infov("Kinesis ESM {0}: function {1} throttled, shard iterator not advanced",
                                    esm.getUuid(), fn.getFunctionName());
                            continue;
                        }
                        throw e;
                    }

                    if (invokeResult.getFunctionError() == null) {
                        String checkpoint = successfulInvocationCheckpoint(
                                esm, invokeResult.getPayload(), lastSeq, records, matched);
                        if (checkpoint != null && !checkpoint.equals(lastSeq)) {
                            advanceCheckpoint(esm, shard.getShardId(), checkpoint);
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warnv("Kinesis ESM {0} error: {1}", esm.getUuid(), e.getMessage());
            } finally {
                activePolls.remove(esm.getUuid());
            }
        });
    }

    private String buildKinesisEvent(List<KinesisRecord> records, EventSourceMapping esm, String shardId) {
        try {
            var recordsArray = objectMapper.createArrayNode();
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
     * The sequence number to checkpoint after a successful invocation. With
     * {@code ReportBatchItemFailures} the shard resumes at the lowest reported failure; Floci
     * resumes {@code AFTER_SEQUENCE_NUMBER}, so that is the fetched record just before it. A
     * malformed {@code batchItemFailures} response retries the whole batch, as on AWS.
     */
    private String successfulInvocationCheckpoint(EventSourceMapping esm, byte[] payload, String previousCheckpoint,
                                                  List<KinesisRecord> fetched, List<KinesisRecord> delivered) {
        String newestFetchedSeq = fetched.get(fetched.size() - 1).getSequenceNumber();
        if (!esm.isReportBatchItemFailures() || payload == null || payload.length == 0) {
            return newestFetchedSeq;
        }

        try {
            JsonNode failures = objectMapper.readTree(payload).get("batchItemFailures");
            if (failures == null || failures.isNull()) {
                return newestFetchedSeq;
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
                return newestFetchedSeq;
            }
            LOG.warnv("Kinesis ESM {0}: function reported batch item failures, resuming at {1}",
                    esm.getUuid(), fetched.get(lowestFailedIndex).getSequenceNumber());
            return lowestFailedIndex == 0
                    ? previousCheckpoint
                    : fetched.get(lowestFailedIndex - 1).getSequenceNumber();
        } catch (Exception e) {
            return retryWholeBatch(esm, previousCheckpoint, "response is not valid JSON: " + e.getMessage());
        }
    }

    private String retryWholeBatch(EventSourceMapping esm, String previousCheckpoint, String reason) {
        LOG.warnv("Kinesis ESM {0}: malformed batchItemFailures response ({1}), retrying the whole batch",
                esm.getUuid(), reason);
        return previousCheckpoint;
    }

    private void advanceCheckpoint(EventSourceMapping esm, String shardId, String newestSeq) {
        esm.getShardSequenceNumbers().put(shardId, newestSeq);
        esmStore.saveForAccount(esm.getAccountId(), esm);
    }

    private static String streamNameFromArn(String arn) {
        return arn.substring(arn.lastIndexOf("/") + 1);
    }
}
