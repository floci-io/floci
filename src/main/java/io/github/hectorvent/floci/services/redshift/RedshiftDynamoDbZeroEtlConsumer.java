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
import io.github.hectorvent.floci.services.dynamodb.DynamoDbStreamService;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbItemAccess.ScanPage;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Scope;
import io.github.hectorvent.floci.services.dynamodb.model.DynamoDbStreamRecord;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@ApplicationScoped
public class RedshiftDynamoDbZeroEtlConsumer {

    private static final Logger LOG = Logger.getLogger(RedshiftDynamoDbZeroEtlConsumer.class);
    private static final int BATCH_SIZE = 100;

    private final Vertx vertx;
    private final DynamoDbStreamService streamService;
    private final DynamoDbFacade dynamoDb;
    private final RedshiftService redshiftService;
    private final RedshiftZeroEtlWriter writer;
    private final ObjectMapper objectMapper;
    private final long pollIntervalMs;
    private final ConcurrentHashMap<String, Long> timerIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> activePolls = new ConcurrentHashMap<>();
    private final ExecutorService pollExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "redshift-zero-etl");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public RedshiftDynamoDbZeroEtlConsumer(Vertx vertx,
                                           DynamoDbStreamService streamService,
                                           DynamoDbFacade dynamoDb,
                                           RedshiftService redshiftService,
                                           RedshiftZeroEtlWriter writer,
                                           ObjectMapper objectMapper,
                                           EmulatorConfig config) {
        this(vertx, streamService, dynamoDb, redshiftService, writer, objectMapper,
                config.services().redshift().pollIntervalMs());
    }

    RedshiftDynamoDbZeroEtlConsumer(DynamoDbStreamService streamService,
                                    DynamoDbFacade dynamoDb,
                                    RedshiftService redshiftService,
                                    RedshiftZeroEtlWriter writer) {
        this(null, streamService, dynamoDb, redshiftService, writer, new ObjectMapper(), 1000);
    }

    private RedshiftDynamoDbZeroEtlConsumer(Vertx vertx,
                                            DynamoDbStreamService streamService,
                                            DynamoDbFacade dynamoDb,
                                            RedshiftService redshiftService,
                                            RedshiftZeroEtlWriter writer,
                                            ObjectMapper objectMapper,
                                            long pollIntervalMs) {
        this.vertx = vertx;
        this.streamService = streamService;
        this.dynamoDb = dynamoDb;
        this.redshiftService = redshiftService;
        this.writer = writer;
        this.objectMapper = objectMapper;
        this.pollIntervalMs = pollIntervalMs;
    }

    void onStart(@Observes StartupEvent event) {
        startPersistedIntegrations();
    }

    public void startPersistedIntegrations() {
        for (Integration integration : redshiftService.listDynamoDbZeroEtlIntegrations()) {
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
    }

    void pollOnce(Integration integration) {
        writer.createLandingTable(integration.getAccountId(), integration.getTargetClusterIdentifier(),
                integration.getLandingTableName());
        if (!integration.isBackfillCompleted()) {
            pollBackfillPage(integration);
            return;
        }
        String iteratorType = integration.getCheckpointSequenceNumber() == null
                ? "TRIM_HORIZON" : "AFTER_SEQUENCE_NUMBER";
        String iterator = streamService.getShardIterator(integration.getSourceStreamArn(),
                DynamoDbStreamService.SHARD_ID, iteratorType, integration.getCheckpointSequenceNumber());
        DynamoDbStreamService.GetRecordsResult result = streamService.getRecords(iterator, BATCH_SIZE);
        if (result.records().isEmpty()) {
            return;
        }
        String sequence = writer.writeBatch(integration.getAccountId(), integration.getTargetClusterIdentifier(),
                integration.getLandingTableName(), result.records());
        redshiftService.updateIntegrationRuntime(integration.getAccountId(), integration.getIntegrationArn(),
                sequence, true, null);
        integration.setCheckpointSequenceNumber(sequence);
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
                List<DynamoDbStreamRecord> records = result.items().stream()
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

    private DynamoDbStreamRecord toBackfillRecord(Integration integration, JsonNode item, TableDefinition table) {
        ObjectNode keys = objectMapper.createObjectNode();
        for (KeySchemaElement keySchemaElement : table.getKeySchema()) {
            String attributeName = keySchemaElement.getAttributeName();
            if (item.has(attributeName)) {
                keys.set(attributeName, item.get(attributeName));
            }
        }
        DynamoDbStreamRecord record = new DynamoDbStreamRecord();
        record.setEventId("backfill#" + integration.getIntegrationArn() + "#" + sha256Hex(keys.toString()));
        record.setEventName("INSERT");
        record.setSequenceNumber("backfill");
        record.setKeys(keys);
        record.setNewImage(item);
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
            if (e instanceof AwsException awsException
                    && "TrimmedDataAccessException".equals(awsException.getErrorCode())) {
                integration.setCheckpointSequenceNumber(null);
            }
            try {
                redshiftService.updateIntegrationRuntime(integration.getAccountId(), integration.getIntegrationArn(),
                        integration.getCheckpointSequenceNumber(), false, e.getMessage());
            } catch (Exception updateError) {
                LOG.warnv(updateError, "Could not persist zero-ETL failure for integration {0}",
                        integration.getIntegrationArn());
            }
        }
    }
}
