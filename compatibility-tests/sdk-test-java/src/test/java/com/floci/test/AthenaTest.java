package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.athena.model.*;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

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
}
