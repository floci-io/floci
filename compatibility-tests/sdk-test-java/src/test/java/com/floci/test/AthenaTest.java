package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.athena.model.*;
import software.amazon.awssdk.services.glue.GlueClient;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Athena Query Execution")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AthenaTest {

    private static AthenaClient athena;
    private static String queryExecutionId;

    @BeforeAll
    static void setup() {
        athena = TestFixtures.athenaClient();
    }

    @AfterAll
    static void cleanup() {
        if (athena != null) {
            athena.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Start query execution returns an execution ID")
    void startQueryExecution() {
        StartQueryExecutionResponse response = athena.startQueryExecution(
                StartQueryExecutionRequest.builder()
                        .queryString("SELECT 1 AS value")
                        .workGroup("primary")
                        .resultConfiguration(ResultConfiguration.builder()
                                .outputLocation("s3://floci-athena-results/sdk-tests/")
                                .build())
                        .build());

        assertThat(response.queryExecutionId()).isNotBlank();
        queryExecutionId = response.queryExecutionId();
    }

    @Test
    @Order(2)
    @DisplayName("Get query execution returns execution details")
    void getQueryExecution() {
        GetQueryExecutionResponse response = athena.getQueryExecution(
                GetQueryExecutionRequest.builder()
                        .queryExecutionId(queryExecutionId)
                        .build());

        QueryExecution execution = response.queryExecution();
        assertThat(execution.queryExecutionId()).isEqualTo(queryExecutionId);
        assertThat(execution.query()).isEqualTo("SELECT 1 AS value");
        assertThat(execution.status().state()).isIn(
                QueryExecutionState.RUNNING, QueryExecutionState.SUCCEEDED);
    }

    @Test
    @Order(3)
    @DisplayName("Get query results returns result set")
    void getQueryResults() {
        // Poll until succeeded (mock mode completes immediately, real duck may take a moment)
        QueryExecutionState state = QueryExecutionState.RUNNING;
        int attempts = 0;
        while (state == QueryExecutionState.RUNNING && attempts++ < 20) {
            GetQueryExecutionResponse exec = athena.getQueryExecution(
                    GetQueryExecutionRequest.builder().queryExecutionId(queryExecutionId).build());
            state = exec.queryExecution().status().state();
            if (state == QueryExecutionState.RUNNING) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
        }

        assertThat(state).isEqualTo(QueryExecutionState.SUCCEEDED);

        GetQueryResultsResponse results = athena.getQueryResults(
                GetQueryResultsRequest.builder()
                        .queryExecutionId(queryExecutionId)
                        .build());

        assertThat(results.resultSet()).isNotNull();
    }

    @Test
    @Order(4)
    @DisplayName("List query executions includes started execution")
    void listQueryExecutions() {
        ListQueryExecutionsResponse response = athena.listQueryExecutions(
                ListQueryExecutionsRequest.builder().build());

        assertThat(response.queryExecutionIds()).contains(queryExecutionId);
    }

    @Test
    @Order(5)
    @DisplayName("Get non-existent query execution throws InvalidRequestException")
    void getQueryExecutionNotFound() {
        assertThatThrownBy(() -> athena.getQueryExecution(
                GetQueryExecutionRequest.builder()
                        .queryExecutionId("00000000-0000-0000-0000-000000000000")
                        .build()))
                .isInstanceOf(InvalidRequestException.class);
    }

    /**
     * Reproduces issue #1498: the AthenaClient fails to unmarshal the {@code CreationTime} field returned by Floci's
     * {@code GetWorkGroup} response.
     */
    @Test
    @Order(6)
    @DisplayName("getWorkGroup must unmarshal creationTime successfully")
    void getWorkGroupCreationTimeCanBeUnmarshalledBySdk() {
        String groupName = UUID.randomUUID().toString();
        athena.createWorkGroup(
                CreateWorkGroupRequest.builder()
                        .name(groupName)
                        .build()
        );

        GetWorkGroupResponse response = athena.getWorkGroup(
                GetWorkGroupRequest.builder()
                        .workGroup(groupName)
                        .build()
        );

        Instant creationTime = response.workGroup().creationTime();
        assertThat(creationTime)
                .as("creationTime must be parseable by the AWS SDK")
                .isNotNull();
    }

    @Test
    @Order(7)
    @DisplayName("getTableMetadata must unmarshal timestamps successfully")
    void getTableMetadataTimestampsCanBeUnmarshalledBySdk() {
        GlueClient glue = TestFixtures.glueClient();
        String dbName = "athena_ts_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String tableName = "orders";

        glue.createDatabase(r -> r.databaseInput(i -> i.name(dbName)));
        glue.createTable(r -> r
                .databaseName(dbName)
                .tableInput(t -> t
                        .name(tableName)
                        .tableType("EXTERNAL_TABLE")
                        .storageDescriptor(
                                sd -> sd
                                        .location("s3://test-bucket/" + dbName + "/")
                                        .columns(c -> c.name("id").type("string"))
                        )
                )
        );

        GetTableMetadataResponse response = athena.getTableMetadata(
                GetTableMetadataRequest.builder()
                        .catalogName("AwsDataCatalog")
                        .databaseName(dbName)
                        .tableName(tableName)
                        .build()
        );

        assertThat(response.tableMetadata().createTime())
                .as("createTime must be parseable by the AWS SDK")
                .isNotNull();
        assertThat(response.tableMetadata().lastAccessTime())
                .as("lastAccessTime must be parseable by the AWS SDK")
                .isNotNull();

        glue.close();
    }
}

