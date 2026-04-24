package io.github.hectorvent.floci.services.pipes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbStreamService;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import io.github.hectorvent.floci.services.pipes.model.DesiredState;
import io.github.hectorvent.floci.services.pipes.model.Pipe;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.vertx.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@ApplicationScoped
public class PipesPoller {

    private static final Logger LOG = Logger.getLogger(PipesPoller.class);
    private static final long POLL_INTERVAL_MS = 1000;
    private static final int DEFAULT_BATCH_SIZE = 10;

    private final Vertx vertx;
    private final SqsService sqsService;
    private final KinesisService kinesisService;
    private final DynamoDbStreamService dynamoDbStreamService;
    private final PipesTargetInvoker targetInvoker;
    private final PipesFilterMatcher filterMatcher;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final ConcurrentHashMap<String, Long> timerIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> activePolls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> kinesisIterators = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> dynamoDbIterators = new ConcurrentHashMap<>();
    private final ExecutorService pollExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "pipes-poller");
        t.setDaemon(true);
        return t;
    });

    @Inject
    public PipesPoller(Vertx vertx,
                       SqsService sqsService,
                       KinesisService kinesisService,
                       DynamoDbStreamService dynamoDbStreamService,
                       PipesTargetInvoker targetInvoker,
                       PipesFilterMatcher filterMatcher,
                       ObjectMapper objectMapper,
                       EmulatorConfig config) {
        this.vertx = vertx;
        this.sqsService = sqsService;
        this.kinesisService = kinesisService;
        this.dynamoDbStreamService = dynamoDbStreamService;
        this.targetInvoker = targetInvoker;
        this.filterMatcher = filterMatcher;
        this.objectMapper = objectMapper;
        this.baseUrl = config.effectiveBaseUrl();
    }

    @PreDestroy
    void shutdown() {
        pollExecutor.shutdownNow();
        timerIds.values().forEach(vertx::cancelTimer);
        timerIds.clear();
        LOG.info("PipesPoller shut down");
    }

    public void startPolling(Pipe pipe) {
        String pipeKey = pipeKey(pipe);
        if (timerIds.containsKey(pipeKey)) {
            return;
        }
        long timerId = vertx.setPeriodic(POLL_INTERVAL_MS, id -> pollAndInvoke(pipe));
        timerIds.put(pipeKey, timerId);
        LOG.infov("Pipe {0}: started polling source {1}", pipe.getName(), pipe.getSource());
    }

    public void stopPolling(Pipe pipe) {
        String pipeKey = pipeKey(pipe);
        Long timerId = timerIds.remove(pipeKey);
        if (timerId != null) {
            vertx.cancelTimer(timerId);
            kinesisIterators.remove(pipeKey);
            dynamoDbIterators.remove(pipeKey);
            LOG.infov("Pipe {0}: stopped polling", pipe.getName());
        }
    }

    public boolean isPolling(Pipe pipe) {
        return timerIds.containsKey(pipeKey(pipe));
    }

    private void pollAndInvoke(Pipe pipe) {
        String pipeKey = pipeKey(pipe);
        if (activePolls.putIfAbsent(pipeKey, Boolean.TRUE) != null) {
            return;
        }
        pollExecutor.submit(() -> {
            try {
                if (pipe.getDesiredState() != DesiredState.RUNNING) {
                    return;
                }
                String sourceArn = pipe.getSource();
                String region = extractRegionFromArn(sourceArn);
                if (sourceArn.contains(":sqs:")) {
                    pollSqs(pipe, region);
                } else if (sourceArn.contains(":kinesis:")) {
                    pollKinesis(pipe, region);
                } else if (sourceArn.contains(":dynamodb:")) {
                    pollDynamoDbStreams(pipe, region);
                } else {
                    LOG.warnv("Pipe {0}: unsupported source type: {1}", pipe.getName(), sourceArn);
                }
            } catch (Exception e) {
                LOG.warnv("Pipe {0}: poll error: {1} ({2})",
                        pipe.getName(), e.getMessage(), e.getClass().getSimpleName());
            } finally {
                activePolls.remove(pipeKey);
            }
        });
    }

    private void pollSqs(Pipe pipe, String region) {
        String queueUrl = AwsArnUtils.arnToQueueUrl(pipe.getSource(), baseUrl);
        int batchSize = getBatchSize(pipe, "SqsQueueParameters");
        List<Message> messages = sqsService.receiveMessage(queueUrl, batchSize, 30, 0, region);
        if (messages.isEmpty()) {
            return;
        }
        LOG.infov("Pipe {0}: received {1} SQS message(s)", pipe.getName(), messages.size());

        List<ObjectNode> recordNodes = buildSqsRecordNodes(messages, pipe);
        List<JsonNode> filtered = filterMatcher.applyFilterCriteria(
                new ArrayList<>(recordNodes), pipe.getSourceParameters());

        // Build a set of messageIds that matched the filter
        Set<String> matchedMessageIds = new HashSet<>();
        for (JsonNode node : filtered) {
            if (node.has("messageId")) {
                matchedMessageIds.add(node.get("messageId").asText());
            }
        }

        // Delete non-matching messages immediately (AWS behavior: filtered-out messages are consumed)
        for (Message msg : messages) {
            if (!matchedMessageIds.contains(msg.getMessageId())) {
                try {
                    sqsService.deleteMessage(queueUrl, msg.getReceiptHandle(), region);
                } catch (Exception e) {
                    LOG.warnv("Pipe {0}: failed to delete SQS message {1}: {2}",
                            pipe.getName(), msg.getMessageId(), e.getMessage());
                }
            }
        }

        if (filtered.isEmpty()) {
            return;
        }

        // Attempt delivery; only delete matching messages after successful delivery or DLQ routing
        String eventJson = wrapRecords(filtered);
        boolean delivered = invokeWithDlq(pipe, eventJson, region);
        if (delivered) {
            for (Message msg : messages) {
                if (matchedMessageIds.contains(msg.getMessageId())) {
                    try {
                        sqsService.deleteMessage(queueUrl, msg.getReceiptHandle(), region);
                    } catch (Exception e) {
                        LOG.warnv("Pipe {0}: failed to delete SQS message {1}: {2}",
                                pipe.getName(), msg.getMessageId(), e.getMessage());
                    }
                }
            }
        }
    }

    private void pollKinesis(Pipe pipe, String region) {
        String pipeKey = pipeKey(pipe);
        String streamName = extractResourceName(pipe.getSource());
        int batchSize = getBatchSize(pipe, "KinesisStreamParameters");
        String iterator = kinesisIterators.get(pipeKey);
        if (iterator == null) {
            iterator = initKinesisIterator(streamName, region);
            if (iterator == null) {
                return;
            }
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = kinesisService.getRecords(iterator, batchSize, region);
            String nextIterator = (String) result.get("NextShardIterator");
            if (nextIterator != null) {
                kinesisIterators.put(pipeKey, nextIterator);
            }
            @SuppressWarnings("unchecked")
            List<?> records = (List<?>) result.get("Records");
            if (records == null || records.isEmpty()) {
                return;
            }
            LOG.infov("Pipe {0}: received {1} Kinesis record(s)", pipe.getName(), records.size());
            List<ObjectNode> recordNodes = buildKinesisRecordNodes(records, pipe, region);
            List<JsonNode> filtered = filterMatcher.applyFilterCriteria(
                    new ArrayList<>(recordNodes), pipe.getSourceParameters());
            if (filtered.isEmpty()) {
                return;
            }
            String eventJson = wrapRecords(filtered);
            if (!invokeWithDlq(pipe, eventJson, region)) {
                LOG.warnv("Pipe {0}: {1} Kinesis record(s) dropped — delivery and DLQ both failed",
                        pipe.getName(), filtered.size());
            }
        } catch (AwsException e) {
            if ("ExpiredIteratorException".equals(e.getErrorCode())) {
                kinesisIterators.remove(pipeKey);
            }
            throw e;
        }
    }

    private String initKinesisIterator(String streamName, String region) {
        try {
            return kinesisService.getShardIterator(
                    streamName, "shardId-000000000000", "TRIM_HORIZON", null, null, region);
        } catch (Exception e) {
            LOG.warnv("Failed to get Kinesis shard iterator for {0}: {1}", streamName, e.getMessage());
            return null;
        }
    }

    private void pollDynamoDbStreams(Pipe pipe, String region) {
        String pipeKey = pipeKey(pipe);
        String streamArn = pipe.getSource();
        int batchSize = getBatchSize(pipe, "DynamoDBStreamParameters");
        String iterator = dynamoDbIterators.get(pipeKey);
        if (iterator == null) {
            iterator = initDynamoDbIterator(streamArn);
            if (iterator == null) {
                return;
            }
        }
        try {
            var result = dynamoDbStreamService.getRecords(iterator, batchSize);
            String nextIterator = result.nextShardIterator();
            if (nextIterator != null) {
                dynamoDbIterators.put(pipeKey, nextIterator);
            }
            var records = result.records();
            if (records == null || records.isEmpty()) {
                return;
            }
            LOG.infov("Pipe {0}: received {1} DynamoDB Stream record(s)", pipe.getName(), records.size());
            List<ObjectNode> recordNodes = buildDynamoDbRecordNodes(records, pipe, region);
            List<JsonNode> filtered = filterMatcher.applyFilterCriteria(
                    new ArrayList<>(recordNodes), pipe.getSourceParameters());
            if (filtered.isEmpty()) {
                return;
            }
            String eventJson = wrapRecords(filtered);
            if (!invokeWithDlq(pipe, eventJson, region)) {
                LOG.warnv("Pipe {0}: {1} DynamoDB Stream record(s) dropped — delivery and DLQ both failed",
                        pipe.getName(), filtered.size());
            }
        } catch (AwsException e) {
            if ("ExpiredIteratorException".equals(e.getErrorCode()) ||
                "TrimmedDataAccessException".equals(e.getErrorCode())) {
                dynamoDbIterators.remove(pipeKey);
            }
            throw e;
        }
    }

    private String initDynamoDbIterator(String streamArn) {
        try {
            return dynamoDbStreamService.getShardIterator(
                    streamArn, DynamoDbStreamService.SHARD_ID, "TRIM_HORIZON", null);
        } catch (Exception e) {
            LOG.warnv("Failed to get DynamoDB stream iterator for {0}: {1}", streamArn, e.getMessage());
            return null;
        }
    }

    // ──────────────────────────── Invocation & DLQ ────────────────────────────

    private boolean invokeWithDlq(Pipe pipe, String eventJson, String region) {
        try {
            targetInvoker.invoke(pipe, eventJson, region);
            return true;
        } catch (Exception e) {
            LOG.warnv("Pipe {0}: delivery failed: {1} ({2})",
                    pipe.getName(), e.getMessage(), e.getClass().getSimpleName());
            return sendToDeadLetterQueue(pipe, eventJson, region);
        }
    }

    private boolean sendToDeadLetterQueue(Pipe pipe, String payload, String region) {
        String dlqArn = getDlqArn(pipe);
        if (dlqArn == null) {
            return false;
        }
        try {
            String queueUrl = AwsArnUtils.arnToQueueUrl(dlqArn, baseUrl);
            sqsService.sendMessage(queueUrl, payload, 0);
            LOG.infov("Pipe {0}: sent failed records to DLQ {1}", pipe.getName(), dlqArn);
            return true;
        } catch (Exception e) {
            LOG.errorv("Pipe {0}: failed to send to DLQ {1}: {2}",
                    pipe.getName(), dlqArn, e.getMessage());
            return false;
        }
    }

    private String getDlqArn(Pipe pipe) {
        JsonNode sp = pipe.getSourceParameters();
        if (sp == null) {
            return null;
        }
        for (String key : List.of("SqsQueueParameters", "KinesisStreamParameters", "DynamoDBStreamParameters")) {
            JsonNode dlq = sp.path(key).path("DeadLetterConfig").path("Arn");
            if (!dlq.isMissingNode() && dlq.isTextual()) {
                return dlq.asText();
            }
        }
        return null;
    }

    private int getBatchSize(Pipe pipe, String sourceParamKey) {
        JsonNode sp = pipe.getSourceParameters();
        if (sp != null && sp.has(sourceParamKey)) {
            return sp.path(sourceParamKey).path("BatchSize").asInt(DEFAULT_BATCH_SIZE);
        }
        return DEFAULT_BATCH_SIZE;
    }

    private String wrapRecords(List<JsonNode> records) {
        try {
            var recordsArray = objectMapper.createArrayNode();
            records.forEach(recordsArray::add);
            ObjectNode root = objectMapper.createObjectNode();
            root.set("Records", recordsArray);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"Records\":[]}";
        }
    }

    // ──────────────────────────── Record Builders ────────────────────────────

    private List<ObjectNode> buildSqsRecordNodes(List<Message> messages, Pipe pipe) {
        List<ObjectNode> nodes = new ArrayList<>();
        for (Message msg : messages) {
            ObjectNode record = objectMapper.createObjectNode();
            record.put("messageId", msg.getMessageId());
            record.put("receiptHandle", msg.getReceiptHandle());
            record.put("body", msg.getBody());
            ObjectNode attrs = record.putObject("attributes");
            attrs.put("ApproximateReceiveCount", String.valueOf(msg.getReceiveCount()));
            attrs.put("SentTimestamp", String.valueOf(System.currentTimeMillis()));
            record.putObject("messageAttributes");
            record.put("md5OfBody", msg.getMd5OfBody() != null ? msg.getMd5OfBody() : "");
            record.put("eventSource", "aws:sqs");
            record.put("eventSourceARN", pipe.getSource());
            record.put("awsRegion", extractRegionFromArn(pipe.getSource()));
            nodes.add(record);
        }
        return nodes;
    }

    private List<ObjectNode> buildKinesisRecordNodes(List<?> records, Pipe pipe, String region) {
        List<ObjectNode> nodes = new ArrayList<>();
        for (Object record : records) {
            ObjectNode node = objectMapper.valueToTree(record);
            ObjectNode eventRecord = objectMapper.createObjectNode();
            eventRecord.put("eventSource", "aws:kinesis");
            eventRecord.put("eventSourceARN", pipe.getSource());
            eventRecord.put("awsRegion", region);
            eventRecord.put("eventID", pipe.getSource() + ":" +
                    node.path("SequenceNumber").asText());
            ObjectNode kinesis = eventRecord.putObject("kinesis");
            kinesis.put("partitionKey", node.path("PartitionKey").asText());
            kinesis.put("sequenceNumber", node.path("SequenceNumber").asText());
            kinesis.put("approximateArrivalTimestamp",
                    node.path("ApproximateArrivalTimestamp").asDouble());
            kinesis.put("data", node.path("Data").asText());
            nodes.add(eventRecord);
        }
        return nodes;
    }

    private List<ObjectNode> buildDynamoDbRecordNodes(List<?> records, Pipe pipe, String region) {
        List<ObjectNode> nodes = new ArrayList<>();
        for (Object record : records) {
            ObjectNode node = objectMapper.valueToTree(record);
            node.put("eventSource", "aws:dynamodb");
            node.put("eventSourceARN", pipe.getSource());
            node.put("awsRegion", region);
            nodes.add(node);
        }
        return nodes;
    }

    // ──────────────────────────── Utilities ────────────────────────────

    private static String pipeKey(Pipe pipe) {
        return pipe.getArn();
    }

    private static String extractRegionFromArn(String arn) {
        String[] parts = arn.split(":");
        return parts.length >= 4 ? parts[3] : "us-east-1";
    }

    private static String extractResourceName(String arn) {
        int slashIdx = arn.lastIndexOf('/');
        if (slashIdx >= 0) {
            return arn.substring(slashIdx + 1);
        }
        int colonIdx = arn.lastIndexOf(':');
        return colonIdx >= 0 ? arn.substring(colonIdx + 1) : arn;
    }
}
