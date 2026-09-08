package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.lambda.model.EventSourceMapping;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.pipes.PipesFilterMatcher;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.vertx.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Polls SQS queues on behalf of Lambda Event Source Mappings.
 * Uses Vert.x periodic timers so polling is non-blocking.
 * Injects LambdaExecutorService + LambdaFunctionStore directly (not LambdaService)
 * to avoid a circular CDI dependency.
 */
@ApplicationScoped
public class SqsEventSourcePoller implements Resettable {

    private static final Logger LOG = Logger.getLogger(SqsEventSourcePoller.class);

    /** AWS SQS default visibility timeout, used as the retry backoff when a queue has none configured. */
    private static final int DEFAULT_RETRY_VISIBILITY_SECONDS = 30;

    private final Vertx vertx;
    private final SqsService sqsService;
    private final LambdaExecutorService executorService;
    private final LambdaFunctionStore functionStore;
    private final EsmStore esmStore;
    private final long pollIntervalMs;
    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final PipesFilterMatcher filterMatcher;
    private final ConcurrentHashMap<String, Long> timerIds = new ConcurrentHashMap<>();
    // Tracks ESMs with an in-flight poll to prevent concurrent deliveries of the same message
    private final ConcurrentHashMap<String, Boolean> activePolls = new ConcurrentHashMap<>();
    // Underfilled batches held open by MaximumBatchingWindowInSeconds, keyed by ESM uuid. Only ever
    // mutated inside the activePolls-guarded section, so one thread touches a given entry at a time.
    private final ConcurrentHashMap<String, PendingBatch> pendingBatches = new ConcurrentHashMap<>();
    // Wall clock for the batching-window deadline; overridable so tests can advance it without sleeping.
    private volatile LongSupplier clockMs = System::currentTimeMillis;
    private final ExecutorService pollExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "esm-poller");
        t.setDaemon(true);
        return t;
    });

    @Inject
    public SqsEventSourcePoller(Vertx vertx, SqsService sqsService,
                                LambdaExecutorService executorService,
                                LambdaFunctionStore functionStore,
                                EsmStore esmStore, EmulatorConfig config,
                                ObjectMapper objectMapper,
                                PipesFilterMatcher filterMatcher) {
        this.vertx = vertx;
        this.sqsService = sqsService;
        this.executorService = executorService;
        this.functionStore = functionStore;
        this.esmStore = esmStore;
        this.pollIntervalMs = config.services().lambda().pollIntervalMs();
        this.baseUrl = config.effectiveBaseUrl();
        this.objectMapper = objectMapper;
        this.filterMatcher = filterMatcher;
    }

    public void startPersistedPollers() {
        List<EventSourceMapping> esms = esmStore.listAll();
        for (EventSourceMapping esm : esms) {
            if (esm.isEnabled() && esm.getEventSourceArn().contains(":sqs:")) {
                startPolling(esm);
            }
        }
        LOG.infov("SqsEventSourcePoller initialized, {0} ESM(s) active", timerIds.size());
    }

    @PreDestroy
    void shutdown() {
        pollExecutor.shutdownNow();
        timerIds.values().forEach(vertx::cancelTimer);
        timerIds.clear();
        LOG.info("SqsEventSourcePoller shut down, all timers cancelled");
    }

    public void clear() {
        timerIds.values().forEach(vertx::cancelTimer);
        timerIds.clear();
        activePolls.clear();
        pendingBatches.clear();
    }

    /** Test seam: replace the wall clock used for the batching-window deadline. */
    void setClockForTest(LongSupplier clockMs) {
        this.clockMs = clockMs;
    }

    /** Messages received across earlier polls that a batching window is still holding open. */
    private static final class PendingBatch {
        final List<Message> messages = new ArrayList<>();
        long windowStartedAtMs;
    }

    public void startPolling(EventSourceMapping esm) {
        if (timerIds.containsKey(esm.getUuid())) {
            return; // already polling
        }
        String uuid = esm.getUuid();
        String accountId = esm.getAccountId();
        long timerId = vertx.setPeriodic(pollIntervalMs, id -> {
            // Re-fetch from storage on each tick so updates (batchSize, enabled) are visible.
            // Use account-scoped lookup since this runs outside request scope.
            esmStore.getForAccount(accountId, uuid).ifPresent(latest -> {
                if (latest.isEnabled()) {
                    pollAndInvoke(latest);
                }
            });
        });
        timerIds.put(uuid, timerId);
        LOG.debugv("Started polling ESM {0} → {1} every {2}ms",
                esm.getUuid(), esm.getQueueUrl(), pollIntervalMs);
    }

    public void stopPolling(String uuid) {
        Long timerId = timerIds.remove(uuid);
        pendingBatches.remove(uuid);
        if (timerId != null) {
            vertx.cancelTimer(timerId);
            LOG.debugv("Stopped polling ESM {0}", uuid);
        }
    }

    /** Package-private (not private) only so unit tests can drive a single poll directly. */
    void pollAndInvoke(EventSourceMapping esm) {
        // Skip this tick if a previous poll for this ESM is still in progress.
        // This prevents concurrent deliveries of the same message when the Lambda
        // cold-start / execution time exceeds the SQS visibility timeout.
        if (activePolls.putIfAbsent(esm.getUuid(), Boolean.TRUE) != null) {
            return;
        }
        pollExecutor.submit(() -> {
            try {
                // Look up the function first so we can set an appropriate visibility
                // timeout: fn.timeout + 30s keeps messages hidden while Lambda runs.
                // Use account-scoped lookup since this runs outside request scope.
                LambdaFunction fn = functionStore.getForAccount(esm.getAccountId(), esm.getRegion(), esm.getFunctionName())
                        .orElse(null);
                if (fn == null) {
                    LOG.warnv("ESM {0}: function {1} not found in region {2}, skipping",
                            esm.getUuid(), esm.getFunctionName(), esm.getRegion());
                    return;
                }

                // MaximumBatchingWindowInSeconds holds an underfilled batch open: keep messages
                // received across earlier polls invisible and only invoke once the batch fills or
                // the window since the first buffered message expires.
                int window = esm.getMaximumBatchingWindowInSeconds() == null
                        ? 0 : esm.getMaximumBatchingWindowInSeconds();
                // A message can be buffered for the whole window and then processed for the full
                // function timeout, so visibility has to cover both plus AWS's 30s margin, not the
                // larger of the two. Otherwise a long invoke can outlive the visibility and another
                // consumer receives the message, causing a duplicate delivery and a stale handle.
                int visibilityTimeout = fn.getTimeout() + Math.max(window, 0) + 30;

                PendingBatch pending = pendingBatches.get(esm.getUuid());
                int alreadyBuffered = pending != null ? pending.messages.size() : 0;
                int wanted = Math.max(1, esm.getBatchSize() - alreadyBuffered);

                List<Message> received = sqsService.receiveMessage(
                        esm.getQueueUrl(), wanted, visibilityTimeout, 0, esm.getRegion());

                List<Message> messages;
                if (window <= 0) {
                    // No window, or one that was just turned off: deliver now, draining anything a
                    // previous positive window had buffered so those messages are not stranded
                    // until their visibility expires (and cannot be replayed if the window returns).
                    List<Message> batch = pending != null ? pending.messages : new ArrayList<>();
                    pendingBatches.remove(esm.getUuid());
                    batch.addAll(received);
                    if (batch.isEmpty()) {
                        return;
                    }
                    messages = batch;
                } else {
                    if (pending == null) {
                        pending = new PendingBatch();
                        pendingBatches.put(esm.getUuid(), pending);
                    }
                    if (pending.messages.isEmpty() && !received.isEmpty()) {
                        pending.windowStartedAtMs = clockMs.getAsLong();
                    }
                    pending.messages.addAll(received);
                    if (pending.messages.isEmpty()) {
                        return;
                    }
                    boolean batchFull = pending.messages.size() >= esm.getBatchSize();
                    boolean windowElapsed =
                            clockMs.getAsLong() - pending.windowStartedAtMs >= window * 1000L;
                    if (!batchFull && !windowElapsed) {
                        return;
                    }
                    messages = new ArrayList<>(pending.messages);
                    pendingBatches.remove(esm.getUuid());
                }

                LOG.infov("ESM {0}: received {1} message(s)", esm.getUuid(), messages.size());

                // Apply FilterCriteria. AWS consumes (permanently deletes) filtered-out SQS messages, so
                // non-matching messages are deleted immediately: leaving them would redeliver every
                // visibility window forever and never reach the DLQ. A batch that matches nothing
                // short-circuits without invoking.
                List<Message> matched = messages;
                JsonNode filterParams = EsmFilterCriteriaUtils.matcherSourceParameters(objectMapper, esm.getFilterCriteria());
                if (filterParams != null) {
                    List<JsonNode> recordNodes = new ArrayList<>(messages.size());
                    for (Message m : messages) {
                        recordNodes.add(buildSqsRecordNode(m, esm));
                    }
                    matched = EsmFilterCriteriaUtils.selectMatched(
                            messages, recordNodes, filterMatcher.applyFilterCriteria(recordNodes, filterParams));
                    Set<Message> keep = Collections.newSetFromMap(new IdentityHashMap<>());
                    keep.addAll(matched);
                    for (Message m : messages) {
                        if (!keep.contains(m)) {
                            try {
                                sqsService.deleteMessage(esm.getQueueUrl(), m.getReceiptHandle(), esm.getRegion());
                            } catch (Exception e) {
                                LOG.warnv("ESM {0}: failed to delete filtered-out message {1}: {2}",
                                        esm.getUuid(), m.getMessageId(), e.getMessage());
                            }
                        }
                    }
                    if (matched.isEmpty()) {
                        return;
                    }
                }

                String eventJson = buildSqsEvent(matched, esm);
                LOG.infov("ESM {0}: invoking function {1}", esm.getUuid(), fn.getFunctionName());
                InvokeResult result;
                try {
                    result = executorService.invoke(
                            fn, eventJson.getBytes(), InvocationType.RequestResponse);
                } catch (AwsException e) {
                    if ("TooManyRequestsException".equals(e.getErrorCode())) {
                        LOG.infov("ESM {0}: function {1} throttled, messages will return to queue after visibility timeout",
                                esm.getUuid(), fn.getFunctionName());
                        return;
                    }
                    throw e;
                }

                if (result.getFunctionError() == null) {
                    // Only the delivered (matched) messages are subject to delete/return here; filtered-out
                    // messages were already deleted above, so a batchItemFailure id that names one is inert.
                    Set<String> failedIds = extractBatchItemFailures(esm, result, messages);
                    List<Message> toDelete = failedIds.isEmpty()
                            ? matched
                            : matched.stream().filter(m -> !failedIds.contains(m.getMessageId())).toList();
                    LOG.infov("ESM {0}: Lambda succeeded, deleting {1} of {2} delivered message(s) ({3} reported as failed)",
                            esm.getUuid(), toDelete.size(), matched.size(), failedIds.size());
                    for (Message msg : toDelete) {
                        try {
                            sqsService.deleteMessage(esm.getQueueUrl(),
                                    msg.getReceiptHandle(), esm.getRegion());
                        } catch (Exception e) {
                            LOG.warnv("Failed to delete message {0}: {1}",
                                    msg.getMessageId(), e.getMessage());
                        }
                    }
                    // Reported partial-batch failures are not deleted; return them to the
                    // queue immediately so they can be retried/redriven rather than sitting
                    // in-flight for the full execution-cover visibility window.
                    if (!failedIds.isEmpty()) {
                        List<Message> toReturn = matched.stream()
                                .filter(m -> failedIds.contains(m.getMessageId())).toList();
                        returnMessagesToQueue(esm, toReturn);
                    }
                } else {
                    LOG.warnv("ESM {0}: Lambda returned error [{1}], returning {2} delivered message(s) to queue for retry/redrive",
                            esm.getUuid(), result.getFunctionError(), matched.size());
                    returnMessagesToQueue(esm, matched);
                }
            } catch (Exception e) {
                LOG.warnv("ESM {0}: poll/invoke error: {1} ({2})",
                        esm.getUuid(), e.getMessage(), e.getClass().getSimpleName());
            } finally {
                activePolls.remove(esm.getUuid());
            }
        });
    }

    /**
     * Returns failed messages to the source queue by resetting their visibility timeout
     * to the queue's own {@code VisibilityTimeout}. The poller hides messages for
     * {@code fn.timeout + 30s} to cover execution time, but on failure that long window
     * would keep the message in-flight (and therefore not redelivered nor redriven) far
     * longer than the queue's own visibility/redrive policy intends. Shrinking the window
     * back to the queue's visibility timeout lets the next poll re-receive them — matching
     * AWS's redelivery cadence rather than spinning a tight retry loop (which resetting to
     * 0 would cause for a persistently failing function) — so ApproximateReceiveCount
     * climbs and the queue's RedrivePolicy moves them to the DLQ once
     * {@code maxReceiveCount} is exceeded.
     */
    private void returnMessagesToQueue(EventSourceMapping esm, List<Message> messages) {
        int retryVisibility = retryVisibilityTimeout(esm);
        for (Message msg : messages) {
            try {
                sqsService.changeMessageVisibility(
                        esm.getQueueUrl(), msg.getReceiptHandle(), retryVisibility, esm.getRegion());
            } catch (Exception e) {
                LOG.warnv("ESM {0}: failed to return message {1} to queue: {2}",
                        esm.getUuid(), msg.getMessageId(), e.getMessage());
            }
        }
    }

    /**
     * The visibility timeout to apply when returning a failed message to the queue: the
     * queue's configured {@code VisibilityTimeout}, or the AWS default of 30s when unset
     * or unreadable. This governs how soon the message is retried/redriven.
     */
    private int retryVisibilityTimeout(EventSourceMapping esm) {
        try {
            String vt = sqsService.getQueueAttributes(
                    esm.getQueueUrl(), List.of("VisibilityTimeout"), esm.getRegion())
                    .get("VisibilityTimeout");
            if (vt != null) {
                return Math.max(0, Integer.parseInt(vt));
            }
        } catch (Exception e) {
            LOG.debugv("ESM {0}: could not read VisibilityTimeout, using default {1}s backoff: {2}",
                    esm.getUuid(), DEFAULT_RETRY_VISIBILITY_SECONDS, e.getMessage());
        }
        return DEFAULT_RETRY_VISIBILITY_SECONDS;
    }

    /**
     * Message IDs the function reported as failed via {@code ReportBatchItemFailures}, following
     * the AWS success/failure conditions: an empty or null list, or an empty or null response, is
     * a complete success, while invalid JSON, a non-array list, an entry without
     * {@code itemIdentifier}, or an empty, null or unknown identifier fails the whole batch, so
     * every received message is reported as failed and the delivered ones are returned to the
     * queue. Identifiers are validated against the received batch, so a filtered-out message's
     * id stays inert rather than failing the batch.
     */
    private Set<String> extractBatchItemFailures(EventSourceMapping esm, InvokeResult result,
                                                 List<Message> received) {
        if (!esm.isReportBatchItemFailures() || result.getPayload() == null || result.getPayload().length == 0) {
            return Set.of();
        }
        Set<String> receivedIds = received.stream().map(Message::getMessageId).collect(Collectors.toSet());
        try {
            var failures = objectMapper.readTree(result.getPayload()).get("batchItemFailures");
            if (failures == null || failures.isNull()) {
                return Set.of();
            }
            if (!failures.isArray()) {
                return failWholeBatch(esm, receivedIds, "batchItemFailures is not an array");
            }
            Set<String> failedIds = new HashSet<>();
            for (var item : failures) {
                var id = item.get("itemIdentifier");
                if (id == null || id.isNull() || id.asText().isEmpty()) {
                    return failWholeBatch(esm, receivedIds, "entry has a missing, null or empty itemIdentifier");
                }
                if (!receivedIds.contains(id.asText())) {
                    return failWholeBatch(esm, receivedIds,
                            "itemIdentifier " + id.asText() + " is not in the received batch");
                }
                failedIds.add(id.asText());
            }
            return failedIds;
        } catch (Exception e) {
            return failWholeBatch(esm, receivedIds, "response is not valid JSON: " + e.getMessage());
        }
    }

    private Set<String> failWholeBatch(EventSourceMapping esm, Set<String> receivedIds, String reason) {
        LOG.warnv("ESM {0}: malformed batchItemFailures response ({1}), failing the whole batch",
                esm.getUuid(), reason);
        return receivedIds;
    }

    String buildSqsEvent(List<Message> messages, EventSourceMapping esm) {
        try {
            var records = objectMapper.createArrayNode();
            for (Message msg : messages) {
                records.add(buildSqsRecordNode(msg, esm));
            }
            ObjectNode root = objectMapper.createObjectNode();
            root.set("Records", records);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"Records\":[]}";
        }
    }

    /**
     * Builds the single SQS record node: top-level {@code body} plus attributes and metadata. This is both
     * the delivery record shape and the structure an SQS filter pattern matches against (patterns nest under
     * {@code body}; the matcher auto-reparses a JSON body).
     */
    private ObjectNode buildSqsRecordNode(Message msg, EventSourceMapping esm) {
        ObjectNode record = objectMapper.createObjectNode();
        record.put("messageId", msg.getMessageId());
        record.put("receiptHandle", msg.getReceiptHandle());
        record.put("body", msg.getBody());
        ObjectNode attrs = record.putObject("attributes");
        attrs.put("ApproximateReceiveCount", String.valueOf(msg.getReceiveCount()));
        attrs.put("SentTimestamp", String.valueOf(msg.getSentTimestamp().toEpochMilli()));
        attrs.put("SenderId", AwsArnUtils.accountOrDefault(esm.getEventSourceArn(), "000000000000"));
        attrs.put("ApproximateFirstReceiveTimestamp",
                String.valueOf(msg.getFirstReceiveTimestamp() != null
                        ? msg.getFirstReceiveTimestamp().toEpochMilli()
                        : System.currentTimeMillis()));
        if (msg.getSequenceNumber() > 0) {
            attrs.put("SequenceNumber", String.valueOf(msg.getSequenceNumber()));
        }
        if (msg.getMessageGroupId() != null) {
            attrs.put("MessageGroupId", msg.getMessageGroupId());
        }
        if (msg.getMessageDeduplicationId() != null) {
            attrs.put("MessageDeduplicationId", msg.getMessageDeduplicationId());
        }
        // Populate messageAttributes from the message model
        ObjectNode msgAttrs = record.putObject("messageAttributes");
        if (msg.getMessageAttributes() != null) {
            msg.getMessageAttributes().forEach((name, val) -> {
                ObjectNode attrNode = msgAttrs.putObject(name);
                attrNode.put("dataType", val.getDataType() != null ? val.getDataType() : "String");
                if (val.getBinaryValue() != null) {
                    attrNode.put("binaryValue",
                            java.util.Base64.getEncoder().encodeToString(val.getBinaryValue()));
                } else if (val.getStringValue() != null) {
                    attrNode.put("stringValue", val.getStringValue());
                }
                attrNode.putArray("stringListValues");
                attrNode.putArray("binaryListValues");
            });
        }
        record.put("md5OfBody", msg.getMd5OfBody() != null ? msg.getMd5OfBody() : "");
        if (msg.getMd5OfMessageAttributes() != null) {
            record.put("md5OfMessageAttributes", msg.getMd5OfMessageAttributes());
        }
        record.put("eventSource", "aws:sqs");
        record.put("eventSourceARN", esm.getEventSourceArn());
        record.put("awsRegion", esm.getRegion());
        return record;
    }

    /**
     * Derives a queue URL from an SQS ARN.
     * arn:aws:sqs:REGION:ACCOUNT:QUEUE_NAME → {baseUrl}/ACCOUNT/QUEUE_NAME
     */
    public String queueArnToUrl(String arn) {
        return AwsArnUtils.arnToQueueUrl(arn, baseUrl);
    }

    /**
     * Extracts region from an SQS ARN.
     * arn:aws:sqs:REGION:ACCOUNT:NAME → REGION
     */
    public static String regionFromArn(String arn) {
        return AwsArnUtils.parse(arn).region();
    }
}
