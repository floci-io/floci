package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.athena.model.*;
import software.amazon.awssdk.services.firehose.FirehoseClient;
import software.amazon.awssdk.services.firehose.model.PutRecordRequest;
import software.amazon.awssdk.services.firehose.model.Record;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Data Lake (Athena + Glue + Firehose)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DataLakeTest {

    private static AthenaClient athena;
    private static GlueClient glue;
    private static FirehoseClient firehose;

    private static final String DB_NAME = TestFixtures.uniqueName("test_db");
    private static final String TABLE_NAME = "orders";
    private static final String STREAM_NAME = TestFixtures.uniqueName("orders_stream");

    @BeforeAll
    static void setup() {
        athena = TestFixtures.athenaClient();
        glue = TestFixtures.glueClient();
        firehose = TestFixtures.firehoseClient();
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void setupInfrastructure() {
        // 1. Glue Database
        glue.createDatabase(CreateDatabaseRequest.builder()
                .databaseInput(DatabaseInput.builder().name(DB_NAME).build())
                .build());

        // 2. Glue Table
        glue.createTable(CreateTableRequest.builder()
                .databaseName(DB_NAME)
                .tableInput(TableInput.builder()
                        .name(TABLE_NAME)
                        .storageDescriptor(StorageDescriptor.builder()
                                .location("s3://floci-firehose-results/" + STREAM_NAME + "/")
                                .inputFormat("Parquet")
                                .columns(
                                        software.amazon.awssdk.services.glue.model.Column.builder().name("id").type("int").build(),
                                        software.amazon.awssdk.services.glue.model.Column.builder().name("amount").type("double").build()
                                )
                                .build())
                        .build())
                .build());

        // 3. Firehose Stream
        firehose.createDeliveryStream(software.amazon.awssdk.services.firehose.model.CreateDeliveryStreamRequest.builder()
                .deliveryStreamName(STREAM_NAME)
                .build());
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void ingestAndQuery() throws Exception {
        // Ingest data
        for (int i = 1; i <= 5; i++) {
            String json = String.format("{\"id\": %d, \"amount\": %.2f}", i, i * 10.0);
            firehose.putRecord(PutRecordRequest.builder()
                    .deliveryStreamName(STREAM_NAME)
                    .record(Record.builder().data(SdkBytes.fromString(json, StandardCharsets.UTF_8)).build())
                    .build());
        }

        // Athena Query
        StartQueryExecutionResponse startResp = athena.startQueryExecution(StartQueryExecutionRequest.builder()
                .queryString("SELECT sum(amount) as total FROM " + TABLE_NAME)
                .queryExecutionContext(QueryExecutionContext.builder().database(DB_NAME).build())
                .build());

        String queryId = startResp.queryExecutionId();

        // Wait for query
        int attempts = 0;
        QueryExecutionStatus status = null;
        while (attempts < 30) {
            GetQueryExecutionResponse getResp = athena.getQueryExecution(GetQueryExecutionRequest.builder()
                    .queryExecutionId(queryId)
                    .build());
            status = getResp.queryExecution().status();
            if (status.state() == QueryExecutionState.SUCCEEDED) break;
            if (status.state() == QueryExecutionState.FAILED) {
                Assertions.fail("Query failed: " + status.stateChangeReason());
            }
            Thread.sleep(1000);
            attempts++;
        }

        assertThat(status.state()).isEqualTo(QueryExecutionState.SUCCEEDED);

        // Get Results
        GetQueryResultsResponse results = athena.getQueryResults(GetQueryResultsRequest.builder()
                .queryExecutionId(queryId)
                .build());

        // Our AthenaService.getQueryResults implementation:
        // results.add(header);
        // ... subList(1, data.size()) ... -> returns only data rows.
        // Wait, if I return subList(1, size) then header is EXCLUDED from Rows.
        // But AWS SDK expects Rows to contain header as first element.
        
        assertThat(results.resultSet().rows()).hasSize(1); 
        
        String totalValue = results.resultSet().rows().get(0).data().get(0).varCharValue();
        assertThat(totalValue).isEqualTo("150.0");
    }
}
