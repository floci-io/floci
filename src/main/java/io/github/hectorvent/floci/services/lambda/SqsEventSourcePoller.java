package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.lambda.model.EventSourceMapping;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.vertx.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Polls SQS queues on behalf of Lambda Event Source Mappings.
 * Uses Vert.x periodic timers so polling is non-blocking.
 * Injects LambdaExecutorService + LambdaFunctionStore directly (not LambdaService)
 * to avoid a circular CDI dependency.
 */
@ApplicationScoped
public class SqsEventSourcePoller {

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
    private final ConcurrentHashMap<String, Long> timerIds = new ConcurrentHashMap<>();
    // Tracks ESMs with an in-flight poll to prevent concurrent deliveries of the same message
    private final ConcurrentHashMap<String, Boolean> activePolls = new ConcurrentHashMap<>();
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
                                ObjectMapper objectMapper) {
        this.vertx = vertx;
        this.sqsService = sqsService;
        this.executorService = executorService;
        this.functionStore = functionStore;
        this.esmStore = esmStore;
        this.pollIntervalMs = config.services().lambda().pollIntervalMs();
        this.baseUrl = config.effectiveBaseUrl();
        this.objectMapper = objectMapper;
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

                int visibilityTimeout = fn.getTimeout() + 30;
                List<Message> messages = sqsService.receiveMessage(
                        esm.getQueueUrl(), esm.getBatchSize(), visibilityTimeout, 0, esm.getRegion());

                if (messages.isEmpty()) {
                    return;
                }

                LOG.infov("ESM {0}: received {1} message(s), invoking {2}",
                        esm.getUuid(), messages.size(), esm.getFunctionName());

                String eventJson = buildSqsEvent(messages, esm);
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
                    Set<String> failedIds = extractBatchItemFailures(esm, result);
                    List<Message> toDelete = failedIds.isEmpty()
                            ? messages
                            : messages.stream().filter(m -> !failedIds.contains(m.getMessageId())).toList();
                    LOG.infov("ESM {0}: Lambda succeeded, deleting {1} of {2} message(s) ({3} reported as failed)",
                            esm.getUuid(), toDelete.size(), messages.size(), failedIds.size());
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
                        List<Message> toReturn = messages.stream()
                                .filter(m -> failedIds.contains(m.getMessageId())).toList();
                        returnMessagesToQueue(esm, toReturn);
                    }
                } else {
                    LOG.warnv("ESM {0}: Lambda returned error [{1}], returning {2} message(s) to queue for retry/redrive",
                            esm.getUuid(), result.getFunctionError(), messages.size());
                    returnMessagesToQueue(esm, messages);
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

    private Set<String> extractBatchItemFailures(EventSourceMapping esm, InvokeResult result) {
        if (!esm.isReportBatchItemFailures() || result.getPayload() == null || result.getPayload().length == 0) {
            return Set.of();
        }
        try {
            var root = objectMapper.readTree(result.getPayload());
            var failures = root.get("batchItemFailures");
            if (failures == null || !failures.isArray()) {
                return Set.of();
            }
            Set<String> failedIds = new HashSet<>();
            for (var item : failures) {
                var id = item.get("itemIdentifier");
                if (id != null && !id.isNull()) {
                    failedIds.add(id.asText());
                }
            }
            return failedIds;
        } catch (Exception e) {
            LOG.warnv("ESM {0}: failed to parse batchItemFailures from Lambda response: {1}",
                    esm.getUuid(), e.getMessage());
            return Set.of();
        }
    }

    String buildSqsEvent(List<Message> messages, EventSourceMapping esm) {
        try {
            var records = objectMapper.createArrayNode();
            for (Message msg : messages) {
                ObjectNode record = objectMapper.createObjectNode();
                record.put("messageId", msg.getMessageId());
                record.put("receiptHandle", msg.getReceiptHandle());
                record.put("body", msg.getBody());
                ObjectNode attrs = record.putObject("attributes");
                attrs.put("ApproximateReceiveCount", String.valueOf(msg.getReceiveCount()));
                attrs.put("SentTimestamp", String.valueOf(msg.getSentTimestamp().toEpochMilli()));
                attrs.put("SenderId", AwsArnUtils.accountOrDefault(esm.getEventSourceArn(), "000000000000"));
                attrs.put("ApproximateFirstReceiveTimestamp", String.valueOf(System.currentTimeMillis()));
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
                records.add(record);
            }
            ObjectNode root = objectMapper.createObjectNode();
            root.set("Records", records);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"Records\":[]}";
        }
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
