package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.GlobalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.KinesisStreamingDestination;
import io.github.hectorvent.floci.services.dynamodb.model.LocalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.StreamDescription;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Table control operations on the native DynamoDB engine: CreateTable, UpdateTable and the Kinesis
 * streaming destination toggles.
 *
 * <p>Owns the sequence each operation runs against the backend: the validation that must fail
 * before any side effect, the underlying create or update, the settings applied to the stored
 * table afterwards, stream enable/disable, and the final flush. The JSON handler parses the wire
 * request into these records and formats the response. It also keeps UpdateTable's
 * PAY_PER_REQUEST with ProvisionedThroughput check and the GSI update index checks, which must
 * precede request parsing errors, and the Kinesis precision check.
 */
@ApplicationScoped
public class NativeDynamoDbTableService {

    private static final String DEFAULT_ACCOUNT_ID = "000000000000";
    private static final String SSE_TYPE_KMS = "KMS";
    private static final Set<String> VALID_SSE_TYPES = Set.of("AES256", "KMS");

    // A null field or throughput record means "not specified in the request". Collections and the
    // settings record must not be null.
    record Throughput(Long readCapacityUnits, Long writeCapacityUnits) {}

    record OnDemandThroughput(Integer maxReadRequestUnits, Integer maxWriteRequestUnits) {}

    record GsiThroughputUpdate(String indexName, Throughput provisionedThroughput,
                               OnDemandThroughput onDemandThroughput) {}

    record TableSettings(String billingMode, Boolean deletionProtectionEnabled, String tableClass,
                         OnDemandThroughput onDemandThroughput,
                         Boolean streamEnabled, String streamViewType,
                         Boolean sseEnabled, String sseType, String kmsMasterKeyId) {}

    record CreateTableRequest(String tableName, List<KeySchemaElement> keySchema,
                              List<AttributeDefinition> attributeDefinitions, Throughput provisionedThroughput,
                              List<GlobalSecondaryIndex> globalSecondaryIndexes,
                              List<LocalSecondaryIndex> localSecondaryIndexes,
                              List<DynamoDbService.VectorIndexCreate> vectorIndexes,
                              Map<String, String> tags, TableSettings settings) {}

    record UpdateTableRequest(String tableName, Throughput provisionedThroughput,
                              List<AttributeDefinition> attributeDefinitions,
                              List<GlobalSecondaryIndex> gsiCreates, List<String> gsiDeletes,
                              List<GsiThroughputUpdate> gsiUpdates,
                              List<DynamoDbService.VectorIndexCreate> vectorCreates, List<String> vectorDeletes,
                              List<String> replicaAdds, List<String> replicaRemoves, List<String> replicaUpdates,
                              TableSettings settings) {}

    private final DynamoDbService dynamoDbService;
    private final DynamoDbStreamService streamService;
    private final KinesisService kinesisService;

    @Inject
    public NativeDynamoDbTableService(DynamoDbService dynamoDbService, DynamoDbStreamService streamService,
                                      KinesisService kinesisService) {
        this.dynamoDbService = dynamoDbService;
        this.streamService = streamService;
        this.kinesisService = kinesisService;
    }

    /**
     * Creates the table, applies the request's settings to it and persists the result.
     *
     * @param initialStatus the status the new table reports: ACTIVE for CreateTable, CREATING for
     *                      a table an ImportTable job is still loading
     */
    TableDefinition createTable(CreateTableRequest request, String initialStatus, String region) {
        String tableName = request.tableName();
        TableSettings settings = request.settings();
        String billingMode = settings.billingMode();

        // Validated before createTable below: that call and the stream-enable that follows it
        // both have side effects, so an invalid SSEType must fail before either runs rather than
        // after, or ValidationException would leave a real table (and stream) behind.
        boolean sseEnabled = Boolean.TRUE.equals(settings.sseEnabled());
        String sseType = sseEnabled ? effectiveSseType(settings) : null;
        if (sseEnabled) {
            validateSseType(sseType);
        }

        Throughput throughput = request.provisionedThroughput();
        if ("PAY_PER_REQUEST".equals(billingMode) && throughput != null) {
            throw new AwsException("ValidationException",
                    "One or more parameter values were invalid: Neither ReadCapacityUnits nor WriteCapacityUnits "
                    + "can be specified when BillingMode is PAY_PER_REQUEST", 400);
        }

        if (Boolean.FALSE.equals(settings.streamEnabled()) && settings.streamViewType() != null) {
            throw new AwsException("ValidationException",
                    "One or more parameter values were invalid: Table is being created with a stream "
                    + "disabled, UpdateViewType should not be specified", 400);
        }

        TableDefinition table = dynamoDbService.createTable(tableName, request.keySchema(),
                request.attributeDefinitions(),
                throughput != null ? throughput.readCapacityUnits() : null,
                throughput != null ? throughput.writeCapacityUnits() : null,
                request.globalSecondaryIndexes(), request.localSecondaryIndexes(), request.vectorIndexes(),
                billingMode, region);
        table.setTableStatus(initialStatus);

        table.setDeletionProtectionEnabled(Boolean.TRUE.equals(settings.deletionProtectionEnabled()));

        if ("PAY_PER_REQUEST".equals(billingMode)) {
            table.setBillingMode("PAY_PER_REQUEST");
            table.getProvisionedThroughput().setReadCapacityUnits(0L);
            table.getProvisionedThroughput().setWriteCapacityUnits(0L);
        } else {
            table.setBillingMode("PROVISIONED");
        }

        if (settings.tableClass() != null) {
            table.setTableClass(settings.tableClass());
        }

        applyOnDemandThroughput(table, settings.onDemandThroughput());

        request.tags().forEach(table.getTags()::put);

        if (Boolean.TRUE.equals(settings.streamEnabled())) {
            enableStream(table, settings.streamViewType(), region);
        }

        if (sseEnabled) {
            table.setSseEnabled(true);
            table.setSseType(sseType);
            if (SSE_TYPE_KMS.equals(sseType)) {
                table.setKmsMasterKeyArn(kmsMasterKeyArn(settings.kmsMasterKeyId(), region));
            }
        }

        // Everything above mutates the table AFTER createTable stored it, and a persistent
        // backend serializes on write rather than holding the live object. Flush once so the
        // stream, billing, class, tag and SSE settings survive a restart: stream state
        // especially, since startup rebuilds streams from the persisted table.
        dynamoDbService.persistTable(tableName, table, region);
        return table;
    }

    /**
     * Updates the table, applies the request's settings and replica changes to it and persists
     * the result.
     *
     * <p>Wire-shape checks, the PAY_PER_REQUEST throughput check and the GSI update index checks
     * stay with the caller, which runs them while it parses so their error precedence holds.
     */
    TableDefinition updateTable(UpdateTableRequest request, String region) {
        String tableName = request.tableName();
        TableSettings settings = request.settings();
        List<String> addRegions = request.replicaAdds();
        List<String> removeRegions = request.replicaRemoves();
        List<String> updateRegions = request.replicaUpdates();
        boolean replicaUpdates = !addRegions.isEmpty() || !removeRegions.isEmpty() || !updateRegions.isEmpty();

        if (replicaUpdates) {
            dynamoDbService.validateReplicaUpdates(
                    tableName, addRegions, removeRegions, updateRegions, region);
        }

        // Validated up front, same as the replica check above: updateTable and the mutations that
        // follow it have side effects (persisted throughput/GSI changes, stream enable/disable),
        // so an invalid SSEType must fail before any of them run rather than after, or
        // ValidationException would leave a partially-applied update behind.
        boolean sseEnabled = Boolean.TRUE.equals(settings.sseEnabled());
        String sseType = sseEnabled ? effectiveSseType(settings) : null;
        if (sseEnabled) {
            validateSseType(sseType);
        }

        Throughput throughput = request.provisionedThroughput();
        TableDefinition table = dynamoDbService.updateTable(tableName,
                throughput != null ? throughput.readCapacityUnits() : null,
                throughput != null ? throughput.writeCapacityUnits() : null,
                request.gsiCreates(), request.gsiDeletes(), request.attributeDefinitions(),
                request.vectorCreates(), request.vectorDeletes(),
                settings.billingMode(), region);

        for (GsiThroughputUpdate update : request.gsiUpdates()) {
            GlobalSecondaryIndex gsi = table.findGsi(update.indexName()).orElseThrow();
            Throughput gsiThroughput = update.provisionedThroughput();
            if (gsiThroughput != null) {
                if (gsiThroughput.readCapacityUnits() != null) {
                    gsi.getProvisionedThroughput().setReadCapacityUnits(gsiThroughput.readCapacityUnits());
                }
                if (gsiThroughput.writeCapacityUnits() != null) {
                    gsi.getProvisionedThroughput().setWriteCapacityUnits(gsiThroughput.writeCapacityUnits());
                }
            }
            OnDemandThroughput gsiOnDemand = update.onDemandThroughput();
            if (gsiOnDemand != null) {
                if (gsiOnDemand.maxReadRequestUnits() != null) {
                    gsi.setOnDemandMaxReadRequestUnits(gsiOnDemand.maxReadRequestUnits());
                }
                if (gsiOnDemand.maxWriteRequestUnits() != null) {
                    gsi.setOnDemandMaxWriteRequestUnits(gsiOnDemand.maxWriteRequestUnits());
                }
            }
        }

        if (settings.deletionProtectionEnabled() != null) {
            table.setDeletionProtectionEnabled(settings.deletionProtectionEnabled());
        }

        String billingMode = settings.billingMode();
        if (billingMode != null) {
            table.setBillingMode(billingMode);
            if ("PAY_PER_REQUEST".equals(billingMode)) {
                table.getProvisionedThroughput().setReadCapacityUnits(0L);
                table.getProvisionedThroughput().setWriteCapacityUnits(0L);
            }
        }

        if (settings.tableClass() != null) {
            table.setTableClass(settings.tableClass());
        }

        applyOnDemandThroughput(table, settings.onDemandThroughput());

        if (settings.streamEnabled() != null) {
            if (settings.streamEnabled()) {
                enableStream(table, settings.streamViewType(), region);
            } else {
                streamService.disableStream(table.getTableName(), region);
                table.setStreamEnabled(false);
            }
        }

        if (settings.sseEnabled() != null) {
            if (sseEnabled) {
                table.setSseEnabled(true);
                table.setSseType(sseType);
                if (SSE_TYPE_KMS.equals(sseType)) {
                    table.setKmsMasterKeyArn(kmsMasterKeyArn(settings.kmsMasterKeyId(), region));
                } else {
                    table.setKmsMasterKeyArn(null);
                }
            } else {
                table.setSseEnabled(false);
                table.setSseType(null);
                table.setKmsMasterKeyArn(null);
            }
        }

        // Same flush as CreateTable: the stream and SSE mutations above land after updateTable's
        // write, so without this a retargeted view type or SSE change is rebuilt from the stale
        // persisted value on restart. Must run before applyReplicaUpdates below, which re-fetches
        // the table from storage for its own persist. A persistent backend serializes on write
        // rather than holding the live object, so without this flush first, that re-fetch would
        // see neither the stream nor the SSE mutations above.
        dynamoDbService.persistTable(tableName, table, region);

        if (replicaUpdates) {
            table = dynamoDbService.applyReplicaUpdates(tableName, addRegions, removeRegions, updateRegions, region);
        }
        return table;
    }

    /**
     * Turns on forwarding to a Kinesis stream, reusing the table's destination for that stream
     * when one exists.
     *
     * @param precision an already validated ApproximateCreationDateTimePrecision
     * @return the resolved (canonical) table name
     */
    String enableKinesisStreamingDestination(String tableName, String streamArn, String precision, String region) {
        TableDefinition table = dynamoDbService.describeTable(tableName, region);
        String resolvedTableName = table.getTableName();

        String streamName = streamArn.substring(streamArn.lastIndexOf('/') + 1);
        try {
            kinesisService.describeStream(streamName, region);
        } catch (AwsException e) {
            throw new AwsException("ResourceNotFoundException",
                    "Kinesis stream not found: " + streamArn, 400);
        }

        Optional<KinesisStreamingDestination> existing = table.findKinesisStreamingDestination(streamArn);
        if (existing.isPresent() && "ACTIVE".equals(existing.get().getDestinationStatus())) {
            throw new AwsException("ValidationException",
                    "Table already has an active Kinesis streaming destination with this stream ARN", 400);
        }

        if (existing.isPresent()) {
            existing.get().setDestinationStatus("ACTIVE");
            existing.get().setDestinationStatusDescription("Kinesis streaming is enabled for this table");
            existing.get().setApproximateCreationDateTimePrecision(precision);
        } else {
            table.getKinesisStreamingDestinations().add(new KinesisStreamingDestination(streamArn, precision));
        }

        // DynamoDB Streams is left as the caller configured it: Kinesis forwarding does not depend on it, and
        // turning it on here showed up as stream_enabled drift on aws_dynamodb_table that never converged.
        dynamoDbService.persistTable(resolvedTableName, table, region);
        return resolvedTableName;
    }

    /**
     * Turns off forwarding to a Kinesis stream and stops the forwarder draining it.
     *
     * @return the resolved (canonical) table name
     */
    String disableKinesisStreamingDestination(String tableName, String streamArn, String region) {
        TableDefinition table = dynamoDbService.describeTable(tableName, region);
        String resolvedTableName = table.getTableName();

        Optional<KinesisStreamingDestination> existing = table.findKinesisStreamingDestination(streamArn);
        if (existing.isEmpty()) {
            throw new AwsException("ResourceNotFoundException",
                    "Kinesis streaming destination not found for stream: " + streamArn, 400);
        }

        if ("DISABLED".equals(existing.get().getDestinationStatus())) {
            throw new AwsException("ValidationException",
                    "Kinesis streaming destination is already disabled for stream: " + streamArn, 400);
        }

        existing.get().setDestinationStatus("DISABLED");
        existing.get().setDestinationStatusDescription("Kinesis streaming is disabled for this table");
        dynamoDbService.persistTable(resolvedTableName, table, region);
        // Stop forwarding and discard buffered CDC records for this now-disabled destination.
        dynamoDbService.onKinesisStreamingDestinationDisabled(resolvedTableName, streamArn, region);
        return resolvedTableName;
    }

    /** The AWS managed key a table reports when SSE is KMS and no customer key was given. */
    static String defaultKmsMasterKeyArn(String region) {
        return AwsArnUtils.Arn.of("kms", region, DEFAULT_ACCOUNT_ID,
                "key/aws-managed-dynamodb").toString();
    }

    private void enableStream(TableDefinition table, String streamViewType, String region) {
        String viewType = streamViewType != null ? streamViewType : DynamoDbService.DEFAULT_STREAM_VIEW_TYPE;
        StreamDescription sd = streamService.enableStream(table.getTableName(), table.getTableArn(), viewType, region);
        table.setStreamEnabled(true);
        table.setStreamArn(sd.getStreamArn());
        table.setStreamViewType(viewType);
    }

    private static void applyOnDemandThroughput(TableDefinition table, OnDemandThroughput onDemand) {
        if (onDemand == null) {
            return;
        }
        if (onDemand.maxReadRequestUnits() != null) {
            table.setOnDemandMaxReadRequestUnits(onDemand.maxReadRequestUnits());
        }
        if (onDemand.maxWriteRequestUnits() != null) {
            table.setOnDemandMaxWriteRequestUnits(onDemand.maxWriteRequestUnits());
        }
    }

    private static String kmsMasterKeyArn(String kmsMasterKeyId, String region) {
        return kmsMasterKeyId != null ? kmsMasterKeyId : defaultKmsMasterKeyArn(region);
    }

    private static void validateSseType(String sseType) {
        if (!VALID_SSE_TYPES.contains(sseType)) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + sseType
                    + "' at 'sSESpecification.sSEType' failed to satisfy constraint: "
                    + "Member must satisfy enum value set: [AES256, KMS]", 400);
        }
    }

    /** SSEType is optional when SSE is enabled; AWS then uses KMS. */
    private static String effectiveSseType(TableSettings settings) {
        return settings.sseType() != null ? settings.sseType() : SSE_TYPE_KMS;
    }
}
