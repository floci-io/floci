package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.lambda.model.EventSourceMapping;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * What Lambda does with a stream batch that keeps failing: the retry backoff, the
 * {@code MaximumRetryAttempts} and {@code MaximumRecordAgeInSeconds} limits, and the invocation
 * record it sends to the mapping's {@code DestinationConfig.OnFailure} when it discards the batch.
 * Only the batch info block and the event depend on the stream source; its poller passes them in a
 * {@link DiscardedBatch}.
 */
@ApplicationScoped
public class StreamBatchFailures {

    private static final Logger LOG = Logger.getLogger(StreamBatchFailures.class);

    static final String RETRY_ATTEMPTS_EXHAUSTED = "RetryAttemptsExhausted";
    static final String MAXIMUM_RECORD_AGE_EXCEEDED = "MaximumRecordAgeExceeded";
    static final long MAX_RETRY_BACKOFF_MS = 60_000;

    private static final DateTimeFormatter S3_KEY_DATE =
            DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter S3_KEY_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH.mm.ss").withZone(ZoneOffset.UTC);

    /**
     * The source-specific part of the invocation record. {@code batchInfoName} names the source's
     * batch info block, such as {@code DDBStreamBatchInfo}. The arrival times are written as given,
     * because their precision depends on the source. {@code event} builds the event of the discarded
     * records, in the shape the function gets it; only an S3 destination asks for it, as the
     * {@code payload} string.
     */
    record DiscardedBatch(String batchInfoName, String shardId, String startSequenceNumber,
                          String endSequenceNumber, String firstArrival, String lastArrival,
                          int batchSize, String streamArn, Supplier<String> event) {
    }

    private final SqsService sqsService;
    private final SnsService snsService;
    private final S3Service s3Service;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    @Inject
    public StreamBatchFailures(SqsService sqsService, SnsService snsService, S3Service s3Service,
                               ObjectMapper objectMapper, EmulatorConfig config) {
        this.sqsService = sqsService;
        this.snsService = snsService;
        this.s3Service = s3Service;
        this.objectMapper = objectMapper;
        this.baseUrl = config.effectiveBaseUrl();
    }

    static long retryBackoffMs(long pollIntervalMs, int retries) {
        int doublings = Math.min(retries, 16);
        return Math.min(pollIntervalMs * (1L << doublings), MAX_RETRY_BACKOFF_MS);
    }

    /** {@code invokeCount} is 1 + the retries so far. An unset or {@code -1} limit never runs out. */
    static boolean retryAttemptsExhausted(EventSourceMapping esm, int invokeCount) {
        Integer maximum = esm.getMaximumRetryAttempts();
        return maximum != null && maximum >= 0 && invokeCount > maximum;
    }

    /** An unset or {@code -1} limit never expires a batch. */
    static boolean maximumRecordAgeExceeded(EventSourceMapping esm, Instant oldestArrival, long nowMillis) {
        Integer maximum = esm.getMaximumRecordAgeInSeconds();
        return maximum != null && maximum > 0 && nowMillis - oldestArrival.toEpochMilli() >= maximum * 1_000L;
    }

    /**
     * With {@code BisectBatchOnFunctionError}, a batch that failed with a function error is split while
     * it holds more than one record. Splitting does not consume the retry quota.
     */
    static boolean splitsOnFunctionError(EventSourceMapping esm, InvokeResult invokeResult, int records) {
        return invokeResult.getFunctionError() != null
                && Boolean.TRUE.equals(esm.getBisectBatchOnFunctionError()) && records > 1;
    }

    /** A split batch is refetched at its first half, rounded up. */
    static int splitBatchSize(int fetched) {
        return (fetched + 1) / 2;
    }

    /** The most records to fetch after the checkpoint {@code key}: its split size, while bisecting. */
    static int bisectLimit(EventSourceMapping esm, Map<String, Integer> splitSizes, String key) {
        return Boolean.TRUE.equals(esm.getBisectBatchOnFunctionError())
                ? splitSizes.getOrDefault(key, Integer.MAX_VALUE)
                : Integer.MAX_VALUE;
    }

    /** The retry state of a failing batch is keyed by the checkpoint it is read after. */
    static String batchStateKey(String esmUuid, String shardId, String checkpoint) {
        return esmUuid + ":" + shardId + ":" + (checkpoint == null ? "TRIM_HORIZON" : checkpoint);
    }

    static String s3Key(String esmUuid, String shardId, Instant now, UUID randomId) {
        return "aws/lambda/" + esmUuid + "/" + shardId + "/"
                + S3_KEY_DATE.format(now) + "/"
                + S3_KEY_TIME.format(now) + "-" + randomId;
    }

    /**
     * Sends the invocation record of a discarded batch to the mapping's OnFailure destination, if it
     * has one. A refused send is logged and the batch is dropped all the same, as AWS does.
     */
    void sendToOnFailureDestination(EventSourceMapping esm, DiscardedBatch batch, InvokeResult invokeResult,
                                    int invokeCount, String condition) {
        if (esm.getDestinationConfig() == null || esm.getDestinationConfig().getOnFailure() == null) {
            return;
        }
        String destinationArn = esm.getDestinationConfig().getOnFailure().getDestination();
        if (destinationArn == null || destinationArn.isBlank()) {
            return;
        }

        // A poller thread carries no request context, so the destination's account is set before
        // its account-aware store is read.
        String destinationAccount = AwsArnUtils.accountOrDefault(destinationArn, esm.getAccountId());
        try {
            if (destinationArn.contains(":sqs:")) {
                String region = AwsArnUtils.regionOrDefault(destinationArn, esm.getRegion());
                String queueUrl = AwsArnUtils.arnToQueueUrl(destinationArn, baseUrl);
                String record = invocationRecord(esm, batch, invokeResult, invokeCount, condition, false);
                RequestScopes.runAs(destinationAccount, () -> sqsService.sendMessage(queueUrl, record, 0, region));
            } else if (destinationArn.contains(":sns:")) {
                String region = AwsArnUtils.regionOrDefault(destinationArn, esm.getRegion());
                String record = invocationRecord(esm, batch, invokeResult, invokeCount, condition, false);
                RequestScopes.runAs(destinationAccount, () ->
                        snsService.publish(destinationArn, null, record, "ESM OnFailure", region));
            } else if (destinationArn.contains(":s3:")) {
                AwsArnUtils.Arn arn = AwsArnUtils.parse(destinationArn);
                if (!"s3".equals(arn.service()) || arn.resource().isBlank()) {
                    throw new IllegalArgumentException("Invalid S3 destination ARN: " + destinationArn);
                }
                String key = s3Key(esm.getUuid(), batch.shardId(), Instant.now(), UUID.randomUUID());
                byte[] body = invocationRecord(esm, batch, invokeResult, invokeCount, condition, true)
                        .getBytes(StandardCharsets.UTF_8);
                RequestScopes.runAs(esm.getAccountId(), () ->
                        s3Service.putObject(arn.resource(), key, body, "application/json", Map.of()));
            } else {
                LOG.warnv("ESM {0}: unsupported OnFailure destination {1}, discarding the batch",
                        esm.getUuid(), destinationArn);
                return;
            }
            LOG.infov("ESM {0}: sent the discarded batch of shard {1} to {2}",
                    esm.getUuid(), batch.shardId(), destinationArn);
        } catch (Exception e) {
            LOG.errorv(e, "ESM {0}: failed to send to OnFailure destination {1}, discarding the batch",
                    esm.getUuid(), destinationArn);
        }
    }

    private String invocationRecord(EventSourceMapping esm, DiscardedBatch batch, InvokeResult invokeResult,
                                    int invokeCount, String condition, boolean withPayload)
            throws JsonProcessingException {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode requestContext = root.putObject("requestContext");
        requestContext.put("requestId", invokeResult.getRequestId() != null ? invokeResult.getRequestId() : "");
        requestContext.put("functionArn", esm.getFunctionArn() != null ? esm.getFunctionArn() : "");
        requestContext.put("condition", condition);
        requestContext.put("approximateInvokeCount", invokeCount);

        ObjectNode responseContext = root.putObject("responseContext");
        responseContext.put("statusCode", invokeResult.getStatusCode() != 0 ? invokeResult.getStatusCode() : 200);
        responseContext.put("executedVersion",
                invokeResult.getExecutedVersion() != null ? invokeResult.getExecutedVersion() : "$LATEST");
        if (invokeResult.getFunctionError() != null) {
            responseContext.put("functionError", invokeResult.getFunctionError());
        }

        root.put("version", "1.0");
        if (withPayload) {
            root.put("payload", batch.event().get());
        }
        root.put("timestamp", AsyncInvokeDestinationRouter.formatRecordTimestamp(Instant.now()));

        ObjectNode batchInfo = root.putObject(batch.batchInfoName());
        batchInfo.put("shardId", batch.shardId());
        batchInfo.put("startSequenceNumber", batch.startSequenceNumber());
        batchInfo.put("endSequenceNumber", batch.endSequenceNumber());
        batchInfo.put("approximateArrivalOfFirstRecord", batch.firstArrival());
        batchInfo.put("approximateArrivalOfLastRecord", batch.lastArrival());
        batchInfo.put("batchSize", batch.batchSize());
        batchInfo.put("streamArn", batch.streamArn());
        return objectMapper.writeValueAsString(root);
    }
}
