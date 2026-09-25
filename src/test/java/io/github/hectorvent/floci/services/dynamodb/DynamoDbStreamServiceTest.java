package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.StreamDescription;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DynamoDbStreamServiceTest {

    private static final String TABLE_ARN =
            "arn:aws:dynamodb:us-east-1:000000000000:table/ViewTypeTable";
    private static final String SHARED_TABLE = "Shared";
    private static final String ACCOUNT_A = "111111111111";
    private static final String ACCOUNT_B = "222222222222";

    private DynamoDbStreamService service;
    private ObjectMapper mapper;
    private StorageBackend<String, TableDefinition> storage;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        storage =  new InMemoryStorage<>();
        TableDefinition table = createTestTableWithStream();
        storage.put("us-east-1::" + table.getTableName(), table);
        service = new DynamoDbStreamService(mapper, storage);
    }

    private TableDefinition createTestTableWithStream() {
        var tableDef = new TableDefinition("TestTable",
                        List.of(new KeySchemaElement("userId", "HASH")),
                List.of(new AttributeDefinition("userId", "S")),
                "us-east-1", "000000000000");
        tableDef.setStreamEnabled(true);
        tableDef.setStreamArn("arn:aws:dynamodb:us-west-2:000000000000:table/TestTable/stream/2026-04-08T15:24:10.801");
        return tableDef;
    }

    /**
     * Re-enabling a live stream with a different view type must retarget it. Records are built
     * from the description's view type, so a stale one keeps emitting the old shape while the
     * table reports the requested one — CloudFormation can request this on an UpdateStack.
     */
    @Test
    void reEnablingALiveStreamRetargetsItsViewType() {
        service.enableStream("ViewTypeTable", TABLE_ARN, "NEW_AND_OLD_IMAGES", "us-east-1");

        StreamDescription sd =
                service.enableStream("ViewTypeTable", TABLE_ARN, "KEYS_ONLY", "us-east-1");

        assertEquals("KEYS_ONLY", sd.getStreamViewType());
        assertEquals("ENABLED", sd.getStreamStatus());
    }

    /** The retarget must reach the records themselves, not just the reported configuration. */
    @Test
    void recordsEmittedAfterARetargetUseTheNewViewType() throws Exception {
        var table = new TableDefinition("ViewTypeTable",
                List.of(new KeySchemaElement("userId", "HASH")),
                List.of(new AttributeDefinition("userId", "S")),
                "us-east-1", "000000000000");
        table.setStreamEnabled(true);

        service.enableStream("ViewTypeTable", TABLE_ARN, "NEW_AND_OLD_IMAGES", "us-east-1");
        StreamDescription sd =
                service.enableStream("ViewTypeTable", TABLE_ARN, "KEYS_ONLY", "us-east-1");

        JsonNode item = mapper.readTree("{\"userId\":{\"S\":\"u1\"},\"name\":{\"S\":\"a\"}}");
        service.captureEvent("INSERT", null, item, table, "us-east-1");

        String iterator = service.getShardIterator(
                sd.getStreamArn(), "shardId-000000000000", "TRIM_HORIZON", null);
        var records = service.getRecords(iterator, 10).records();

        assertEquals(1, records.size());
        assertEquals("KEYS_ONLY", records.get(0).getStreamViewType());
        // KEYS_ONLY carries keys only — a stale NEW_AND_OLD_IMAGES view would attach the image.
        assertNull(records.get(0).getNewImage());
    }

    @Test
    void loadsStreamOnStartup() {
        List<StreamDescription> streams = service.listStreams(null, "000000000000", "us-west-2");
        assertEquals(1, streams.size());
        StreamDescription stream = streams.get(0);
        assertEquals("TestTable", stream.getTableName());
        assertEquals("ENABLED", stream.getStreamStatus());
    }

    @Test
    void loadsPersistedStreamsOfEveryAccountOnStartup() {
        AccountAwareStorageBackend<TableDefinition> tables = AccountAwareStorageBackend.inMemory(ACCOUNT_A);
        TableDefinition tableA = sharedNameTable(ACCOUNT_A);
        TableDefinition tableB = sharedNameTable(ACCOUNT_B);
        tableA.setStreamArn(tableA.getTableArn() + "/stream/2026-01-01T00:00:00.000");
        tableB.setStreamArn(tableB.getTableArn() + "/stream/2026-01-02T00:00:00.000");
        tables.putForAccount(ACCOUNT_A, "us-east-1::" + SHARED_TABLE, tableA);
        tables.putForAccount(ACCOUNT_B, "us-east-1::" + SHARED_TABLE, tableB);

        DynamoDbStreamService restarted = new DynamoDbStreamService(mapper, tables);

        assertEquals(List.of(tableA.getStreamArn()),
                streamArns(restarted.listStreams(null, ACCOUNT_A, "us-east-1")));
        assertEquals(List.of(tableB.getStreamArn()),
                streamArns(restarted.listStreams(null, ACCOUNT_B, "us-east-1")));
    }

    @Test
    void rejectsAnIteratorWhenItsSequenceNumberHasBeenTrimmed() throws Exception {
        var table = createTestTableWithStream();
        service.enableStream(table.getTableName(), table.getTableArn(), "NEW_IMAGE", "us-east-1");
        JsonNode item = mapper.readTree("{\"userId\":{\"S\":\"u1\"}}");

        service.captureEvent("INSERT", null, item, table, "us-east-1");
        String iterator = service.getShardIterator(
                table.getStreamArn(), DynamoDbStreamService.SHARD_ID, "AT_SEQUENCE_NUMBER", "000000000000000000001");

        for (int i = 0; i < DynamoDbStreamService.MAX_RECORDS; i++) {
            service.captureEvent("INSERT", null, item, table, "us-east-1");
        }

        AwsException exception = assertThrows(AwsException.class,
                () -> service.getRecords(iterator, 100));

        assertEquals("TrimmedDataAccessException", exception.getErrorCode());
    }

    @Test
    void rejectsAnEmptyStreamIteratorWhenRecordsAreTrimmedBeforeFirstPoll() throws Exception {
        var table = new TableDefinition("EmptyTable",
                List.of(new KeySchemaElement("userId", "HASH")),
                List.of(new AttributeDefinition("userId", "S")),
                "us-east-1", "000000000000");
        table.setStreamEnabled(true);
        StreamDescription stream = service.enableStream(
                table.getTableName(), table.getTableArn(), "NEW_IMAGE", "us-east-1");
        JsonNode item = mapper.readTree("{\"userId\":{\"S\":\"u1\"}}");
        String iterator = service.getShardIterator(
                stream.getStreamArn(), DynamoDbStreamService.SHARD_ID, "TRIM_HORIZON", null);

        for (int i = 0; i <= DynamoDbStreamService.MAX_RECORDS; i++) {
            service.captureEvent("INSERT", null, item, table, "us-east-1");
        }

        AwsException exception = assertThrows(AwsException.class,
                () -> service.getRecords(iterator, 100));

        assertEquals("TrimmedDataAccessException", exception.getErrorCode());
    }

    @Test
    void rejectsAnEmptyLatestIteratorWhenRecordsAreTrimmedBeforeFirstPoll() throws Exception {
        var table = new TableDefinition("EmptyLatestTable",
                List.of(new KeySchemaElement("userId", "HASH")),
                List.of(new AttributeDefinition("userId", "S")),
                "us-east-1", "000000000000");
        table.setStreamEnabled(true);
        StreamDescription stream = service.enableStream(
                table.getTableName(), table.getTableArn(), "NEW_IMAGE", "us-east-1");
        JsonNode item = mapper.readTree("{\"userId\":{\"S\":\"u1\"}}");
        String iterator = service.getShardIterator(
                stream.getStreamArn(), DynamoDbStreamService.SHARD_ID, "LATEST", null);

        for (int i = 0; i <= DynamoDbStreamService.MAX_RECORDS; i++) {
            service.captureEvent("INSERT", null, item, table, "us-east-1");
        }

        AwsException exception = assertThrows(AwsException.class,
                () -> service.getRecords(iterator, 100));

        assertEquals("TrimmedDataAccessException", exception.getErrorCode());
    }

    @Test
    void allowsAnEmptyStreamIteratorWhenRetentionHasNotBeenExceeded() throws Exception {
        var table = new TableDefinition("EmptyTable",
                List.of(new KeySchemaElement("userId", "HASH")),
                List.of(new AttributeDefinition("userId", "S")),
                "us-east-1", "000000000000");
        table.setStreamEnabled(true);
        StreamDescription stream = service.enableStream(
                table.getTableName(), table.getTableArn(), "NEW_IMAGE", "us-east-1");
        JsonNode item = mapper.readTree("{\"userId\":{\"S\":\"u1\"}}");
        String iterator = service.getShardIterator(
                stream.getStreamArn(), DynamoDbStreamService.SHARD_ID, "TRIM_HORIZON", null);

        for (int i = 0; i < DynamoDbStreamService.MAX_RECORDS; i++) {
            service.captureEvent("INSERT", null, item, table, "us-east-1");
        }

        var result = service.getRecords(iterator, 1);

        assertEquals(1, result.records().size());
        assertEquals("000000000000000000001", result.records().get(0).getSequenceNumber());
    }

    @Test
    void appliesInclusiveAndExclusiveSequencePositions() throws Exception {
        var table = createTestTableWithStream();
        service.enableStream(table.getTableName(), table.getTableArn(), "NEW_IMAGE", "us-east-1");
        JsonNode item = mapper.readTree("{\"userId\":{\"S\":\"u1\"}}");

        service.captureEvent("INSERT", null, item, table, "us-east-1");
        service.captureEvent("MODIFY", item, item, table, "us-east-1");

        String at = service.getShardIterator(
                table.getStreamArn(), DynamoDbStreamService.SHARD_ID,
                "AT_SEQUENCE_NUMBER", "000000000000000000001");
        String after = service.getShardIterator(
                table.getStreamArn(), DynamoDbStreamService.SHARD_ID,
                "AFTER_SEQUENCE_NUMBER", "000000000000000000001");

        var atRecords = service.getRecords(at, 1).records();
        var afterRecords = service.getRecords(after, 1).records();

        assertEquals("000000000000000000001", atRecords.get(0).getSequenceNumber());
        assertEquals("000000000000000000002", afterRecords.get(0).getSequenceNumber());
        assertEquals("MODIFY", afterRecords.get(0).getEventName());
    }

    @Test
    void resumesAtTheRetainedSequenceAfterTheHeadIsTrimmed() throws Exception {
        var table = createTestTableWithStream();
        service.enableStream(table.getTableName(), table.getTableArn(), "NEW_IMAGE", "us-east-1");
        JsonNode item = mapper.readTree("{\"userId\":{\"S\":\"u1\"}}");

        service.captureEvent("EVENT_1", null, item, table, "us-east-1");
        service.captureEvent("EVENT_2", null, item, table, "us-east-1");
        String iterator = service.getShardIterator(
                table.getStreamArn(), DynamoDbStreamService.SHARD_ID,
                "AT_SEQUENCE_NUMBER", "000000000000000000002");

        for (int i = 3; i <= DynamoDbStreamService.MAX_RECORDS + 1; i++) {
            service.captureEvent("EVENT_" + i, null, item, table, "us-east-1");
        }

        var records = service.getRecords(iterator, 1).records();

        assertEquals(1, records.size());
        assertEquals("EVENT_2", records.get(0).getEventName());
    }

    @Test
    void advancesToTheNextSequenceWithoutDuplicatingRecords() throws Exception {
        var table = createTestTableWithStream();
        service.enableStream(table.getTableName(), table.getTableArn(), "NEW_IMAGE", "us-east-1");
        JsonNode item = mapper.readTree("{\"userId\":{\"S\":\"u1\"}}");

        service.captureEvent("EVENT_1", null, item, table, "us-east-1");
        service.captureEvent("EVENT_2", null, item, table, "us-east-1");
        String iterator = service.getShardIterator(
                table.getStreamArn(), DynamoDbStreamService.SHARD_ID,
                "TRIM_HORIZON", null);

        var firstPage = service.getRecords(iterator, 1);
        var secondPage = service.getRecords(firstPage.nextShardIterator(), 1);

        assertEquals("000000000000000000001", firstPage.records().get(0).getSequenceNumber());
        assertEquals("000000000000000000002", secondPage.records().get(0).getSequenceNumber());
    }

    @Test
    void latestSequenceNumberIsNullForAnUnknownOrEmptyStream() {
        TableDefinition table = createTestTableWithStream();

        assertNull(service.latestSequenceNumber(TABLE_ARN + "/stream/unknown"));
        assertNull(service.latestSequenceNumber(table.getStreamArn()));
    }

    @Test
    void latestSequenceNumberIsTheNewestRetainedRecord() throws Exception {
        TableDefinition table = createTestTableWithStream();
        service.enableStream(table.getTableName(), table.getTableArn(), "NEW_IMAGE", "us-east-1");
        JsonNode item = mapper.readTree("{\"userId\":{\"S\":\"u1\"}}");

        service.captureEvent("INSERT", null, item, table, "us-east-1");
        service.captureEvent("MODIFY", item, item, table, "us-east-1");

        assertEquals("000000000000000000002", service.latestSequenceNumber(table.getStreamArn()));
    }

    private TableDefinition sharedNameTable(String accountId) {
        TableDefinition table = new TableDefinition(SHARED_TABLE,
                List.of(new KeySchemaElement("userId", "HASH")),
                List.of(new AttributeDefinition("userId", "S")),
                "us-east-1", accountId);
        table.setStreamEnabled(true);
        return table;
    }

    private List<String> userIdsIn(String streamArn) {
        String iterator = service.getShardIterator(streamArn, DynamoDbStreamService.SHARD_ID, "TRIM_HORIZON", null);
        return service.getRecords(iterator, 100).records().stream()
                .map(record -> record.getKeys().path("userId").path("S").asText())
                .toList();
    }

    private static List<String> streamArns(List<StreamDescription> streams) {
        return streams.stream().map(StreamDescription::getStreamArn).toList();
    }

    @Test
    void sameNamedTablesInTwoAccountsKeepSeparateStreams() throws Exception {
        TableDefinition tableA = sharedNameTable(ACCOUNT_A);
        TableDefinition tableB = sharedNameTable(ACCOUNT_B);
        StreamDescription streamA = service.enableStream(SHARED_TABLE, tableA.getTableArn(), "NEW_IMAGE", "us-east-1");
        StreamDescription streamB = service.enableStream(SHARED_TABLE, tableB.getTableArn(), "NEW_IMAGE", "us-east-1");

        service.captureEvent("INSERT", null, mapper.readTree("{\"userId\":{\"S\":\"a\"}}"),
                tableA, "us-east-1");
        service.captureEvent("INSERT", null, mapper.readTree("{\"userId\":{\"S\":\"b\"}}"),
                tableB, "us-east-1");

        assertNotEquals(streamA.getStreamArn(), streamB.getStreamArn());
        assertEquals(List.of(streamA.getStreamArn()),
                streamArns(service.listStreams(SHARED_TABLE, ACCOUNT_A, "us-east-1")));
        assertEquals(List.of(streamB.getStreamArn()),
                streamArns(service.listStreams(null, ACCOUNT_B, "us-east-1")));
        assertEquals(List.of("a"), userIdsIn(streamA.getStreamArn()));
        assertEquals(List.of("b"), userIdsIn(streamB.getStreamArn()));
    }

    @Test
    void disablingOrDeletingOneAccountsStreamLeavesTheOtherEnabled() {
        TableDefinition tableA = sharedNameTable(ACCOUNT_A);
        TableDefinition tableB = sharedNameTable(ACCOUNT_B);
        StreamDescription streamA = service.enableStream(SHARED_TABLE, tableA.getTableArn(), "NEW_IMAGE", "us-east-1");
        StreamDescription streamB = service.enableStream(SHARED_TABLE, tableB.getTableArn(), "NEW_IMAGE", "us-east-1");

        service.disableStream(tableA.getTableArn());

        assertEquals("DISABLED", service.describeStream(streamA.getStreamArn()).getStreamStatus());
        assertEquals("ENABLED", service.describeStream(streamB.getStreamArn()).getStreamStatus());

        service.deleteStream(tableA.getTableArn());

        assertThrows(AwsException.class, () -> service.describeStream(streamA.getStreamArn()));
        assertEquals("ENABLED", service.describeStream(streamB.getStreamArn()).getStreamStatus());
    }

    @Test
    void requiresASequenceNumberForSequenceBasedIterators() {
        var table = createTestTableWithStream();

        AwsException nullSequence = assertThrows(AwsException.class,
                () -> service.getShardIterator(table.getStreamArn(), DynamoDbStreamService.SHARD_ID,
                        "AT_SEQUENCE_NUMBER", null));
        AwsException blankSequence = assertThrows(AwsException.class,
                () -> service.getShardIterator(table.getStreamArn(), DynamoDbStreamService.SHARD_ID,
                        "AFTER_SEQUENCE_NUMBER", "  "));

        assertEquals("ValidationException", nullSequence.getErrorCode());
        assertEquals("ValidationException", blankSequence.getErrorCode());
    }

}
