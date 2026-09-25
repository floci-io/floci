package io.github.hectorvent.floci.services.redshift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbFacade;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbStreamService;
import io.github.hectorvent.floci.services.dynamodb.backend.RecordingDynamoDbBackend;
import io.github.hectorvent.floci.services.dynamodb.backend.RecordingDynamoDbBackend.Invocation;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.DynamoDbStreamRecord;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.redshift.model.Integration;
import io.github.hectorvent.floci.services.redshiftdata.RedshiftZeroEtlWriter;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class RedshiftDynamoDbZeroEtlConsumerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    DynamoDbService dynamoDbService;

    @Inject
    DynamoDbFacade dynamoDb;

    @Test
    void writesRecordsAndAdvancesCheckpointOnlyAfterSuccessfulBatch() {
        DynamoDbStreamService streamService = mock(DynamoDbStreamService.class);
        RedshiftService redshiftService = mock(RedshiftService.class);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        Integration integration = integration("orders");
        integration.setBackfillCompleted(true);
        DynamoDbStreamRecord record = new DynamoDbStreamRecord();
        record.setEventId("event-1");
        record.setSequenceNumber("000000000000000000001");
        String iterator = "iterator";
        when(streamService.getShardIterator(eq(integration.getSourceStreamArn()), eq(DynamoDbStreamService.SHARD_ID),
                eq("TRIM_HORIZON"), eq(null))).thenReturn(iterator);
        when(streamService.getRecords(iterator, 100)).thenReturn(
                new DynamoDbStreamService.GetRecordsResult(List.of(record), "next"));
        when(writer.writeBatch(integration.getAccountId(), "warehouse", "floci_zetl_orders", List.of(record)))
                .thenReturn(record.getSequenceNumber());

        RedshiftDynamoDbZeroEtlConsumer consumer =
                new RedshiftDynamoDbZeroEtlConsumer(streamService, dynamoDb, redshiftService, writer);
        consumer.pollOnce(integration);

        verify(writer).createLandingTable(integration.getAccountId(), "warehouse", "floci_zetl_orders");
        verify(writer).writeBatch(integration.getAccountId(), "warehouse", "floci_zetl_orders", List.of(record));
        verify(redshiftService).updateIntegrationRuntime(integration.getAccountId(), integration.getIntegrationArn(),
                record.getSequenceNumber(), true, null);
    }

    @Test
    void backfillsOnePageAtATimeBeforeSwitchingToStreamPolling() {
        DynamoDbStreamService streamService = mock(DynamoDbStreamService.class);
        RedshiftService redshiftService = mock(RedshiftService.class);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        String tableName = "orders-page-" + System.nanoTime();
        Integration integration = integration(tableName);
        assertFalse(integration.isBackfillCompleted());

        // Create table and items in the integration's account scope (111111111111).
        // Populate > BATCH_SIZE (100) items so the scan produces a LastEvaluatedKey.
        RequestScopes.runAs(integration.getAccountId(), () -> {
            dynamoDbService.createTable(tableName,
                    List.of(new KeySchemaElement("id", "HASH")),
                    List.of(new AttributeDefinition("id", "S")),
                    5L, 5L, "us-east-1");
            for (int i = 1; i <= 101; i++) {
                dynamoDbService.putItem(tableName, itemWithId(String.valueOf(i)), "us-east-1");
            }
        });

        // The table must NOT exist in the default account: this proves the account scope is applied.
        assertThrows(AwsException.class, () -> dynamoDbService.describeTable(tableName, "us-east-1"));

        RedshiftDynamoDbZeroEtlConsumer consumer =
                new RedshiftDynamoDbZeroEtlConsumer(streamService, dynamoDb, redshiftService, writer);
        consumer.pollOnce(integration);

        assertFalse(integration.isBackfillCompleted());
        assertNotNull(integration.getBackfillLastEvaluatedKey());
        verify(redshiftService).updateIntegrationBackfillProgress(eq(integration.getAccountId()),
                eq(integration.getIntegrationArn()), eq(integration.getBackfillLastEvaluatedKey()), eq(false));
        verify(streamService, never()).getShardIterator(any(), any(), any(), any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DynamoDbStreamRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).writeBatch(eq(integration.getAccountId()), eq("warehouse"), eq("floci_zetl_orders"),
                captor.capture());
        assertEquals(100, captor.getValue().size());
        DynamoDbStreamRecord written = captor.getValue().get(0);
        assertEquals("INSERT", written.getEventName());
        assertTrue(written.getEventId().startsWith("backfill#" + integration.getIntegrationArn() + "#"));
    }

    @Test
    void backfillCompletesWhenScanReturnsNoLastEvaluatedKey() {
        DynamoDbStreamService streamService = mock(DynamoDbStreamService.class);
        RedshiftService redshiftService = mock(RedshiftService.class);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        String tableName = "orders-empty-" + System.nanoTime();
        Integration integration = integration(tableName);

        RequestScopes.runAs(integration.getAccountId(), () -> {
            dynamoDbService.createTable(tableName,
                    List.of(new KeySchemaElement("id", "HASH")),
                    List.of(new AttributeDefinition("id", "S")),
                    5L, 5L, "us-east-1");
        });

        assertThrows(AwsException.class, () -> dynamoDbService.describeTable(tableName, "us-east-1"));

        RedshiftDynamoDbZeroEtlConsumer consumer =
                new RedshiftDynamoDbZeroEtlConsumer(streamService, dynamoDb, redshiftService, writer);
        consumer.pollOnce(integration);

        assertTrue(integration.isBackfillCompleted());
        verify(redshiftService).updateIntegrationBackfillProgress(integration.getAccountId(),
                integration.getIntegrationArn(), null, true);
        verify(writer, never()).writeBatch(any(), any(), any(), any());
    }

    @Test
    void sameItemProducesTheSameEventIdAcrossScans() {
        DynamoDbStreamService streamService = mock(DynamoDbStreamService.class);
        RedshiftService redshiftService = mock(RedshiftService.class);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        String tableName = "orders-same-" + System.nanoTime();
        Integration integration = integration(tableName);

        ObjectNode item = itemWithId("1");
        RequestScopes.runAs(integration.getAccountId(), () -> {
            dynamoDbService.createTable(tableName,
                    List.of(new KeySchemaElement("id", "HASH")),
                    List.of(new AttributeDefinition("id", "S")),
                    5L, 5L, "us-east-1");
            dynamoDbService.putItem(tableName, item, "us-east-1");
        });

        RedshiftDynamoDbZeroEtlConsumer consumer =
                new RedshiftDynamoDbZeroEtlConsumer(streamService, dynamoDb, redshiftService, writer);
        consumer.pollOnce(integration);
        integration.setBackfillCompleted(false);
        consumer.pollOnce(integration);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DynamoDbStreamRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer, times(2)).writeBatch(any(), any(), any(), captor.capture());
        assertEquals(captor.getAllValues().get(0).get(0).getEventId(),
                captor.getAllValues().get(1).get(0).getEventId());
    }

    @Test
    void backfillReadsTheTableAsTheIntegrationAccountInTheStreamRegion() {
        RecordingDynamoDbBackend backend = new RecordingDynamoDbBackend();
        DynamoDbFacade recording = new DynamoDbFacade(backend, backend, new RegionResolver("us-east-1", "000000000000"));
        Integration integration = integration("orders");
        integration.setSourceStreamArn("arn:aws:dynamodb:eu-west-1:111111111111:table/orders/stream/one");

        new RedshiftDynamoDbZeroEtlConsumer(mock(DynamoDbStreamService.class), recording,
                mock(RedshiftService.class), mock(RedshiftZeroEtlWriter.class)).pollOnce(integration);

        assertEquals(List.of(
                new Invocation("describeTable", "111111111111", "eu-west-1"),
                new Invocation("scan", "111111111111", "eu-west-1")), backend.invocations());
    }

    private static ObjectNode itemWithId(String id) {
        ObjectNode item = MAPPER.createObjectNode();
        ObjectNode idAttr = MAPPER.createObjectNode();
        idAttr.put("S", id);
        item.set("id", idAttr);
        return item;
    }

    private static Integration integration(String tableName) {
        Integration integration = new Integration();
        integration.setIntegrationArn("arn:aws:redshift:us-east-1:111111111111:integration:one");
        integration.setAccountId("111111111111");
        integration.setSourceStreamArn("arn:aws:dynamodb:us-east-1:111111111111:table/" + tableName + "/stream/one");
        integration.setTargetClusterIdentifier("warehouse");
        integration.setLandingTableName("floci_zetl_orders");
        integration.setPollingEnabled(true);
        return integration;
    }
}
