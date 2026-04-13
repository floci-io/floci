package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbStreamService;
import io.github.hectorvent.floci.services.dynamodb.model.DynamoDbStreamRecord;
import io.github.hectorvent.floci.services.lambda.model.EventSourceMapping;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Vertx;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@ApplicationScoped
public class DynamoDbStreamsEventSourcePoller {

    private static final Logger LOG = Logger.getLogger(DynamoDbStreamsEventSourcePoller.class);

    private final Vertx vertx;
    private final DynamoDbStreamService streamService;
    private final LambdaExecutorService executorService;
    private final LambdaFunctionStore functionStore;
    private final EsmStore esmStore;
    private final ObjectMapper objectMapper;
    private final long pollIntervalMs;
    private final ConcurrentHashMap<String, Long> timerIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> activePolls = new ConcurrentHashMap<>();
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
                                            EmulatorConfig config) {
        this.vertx = vertx;
        this.streamService = streamService;
        this.executorService = executorService;
        this.functionStore = functionStore;
        this.esmStore = esmStore;
        this.objectMapper = objectMapper;
        this.pollIntervalMs = config.services().lambda().pollIntervalMs();
    }

    @PostConstruct
    void init() {
        for (EventSourceMapping esm : esmStore.list()) {
            if (esm.isEnabled() && esm.getEventSourceArn().contains(":dynamodb:")) {
                startPolling(esm);
            }
        }
        LOG.infov("DynamoDbStreamsEventSourcePoller initialized");
    }

    @PreDestroy
    void shutdown() {
        pollExecutor.shutdownNow();
        timerIds.values().forEach(vertx::cancelTimer);
        timerIds.clear();
    }

    public void startPolling(EventSourceMapping esm) {
        if (timerIds.containsKey(esm.getUuid())) {
            return;
        }
        String uuid = esm.getUuid();
        long timerId = vertx.setPeriodic(pollIntervalMs, id ->
                esmStore.get(uuid).ifPresent(latest -> {
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
    }

    private void pollAndInvoke(EventSourceMapping esm) {
        if (activePolls.putIfAbsent(esm.getUuid(), Boolean.TRUE) != null) {
            return;
        }
        pollExecutor.submit(() -> {
            try {
                LambdaFunction fn = functionStore.get(esm.getRegion(), esm.getFunctionName()).orElse(null);
                if (fn == null) {
                    LOG.warnv("DynamoDB Streams ESM {0}: function {1} not found, skipping",
                            esm.getUuid(), esm.getFunctionName());
                    return;
                }

                String streamArn = esm.getEventSourceArn();
                String shardId = DynamoDbStreamService.SHARD_ID;
                String lastSeq = esm.getShardSequenceNumbers().get(shardId);

                String iterator = lastSeq == null
                        ? streamService.getShardIterator(streamArn, shardId, "TRIM_HORIZON", null)
                        : streamService.getShardIterator(streamArn, shardId, "AFTER_SEQUENCE_NUMBER", lastSeq);

                DynamoDbStreamService.GetRecordsResult result = streamService.getRecords(iterator, esm.getBatchSize());
                List<DynamoDbStreamRecord> records = result.records();

                if (records.isEmpty()) {
                    return;
                }

                LOG.infov("DynamoDB Streams ESM {0}: received {1} record(s), invoking {2}",
                        esm.getUuid(), records.size(), esm.getFunctionName());

                String eventJson = buildDynamoDbEvent(records, esm);
                io.github.hectorvent.floci.services.lambda.model.InvokeResult invokeResult;
                try {
                    invokeResult = executorService.invoke(fn, eventJson.getBytes(), InvocationType.RequestResponse);
                } catch (io.github.hectorvent.floci.core.common.AwsException e) {
                    if ("TooManyRequestsException".equals(e.getErrorCode())) {
                        LOG.infov("DynamoDB Streams ESM {0}: function {1} throttled, shard iterator not advanced",
                                esm.getUuid(), fn.getFunctionName());
                        return;
                    }
                    throw e;
                }

                if (invokeResult.getFunctionError() == null) {
                    String newestSeq = records.get(records.size() - 1).getSequenceNumber();
                    esm.getShardSequenceNumbers().put(shardId, newestSeq);
                    esmStore.save(esm);
                } else {
                    LOG.warnv("DynamoDB Streams ESM {0}: Lambda returned error [{1}], records will be retried",
                            esm.getUuid(), invokeResult.getFunctionError());
                }
            } catch (Exception e) {
                LOG.warnv("DynamoDB Streams ESM {0} poll error: {1}", esm.getUuid(), e.getMessage());
            } finally {
                activePolls.remove(esm.getUuid());
            }
        });
    }

    private String buildDynamoDbEvent(List<DynamoDbStreamRecord> records, EventSourceMapping esm) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            ArrayNode array = root.putArray("Records");
            for (DynamoDbStreamRecord rec : records) {
                ObjectNode item = array.addObject();
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
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize DynamoDB Streams event", e);
        }
    }
}
