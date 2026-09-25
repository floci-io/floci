package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.dynamodb.NativeDynamoDbTableService.CreateTableRequest;
import io.github.hectorvent.floci.services.dynamodb.NativeDynamoDbTableService.GsiThroughputUpdate;
import io.github.hectorvent.floci.services.dynamodb.NativeDynamoDbTableService.OnDemandThroughput;
import io.github.hectorvent.floci.services.dynamodb.NativeDynamoDbTableService.TableSettings;
import io.github.hectorvent.floci.services.dynamodb.NativeDynamoDbTableService.Throughput;
import io.github.hectorvent.floci.services.dynamodb.NativeDynamoDbTableService.UpdateTableRequest;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.GlobalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.KinesisStreamingDestination;
import io.github.hectorvent.floci.services.dynamodb.model.StreamDescription;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Table control on the native engine: validation runs before any side effect, and every setting the
 * service applies after the underlying create or update reaches disk, since a persistent backend
 * serializes on write rather than holding the live table.
 */
class NativeDynamoDbTableServiceTest {

    private static final String TABLE = "native-table";
    private static final String GSI = "byCategory";
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String STREAM_NAME = "orders";
    private static final String STREAM_ARN = "arn:aws:kinesis:us-east-1:000000000000:stream/" + STREAM_NAME;
    private static final TableSettings NO_SETTINGS =
            new TableSettings(null, null, null, null, null, null, null, null, null);
    private static final String BOGUS_SSE_TYPE_MESSAGE = "1 validation error detected: Value 'BOGUS' at "
            + "'sSESpecification.sSEType' failed to satisfy constraint: Member must satisfy enum value set: "
            + "[AES256, KMS]";

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tmp;

    private Path file;
    private DynamoDbStreamService streams;
    private KinesisService kinesisService;
    private KinesisStreamingForwarder forwarder;
    private DynamoDbService dynamoDbService;
    private NativeDynamoDbTableService tableService;

    @BeforeEach
    void setUp() {
        file = tmp.resolve("tables.json");
        StorageBackend<String, TableDefinition> store = diskStore(file);
        streams = new DynamoDbStreamService(mapper, store);
        kinesisService = mock(KinesisService.class);
        forwarder = mock(KinesisStreamingForwarder.class);
        dynamoDbService = new DynamoDbService(store, null, new RegionResolver(REGION, ACCOUNT), streams, forwarder);
        tableService = new NativeDynamoDbTableService(dynamoDbService, streams, kinesisService);
    }

    private StorageBackend<String, TableDefinition> diskStore(Path path) {
        PersistentStorage<String, TableDefinition> store =
                new PersistentStorage<>(path, new TypeReference<Map<String, TableDefinition>>() { });
        store.load();
        return store;
    }

    /** What a restart would read back off disk. */
    private TableDefinition reloadFromDisk() {
        return diskStore(file).get(REGION + "::" + TABLE).orElseThrow();
    }

    private static TableSettings settings(String billingMode, Boolean streamEnabled, String streamViewType,
                                          Boolean sseEnabled, String sseType) {
        return new TableSettings(billingMode, null, null, null, streamEnabled, streamViewType,
                sseEnabled, sseType, null);
    }

    private static CreateTableRequest createRequest(Throughput throughput, TableSettings settings) {
        return new CreateTableRequest(TABLE,
                new ArrayList<>(List.of(new KeySchemaElement("id", "HASH"))),
                new ArrayList<>(List.of(new AttributeDefinition("id", "S"))),
                throughput, List.of(), List.of(), List.of(), Map.of(), settings);
    }

    private static CreateTableRequest createRequestWithGsi(TableSettings settings) {
        GlobalSecondaryIndex gsi = new GlobalSecondaryIndex(GSI,
                new ArrayList<>(List.of(new KeySchemaElement("category", "HASH"))), null, "ALL", new ArrayList<>());
        gsi.getProvisionedThroughput().setReadCapacityUnits(5L);
        gsi.getProvisionedThroughput().setWriteCapacityUnits(5L);
        return new CreateTableRequest(TABLE,
                new ArrayList<>(List.of(new KeySchemaElement("id", "HASH"))),
                new ArrayList<>(List.of(new AttributeDefinition("id", "S"), new AttributeDefinition("category", "S"))),
                new Throughput(5L, 5L), new ArrayList<>(List.of(gsi)), List.of(), List.of(), Map.of(), settings);
    }

    private static UpdateTableRequest updateRequest(List<GsiThroughputUpdate> gsiUpdates,
                                                    List<String> replicaUpdates, TableSettings settings) {
        return new UpdateTableRequest(TABLE, null, List.of(), List.of(), List.of(), gsiUpdates,
                List.of(), List.of(), List.of(), List.of(), replicaUpdates, settings);
    }

    private void assertValidationFailure(Executable call, String message) {
        AwsException ex = assertThrows(AwsException.class, call);
        assertEquals("ValidationException", ex.getErrorCode());
        assertEquals(message, ex.getMessage());
    }

    private void assertNoTableAndNoStream() {
        assertTrue(dynamoDbService.findTable(TABLE, REGION).isEmpty(), "no table may be left behind");
        assertTrue(streams.listStreams(TABLE, REGION).isEmpty(), "no stream may be left behind");
    }

    @Test
    void invalidSseTypeOnCreateLeavesNoTable() {
        CreateTableRequest request = createRequest(null, settings(null, true, "NEW_IMAGE", true, "BOGUS"));

        assertValidationFailure(() -> tableService.createTable(request, "ACTIVE", REGION), BOGUS_SSE_TYPE_MESSAGE);

        assertNoTableAndNoStream();
    }

    @Test
    void payPerRequestWithProvisionedThroughputOnCreateLeavesNoTable() {
        CreateTableRequest request = createRequest(new Throughput(5L, 5L),
                settings("PAY_PER_REQUEST", true, null, null, null));

        assertValidationFailure(() -> tableService.createTable(request, "ACTIVE", REGION),
                "One or more parameter values were invalid: Neither ReadCapacityUnits nor WriteCapacityUnits "
                + "can be specified when BillingMode is PAY_PER_REQUEST");

        assertNoTableAndNoStream();
    }

    @Test
    void disabledStreamWithViewTypeOnCreateLeavesNoTable() {
        CreateTableRequest request = createRequest(null, settings(null, false, "NEW_IMAGE", null, null));

        assertValidationFailure(() -> tableService.createTable(request, "ACTIVE", REGION),
                "One or more parameter values were invalid: Table is being created with a stream "
                + "disabled, UpdateViewType should not be specified");

        assertNoTableAndNoStream();
    }

    @Test
    void createSettingsSurviveARestart() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("env", "test");
        tags.put("team", "storage");
        TableSettings settings = new TableSettings("PAY_PER_REQUEST", true, "STANDARD_INFREQUENT_ACCESS",
                new OnDemandThroughput(100, 200), true, "NEW_IMAGE", true, "KMS", null);
        CreateTableRequest request = new CreateTableRequest(TABLE,
                new ArrayList<>(List.of(new KeySchemaElement("id", "HASH"))),
                new ArrayList<>(List.of(new AttributeDefinition("id", "S"))),
                null, List.of(), List.of(), List.of(), tags, settings);

        TableDefinition created = tableService.createTable(request, "ACTIVE", REGION);

        TableDefinition persisted = reloadFromDisk();
        assertEquals("ACTIVE", persisted.getTableStatus());
        assertEquals("PAY_PER_REQUEST", persisted.getBillingMode());
        assertEquals(0L, persisted.getProvisionedThroughput().getReadCapacityUnits());
        assertEquals(0L, persisted.getProvisionedThroughput().getWriteCapacityUnits());
        assertTrue(persisted.isDeletionProtectionEnabled());
        assertEquals("STANDARD_INFREQUENT_ACCESS", persisted.getTableClass());
        assertEquals(100, persisted.getOnDemandMaxReadRequestUnits());
        assertEquals(200, persisted.getOnDemandMaxWriteRequestUnits());
        assertEquals(tags, persisted.getTags());
        // Tags are applied one put at a time, so ListTagsOfResource and the stored JSON keep that iteration order.
        Map<String, String> sequentiallyPut = new HashMap<>();
        tags.forEach(sequentiallyPut::put);
        assertEquals(new ArrayList<>(sequentiallyPut.keySet()), new ArrayList<>(created.getTags().keySet()));
        assertEquals(new ArrayList<>(sequentiallyPut.keySet()), new ArrayList<>(persisted.getTags().keySet()));
        assertTrue(persisted.isStreamEnabled());
        assertEquals("NEW_IMAGE", persisted.getStreamViewType());
        assertEquals(created.getStreamArn(), persisted.getStreamArn());
        assertTrue(persisted.isSseEnabled());
        assertEquals("KMS", persisted.getSseType());
        assertEquals("arn:aws:kms:us-east-1:000000000000:key/aws-managed-dynamodb",
                persisted.getKmsMasterKeyArn());

        StreamDescription stream = new DynamoDbStreamService(mapper, diskStore(file))
                .listStreams(TABLE, REGION).get(0);
        assertEquals("NEW_IMAGE", stream.getStreamViewType(),
                "a restarted stream must come back with the persisted view type");
    }

    @Test
    void createDefaultsToProvisionedWithoutDeletionProtection() {
        TableDefinition created = tableService.createTable(
                createRequest(new Throughput(3L, 4L), NO_SETTINGS), "ACTIVE", REGION);

        assertEquals("PROVISIONED", created.getBillingMode());
        TableDefinition persisted = reloadFromDisk();
        assertEquals("PROVISIONED", persisted.getBillingMode());
        assertEquals(3L, persisted.getProvisionedThroughput().getReadCapacityUnits());
        assertEquals(4L, persisted.getProvisionedThroughput().getWriteCapacityUnits());
        assertFalse(persisted.isDeletionProtectionEnabled());
        assertFalse(persisted.isStreamEnabled());
        assertFalse(persisted.isSseEnabled());
    }

    @Test
    void importTableRetainsItsCreationState() {
        TableDefinition created = tableService.createTable(createRequest(null, NO_SETTINGS), "CREATING", REGION);

        assertEquals("CREATING", created.getTableStatus());
        assertEquals("CREATING", reloadFromDisk().getTableStatus());
    }

    @Test
    void updateSettingsSurviveARestart() {
        TableDefinition created = tableService.createTable(
                createRequestWithGsi(settings(null, true, "NEW_AND_OLD_IMAGES", true, "KMS")), "ACTIVE", REGION);
        TableSettings update = new TableSettings("PAY_PER_REQUEST", true, "STANDARD_INFREQUENT_ACCESS",
                new OnDemandThroughput(100, 200), true, "NEW_IMAGE", false, null, null);
        List<GsiThroughputUpdate> gsiUpdates = List.of(
                new GsiThroughputUpdate(GSI, new Throughput(7L, null), new OnDemandThroughput(30, null)));

        tableService.updateTable(updateRequest(gsiUpdates, List.of(), update), REGION);

        TableDefinition persisted = reloadFromDisk();
        assertEquals("PAY_PER_REQUEST", persisted.getBillingMode());
        assertEquals(0L, persisted.getProvisionedThroughput().getReadCapacityUnits());
        assertEquals(0L, persisted.getProvisionedThroughput().getWriteCapacityUnits());
        assertTrue(persisted.isDeletionProtectionEnabled());
        assertEquals("STANDARD_INFREQUENT_ACCESS", persisted.getTableClass());
        assertEquals(100, persisted.getOnDemandMaxReadRequestUnits());
        assertEquals(200, persisted.getOnDemandMaxWriteRequestUnits());

        GlobalSecondaryIndex gsi = persisted.findGsi(GSI).orElseThrow();
        assertEquals(7L, gsi.getProvisionedThroughput().getReadCapacityUnits());
        assertEquals(5L, gsi.getProvisionedThroughput().getWriteCapacityUnits(),
                "a member the update leaves out keeps its value");
        assertEquals(30, gsi.getOnDemandMaxReadRequestUnits());
        assertNull(gsi.getOnDemandMaxWriteRequestUnits());

        assertTrue(persisted.isStreamEnabled());
        assertEquals("NEW_IMAGE", persisted.getStreamViewType());
        assertEquals(created.getStreamArn(), persisted.getStreamArn());

        assertFalse(persisted.isSseEnabled());
        assertNull(persisted.getSseType());
        assertNull(persisted.getKmsMasterKeyArn());

        StreamDescription stream = new DynamoDbStreamService(mapper, diskStore(file))
                .listStreams(TABLE, REGION).get(0);
        assertEquals("NEW_IMAGE", stream.getStreamViewType(),
                "a restarted stream must not resume the old image shape");
    }

    @Test
    void updateDisablingTheStreamSurvivesARestart() {
        tableService.createTable(createRequest(null, settings(null, true, "KEYS_ONLY", null, null)),
                "ACTIVE", REGION);

        tableService.updateTable(updateRequest(List.of(), List.of(), settings(null, false, null, null, null)), REGION);

        assertFalse(reloadFromDisk().isStreamEnabled());
        assertEquals("DISABLED", streams.listStreams(TABLE, REGION).get(0).getStreamStatus());
    }

    @Test
    void updateEnablingAes256SseClearsTheKmsKey() {
        tableService.createTable(createRequest(null, settings(null, null, null, true, "KMS")), "ACTIVE", REGION);

        tableService.updateTable(updateRequest(List.of(), List.of(), settings(null, null, null, true, "AES256")),
                REGION);

        TableDefinition persisted = reloadFromDisk();
        assertTrue(persisted.isSseEnabled());
        assertEquals("AES256", persisted.getSseType());
        assertNull(persisted.getKmsMasterKeyArn());
    }

    @Test
    void invalidSseTypeOnUpdateLeavesThePersistedTableUnchanged() {
        tableService.createTable(createRequest(new Throughput(5L, 5L), NO_SETTINGS), "ACTIVE", REGION);
        TableSettings update = new TableSettings("PAY_PER_REQUEST", true, "STANDARD_INFREQUENT_ACCESS",
                null, true, "NEW_IMAGE", true, "BOGUS", null);

        assertValidationFailure(() -> tableService.updateTable(updateRequest(List.of(), List.of(), update), REGION),
                BOGUS_SSE_TYPE_MESSAGE);

        assertUnchangedAfterRejectedUpdate();
    }

    @Test
    void invalidReplicaUpdateLeavesThePersistedTableUnchanged() {
        tableService.createTable(createRequest(new Throughput(5L, 5L), NO_SETTINGS), "ACTIVE", REGION);
        TableSettings update = new TableSettings("PAY_PER_REQUEST", true, "STANDARD_INFREQUENT_ACCESS",
                null, true, "NEW_IMAGE", null, null, null);

        assertValidationFailure(() -> tableService.updateTable(
                updateRequest(List.of(), List.of("eu-west-1"), update), REGION), "Replica eu-west-1 does not exist");

        assertUnchangedAfterRejectedUpdate();
    }

    private void assertUnchangedAfterRejectedUpdate() {
        TableDefinition persisted = reloadFromDisk();
        assertEquals("PROVISIONED", persisted.getBillingMode());
        assertEquals(5L, persisted.getProvisionedThroughput().getReadCapacityUnits());
        assertFalse(persisted.isDeletionProtectionEnabled());
        assertNull(persisted.getTableClass());
        assertFalse(persisted.isStreamEnabled());
        assertTrue(streams.listStreams(TABLE, REGION).isEmpty(), "the rejected update must not enable a stream");
    }

    @Test
    void kinesisDestinationTransitionsPersistAndRepeatedTransitionsAreRejected() {
        TableDefinition created = tableService.createTable(createRequest(null, NO_SETTINGS), "ACTIVE", REGION);

        String resolved = tableService.enableKinesisStreamingDestination(
                created.getTableArn(), STREAM_ARN, KinesisStreamingDestination.PRECISION_MICROSECOND, REGION);

        assertEquals(TABLE, resolved, "an ARN input resolves to the canonical table name");
        verify(kinesisService).describeStream(STREAM_NAME, REGION);
        KinesisStreamingDestination enabled = reloadFromDisk().findKinesisStreamingDestination(STREAM_ARN)
                .orElseThrow();
        assertEquals("ACTIVE", enabled.getDestinationStatus());
        assertEquals(KinesisStreamingDestination.PRECISION_MICROSECOND,
                enabled.getApproximateCreationDateTimePrecision());

        assertValidationFailure(() -> tableService.enableKinesisStreamingDestination(
                TABLE, STREAM_ARN, KinesisStreamingDestination.PRECISION_MILLISECOND, REGION),
                "Table already has an active Kinesis streaming destination with this stream ARN");

        assertEquals(TABLE, tableService.disableKinesisStreamingDestination(TABLE, STREAM_ARN, REGION));
        TableDefinition disabled = reloadFromDisk();
        assertEquals(1, disabled.getKinesisStreamingDestinations().size());
        assertEquals("DISABLED", disabled.findKinesisStreamingDestination(STREAM_ARN).orElseThrow()
                .getDestinationStatus());
        verify(forwarder).onDestinationDisabled(ACCOUNT, REGION, TABLE, STREAM_ARN);

        assertValidationFailure(() -> tableService.disableKinesisStreamingDestination(TABLE, STREAM_ARN, REGION),
                "Kinesis streaming destination is already disabled for stream: " + STREAM_ARN);

        tableService.enableKinesisStreamingDestination(
                TABLE, STREAM_ARN, KinesisStreamingDestination.PRECISION_MILLISECOND, REGION);
        KinesisStreamingDestination reenabled = reloadFromDisk().findKinesisStreamingDestination(STREAM_ARN)
                .orElseThrow();
        assertEquals("ACTIVE", reenabled.getDestinationStatus());
        assertEquals(KinesisStreamingDestination.PRECISION_MILLISECOND,
                reenabled.getApproximateCreationDateTimePrecision());
        assertEquals(1, reloadFromDisk().getKinesisStreamingDestinations().size(),
                "re-enabling reuses the existing destination");
    }

    @Test
    void unknownKinesisStreamIsReportedAsNotFound() {
        tableService.createTable(createRequest(null, NO_SETTINGS), "ACTIVE", REGION);
        String missingArn = "arn:aws:kinesis:us-east-1:000000000000:stream/missing";
        when(kinesisService.describeStream("missing", REGION))
                .thenThrow(new AwsException("ResourceNotFoundException", "Stream missing not found", 400));

        AwsException ex = assertThrows(AwsException.class, () -> tableService.enableKinesisStreamingDestination(
                TABLE, missingArn, KinesisStreamingDestination.PRECISION_MILLISECOND, REGION));

        assertEquals("ResourceNotFoundException", ex.getErrorCode());
        assertEquals("Kinesis stream not found: " + missingArn, ex.getMessage());
        assertTrue(reloadFromDisk().getKinesisStreamingDestinations().isEmpty());
    }

    @Test
    void disablingAnUnknownDestinationIsReportedAsNotFound() {
        tableService.createTable(createRequest(null, NO_SETTINGS), "ACTIVE", REGION);

        AwsException ex = assertThrows(AwsException.class,
                () -> tableService.disableKinesisStreamingDestination(TABLE, STREAM_ARN, REGION));

        assertEquals("ResourceNotFoundException", ex.getErrorCode());
        assertEquals("Kinesis streaming destination not found for stream: " + STREAM_ARN, ex.getMessage());
        verifyNoInteractions(forwarder);
    }
}
