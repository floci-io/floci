package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.cloudwatch.logs.filter.FilterPattern;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogEvent;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.SubscriptionFilter;
import io.github.hectorvent.floci.services.firehose.FirehoseService;
import io.github.hectorvent.floci.services.firehose.model.Record;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import io.github.hectorvent.floci.services.lambda.LambdaArnUtils;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/**
 * Delivers matching log events to CloudWatch Logs subscription filter destinations
 * (Lambda, Kinesis Data Streams, and Firehose Delivery Streams) upon log event ingestion.
 *
 * <p>Delivery occurs synchronously on the calling ingestion thread without failing the log write.
 * Subscription filter payloads are gzip-compressed JSON DATA_MESSAGE records matching the AWS wire format.
 *
 * <p>Note: FirehoseCompression is package-private to the firehose package and models S3 delivery formats,
 * while FlowLogService gzip helper is private in ec2. This compact gzip helper avoids coupling across service
 * internals.
 */
@ApplicationScoped
public class CloudWatchLogsSubscriptionDeliveryService {

    private static final Logger LOG = Logger.getLogger(CloudWatchLogsSubscriptionDeliveryService.class);
    private static final String DATA_MESSAGE = "DATA_MESSAGE";
    private static final String LAMBDA_LOG_PREFIX = "/aws/lambda/";
    /** Max compressed record size for Kinesis and Firehose delivery (1 MB). */
    private static final int MAX_RECORD_PAYLOAD_BYTES = 1_000_000;

    private final StorageBackend<String, SubscriptionFilter> store;
    private final LambdaService lambdaService;
    private final KinesisService kinesisService;
    private final FirehoseService firehoseService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public CloudWatchLogsSubscriptionDeliveryService(CloudWatchLogsService logsService,
                                                     LambdaService lambdaService,
                                                     KinesisService kinesisService,
                                                     FirehoseService firehoseService,
                                                     RegionResolver regionResolver,
                                                     ObjectMapper objectMapper) {
        this.store = logsService.subscriptionFilterStore();
        this.lambdaService = lambdaService;
        this.kinesisService = kinesisService;
        this.firehoseService = firehoseService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @RegisterForReflection
    public record SubscriptionPayload(
            String messageType,
            String owner,
            String logGroup,
            String logStream,
            List<String> subscriptionFilters,
            List<SubscriptionLogEvent> logEvents) {
    }

    @RegisterForReflection
    public record SubscriptionLogEvent(
            String id,
            long timestamp,
            String message) {
    }

    void onLogEventsIngested(@Observes LogEventsIngested event) {
        if (event == null || event.events() == null || event.events().isEmpty()) {
            return;
        }
        String account = event.accountId() == null || event.accountId().isBlank()
                ? regionResolver.getAccountId() : event.accountId();
        try {
            List<SubscriptionFilter> filters = filtersOf(event.logGroupName(), event.region(), account);
            for (SubscriptionFilter filter : filters) {
                try {
                    deliver(filter, event, account);
                } catch (Exception e) {
                    LOG.errorv(e, "Cannot deliver subscription filter events: account={0}, region={1}, group={2}, filter={3}",
                            account, event.region(), event.logGroupName(), filter.getFilterName());
                }
            }
        } catch (Exception e) {
            LOG.errorv(e, "Cannot load subscription filters; delivery skipped: account={0}, region={1}, group={2}",
                    account, event.region(), event.logGroupName());
        }
    }

    private List<SubscriptionFilter> filtersOf(String logGroupName, String region, String accountId) {
        String prefix = CloudWatchLogsService.subscriptionFilterKeyPrefix(region, logGroupName);
        if (accountId != null && store instanceof AccountAwareStorageBackend<?> rawAware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<SubscriptionFilter> aware =
                    (AccountAwareStorageBackend<SubscriptionFilter>) rawAware;
            return aware.scanForAccount(accountId, key -> key.startsWith(prefix));
        }
        return store.scan(key -> key.startsWith(prefix));
    }

    private void deliver(SubscriptionFilter filter, LogEventsIngested event, String account) throws IOException {
        FilterPattern pattern = FilterPattern.parse(filter.getFilterPattern());
        List<SubscriptionLogEvent> matching = new ArrayList<>();
        for (LogEvent logEvent : event.events()) {
            if (pattern.match(logEvent.getMessage()).matched()) {
                matching.add(new SubscriptionLogEvent(
                        logEvent.getEventId(),
                        logEvent.getTimestamp(),
                        logEvent.getMessage()));
            }
        }
        if (matching.isEmpty()) {
            return;
        }

        String destinationArn = filter.getDestinationArn();
        if (destinationArn == null || !AwsArnUtils.isArn(destinationArn)) {
            LOG.warnv("Cannot deliver to unresolvable destinationArn: {0}", destinationArn);
            return;
        }

        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(destinationArn);
        } catch (IllegalArgumentException e) {
            LOG.warnv("Cannot parse destinationArn: {0}", destinationArn);
            return;
        }

        // Direct cross-account delivery without a Logs Destination (PutDestination) is unauthorized in AWS
        if (!arn.accountId().isBlank() && !arn.accountId().equals(account)) {
            LOG.warnv("Cross-account subscription delivery is unauthorized without a Logs destination: destinationAccount={0}, groupAccount={1}",
                    arn.accountId(), account);
            return;
        }

        String targetRegion = (arn.region() != null && !arn.region().isBlank())
                ? arn.region() : event.region();
        String service = arn.service();

        if ("lambda".equals(service)) {
            if (isSelfSubscribedLambda(event.logGroupName(), destinationArn)) {
                LOG.warnv("Skipping delivery to Lambda destination {0} to prevent recursive loop on its own log group {1}",
                        destinationArn, event.logGroupName());
                return;
            }
            deliverChunks(filter, event, account, matching, gzipped -> deliverToLambda(destinationArn, gzipped));
        } else if ("kinesis".equals(service)) {
            deliverChunks(filter, event, account, matching, gzipped ->
                    deliverToKinesis(arn, filter, gzipped, event.logStreamName(), account, targetRegion));
        } else if ("firehose".equals(service)) {
            deliverChunks(filter, event, account, matching, gzipped ->
                    deliverToFirehose(arn, gzipped, account, targetRegion));
        } else {
            LOG.warnv("Unsupported subscription filter destination service: {0}", service);
        }
    }

    private boolean isSelfSubscribedLambda(String logGroupName, String functionArn) {
        if (logGroupName == null || functionArn == null || !logGroupName.startsWith(LAMBDA_LOG_PREFIX)) {
            return false;
        }
        String logFunctionName = logGroupName.substring(LAMBDA_LOG_PREFIX.length());
        try {
            String targetFunctionName = LambdaArnUtils.resolve(functionArn).name();
            return logFunctionName.equals(targetFunctionName);
        } catch (Exception e) {
            return false;
        }
    }

    @FunctionalInterface
    private interface PayloadConsumer {
        void accept(byte[] gzippedBytes) throws Exception;
    }

    private void deliverChunks(SubscriptionFilter filter, LogEventsIngested event,
                               String account, List<SubscriptionLogEvent> matching,
                               PayloadConsumer consumer) {
        int start = 0;
        int currentBytes = 0;
        for (int i = 0; i < matching.size(); i++) {
            SubscriptionLogEvent logEvent = matching.get(i);
            int eventBytes = (logEvent.message() != null ? logEvent.message().length() : 0) + 64;
            if (i > start && (i - start >= 500 || currentBytes + eventBytes > 500_000)) {
                deliverSlice(filter, event, account, matching.subList(start, i), consumer);
                start = i;
                currentBytes = 0;
            }
            currentBytes += eventBytes;
        }
        if (start < matching.size()) {
            deliverSlice(filter, event, account, matching.subList(start, matching.size()), consumer);
        }
    }

    private void deliverSlice(SubscriptionFilter filter, LogEventsIngested event,
                              String account, List<SubscriptionLogEvent> slice,
                              PayloadConsumer consumer) {
        if (slice.isEmpty()) {
            return;
        }
        byte[] gzippedBytes;
        try {
            SubscriptionPayload payload = new SubscriptionPayload(
                    DATA_MESSAGE,
                    account,
                    event.logGroupName(),
                    event.logStreamName(),
                    List.of(filter.getFilterName()),
                    slice);
            byte[] jsonBytes = objectMapper.writeValueAsBytes(payload);
            gzippedBytes = gzip(jsonBytes);
        } catch (Exception e) {
            LOG.warnv(e, "Cannot serialize subscription payload: filter={0}", filter.getFilterName());
            return;
        }

        if (gzippedBytes.length <= MAX_RECORD_PAYLOAD_BYTES || slice.size() <= 1) {
            try {
                consumer.accept(gzippedBytes);
            } catch (Exception e) {
                LOG.warnv(e, "Cannot deliver subscription filter chunk: filter={0}, destination={1}",
                        filter.getFilterName(), filter.getDestinationArn());
            }
            return;
        }

        int mid = slice.size() / 2;
        deliverSlice(filter, event, account, slice.subList(0, mid), consumer);
        deliverSlice(filter, event, account, slice.subList(mid, slice.size()), consumer);
    }

    private void deliverToLambda(String functionArn, byte[] gzippedBytes) throws JsonProcessingException {
        if (lambdaService == null) {
            LOG.warnv("Cannot deliver to Lambda; service not available: {0}", functionArn);
            return;
        }
        String base64Data = Base64.getEncoder().encodeToString(gzippedBytes);
        Map<String, Map<String, String>> lambdaEvent = Map.of(
                "awslogs", Map.of("data", base64Data));
        byte[] payloadBytes = objectMapper.writeValueAsBytes(lambdaEvent);
        lambdaService.invokeArn(functionArn, payloadBytes, InvocationType.Event);
        LOG.debugv("Subscription filter delivered to Lambda: {0}", functionArn);
    }

    private void deliverToKinesis(AwsArnUtils.Arn arn, SubscriptionFilter filter, byte[] gzippedBytes,
                                  String logStreamName, String targetAccount, String targetRegion) {
        if (kinesisService == null) {
            LOG.warnv("Cannot deliver to Kinesis; service not available: {0}", arn);
            return;
        }
        String streamName = arn.resource().startsWith("stream/")
                ? arn.resource().substring("stream/".length()) : arn.resource();
        String partitionKey = "Random".equalsIgnoreCase(filter.getDistribution())
                ? UUID.randomUUID().toString()
                : (logStreamName != null ? logStreamName : "");
        kinesisService.putRecordForAccount(targetAccount, streamName, gzippedBytes, partitionKey, targetRegion);
        LOG.debugv("Subscription filter delivered to Kinesis stream {0} partitionKey {1}", streamName, partitionKey);
    }

    private void deliverToFirehose(AwsArnUtils.Arn arn, byte[] gzippedBytes,
                                   String targetAccount, String targetRegion) {
        if (firehoseService == null) {
            LOG.warnv("Cannot deliver to Firehose; service not available: {0}", arn);
            return;
        }
        String streamName = arn.resource().startsWith("deliverystream/")
                ? arn.resource().substring("deliverystream/".length()) : arn.resource();
        Record record = new Record(gzippedBytes);
        firehoseService.putRecord(targetAccount, targetRegion, streamName, record);
        LOG.debugv("Subscription filter delivered to Firehose delivery stream: {0}", streamName);
    }

    static byte[] gzip(byte[] input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, input.length / 2));
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(input);
        }
        return out.toByteArray();
    }
}
