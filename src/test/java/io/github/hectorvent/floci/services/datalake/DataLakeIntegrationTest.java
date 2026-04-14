package io.github.hectorvent.floci.services.datalake;

import io.github.hectorvent.floci.services.athena.AthenaService;
import io.github.hectorvent.floci.services.athena.model.QueryExecutionState;
import io.github.hectorvent.floci.services.athena.model.ResultSet;
import io.github.hectorvent.floci.services.firehose.FirehoseService;
import io.github.hectorvent.floci.services.firehose.model.Record;
import io.github.hectorvent.floci.services.glue.GlueService;
import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.Database;
import io.github.hectorvent.floci.services.glue.model.StorageDescriptor;
import io.github.hectorvent.floci.services.glue.model.Table;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class DataLakeIntegrationTest {

    @Inject
    GlueService glueService;

    @Inject
    FirehoseService firehoseService;

    @Inject
    AthenaService athenaService;

    @Test
    void testEndToEndDataLake() throws Exception {
        // 1. Create Glue Metadata
        glueService.createDatabase(new Database("test_db"));
        
        Table table = new Table();
        table.setName("orders");
        StorageDescriptor sd = new StorageDescriptor();
        sd.setLocation("s3://floci-firehose-results/orders/");
        sd.setInputFormat("Parquet");
        sd.setColumns(List.of(new Column("id", "int"), new Column("amount", "double")));
        table.setStorageDescriptor(sd);
        
        glueService.createTable("test_db", table);

        // 2. Ingest Data via Firehose
        firehoseService.createDeliveryStream("orders");
        
        for (int i = 1; i <= 5; i++) {
            String json = String.format("{\"id\": %d, \"amount\": %.2f}", i, i * 10.5);
            firehoseService.putRecord("orders", new Record(json.getBytes(StandardCharsets.UTF_8)));
        }

        // 3. Athena Query
        String queryId = athenaService.startQueryExecution("SELECT count(*) as total, sum(amount) as total_amount FROM orders", "primary", "test_db");
        
        // Wait for query to complete
        int attempts = 0;
        while (attempts < 10) {
            var execution = athenaService.getQueryExecution(queryId);
            if (execution.getStatus().getState() == QueryExecutionState.SUCCEEDED) {
                break;
            }
            if (execution.getStatus().getState() == QueryExecutionState.FAILED) {
                fail("Query failed: " + execution.getStatus().getStateChangeReason());
            }
            Thread.sleep(1000);
            attempts++;
        }

        // 4. Verify Results
        ResultSet results = athenaService.getQueryResults(queryId);
        assertNotNull(results);
        assertEquals(1, results.getRows().size(), "Should have 1 data row");
        
        assertEquals("5", results.getRows().get(0).getData().get(0).getVarCharValue());
        assertEquals("157.5", results.getRows().get(0).getData().get(1).getVarCharValue());
    }
}
