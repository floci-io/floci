package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import io.github.hectorvent.floci.services.kinesis.model.KinesisRecord;
import io.github.hectorvent.floci.services.kinesis.model.KinesisShard;
import io.github.hectorvent.floci.services.kinesis.model.KinesisStream;
import io.github.hectorvent.floci.services.lambda.model.EventSourceMapping;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.vertx.core.Vertx;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@ApplicationScoped
public class KinesisEventSourcePoller {

    private static final Logger LOG = Logger.getLogger(KinesisEventSourcePoller.class);

    private final Vertx vertx;
    private final KinesisService kinesisService;
    private final LambdaExecutorService executorService;
    private final LambdaFunctionStore functionStore;
    private final EsmStore esmStore;
    private final long pollIntervalMs;
    private final ObjectMapper objectMapper;
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
                                     LambdaFunctionStore functionStore,
                                     EsmStore esmStore, EmulatorConfig config,
                                     ObjectMapper objectMapper) {
        this.vertx = vertx;
        this.kinesisService = kinesisService;
        this.executorService = executorService;
        this.functionStore = functionStore;
        this.esmStore = esmStore;
        this.pollIntervalMs = config.services().lambda().pollIntervalMs();
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        List<EventSourceMapping> esms = esmStore.list();
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

    public void startPolling(EventSourceMapping esm) {
        if (timerIds.containsKey(esm.getUuid())) return;
        String uuid = esm.getUuid();
        long timerId = vertx.setPeriodic(pollIntervalMs, id -> {
            esmStore.get(uuid).ifPresent(latest -> {
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

    private void pollAndInvoke(EventSourceMapping esm) {
        if (activePolls.putIfAbsent(esm.getUuid(), Boolean.TRUE) != null) return;
        pollExecutor.submit(() -> {
            try {
                LambdaFunction fn = functionStore.get(esm.getRegion(), esm.getFunctionName()).orElse(null);
                if (fn == null) return;

                String streamName = streamNameFromArn(esm.getEventSourceArn());
                KinesisStream stream = kinesisService.describeStream(streamName, esm.getRegion());

                for (KinesisShard shard : stream.getShards()) {
                    String lastSeq = esm.getShardSequenceNumbers().get(shard.getShardId());
                    String iterator;
                    if (lastSeq == null) {
                        iterator = kinesisService.getShardIterator(streamName, shard.getShardId(), "TRIM_HORIZON", null, esm.getRegion());
                    } else {
                        iterator = kinesisService.getShardIterator(streamName, shard.getShardId(), "AFTER_SEQUENCE_NUMBER", lastSeq, esm.getRegion());
                    }

                    Map<String, Object> result = kinesisService.getRecords(iterator, esm.getBatchSize(), esm.getRegion());
                    List<KinesisRecord> records = (List<KinesisRecord>) result.get("Records");

                    if (!records.isEmpty()) {
                        String eventJson = buildKinesisEvent(records, esm, shard.getShardId());
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
                            String newestSeq = records.get(records.size() - 1).getSequenceNumber();
                            esm.getShardSequenceNumbers().put(shard.getShardId(), newestSeq);
                            esmStore.save(esm);
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
                record.put("invokeIdentityArn", "arn:aws:iam::000000000000:role/lambda-role");
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

    private static String streamNameFromArn(String arn) {
        return arn.substring(arn.lastIndexOf("/") + 1);
    }
}
