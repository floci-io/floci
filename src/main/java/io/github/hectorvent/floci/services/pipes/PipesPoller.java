package io.github.hectorvent.floci.services.pipes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbStreamService;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import io.github.hectorvent.floci.services.pipes.model.DesiredState;
import io.github.hectorvent.floci.services.pipes.model.Pipe;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue;
import io.vertx.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
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
public class PipesPoller implements Resettable {

    private static final Logger LOG = Logger.getLogger(PipesPoller.class);
    private static final long POLL_INTERVAL_MS = 1000;
    private static final int DEFAULT_BATCH_SIZE = 10;

    private final Vertx vertx;
    private final SqsService sqsService;
    private final KinesisService kinesisService;
    private final DynamoDbStreamService dynamoDbStreamService;
    private final PipesKafkaConsumerManager kafkaConsumerManager;
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
                       PipesKafkaConsumerManager kafkaConsumerManager,
                       PipesTargetInvoker targetInvoker,
                       PipesFilterMatcher filterMatcher,
                       ObjectMapper objectMapper,
                       EmulatorConfig config) {
        this.vertx = vertx;
        this.sqsService = sqsService;
        this.kinesisService = kinesisService;
        this.dynamoDbStreamService = dynamoDbStreamService;
        this.kafkaConsumerManager = kafkaConsumerManager;
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

    public void clear() {
        timerIds.values().forEach(vertx::cancelTimer);
        timerIds.clear();
        activePolls.clear();
        kinesisIterators.clear();
        dynamoDbIterators.clear();
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
            kafkaConsumerManager.close(pipe);
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
                } else if (isKafkaSource(sourceArn)) {
                    pollKafka(pipe, region);
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

        if (isLambdaTarget(pipe)) {
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
        } else {
            Map<String, Message> messagesById = new HashMap<>();
            for (Message msg : messages) {
                messagesById.put(msg.getMessageId(), msg);
            }
            for (JsonNode record : filtered) {
                String messageId = record.get("messageId").asText();
                if (invokeWithDlq(pipe, record.toString(), region)) {
                    Message msg = messagesById.get(messageId);
                    if (msg != null) {
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
    }

    private void pollKinesis(Pipe pipe, String region) {
        String pipeKey = pipeKey(pipe);
        String streamName = extractResourceName(pipe.getSource());
        String pipeAccountId = pipe.getAccountId();
        int batchSize = getBatchSize(pipe, "KinesisStreamParameters");
        String iterator = kinesisIterators.get(pipeKey);
        if (iterator == null) {
            iterator = initKinesisIterator(streamName, region, pipeAccountId);
            if (iterator == null) {
                return;
            }
        }
        try {
            Map<String, Object> result = (pipeAccountId != null)
                    ? kinesisService.getRecordsForAccount(pipeAccountId, iterator, batchSize, region)
                    : kinesisService.getRecords(iterator, batchSize, region);
            String nextIterator = (String) result.get("NextShardIterator");
            if (nextIterator != null) {
                kinesisIterators.put(pipeKey, nextIterator);
            }
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
            int failed = deliverRecords(pipe, filtered, region);
            if (failed > 0) {
                LOG.warnv("Pipe {0}: {1} Kinesis record(s) dropped — delivery and DLQ both failed",
                        pipe.getName(), failed);
            }
        } catch (AwsException e) {
            if ("ExpiredIteratorException".equals(e.getErrorCode())) {
                kinesisIterators.remove(pipeKey);
            }
            throw e;
        }
    }

    private String initKinesisIterator(String streamName, String region, String accountId) {
        try {
            return (accountId != null)
                    ? kinesisService.getShardIteratorForAccount(
                            accountId, streamName, "shardId-000000000000", "TRIM_HORIZON", null, region)
                    : kinesisService.getShardIterator(
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
            int failed = deliverRecords(pipe, filtered, region);
            if (failed > 0) {
                LOG.warnv("Pipe {0}: {1} DynamoDB Stream record(s) dropped — delivery and DLQ both failed",
                        pipe.getName(), failed);
            }
        } catch (AwsException e) {
            if ("ExpiredIteratorException".equals(e.getErrorCode()) ||
                "TrimmedDataAccessException".equals(e.getErrorCode())) {
                dynamoDbIterators.remove(pipeKey);
            }
            throw e;
        }
    }

    void pollKafka(Pipe pipe, String region) {
        ConsumerRecords<byte[], byte[]> records = kafkaConsumerManager.poll(pipe);
        if (records.isEmpty()) {
            return;
        }

        List<ConsumerRecord<byte[], byte[]>> batch = new ArrayList<>(records.count());
        records.forEach(batch::add);

        LOG.infov("Pipe {0}: received {1} Kafka record(s)", pipe.getName(), batch.size());
        List<ObjectNode> deliveryNodes = buildKafkaRecordNodes(batch, pipe);
        List<ObjectNode> filterNodes = buildKafkaFilterNodes(deliveryNodes, batch);
        List<JsonNode> filtered = filterMatcher.applyFilterCriteria(new ArrayList<>(filterNodes), pipe.getSourceParameters());
        if (filtered.isEmpty()) {
            kafkaConsumerManager.commit(pipe);
            return;
        }

        Map<String, JsonNode> deliveryRecordsByIdentity = new HashMap<>();
        for (JsonNode record : deliveryNodes) {
            deliveryRecordsByIdentity.put(kafkaRecordIdentity(record), record);
        }

        List<JsonNode> deliveryRecords = new ArrayList<>(filtered.size());
        for (JsonNode record : filtered) {
            JsonNode deliveryRecord = deliveryRecordsByIdentity.get(kafkaRecordIdentity(record));
            if (deliveryRecord != null) {
                deliveryRecords.add(deliveryRecord);
            }
        }
        if (deliveryRecords.isEmpty()) {
            kafkaConsumerManager.commit(pipe);
            return;
        }

        int failed = isLambdaTarget(pipe)
                ? deliverKafkaLambdaRecords(pipe, region, batch, deliveryRecordsByIdentity, filtered)
                : deliverKafkaRecords(pipe, region, batch, deliveryRecordsByIdentity, filtered);
        if (failed == 0) {
            return;
        }

        LOG.warnv("Pipe {0}: {1} Kafka record(s) not committed because delivery failed",
                pipe.getName(), failed);
    }

    private int deliverKafkaLambdaRecords(Pipe pipe,
                                          String region,
                                          List<ConsumerRecord<byte[], byte[]>> batch,
                                          Map<String, JsonNode> deliveryRecordsByIdentity,
                                          List<JsonNode> filtered) {
        Set<String> matchedIdentities = new HashSet<>(filtered.size());
        filtered.forEach(record -> matchedIdentities.add(kafkaRecordIdentity(record)));

        Map<TopicPartition, OffsetAndMetadata> offsetsToCommit = new HashMap<>();
        Map<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> recordsByPartition = groupKafkaRecordsByPartition(batch);
        int failed = 0;

        for (Map.Entry<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> entry : recordsByPartition.entrySet()) {
            failed += deliverKafkaLambdaPartition(pipe, region, entry.getKey(), entry.getValue(),
                    deliveryRecordsByIdentity, matchedIdentities, offsetsToCommit);
        }

        if (!offsetsToCommit.isEmpty()) {
            kafkaConsumerManager.commit(pipe, offsetsToCommit);
        }
        return failed;
    }

    private int deliverKafkaLambdaPartition(Pipe pipe,
                                            String region,
                                            TopicPartition partition,
                                            List<ConsumerRecord<byte[], byte[]>> partitionRecords,
                                            Map<String, JsonNode> deliveryRecordsByIdentity,
                                            Set<String> matchedIdentities,
                                            Map<TopicPartition, OffsetAndMetadata> offsetsToCommit) {
        List<JsonNode> pendingBatch = new ArrayList<>();
        long pendingOffset = -1L;

        for (ConsumerRecord<byte[], byte[]> record : partitionRecords) {
            String identity = kafkaRecordIdentity(record);
            if (!matchedIdentities.contains(identity)) {
                if (!pendingBatch.isEmpty()) {
                    if (!invokeWithDlq(pipe, wrapRecords(pendingBatch), region)) {
                        return pendingBatch.size();
                    }
                    offsetsToCommit.put(partition, new OffsetAndMetadata(pendingOffset));
                    pendingBatch.clear();
                }
                offsetsToCommit.put(partition, new OffsetAndMetadata(record.offset() + 1));
                continue;
            }

            JsonNode deliveryRecord = deliveryRecordsByIdentity.get(identity);
            if (deliveryRecord != null) {
                pendingBatch.add(deliveryRecord);
                pendingOffset = record.offset() + 1;
            }
        }

        if (!pendingBatch.isEmpty()) {
            if (!invokeWithDlq(pipe, wrapRecords(pendingBatch), region)) {
                return pendingBatch.size();
            }
            offsetsToCommit.put(partition, new OffsetAndMetadata(pendingOffset));
        }
        return 0;
    }

    private int deliverKafkaRecords(Pipe pipe,
                                    String region,
                                    List<ConsumerRecord<byte[], byte[]>> batch,
                                    Map<String, JsonNode> deliveryRecordsByIdentity,
                                    List<JsonNode> filtered) {
        Set<String> matchedIdentities = new HashSet<>(filtered.size());
        filtered.forEach(record -> matchedIdentities.add(kafkaRecordIdentity(record)));

        Map<TopicPartition, OffsetAndMetadata> offsetsToCommit = new HashMap<>();
        Set<TopicPartition> blockedPartitions = new HashSet<>();
        int failed = 0;

        for (ConsumerRecord<byte[], byte[]> record : batch) {
            TopicPartition partition = new TopicPartition(record.topic(), record.partition());
            if (blockedPartitions.contains(partition)) {
                continue;
            }

            String identity = kafkaRecordIdentity(record);
            if (!matchedIdentities.contains(identity)) {
                offsetsToCommit.put(partition, new OffsetAndMetadata(record.offset() + 1));
                continue;
            }

            JsonNode deliveryRecord = deliveryRecordsByIdentity.get(identity);
            if (deliveryRecord != null && invokeWithDlq(pipe, deliveryRecord.toString(), region)) {
                offsetsToCommit.put(partition, new OffsetAndMetadata(record.offset() + 1));
                continue;
            }

            blockedPartitions.add(partition);
            failed++;
        }

        if (!offsetsToCommit.isEmpty()) {
            kafkaConsumerManager.commit(pipe, offsetsToCommit);
        }
        return failed;
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

    private int deliverRecords(Pipe pipe, List<JsonNode> records, String region) {
        if (isLambdaTarget(pipe)) {
            return invokeWithDlq(pipe, wrapRecords(records), region) ? 0 : records.size();
        }
        int failed = 0;
        for (JsonNode record : records) {
            if (!invokeWithDlq(pipe, record.toString(), region)) {
                failed++;
            }
        }
        return failed;
    }

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
            sqsService.sendMessage(queueUrl, payload, 0, region);
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
            attrs.put("SentTimestamp", String.valueOf(msg.getSentTimestamp().toEpochMilli()));
            attrs.put("SenderId", AwsArnUtils.accountOrDefault(pipe.getSource(), "000000000000"));
            attrs.put("ApproximateFirstReceiveTimestamp",
                    String.valueOf(msg.getFirstReceiveTimestamp() != null
                            ? msg.getFirstReceiveTimestamp().toEpochMilli()
                            : System.currentTimeMillis()));
            ObjectNode msgAttrs = record.putObject("messageAttributes");
            for (Map.Entry<String, MessageAttributeValue> entry : msg.getMessageAttributes().entrySet()) {
                ObjectNode attrNode = msgAttrs.putObject(entry.getKey());
                MessageAttributeValue val = entry.getValue();
                if (val.getBinaryValue() != null) {
                    attrNode.put("binaryValue", Base64.getEncoder().encodeToString(val.getBinaryValue()));
                } else if (val.getStringValue() != null) {
                    attrNode.put("stringValue", val.getStringValue());
                }
                attrNode.putArray("stringListValues");
                attrNode.putArray("binaryListValues");
                attrNode.put("dataType", val.getDataType());
            }
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

    private List<ObjectNode> buildKafkaRecordNodes(List<ConsumerRecord<byte[], byte[]>> records, Pipe pipe) {
        List<ObjectNode> nodes = new ArrayList<>();
        String eventSource = pipe.getSource().contains(":kafka:") ? "aws:kafka" : "SelfManagedKafka";
        String bootstrapServers = kafkaConsumerManager.resolveBootstrapServers(pipe);
        for (ConsumerRecord<byte[], byte[]> record : records) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("eventSource", eventSource);
            if (pipe.getSource().contains(":kafka:")) {
                node.put("eventSourceArn", pipe.getSource());
            } else {
                node.put("bootstrapServers", bootstrapServers);
            }
            node.put("topic", record.topic());
            node.put("partition", record.partition());
            node.put("offset", record.offset());
            node.put("timestamp", record.timestamp());
            node.put("timestampType", record.timestampType().name());
            putKafkaBinaryField(node, "key", record.key());
            putKafkaBinaryField(node, "value", record.value());

            var headersNode = node.putArray("headers");
            for (Header header : record.headers()) {
                ObjectNode headerNode = objectMapper.createObjectNode();
                byte[] headerValue = header.value();
                if (headerValue == null) {
                    headerNode.putNull(header.key());
                } else {
                    var values = headerNode.putArray(header.key());
                    for (byte b : headerValue) {
                        values.add(b & 0xFF);
                    }
                }
                headersNode.add(headerNode);
            }
            nodes.add(node);
        }
        return nodes;
    }

    private List<ObjectNode> buildKafkaFilterNodes(List<ObjectNode> deliveryNodes,
                                                   List<ConsumerRecord<byte[], byte[]>> records) {
        List<ObjectNode> nodes = new ArrayList<>(deliveryNodes.size());
        for (int i = 0; i < deliveryNodes.size(); i++) {
            ObjectNode node = deliveryNodes.get(i).deepCopy();
            ConsumerRecord<byte[], byte[]> record = records.get(i);
            applyDecodedKafkaField(node, "key", record.key());
            applyDecodedKafkaField(node, "value", record.value());
            nodes.add(node);
        }
        return nodes;
    }

    // ──────────────────────────── Utilities ────────────────────────────

    private static boolean isLambdaTarget(Pipe pipe) {
        String targetArn = pipe.getTarget();
        return targetArn.contains(":lambda:") || targetArn.contains(":function:");
    }

    private static boolean isKafkaSource(String sourceArn) {
        return sourceArn.startsWith("smk://") || sourceArn.contains(":kafka:");
    }

    private static String base64(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    private static String kafkaRecordIdentity(JsonNode record) {
        return record.path("topic").asText() + ":"
                + record.path("partition").asText() + ":"
                + record.path("offset").asText();
    }

    private static String kafkaRecordIdentity(ConsumerRecord<byte[], byte[]> record) {
        return record.topic() + ":" + record.partition() + ":" + record.offset();
    }

    private void applyDecodedKafkaField(ObjectNode node, String fieldName, byte[] value) {
        if (value == null) {
            node.putNull(fieldName);
            return;
        }
        String decoded = new String(value, StandardCharsets.UTF_8);
        try {
            JsonNode parsed = objectMapper.readTree(decoded);
            node.set(fieldName, parsed);
        } catch (JsonProcessingException e) {
            LOG.debugv("Kafka {0} is not valid JSON: {1}", fieldName, e.getOriginalMessage());
            node.put(fieldName, decoded);
        }
    }

    private void putKafkaBinaryField(ObjectNode node, String fieldName, byte[] value) {
        if (value == null) {
            node.putNull(fieldName);
            return;
        }
        node.put(fieldName, base64(value));
    }

    private Map<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> groupKafkaRecordsByPartition(
            List<ConsumerRecord<byte[], byte[]>> records) {
        Map<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> recordsByPartition = new HashMap<>();
        for (ConsumerRecord<byte[], byte[]> record : records) {
            TopicPartition partition = new TopicPartition(record.topic(), record.partition());
            recordsByPartition.computeIfAbsent(partition, ignored -> new ArrayList<>()).add(record);
        }
        return recordsByPartition;
    }

    private static String pipeKey(Pipe pipe) {
        return pipe.getArn();
    }

    private static String extractRegionFromArn(String arn) {
        return AwsArnUtils.regionOrDefault(arn, "us-east-1");
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
