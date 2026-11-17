package io.floci.aws.services.redshift;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Digests;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.services.dynamodb.DynamoDbFacade;
import io.floci.aws.services.dynamodb.backend.DynamoDbItemAccess.ScanPage;
import io.floci.aws.services.dynamodb.backend.DynamoDbOperations.Scope;
import io.floci.aws.services.dynamodb.backend.DynamoDbStreamReader;
import io.floci.aws.services.dynamodb.model.KeySchemaElement;
import io.floci.aws.services.dynamodb.model.TableDefinition;
import io.floci.aws.services.redshift.model.Integration;
import io.floci.aws.services.redshiftdata.RedshiftZeroEtlCurrentState;
import io.floci.aws.services.redshiftdata.RedshiftZeroEtlCurrentState.Change;
import io.floci.aws.services.redshiftdata.RedshiftZeroEtlWriter;
import io.vertx.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

@ApplicationScoped
public class RedshiftDynamoDbZeroEtlConsumer implements Resettable {

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
    /** Per integration, the current-state table its source projects into, created on the target once resolved. */
    private final ConcurrentHashMap<String, RedshiftZeroEtlCurrentState> currentStates = new ConcurrentHashMap<>();
    /**
     * The integrations whose current-state table could not be written. Their landing log carries on regardless;
     * each poll tries to make the table again, and rebuilds it from a scan once that works.
     */
    private final Set<String> stalledProjections = ConcurrentHashMap.newKeySet();
    private final ExecutorService pollExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "redshift-zero-etl");
        thread.setDaemon(true);
        return thread;
    });
    /** Polls hold the read lock; a reset takes the write lock to wait them out. */
    private final ReentrantReadWriteLock quiesceLock = new ReentrantReadWriteLock();
    private volatile boolean quiesced;
    /** Completed resets: a poll writes only while none is in progress and none completed since its submission. */
    private volatile long resets;

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
            if (integration.isBackfillCompleted() && !integration.isCurrentStateProjected()) {
                // An integration whose backfill ran before the current-state table existed scans its source
                // again to fill it; the landing log keeps each event once, so the rerun adds no rows there.
                integration.setBackfillCompleted(false);
                integration.setBackfillLastEvaluatedKey(null);
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
        timerIds.put(integration.getIntegrationArn(),
                vertx.setPeriodic(pollIntervalMs, timerId -> pollAnd(integration, timerId)));
    }

    public void stopPolling(String integrationArn) {
        Long timerId = timerIds.remove(integrationArn);
        if (timerId != null) {
            vertx.cancelTimer(timerId);
        }
        activePolls.remove(integrationArn);
        finishedShards.remove(integrationArn);
        currentStates.remove(integrationArn);
        stalledProjections.remove(integrationArn);
    }

    void pollOnce(Integration integration) {
        unlessQuiesced(resets, () -> poll(integration));
    }

    private void poll(Integration integration) {
        if (targetPaused(integration)) {
            return;
        }
        writer.createLandingTable(integration.getAccountId(), integration.getTargetArn(),
                integration.getLandingTableName());
        if (stalledProjections.contains(integration.getIntegrationArn())) {
            restoreProjection(integration);
        }
        if (!integration.isBackfillCompleted()) {
            pollBackfillPage(integration);
            return;
        }
        String streamArn = followedStream(integration);
        if (streamArn == null) {
            // A table that never had a stream has nothing to replicate after its backfill.
            redshiftService.recoverIntegrationRuntime(integration.getAccountId(), integration.getIntegrationArn());
            return;
        }
        DynamoDbStreamReader.Stream stream = DynamoDbStreamReader.Stream.of(streamArn);
        List<DynamoDbStreamReader.Shard> shards;
        try {
            shards = streamReader.shards(stream);
        } catch (AwsException e) {
            // A stream the table has replaced and that has since expired has nothing left to read.
            if (!"ResourceNotFoundException".equals(e.getErrorCode()) || !moveToLatestStream(integration)) {
                throw e;
            }
            return;
        }
        Set<String> finished = finishedShards.computeIfAbsent(integration.getIntegrationArn(),
                ignored -> ConcurrentHashMap.newKeySet());
        // ponytail: a shard whose read or write throws ends the tick for the shards after it; the next
        // tick starts over from committed progress, so it only delays them.
        boolean checkpointPersisted = false;
        for (DynamoDbStreamReader.Shard shard : DynamoDbStreamReader.readable(shards, finished)) {
            if (pollShard(integration, stream, shard.shardId(), finished)) {
                checkpointPersisted = true;
            }
        }
        if (!checkpointPersisted) {
            // Every record of the followed stream has landed, so a newer stream of the table takes over.
            moveToLatestStream(integration);
            redshiftService.recoverIntegrationRuntime(integration.getAccountId(), integration.getIntegrationArn());
        }
    }

    /**
     * The stream to read changes from. A stream source is that stream. A table source follows the
     * stream it already reads, or the table's latest stream when it reads none yet.
     */
    private String followedStream(Integration integration) {
        if (integration.getSourceStreamArn() == null) {
            moveToLatestStream(integration);
        }
        return integration.getSourceStreamArn();
    }

    /**
     * Moves a table source to the table's latest stream when that is another stream: none exists until
     * streams are first turned on, and a new one each time they are turned on again. Callers move only
     * once the followed stream has been read to its end or has expired, since a disabled stream stays
     * readable and its last records must still land. A table whose streams are off reports no latest
     * stream, so the followed one is kept. Shard ids repeat across a table's streams, so the move drops
     * the checkpoints of the old stream; the new one is read from its trim horizon.
     *
     * @return whether the integration moved to another stream
     */
    private boolean moveToLatestStream(Integration integration) {
        String sourceArn = integration.getSourceArn();
        if (sourceArn == null || RedshiftZeroEtlEndpoints.isStreamArn(sourceArn)) {
            return false;
        }
        String region = AwsArnUtils.parse(sourceArn).region();
        String latest = RequestScopes.callAs(integration.getAccountId(), region, () -> {
            TableDefinition table = dynamoDb.tables().describeTable(new Scope(integration.getAccountId(), region),
                    RedshiftZeroEtlEndpoints.tableName(sourceArn));
            return table.getStreamArn();
        });
        if (latest == null || latest.equals(integration.getSourceStreamArn())) {
            return false;
        }
        integration.getShardSequenceNumbers().clear();
        finishedShards.remove(integration.getIntegrationArn());
        // A new stream may belong to a table made again with other keys, so its projection is resolved afresh.
        currentStates.remove(integration.getIntegrationArn());
        integration.setSourceStreamArn(latest);
        redshiftService.updateIntegrationSourceStream(integration.getAccountId(), integration.getIntegrationArn(),
                latest);
        return true;
    }

    private boolean targetPaused(Integration integration) {
        if (integration.getTargetClusterIdentifier() == null) {
            // Only a provisioned cluster pauses.
            return false;
        }
        try {
            return redshiftService.describeClustersForAccount(integration.getAccountId(),
                            integration.getTargetClusterIdentifier()).stream()
                    .anyMatch(cluster -> "paused".equals(cluster.getClusterStatus()));
        } catch (AwsException expected) {
            if (!"ClusterNotFound".equals(expected.getErrorCode())) {
                throw expected;
            }
            // A missing target is a polling failure, not an intentional pause; let the writer report it.
            return false;
        }
    }

    /**
     * Writes one batch after the shard's committed sequence and commits it only once written, so a
     * failed write is read and written again next poll.
     */
    private boolean pollShard(Integration integration, DynamoDbStreamReader.Stream stream, String shardId,
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
            return false;
        }
        List<JsonNode> awsRecords = records.stream().map(DynamoDbStreamReader.Record::awsRecord).toList();
        writer.writeBatch(integration.getAccountId(), integration.getTargetArn(),
                integration.getLandingTableName(), awsRecords);
        projectSafely(integration, null, () -> readThrough(integration, awsRecords));
        committed.put(shardId, records.get(records.size() - 1).sequenceNumber());
        redshiftService.updateIntegrationRuntime(integration.getAccountId(), integration.getIntegrationArn(),
                committed, true, null);
        return true;
    }

    private void pollBackfillPage(Integration integration) {
        RequestScopes.runAs(integration.getAccountId(), () -> {
            String sourceArn = integration.getSourceArn() != null
                    ? integration.getSourceArn() : integration.getSourceStreamArn();
            String tableName = RedshiftZeroEtlEndpoints.tableName(sourceArn);
            String region = AwsArnUtils.parse(sourceArn).region();
            Scope scope = new Scope(integration.getAccountId(), region);
            TableDefinition table = dynamoDb.tables().describeTable(scope, tableName);
            JsonNode exclusiveStartKey = parseBackfillKey(integration.getBackfillLastEvaluatedKey());
            // The current-state table exists from the first backfill page on, as AWS creates it before the
            // initial export lands, so an empty source still shows as an empty table. Its first page empties it:
            // the scan only upserts, so a row an earlier run left would otherwise outlive its item. A table that
            // cannot be made stalls the projection alone; the scan still lands.
            RedshiftZeroEtlCurrentState currentState = null;
            if (!stalledProjections.contains(integration.getIntegrationArn())) {
                try {
                    currentState = currentState(integration, table);
                    if (exclusiveStartKey == null) {
                        writer.resetCurrentStateTable(integration.getAccountId(), integration.getTargetArn(),
                                currentState);
                    }
                } catch (RuntimeException e) {
                    stallProjection(integration, e);
                    currentState = null;
                }
            }

            ScanPage result = dynamoDb.items().scan(scope, tableName, null, null, null, null,
                    BATCH_SIZE, exclusiveStartKey);
            if (!result.items().isEmpty()) {
                List<JsonNode> records = result.items().stream()
                        .map(item -> toBackfillRecord(integration, item, table))
                        .toList();
                writer.writeBatch(integration.getAccountId(), integration.getTargetArn(),
                        integration.getLandingTableName(), records);
                if (currentState != null) {
                    projectSafely(integration, currentState, () -> scannedItems(records));
                }
            }

            boolean completed = result.lastEvaluatedKey() == null;
            String nextKey = completed ? null : writeAsString(result.lastEvaluatedKey());
            redshiftService.updateIntegrationBackfillProgress(integration.getAccountId(), integration.getIntegrationArn(),
                    nextKey, completed);
            integration.setBackfillLastEvaluatedKey(nextKey);
            integration.setBackfillCompleted(completed);
            if (completed && !stalledProjections.contains(integration.getIntegrationArn())) {
                integration.setCurrentStateProjected(true);
                redshiftService.updateIntegrationCurrentState(integration.getAccountId(),
                        integration.getIntegrationArn(), true);
            }
        });
    }

    /**
     * Applies the records to the current-state table without ever failing the poll: the records have landed, and a
     * projection that cannot be written must not hold the landing log back. A failure stalls the projection, and
     * {@link #restoreProjection} rebuilds the table from a scan once it can be written again.
     */
    private void projectSafely(Integration integration, RedshiftZeroEtlCurrentState currentState,
                               Supplier<List<Change>> changes) {
        if (stalledProjections.contains(integration.getIntegrationArn())) {
            return;
        }
        try {
            project(integration, currentState, changes.get());
        } catch (RuntimeException e) {
            stallProjection(integration, e);
        }
    }

    private void stallProjection(Integration integration, RuntimeException cause) {
        String integrationArn = integration.getIntegrationArn();
        currentStates.remove(integrationArn);
        if (stalledProjections.add(integrationArn)) {
            LOG.warnv(cause, "The current-state table of zero-ETL integration {0} cannot be written; the landing "
                    + "log carries on and the table is rebuilt once it can be", integrationArn);
        }
        integration.setCurrentStateProjected(false);
        redshiftService.updateIntegrationCurrentState(integration.getAccountId(), integrationArn, false);
    }

    /**
     * Tries to make the stalled integration's table again, the one thing that can have been wrong being the table:
     * dropped, a user's own of that name, or the target unreachable. Changes landed since the stall never reached
     * it, so once it is made the backfill starts over to rebuild it.
     */
    private void restoreProjection(Integration integration) {
        String integrationArn = integration.getIntegrationArn();
        try {
            TableDefinition table = describeSource(integration);
            currentStates.remove(integrationArn);
            currentState(integration, table);
        } catch (RuntimeException e) {
            LOG.debugv(e, "The current-state table of zero-ETL integration {0} still cannot be written",
                    integrationArn);
            return;
        }
        stalledProjections.remove(integrationArn);
        // Making the table afresh has already started the backfill over; otherwise it is past some pages, or done.
        if (integration.isBackfillCompleted() || integration.getBackfillLastEvaluatedKey() != null) {
            resyncProjection(integration);
        }
    }

    /** Starts the backfill over so that the scan fills a current-state table that is missing items. */
    private void resyncProjection(Integration integration) {
        integration.setBackfillCompleted(false);
        integration.setBackfillLastEvaluatedKey(null);
        integration.setCurrentStateProjected(false);
        redshiftService.updateIntegrationBackfillProgress(integration.getAccountId(), integration.getIntegrationArn(),
                null, false);
        redshiftService.updateIntegrationCurrentState(integration.getAccountId(), integration.getIntegrationArn(),
                false);
    }

    private TableDefinition describeSource(Integration integration) {
        String sourceArn = integration.getSourceArn() != null
                ? integration.getSourceArn() : integration.getSourceStreamArn();
        String tableName = RedshiftZeroEtlEndpoints.tableName(sourceArn);
        String region = AwsArnUtils.parse(sourceArn).region();
        return RequestScopes.callAs(integration.getAccountId(), region,
                () -> dynamoDb.tables().describeTable(new Scope(integration.getAccountId(), region), tableName));
    }

    /**
     * The changes the stream records make: each key a record names, projected as the source holds it now, once
     * however many records name it. A record's own image is not used, so replaying a record after the backfill
     * scan read a newer version of its item, or after the item changed again, cannot put the older image over the
     * newer one: every record of a key projects the same state, and the table follows the source as the stream
     * drains. The scan reads each item it projects itself, so only the stream pays this lookup.
     */
    private List<Change> readThrough(Integration integration, List<JsonNode> records) {
        String sourceArn = integration.getSourceArn() != null
                ? integration.getSourceArn() : integration.getSourceStreamArn();
        String tableName = RedshiftZeroEtlEndpoints.tableName(sourceArn);
        String region = AwsArnUtils.parse(sourceArn).region();
        Scope scope = new Scope(integration.getAccountId(), region);
        Map<String, Change> changes = new LinkedHashMap<>();
        for (JsonNode record : records) {
            JsonNode keys = record.path("dynamodb").path("Keys");
            if (!keys.isObject() || changes.containsKey(keys.toString())) {
                continue;
            }
            Change change = RedshiftZeroEtlCurrentState.changeOf(record, lookedUp -> RequestScopes.callAs(
                    integration.getAccountId(), region, () -> dynamoDb.items().getItem(scope, tableName, lookedUp)));
            if (change != null) {
                changes.put(keys.toString(), change);
            }
        }
        return new ArrayList<>(changes.values());
    }

    /** The changes a backfill page makes: each item the scan read, upserted under its keys. */
    private static List<Change> scannedItems(List<JsonNode> backfillRecords) {
        List<Change> changes = new ArrayList<>();
        for (JsonNode record : backfillRecords) {
            JsonNode dynamodb = record.path("dynamodb");
            changes.add(new Change(dynamodb.get("Keys"), dynamodb.get("NewImage")));
        }
        return changes;
    }

    /**
     * Applies the changes to the current-state table, after the records they come from have landed. The projection
     * is idempotent, so an item projected twice, once by the backfill scan and once from the stream, or a batch
     * applied again after a stalled poll, leaves the same state.
     *
     * @param currentState the table resolved by the caller, or null to resolve it from the source table
     */
    private void project(Integration integration, RedshiftZeroEtlCurrentState currentState, List<Change> changes) {
        if (changes.isEmpty()) {
            return;
        }
        RedshiftZeroEtlCurrentState state = currentState != null
                ? currentState : currentStates.get(integration.getIntegrationArn());
        if (state == null) {
            state = currentState(integration, describeSource(integration));
        }
        writer.applyChanges(integration.getAccountId(), integration.getTargetArn(), state, changes);
    }

    /**
     * The integration's current-state table, made its own on the target the first time it is resolved. A table made
     * afresh over a running integration (dropped by a user, or the source made again with other keys) holds none of
     * the source's items, so the backfill starts over to fill it.
     */
    private RedshiftZeroEtlCurrentState currentState(Integration integration, TableDefinition table) {
        boolean[] fresh = new boolean[1];
        RedshiftZeroEtlCurrentState state = currentStates.computeIfAbsent(integration.getIntegrationArn(), ignored -> {
            RedshiftZeroEtlCurrentState created = RedshiftZeroEtlCurrentState.of(table);
            fresh[0] = writer.createCurrentStateTable(integration.getAccountId(), integration.getTargetArn(), created,
                    integrationId(integration));
            return created;
        });
        if (fresh[0] && integration.isBackfillCompleted()) {
            resyncProjection(integration);
        }
        return state;
    }

    private static String integrationId(Integration integration) {
        String arn = integration.getIntegrationArn();
        return arn.substring(arn.lastIndexOf(':') + 1);
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
                .put("eventID", "backfill#" + integration.getIntegrationArn() + "#" + Digests.sha256Hex(keys.toString()))
                .put("eventName", "INSERT");
        ObjectNode dynamodb = record.putObject("dynamodb").put("SequenceNumber", "backfill");
        dynamodb.set("Keys", keys);
        dynamodb.set("NewImage", item);
        return record;
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

    /**
     * Waits for the poll in progress, then keeps every poll from writing until {@link #afterReset()},
     * and a poll submitted before that from writing at all.
     */
    @Override
    public void beforeReset() {
        quiesceLock.writeLock().lock();
        try {
            quiesced = true;
        } finally {
            quiesceLock.writeLock().unlock();
        }
    }

    @Override
    public void clear() {
        for (String integrationArn : timerIds.keySet()) {
            stopPolling(integrationArn);
        }
        activePolls.clear();
        finishedShards.clear();
        currentStates.clear();
        stalledProjections.clear();
    }

    /** Lets the polls submitted from now on write again. */
    @Override
    public void afterReset() {
        quiesceLock.writeLock().lock();
        try {
            resets++;
            quiesced = false;
        } finally {
            quiesceLock.writeLock().unlock();
        }
    }

    /**
     * Stops every integration for good, waiting up to five seconds for a poll in progress so the storage
     * flush that follows holds its checkpoint. Idempotent, so it may run again from {@code @PreDestroy}.
     */
    @PreDestroy
    public void shutdown() {
        clear();
        pollExecutor.shutdown();
        try {
            if (!pollExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                pollExecutor.shutdownNow();
                LOG.warnv("A zero-ETL poll was still running at shutdown, so its last checkpoint may not be flushed");
            }
        } catch (InterruptedException e) {
            pollExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** Runs the section unless a reset is in progress or one completed since {@code submittedAt} was read. */
    private void unlessQuiesced(long submittedAt, Runnable section) {
        quiesceLock.readLock().lock();
        try {
            if (!quiesced && resets == submittedAt) {
                section.run();
            }
        } finally {
            quiesceLock.readLock().unlock();
        }
    }

    /**
     * Submits the tick's poll only while its timer is still registered, building the task first: {@code clear()}
     * removes the timer before {@code afterReset()} counts the reset, both on the reset thread, so a task that
     * read the new count sees its timer gone and one that read the old count is rejected by {@code unlessQuiesced}.
     */
    private void pollAnd(Integration integration, long timerId) {
        Runnable task = pollTask(integration);
        String integrationArn = integration.getIntegrationArn();
        if (!Long.valueOf(timerId).equals(timerIds.get(integrationArn))) {
            return;
        }
        if (activePolls.putIfAbsent(integrationArn, Boolean.TRUE) != null) {
            return;
        }
        pollExecutor.submit(task);
    }

    /** The poll {@code pollAnd} submits, bound to the resets completed at its submission. */
    Runnable pollTask(Integration integration) {
        long submittedAt = resets;
        return () -> {
            try {
                pollSafely(integration, submittedAt);
            } finally {
                activePolls.remove(integration.getIntegrationArn());
            }
        };
    }

    private void pollSafely(Integration integration, long submittedAt) {
        try {
            unlessQuiesced(submittedAt, () -> poll(integration));
        } catch (Exception e) {
            try {
                unlessQuiesced(submittedAt, () -> {
                    if (!targetPaused(integration)) {
                        LOG.warnv(e, "Zero-ETL polling failed for integration {0}", integration.getIntegrationArn());
                        redshiftService.updateIntegrationRuntime(integration.getAccountId(), integration.getIntegrationArn(),
                                integration.getShardSequenceNumbers(), false, e.getMessage());
                    }
                });
            } catch (Exception updateError) {
                LOG.warnv(updateError, "Could not persist zero-ETL failure for integration {0}",
                        integration.getIntegrationArn());
            }
        }
    }
}
