package io.github.hectorvent.floci.services.redshift;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbFacade;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbItemAccess.ScanPage;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Scope;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbStreamReader;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import io.github.hectorvent.floci.services.redshift.model.Integration;
import io.github.hectorvent.floci.services.redshiftdata.RedshiftZeroEtlWriter;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@ApplicationScoped
public class RedshiftDynamoDbZeroEtlConsumer {

    private static final Logger LOG = Logger.getLogger(RedshiftDynamoDbZeroEtlConsumer.class);
    private static final int BATCH_SIZE = 100;

    private final Vertx vertx;
    private final DynamoDbStreamReader streamReader;
    private final DynamoDbFacade dynamoDb;
    private final RedshiftService redshiftService;
    private final RedshiftZeroEtlWriter writer;
    private final ObjectMapper objectMapper;
    private final long pollIntervalMs;
    private final ConcurrentHashMap<String, Long> timerIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> activePolls = new ConcurrentHashMap<>();
    /** Per integration, the DynamoDB Stream shards read to their end. */
    private final ConcurrentHashMap<String, Set<String>> finishedShards = new ConcurrentHashMap<>();
    private final ExecutorService pollExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "redshift-zero-etl");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public RedshiftDynamoDbZeroEtlConsumer(Vertx vertx,
                                           DynamoDbStreamReader streamReader,
                                           DynamoDbFacade dynamoDb,
                                           RedshiftService redshiftService,
                                           RedshiftZeroEtlWriter writer,
                                           ObjectMapper objectMapper,
                                           EmulatorConfig config) {
        this(vertx, streamReader, dynamoDb, redshiftService, writer, objectMapper,
                config.services().redshift().pollIntervalMs());
    }

    RedshiftDynamoDbZeroEtlConsumer(DynamoDbStreamReader streamReader,
                                    DynamoDbFacade dynamoDb,
                                    RedshiftService redshiftService,
                                    RedshiftZeroEtlWriter writer) {
        this(null, streamReader, dynamoDb, redshiftService, writer, new ObjectMapper(), 1000);
    }

    private RedshiftDynamoDbZeroEtlConsumer(Vertx vertx,
                                            DynamoDbStreamReader streamReader,
                                            DynamoDbFacade dynamoDb,
                                            RedshiftService redshiftService,
                                            RedshiftZeroEtlWriter writer,
                                            ObjectMapper objectMapper,
                                            long pollIntervalMs) {
        this.vertx = vertx;
        this.streamReader = streamReader;
        this.dynamoDb = dynamoDb;
        this.redshiftService = redshiftService;
        this.writer = writer;
        this.objectMapper = objectMapper;
        this.pollIntervalMs = pollIntervalMs;
    }

    void onStart(@Observes StartupEvent event) {
        startPersistedIntegrations();
    }

    /**
     * Starts the persisted integrations, first discarding their stream progress when it lasts only
     * for the process: native stream history is volatile, so a sequence number saved by a previous
     * run would skip the records of the new stream epoch.
     */
    public void startPersistedIntegrations() {
        for (Integration integration : redshiftService.listDynamoDbZeroEtlIntegrations()) {
            if (streamReader.checkpointLifetime() == DynamoDbStreamReader.CheckpointLifetime.PROCESS) {
                integration.getShardSequenceNumbers().clear();
            }
            if (integration.isPollingEnabled()) {
                startPolling(integration);
            }
        }
    }

    public void startPolling(Integration integration) {
        if (vertx == null || timerIds.containsKey(integration.getIntegrationArn())) {
            return;
        }
        long timerId = vertx.setPeriodic(pollIntervalMs, ignored -> pollAnd(integration));
        timerIds.put(integration.getIntegrationArn(), timerId);
    }

    public void stopPolling(String integrationArn) {
        Long timerId = timerIds.remove(integrationArn);
        if (timerId != null) {
            vertx.cancelTimer(timerId);
        }
        activePolls.remove(integrationArn);
        finishedShards.remove(integrationArn);
    }

    void pollOnce(Integration integration) {
        writer.createLandingTable(integration.getAccountId(), integration.getTargetClusterIdentifier(),
                integration.getLandingTableName());
        if (!integration.isBackfillCompleted()) {
            pollBackfillPage(integration);
            return;
        }
        DynamoDbStreamReader.Stream stream = DynamoDbStreamReader.Stream.of(integration.getSourceStreamArn());
        Set<String> finished = finishedShards.computeIfAbsent(integration.getIntegrationArn(),
                ignored -> ConcurrentHashMap.newKeySet());
        // ponytail: a shard whose read or write throws ends the tick for the shards after it; the next
        // tick starts over from committed progress, so it only delays them.
        for (DynamoDbStreamReader.Shard shard : DynamoDbStreamReader.readable(streamReader.shards(stream), finished)) {
            pollShard(integration, stream, shard.shardId(), finished);
        }
    }

    /**
     * Writes one batch after the shard's committed sequence and commits it only once written, so a
     * failed write is read and written again next poll.
     */
    private void pollShard(Integration integration, DynamoDbStreamReader.Stream stream, String shardId,
                           Set<String> finished) {
        Map<String, String> committed = integration.getShardSequenceNumbers();
        DynamoDbStreamReader.RecordsPage page;
        try {
            page = streamReader.readAfter(stream, shardId, committed.get(shardId), BATCH_SIZE);
        } catch (AwsException e) {
            if ("TrimmedDataAccessException".equals(e.getErrorCode())) {
                committed.remove(shardId);
            }
            throw e;
        }
        List<DynamoDbStreamReader.Record> records = page.records();
        if (records.isEmpty()) {
            if (page.closed()) {
                finished.add(shardId);
            }
            return;
        }
        writer.writeBatch(integration.getAccountId(), integration.getTargetClusterIdentifier(),
                integration.getLandingTableName(),
                records.stream().map(DynamoDbStreamReader.Record::awsRecord).toList());
        committed.put(shardId, records.get(records.size() - 1).sequenceNumber());
        redshiftService.updateIntegrationRuntime(integration.getAccountId(), integration.getIntegrationArn(),
                committed, true, null);
    }

    private void pollBackfillPage(Integration integration) {
        RequestScopes.runAs(integration.getAccountId(), () -> {
            String tableName = extractTableName(integration.getSourceStreamArn());
            String region = AwsArnUtils.parse(integration.getSourceStreamArn()).region();
            Scope scope = new Scope(integration.getAccountId(), region);
            TableDefinition table = dynamoDb.tables().describeTable(scope, tableName);
            JsonNode exclusiveStartKey = parseBackfillKey(integration.getBackfillLastEvaluatedKey());

            ScanPage result = dynamoDb.items().scan(scope, tableName, null, null, null, null,
                    BATCH_SIZE, exclusiveStartKey);
            if (!result.items().isEmpty()) {
                List<JsonNode> records = result.items().stream()
                        .map(item -> toBackfillRecord(integration, item, table))
                        .toList();
                writer.writeBatch(integration.getAccountId(), integration.getTargetClusterIdentifier(),
                        integration.getLandingTableName(), records);
            }

            boolean completed = result.lastEvaluatedKey() == null;
            String nextKey = completed ? null : writeAsString(result.lastEvaluatedKey());
            redshiftService.updateIntegrationBackfillProgress(integration.getAccountId(), integration.getIntegrationArn(),
                    nextKey, completed);
            integration.setBackfillLastEvaluatedKey(nextKey);
            integration.setBackfillCompleted(completed);
        });
    }

    /** The item as an AWS stream {@code INSERT} record, with an event id stable across scans. */
    private JsonNode toBackfillRecord(Integration integration, JsonNode item, TableDefinition table) {
        ObjectNode keys = objectMapper.createObjectNode();
        for (KeySchemaElement keySchemaElement : table.getKeySchema()) {
            String attributeName = keySchemaElement.getAttributeName();
            if (item.has(attributeName)) {
                keys.set(attributeName, item.get(attributeName));
            }
        }
        ObjectNode record = objectMapper.createObjectNode()
                .put("eventID", "backfill#" + integration.getIntegrationArn() + "#" + sha256Hex(keys.toString()))
                .put("eventName", "INSERT");
        ObjectNode dynamodb = record.putObject("dynamodb").put("SequenceNumber", "backfill");
        dynamodb.set("Keys", keys);
        dynamodb.set("NewImage", item);
        return record;
    }

    private static String extractTableName(String sourceStreamArn) {
        String resource = AwsArnUtils.parse(sourceStreamArn).resource();
        return resource.substring("table/".length(), resource.indexOf("/stream/"));
    }

    private JsonNode parseBackfillKey(String backfillLastEvaluatedKey) {
        if (backfillLastEvaluatedKey == null) {
            return null;
        }
        try {
            return objectMapper.readTree(backfillLastEvaluatedKey);
        } catch (JsonProcessingException e) {
            throw new AwsException("InternalFailure",
                    "Could not parse a persisted zero-ETL backfill checkpoint.", 500);
        }
    }

    private String writeAsString(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new AwsException("InternalFailure",
                    "Could not persist a zero-ETL backfill checkpoint.", 500);
        }
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public void reset() {
        for (String integrationArn : timerIds.keySet()) {
            stopPolling(integrationArn);
        }
        activePolls.clear();
        finishedShards.clear();
    }

    @PreDestroy
    void onStop() {
        reset();
        pollExecutor.shutdownNow();
    }

    private void pollAnd(Integration integration) {
        String integrationArn = integration.getIntegrationArn();
        if (activePolls.putIfAbsent(integrationArn, Boolean.TRUE) != null) {
            return;
        }
        pollExecutor.submit(() -> {
            try {
                pollSafely(integration);
            } finally {
                activePolls.remove(integrationArn);
            }
        });
    }

    private void pollSafely(Integration integration) {
        try {
            pollOnce(integration);
        } catch (Exception e) {
            LOG.warnv(e, "Zero-ETL polling failed for integration {0}", integration.getIntegrationArn());
            try {
                redshiftService.updateIntegrationRuntime(integration.getAccountId(), integration.getIntegrationArn(),
                        integration.getShardSequenceNumbers(), false, e.getMessage());
            } catch (Exception updateError) {
                LOG.warnv(updateError, "Could not persist zero-ETL failure for integration {0}",
                        integration.getIntegrationArn());
            }
        }
    }
}
