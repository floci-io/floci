package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.services.dynamodb.DynamoDbStreamService;
import io.github.hectorvent.floci.services.dynamodb.model.DynamoDbStreamRecord;
import io.github.hectorvent.floci.services.redshift.model.Integration;
import io.github.hectorvent.floci.services.redshiftdata.RedshiftZeroEtlWriter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RedshiftDynamoDbZeroEtlConsumerTest {

    @Test
    void writesRecordsAndAdvancesCheckpointOnlyAfterSuccessfulBatch() {
        DynamoDbStreamService streamService = mock(DynamoDbStreamService.class);
        RedshiftService redshiftService = mock(RedshiftService.class);
        RedshiftZeroEtlWriter writer = mock(RedshiftZeroEtlWriter.class);
        Integration integration = integration();
        DynamoDbStreamRecord record = new DynamoDbStreamRecord();
        record.setEventId("event-1");
        record.setSequenceNumber("000000000000000000001");
        String iterator = "iterator";
        when(streamService.getShardIterator(eq(integration.getSourceStreamArn()), eq(DynamoDbStreamService.SHARD_ID),
                eq("TRIM_HORIZON"), eq(null))).thenReturn(iterator);
        when(streamService.getRecords(iterator, 100)).thenReturn(
                new DynamoDbStreamService.GetRecordsResult(List.of(record), "next"));
        when(writer.writeBatch("warehouse", "floci_zetl_orders", List.of(record)))
                .thenReturn(record.getSequenceNumber());

        RedshiftDynamoDbZeroEtlConsumer consumer =
                new RedshiftDynamoDbZeroEtlConsumer(streamService, redshiftService, writer);
        consumer.pollOnce(integration);

        verify(writer).createLandingTable("warehouse", "floci_zetl_orders");
        verify(writer).writeBatch("warehouse", "floci_zetl_orders", List.of(record));
        verify(redshiftService).updateIntegrationRuntime(integration.getIntegrationArn(),
                record.getSequenceNumber(), true, null);
    }

    private static Integration integration() {
        Integration integration = new Integration();
        integration.setIntegrationArn("arn:aws:redshift:us-east-1:111111111111:integration:one");
        integration.setSourceStreamArn("arn:aws:dynamodb:us-east-1:111111111111:table/orders/stream/one");
        integration.setTargetClusterIdentifier("warehouse");
        integration.setLandingTableName("floci_zetl_orders");
        integration.setPollingEnabled(true);
        return integration;
    }
}
