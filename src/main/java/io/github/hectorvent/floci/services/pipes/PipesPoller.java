package io.github.hectorvent.floci.services.pipes;

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

import java.util.List;
import java.util.Map;
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
                       ObjectMapper objectMapper,
                       EmulatorConfig config) {
        this.vertx = vertx;
        this.sqsService = sqsService;
        this.kinesisService = kinesisService;
        this.dynamoDbStreamService = dynamoDbStreamService;
        this.targetInvoker = targetInvoker;
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
        List<Message> messages = sqsService.receiveMessage(queueUrl, DEFAULT_BATCH_SIZE, 30, 0, region);
        if (messages.isEmpty()) {
            return;
        }
        LOG.infov("Pipe {0}: received {1} SQS message(s)", pipe.getName(), messages.size());
        String eventJson = buildSqsEvent(messages, pipe);
        targetInvoker.invoke(pipe, eventJson, region);
        for (Message msg : messages) {
            try {
                sqsService.deleteMessage(queueUrl, msg.getReceiptHandle(), region);
            } catch (Exception e) {
                LOG.warnv("Pipe {0}: failed to delete SQS message {1}: {2}",
                        pipe.getName(), msg.getMessageId(), e.getMessage());
            }
        }
    }

    private void pollKinesis(Pipe pipe, String region) {
        String pipeKey = pipeKey(pipe);
        String streamName = extractResourceName(pipe.getSource());
        String iterator = kinesisIterators.get(pipeKey);
        if (iterator == null) {
            iterator = initKinesisIterator(streamName, region);
            if (iterator == null) return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = kinesisService.getRecords(iterator, DEFAULT_BATCH_SIZE, region);
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
            String eventJson = buildKinesisEvent(records, pipe, region);
            targetInvoker.invoke(pipe, eventJson, region);
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
        String iterator = dynamoDbIterators.get(pipeKey);
        if (iterator == null) {
            iterator = initDynamoDbIterator(streamArn);
            if (iterator == null) return;
        }
        try {
            var result = dynamoDbStreamService.getRecords(iterator, DEFAULT_BATCH_SIZE);
            String nextIterator = result.nextShardIterator();
            if (nextIterator != null) {
                dynamoDbIterators.put(pipeKey, nextIterator);
            }
            var records = result.records();
            if (records == null || records.isEmpty()) {
                return;
            }
            LOG.infov("Pipe {0}: received {1} DynamoDB Stream record(s)", pipe.getName(), records.size());
            String eventJson = buildDynamoDbEvent(records, pipe, region);
            targetInvoker.invoke(pipe, eventJson, region);
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

    // ──────────────────────────── Event Builders ────────────────────────────

    private String buildSqsEvent(List<Message> messages, Pipe pipe) {
        try {
            var records = objectMapper.createArrayNode();
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
                records.add(record);
            }
            ObjectNode root = objectMapper.createObjectNode();
            root.set("Records", records);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"Records\":[]}";
        }
    }

    private String buildKinesisEvent(List<?> records, Pipe pipe, String region) {
        try {
            var recordsArray = objectMapper.createArrayNode();
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
                String data = node.path("Data").asText();
                kinesis.put("data", data);
                recordsArray.add(eventRecord);
            }
            ObjectNode root = objectMapper.createObjectNode();
            root.set("Records", recordsArray);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"Records\":[]}";
        }
    }

    private String buildDynamoDbEvent(List<?> records, Pipe pipe, String region) {
        try {
            var recordsArray = objectMapper.createArrayNode();
            for (Object record : records) {
                ObjectNode node = objectMapper.valueToTree(record);
                node.put("eventSource", "aws:dynamodb");
                node.put("eventSourceARN", pipe.getSource());
                node.put("awsRegion", region);
                recordsArray.add(node);
            }
            ObjectNode root = objectMapper.createObjectNode();
            root.set("Records", recordsArray);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"Records\":[]}";
        }
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
        if (slashIdx >= 0) return arn.substring(slashIdx + 1);
        int colonIdx = arn.lastIndexOf(':');
        return colonIdx >= 0 ? arn.substring(colonIdx + 1) : arn;
    }
}
