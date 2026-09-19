package io.github.hectorvent.floci.services.redshift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbStreamService;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.DynamoDbStreamRecord;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import io.github.hectorvent.floci.services.redshift.model.Integration;
import io.github.hectorvent.floci.services.redshiftdata.RedshiftZeroEtlWriter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class RedshiftDynamoDbZeroEtlConsumerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void writesRecordsAndAdvancesCheckpointOnlyAfterSuccessfulBatch() {
        DynamoDbStreamService streamService = mock(DynamoDbStreamService.class);
        DynamoDbService dynamoDbService = mock(DynamoDbService.class);
        RedshiftService redshiftService = mock(RedshiftService.class);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        Integration integration = integration();
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
                new RedshiftDynamoDbZeroEtlConsumer(streamService, dynamoDbService, redshiftService, writer);
        consumer.pollOnce(integration);

        verify(writer).createLandingTable(integration.getAccountId(), "warehouse", "floci_zetl_orders");
        verify(writer).writeBatch(integration.getAccountId(), "warehouse", "floci_zetl_orders", List.of(record));
        verify(redshiftService).updateIntegrationRuntime(integration.getAccountId(), integration.getIntegrationArn(),
                record.getSequenceNumber(), true, null);
        verify(dynamoDbService, never()).scan(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void backfillsOnePageAtATimeBeforeSwitchingToStreamPolling() throws Exception {
        DynamoDbStreamService streamService = mock(DynamoDbStreamService.class);
        DynamoDbService dynamoDbService = mock(DynamoDbService.class);
        RedshiftService redshiftService = mock(RedshiftService.class);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        Integration integration = integration();
        assertFalse(integration.isBackfillCompleted());

        when(dynamoDbService.describeTable("orders", "us-east-1")).thenReturn(ordersTable());

        ObjectNode item1 = itemWithId("1");
        ObjectNode lastKey = MAPPER.createObjectNode();
        lastKey.set("id", item1.get("id"));
        when(dynamoDbService.scan(eq("orders"), isNull(), isNull(), isNull(), isNull(),
                eq(100), isNull(), eq("us-east-1")))
                .thenReturn(new DynamoDbService.ScanResult(List.of(item1), 1, 0, lastKey));

        RedshiftDynamoDbZeroEtlConsumer consumer =
                new RedshiftDynamoDbZeroEtlConsumer(streamService, dynamoDbService, redshiftService, writer);
        consumer.pollOnce(integration);

        assertFalse(integration.isBackfillCompleted());
        assertEquals(MAPPER.writeValueAsString(lastKey), integration.getBackfillLastEvaluatedKey());
        verify(redshiftService).updateIntegrationBackfillProgress(integration.getAccountId(),
                integration.getIntegrationArn(), MAPPER.writeValueAsString(lastKey), false);
        verify(streamService, never()).getShardIterator(any(), any(), any(), any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DynamoDbStreamRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).writeBatch(eq(integration.getAccountId()), eq("warehouse"), eq("floci_zetl_orders"),
                captor.capture());
        DynamoDbStreamRecord written = captor.getValue().get(0);
        assertEquals("INSERT", written.getEventName());
        assertEquals(item1, written.getNewImage());
        assertTrue(written.getEventId().startsWith("backfill#" + integration.getIntegrationArn() + "#"));
    }

    @Test
    void backfillCompletesWhenScanReturnsNoLastEvaluatedKey() {
        DynamoDbStreamService streamService = mock(DynamoDbStreamService.class);
        DynamoDbService dynamoDbService = mock(DynamoDbService.class);
        RedshiftService redshiftService = mock(RedshiftService.class);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        Integration integration = integration();

        when(dynamoDbService.describeTable("orders", "us-east-1")).thenReturn(ordersTable());
        when(dynamoDbService.scan(eq("orders"), isNull(), isNull(), isNull(), isNull(),
                eq(100), isNull(), eq("us-east-1")))
                .thenReturn(new DynamoDbService.ScanResult(List.of(), 0, 0, null));

        RedshiftDynamoDbZeroEtlConsumer consumer =
                new RedshiftDynamoDbZeroEtlConsumer(streamService, dynamoDbService, redshiftService, writer);
        consumer.pollOnce(integration);

        assertTrue(integration.isBackfillCompleted());
        verify(redshiftService).updateIntegrationBackfillProgress(integration.getAccountId(),
                integration.getIntegrationArn(), null, true);
        verify(writer, never()).writeBatch(any(), any(), any(), any());
    }

    @Test
    void sameItemProducesTheSameEventIdAcrossScans() {
        DynamoDbStreamService streamService = mock(DynamoDbStreamService.class);
        DynamoDbService dynamoDbService = mock(DynamoDbService.class);
        RedshiftService redshiftService = mock(RedshiftService.class);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        when(dynamoDbService.describeTable("orders", "us-east-1")).thenReturn(ordersTable());
        ObjectNode item = itemWithId("1");
        when(dynamoDbService.scan(eq("orders"), isNull(), isNull(), isNull(), isNull(),
                eq(100), isNull(), eq("us-east-1")))
                .thenReturn(new DynamoDbService.ScanResult(List.of(item), 1, 0, null));

        RedshiftDynamoDbZeroEtlConsumer consumer =
                new RedshiftDynamoDbZeroEtlConsumer(streamService, dynamoDbService, redshiftService, writer);
        consumer.pollOnce(integration());
        consumer.pollOnce(integration());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DynamoDbStreamRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer, times(2)).writeBatch(any(), any(), any(), captor.capture());
        assertEquals(captor.getAllValues().get(0).get(0).getEventId(),
                captor.getAllValues().get(1).get(0).getEventId());
    }

    private static TableDefinition ordersTable() {
        return new TableDefinition("orders",
                List.of(new KeySchemaElement("id", "HASH")),
                List.of(new AttributeDefinition("id", "S")));
    }

    private static ObjectNode itemWithId(String id) {
        ObjectNode item = MAPPER.createObjectNode();
        ObjectNode idAttr = MAPPER.createObjectNode();
        idAttr.put("S", id);
        item.set("id", idAttr);
        return item;
    }

    private static Integration integration() {
        Integration integration = new Integration();
        integration.setIntegrationArn("arn:aws:redshift:us-east-1:111111111111:integration:one");
        integration.setAccountId("111111111111");
        integration.setSourceStreamArn("arn:aws:dynamodb:us-east-1:111111111111:table/orders/stream/one");
        integration.setTargetClusterIdentifier("warehouse");
        integration.setLandingTableName("floci_zetl_orders");
        integration.setPollingEnabled(true);
        return integration;
    }
}
