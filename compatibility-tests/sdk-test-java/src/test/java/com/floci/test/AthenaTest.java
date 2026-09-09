package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.athena.model.*;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
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
    void getQueryResults() throws InterruptedException {
        QueryExecutionStatus status = TestFixtures.awaitAthenaQueryTerminal(
                athena, queryExecutionId, Duration.ofSeconds(60));
        assertThat(status.state())
                .as("Athena query did not succeed: %s", status.stateChangeReason())
                .isEqualTo(QueryExecutionState.SUCCEEDED);

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

        try {
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
        } finally {
            try {
                glue.deleteTable(r -> r.databaseName(dbName).name(tableName));
                glue.deleteDatabase(r -> r.name(dbName));
            } catch (Exception ignored) {
                // Best-effort test cleanup
            }
            glue.close();
        }
    }

    @Test
    @Order(8)
    @DisplayName("CREATE DATABASE through Athena updates the Glue catalog")
    void createDatabaseDdlUsesGlueCatalog() throws InterruptedException {
        String dbName = "athena_ddl_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String location = "s3://test-bucket/" + dbName + "/";

        try (GlueClient glue = TestFixtures.glueClient()) {
            try {
                StartQueryExecutionResponse started = athena.startQueryExecution(
                        StartQueryExecutionRequest.builder()
                                .queryString("CREATE DATABASE IF NOT EXISTS " + dbName
                                        + " LOCATION '" + location + "'")
                                .workGroup("primary")
                                .build());

                QueryExecutionStatus status = TestFixtures.awaitAthenaQueryTerminal(
                        athena, started.queryExecutionId(), Duration.ofSeconds(60));
                assertThat(status.state())
                        .as("Athena DDL did not succeed: %s", status.stateChangeReason())
                        .isEqualTo(QueryExecutionState.SUCCEEDED);

                QueryExecution execution = athena.getQueryExecution(r -> r
                        .queryExecutionId(started.queryExecutionId())).queryExecution();
                assertThat(execution.statementType()).isEqualTo(StatementType.DDL);
                assertThat(execution.resultConfiguration()).isNull();

                GetQueryResultsResponse results = athena.getQueryResults(r -> r
                        .queryExecutionId(started.queryExecutionId()));
                assertThat(results.resultSet().rows()).isEmpty();
                assertThat(results.resultSet().resultSetMetadata().columnInfo()).isEmpty();

                assertThat(glue.getDatabase(r -> r.name(dbName)).database().locationUri())
                        .isEqualTo(location);
            } finally {
                try {
                    glue.deleteDatabase(r -> r.name(dbName));
                } catch (Exception ignored) {
                    // Best-effort test cleanup
                }
            }
        }
    }

    /**
     * Reproduces issue #2859: Athena queries referencing tables with qualified names (e.g.
     * {@code FROM shop.orders}) failed with "schema does not exist" when DuckDB schemas were not
     * registered for Glue databases.
     */
    @Test
    @Order(9)
    @DisplayName("Query table with qualified database schema resolves and executes")
    void queryTableWithQualifiedDatabaseSchemaResolves() throws InterruptedException {
        String uniqueSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String dbName = "shop_" + uniqueSuffix;
        String tableName = "orders";
        String bucketName = "athena-compat-" + uniqueSuffix;
        String dataPrefix = "orders/";
        String location = "s3://" + bucketName + "/" + dataPrefix;

        try (GlueClient glue = TestFixtures.glueClient();
             S3Client s3 = TestFixtures.s3Client()) {

            s3.createBucket(r -> r.bucket(bucketName));
            s3.putObject(r -> r.bucket(bucketName).key(dataPrefix + "orders.json"),
                    RequestBody.fromString(
                            "{\"customer\":\"ana\",\"amount\":10}\n{\"customer\":\"leo\",\"amount\":7}\n"));

            glue.createDatabase(r -> r.databaseInput(i -> i.name(dbName)));
            glue.createTable(r -> r
                    .databaseName(dbName)
                    .tableInput(t -> t
                            .name(tableName)
                            .tableType("EXTERNAL_TABLE")
                            .storageDescriptor(sd -> sd
                                    .location(location)
                                    .inputFormat("org.apache.hadoop.mapred.TextInputFormat")
                                    .outputFormat("org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat")
                                    .serdeInfo(s -> s
                                            .serializationLibrary("org.openx.data.jsonserde.JsonSerDe")
                                            .parameters(Map.of("serialization.format", "1")))
                                    .columns(
                                            software.amazon.awssdk.services.glue.model.Column.builder().name("customer").type("string").build(),
                                            software.amazon.awssdk.services.glue.model.Column.builder().name("amount").type("int").build()
                                    )
                            )
                    )
            );

            try {
                // Qualified query without context database (issue #2859 exact shape)
                StartQueryExecutionResponse started = athena.startQueryExecution(
                        StartQueryExecutionRequest.builder()
                                .queryString("SELECT customer, sum(amount) as total FROM " + dbName + "." + tableName
                                        + " GROUP BY customer ORDER BY customer")
                                .workGroup("primary")
                                .resultConfiguration(ResultConfiguration.builder()
                                        .outputLocation("s3://" + bucketName + "/results/")
                                        .build())
                                .build());

                QueryExecutionStatus status = TestFixtures.awaitAthenaQueryTerminal(
                        athena, started.queryExecutionId(), Duration.ofSeconds(60));
                assertThat(status.state())
                        .as("Athena qualified query did not succeed: %s", status.stateChangeReason())
                        .isEqualTo(QueryExecutionState.SUCCEEDED);

                GetQueryResultsResponse results = athena.getQueryResults(r -> r
                        .queryExecutionId(started.queryExecutionId()));
                assertThat(results.resultSet().rows()).hasSizeGreaterThanOrEqualTo(3);

                // Unqualified query with context database (context alias verification)
                StartQueryExecutionResponse startedUnqualified = athena.startQueryExecution(
                        StartQueryExecutionRequest.builder()
                                .queryString("SELECT customer, sum(amount) as total FROM " + tableName
                                        + " GROUP BY customer ORDER BY customer")
                                .queryExecutionContext(QueryExecutionContext.builder().database(dbName).build())
                                .workGroup("primary")
                                .resultConfiguration(ResultConfiguration.builder()
                                        .outputLocation("s3://" + bucketName + "/results/")
                                        .build())
                                .build());

                QueryExecutionStatus statusUnqualified = TestFixtures.awaitAthenaQueryTerminal(
                        athena, startedUnqualified.queryExecutionId(), Duration.ofSeconds(60));
                assertThat(statusUnqualified.state())
                        .as("Athena unqualified query did not succeed: %s", statusUnqualified.stateChangeReason())
                        .isEqualTo(QueryExecutionState.SUCCEEDED);
            } finally {
                try {
                    glue.deleteTable(r -> r.databaseName(dbName).name(tableName));
                    glue.deleteDatabase(r -> r.name(dbName));
                } catch (Exception ignored) {
                    // Best-effort test cleanup
                }
                try {
                    s3.listObjectsV2(r -> r.bucket(bucketName)).contents().forEach(obj ->
                            s3.deleteObject(r -> r.bucket(bucketName).key(obj.key())));
                    s3.deleteBucket(r -> r.bucket(bucketName));
                } catch (Exception ignored) {
                    // Best-effort test cleanup
                }
            }
        }
    }
}
