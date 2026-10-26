package io.github.hectorvent.floci.services.redshiftdata;

import io.github.hectorvent.floci.services.redshift.RedshiftService;
import io.github.hectorvent.floci.services.redshift.model.Parameter;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.GetLogEventsRequest;
import software.amazon.awssdk.services.redshiftdata.RedshiftDataClient;
import software.amazon.awssdk.services.redshiftdata.model.ExecuteStatementResponse;
import software.amazon.awssdk.services.redshiftdata.model.ResourceNotFoundException;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedshiftDataApiIntegrationTest {

    private static final String SHARED_CLUSTER_ID = "it-rsdata-shared";

    @Inject
    RedshiftService redshift;

    @TestHTTPResource("/")
    URI endpoint;

    private String clusterId;

    @BeforeAll
    void createSharedCluster() {
        Assumptions.assumeTrue(dockerAvailable(), "Docker is required for Redshift Data API integration tests");
        RestAssuredJsonUtils.configureAwsContentTypes();
        clusterId = SHARED_CLUSTER_ID;
        redshift.createCluster(SHARED_CLUSTER_ID, "dc2.large", "admin", "Secret123");
    }

    private static boolean dockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @AfterAll
    void deleteSharedCluster() {
        if (clusterId != null) {
            redshift.deleteCluster(clusterId);
        }
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String executeAndWait(String sql) {
        String id = RestAssuredJsonUtils.awsAction("RedshiftData", "ExecuteStatement", """
                {"Sql": %s, "ClusterIdentifier": "%s", "DbUser": "admin", "Database": "dev"}
                """.formatted(quote(sql), clusterId))
                .then().statusCode(200).extract().path("Id");
        awaitFinished(id);
        return id;
    }

    private void awaitFinished(String id) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(250)).until(() -> {
            ExtractableResponse<Response> described = RestAssuredJsonUtils.awsAction("RedshiftData", "DescribeStatement",
                    "{\"Id\":\"" + id + "\"}").then().statusCode(200).extract();
            String status = described.path("Status");
            assertTrue(!"FAILED".equals(status) && !"ABORTED".equals(status),
                    () -> "statement " + id + " ended " + status + ": " + described.path("Error"));
            return "FINISHED".equals(status);
        });
    }

    private static int asInt(Object value) {
        return ((Number) value).intValue();
    }

    @Test
    void sdkSessionPreservesPostgresTemporaryTablesAcrossExecuteAndBatch() {
        try (RedshiftDataClient data = RedshiftDataClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build()) {
            ExecuteStatementResponse created = data.executeStatement(request -> request.clusterIdentifier(clusterId)
                    .dbUser("admin").database("dev").sessionKeepAliveSeconds(60)
                    .sql("CREATE TEMP TABLE session_rows(id integer)"));
            assertNotNull(created.sessionId());
            awaitFinished(created.id());
            String session = created.sessionId();
            awaitFinished(data.batchExecuteStatement(request -> request.sessionId(session)
                    .sqls("INSERT INTO session_rows VALUES (41)", "INSERT INTO session_rows VALUES (42)")).id());
            awaitFinished(data.executeStatement(request -> request.sessionId(session).sql("VACUUM")).id());
            awaitFinished(data.executeStatement(request -> request.sessionId(session).sql("BEGIN")).id());
            awaitFinished(data.executeStatement(request -> request.sessionId(session).sql("INSERT INTO session_rows VALUES (99)")).id());
            awaitFinished(data.executeStatement(request -> request.sessionId(session).sql("ROLLBACK")).id());
            String query = data.executeStatement(request -> request.sessionId(session).sql("SELECT id FROM session_rows ORDER BY id")).id();
            awaitFinished(query);
            assertEquals(session, data.describeStatement(request -> request.id(query)).sessionId());
            assertEquals(2L, data.getStatementResult(request -> request.id(query)).totalNumRows());
            assertEquals(41L, data.getStatementResult(request -> request.id(query)).records().getFirst().getFirst().longValue());
            String last = data.executeStatement(request -> request.sessionId(session).sessionKeepAliveSeconds(0).sql("SELECT 1")).id();
            awaitFinished(last);
            assertThrows(ResourceNotFoundException.class,
                    () -> data.executeStatement(request -> request.sessionId(session).sql("SELECT 1")));
        }
    }

    @Test
    void sdkExecuteStatementPublishesUserActivityToCloudWatchLogs() {
        String parameterGroup = "it-rs-audit-" + clusterId;
        redshift.createClusterParameterGroup(parameterGroup, "redshift-1.0", "Audit logging integration test");
        redshift.modifyClusterParameterGroup(parameterGroup,
                List.of(new Parameter("enable_user_activity_logging", "true")));
        redshift.modifyCluster(clusterId, null, null, null, parameterGroup, null);
        redshift.enableLogging(clusterId, null, null, "cloudwatch", List.of("useractivitylog"));
        String group = "/aws/redshift/cluster/" + clusterId + "/useractivitylog";

        try (RedshiftDataClient data = RedshiftDataClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build();
             CloudWatchLogsClient logs = CloudWatchLogsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                     .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build()) {
            String statementId = data.executeStatement(request -> request.clusterIdentifier(clusterId)
                    .dbUser("admin").database("dev").sql("SELECT 741")).id();
            awaitFinished(statementId);

            Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100)).until(() ->
                    logs.getLogEvents(GetLogEventsRequest.builder().logGroupName(group).logStreamName(clusterId).build())
                            .events().stream().anyMatch(event -> event.message().contains("LOG: SELECT 741")));
        } finally {
            redshift.disableLogging(clusterId);
            redshift.modifyCluster(clusterId, null, null, null,
                    "default.redshift-1.0", null);
            redshift.deleteClusterParameterGroup(parameterGroup);
        }
    }

    @Test
    void sdkBatchSavepointRollbackOmitsTheRolledBackUserFromCloudWatchLogs() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String kept = "audit_kept_" + suffix;
        String discarded = "audit_discarded_" + suffix;
        String group = "/aws/redshift/cluster/" + clusterId + "/userlog";
        redshift.enableLogging(clusterId, null, null, "cloudwatch", List.of("userlog"));
        try (RedshiftDataClient data = RedshiftDataClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build();
             CloudWatchLogsClient logs = CloudWatchLogsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                     .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build()) {
            String batch = data.batchExecuteStatement(request -> request.clusterIdentifier(clusterId)
                    .dbUser("admin").database("dev").sqls("CREATE USER " + kept + " PASSWORD 'Secret123'",
                            "SAVEPOINT s; CREATE USER " + discarded + " PASSWORD 'Secret123'; ROLLBACK TO s")).id();
            awaitFinished(batch);
            String query = data.executeStatement(request -> request.clusterIdentifier(clusterId).dbUser("admin")
                    .database("dev").sql("SELECT COUNT(*) FROM pg_user WHERE usename = '" + discarded + "'")).id();
            awaitFinished(query);
            assertEquals(0L, data.getStatementResult(request -> request.id(query)).records().getFirst().getFirst().longValue());
            List<String> messages = logs.getLogEvents(GetLogEventsRequest.builder().logGroupName(group)
                    .logStreamName(clusterId).build()).events().stream().map(event -> event.message()).toList();
            assertTrue(messages.stream().anyMatch(message -> message.contains("," + kept + ",,Create,")));
            assertFalse(messages.stream().anyMatch(message -> message.contains("," + discarded + ",")));
        } finally {
            redshift.disableLogging(clusterId);
            executeAndWait("DROP USER IF EXISTS " + kept);
            executeAndWait("DROP USER IF EXISTS " + discarded);
        }
    }

    @Test
    void vacuumPreservesAutoCommitExecution() {
        executeAndWait("VACUUM");
    }

    @Test
    void executeDescribeGetResultRoundTrip() {
        executeAndWait("CREATE TABLE t (id int, name varchar(20))");
        String insertId = executeAndWait("INSERT INTO t VALUES (1, 'a'), (2, 'b')");
        Object insertRows = RestAssuredJsonUtils.awsAction("RedshiftData", "DescribeStatement",
                "{\"Id\":\"" + insertId + "\"}").then().statusCode(200).extract().path("ResultRows");
        assertEquals(2, asInt(insertRows));

        String selectId = executeAndWait("SELECT id, name FROM t ORDER BY id");
        ExtractableResponse<Response> result = RestAssuredJsonUtils.awsAction("RedshiftData", "GetStatementResult",
                "{\"Id\":\"" + selectId + "\"}").then().statusCode(200).extract();
        assertEquals(2, asInt(result.path("TotalNumRows")));
        assertEquals("id", result.path("ColumnMetadata[0].name"));
        assertEquals(1, asInt(result.path("Records[0][0].longValue")));
        assertEquals("a", result.path("Records[0][1].stringValue"));
    }

    @Test
    void parameterisedStatement() {
        executeAndWait("CREATE TABLE p (id int)");
        executeAndWait("INSERT INTO p VALUES (1), (2), (3)");

        String id = RestAssuredJsonUtils.awsAction("RedshiftData", "ExecuteStatement", """
                {"Sql": "SELECT id FROM p WHERE id = :id",
                 "ClusterIdentifier": "%s", "DbUser": "admin", "Database": "dev",
                 "Parameters": [{"name": "id", "value": "2"}]}
                """.formatted(clusterId)).then().statusCode(200).extract().path("Id");
        awaitFinished(id);

        Object rows = RestAssuredJsonUtils.awsAction("RedshiftData", "GetStatementResult",
                "{\"Id\":\"" + id + "\"}").then().statusCode(200).extract().path("TotalNumRows");
        assertEquals(1, asInt(rows));
    }

    @Test
    void batchExecuteReportsSubStatements() {
        executeAndWait("CREATE TABLE bt (id int)");

        String id = RestAssuredJsonUtils.awsAction("RedshiftData", "BatchExecuteStatement", """
                {"Sqls": ["INSERT INTO bt VALUES (1)", "SELECT count(*) AS c FROM bt"],
                 "ClusterIdentifier": "%s", "DbUser": "admin", "Database": "dev"}
                """.formatted(clusterId)).then().statusCode(200).extract().path("Id");
        awaitFinished(id);

        List<?> subs = RestAssuredJsonUtils.awsAction("RedshiftData", "DescribeStatement",
                "{\"Id\":\"" + id + "\"}").then().statusCode(200).extract().path("SubStatements");
        assertEquals(2, subs.size());

        Object first = RestAssuredJsonUtils.awsAction("RedshiftData", "GetStatementResult",
                "{\"Id\":\"" + id + "\"}").then().statusCode(200)
                .extract().path("Records[0][0].longValue");
        assertEquals(1, asInt(first));
    }

    @Test
    void batchRollsBackEveryStatementWhenOneFails() {
        executeAndWait("CREATE TABLE br (id int)");

        String id = RestAssuredJsonUtils.awsAction("RedshiftData", "BatchExecuteStatement", """
                {"Sqls": ["INSERT INTO br VALUES (1)", "INSERT INTO br (nope) VALUES (2)"],
                 "ClusterIdentifier": "%s", "DbUser": "admin", "Database": "dev"}
                """.formatted(clusterId)).then().statusCode(200).extract().path("Id");
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(250)).until(() ->
                "FAILED".equals(RestAssuredJsonUtils.awsAction("RedshiftData", "DescribeStatement",
                        "{\"Id\":\"" + id + "\"}").then().statusCode(200).extract().path("Status")));

        // The first INSERT must have been rolled back with the batch: br is empty.
        String countId = executeAndWait("SELECT count(*) AS c FROM br");
        Object count = RestAssuredJsonUtils.awsAction("RedshiftData", "GetStatementResult",
                "{\"Id\":\"" + countId + "\"}").then().statusCode(200).extract().path("Records[0][0].longValue");
        assertEquals(0, asInt(count));
    }

    @Test
    void schemaIntrospection() {
        executeAndWait("CREATE TABLE si (a int, b int)");

        String target = "\"ClusterIdentifier\": \"" + clusterId + "\", \"DbUser\": \"admin\", \"Database\": \"dev\"";
        RestAssuredJsonUtils.awsAction("RedshiftData", "ListDatabases", "{" + target + "}")
                .then().statusCode(200).body("Databases", hasItem("dev"));
        RestAssuredJsonUtils.awsAction("RedshiftData", "ListTables", "{" + target + ", \"TablePattern\": \"si\"}")
                .then().statusCode(200).body("Tables.name", hasItem("si"));
        List<?> columns = RestAssuredJsonUtils.awsAction("RedshiftData", "DescribeTable",
                "{" + target + ", \"Table\": \"si\"}").then().statusCode(200).extract().path("ColumnList");
        assertEquals(2, columns.size());
    }

    @Test
    void workgroupNameIsRejected() {
        RestAssuredJsonUtils.awsAction("RedshiftData", "ExecuteStatement",
                "{\"Sql\": \"SELECT 1\", \"WorkgroupName\": \"wg\", \"Database\": \"dev\"}")
                .then().statusCode(400).body("__type", containsString("ValidationException"));
    }

    @Test
    void executionErrorSurfacesThroughDescribeNotHttp() {
        String id = RestAssuredJsonUtils.awsAction("RedshiftData", "ExecuteStatement", """
                {"Sql": "SELECT * FROM nope", "ClusterIdentifier": "%s", "DbUser": "admin", "Database": "dev"}
                """.formatted(clusterId)).then().statusCode(200).extract().path("Id");
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(250)).until(() ->
                "FAILED".equals(RestAssuredJsonUtils.awsAction("RedshiftData", "DescribeStatement",
                        "{\"Id\":\"" + id + "\"}").then().statusCode(200).extract().path("Status")));
    }

    @Test
    void slowSqlReturnsEarlyAndCanBeCancelled() {
        long started = System.nanoTime();
        String id = RestAssuredJsonUtils.awsAction("RedshiftData", "ExecuteStatement", """
                {"Sql":"SELECT pg_sleep(20)","ClusterIdentifier":"%s","DbUser":"admin","Database":"dev"}
                """.formatted(clusterId)).then().statusCode(200).extract().path("Id");
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(10)) < 0);
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> "STARTED".equals(
                RestAssuredJsonUtils.awsAction("RedshiftData", "DescribeStatement", "{\"Id\":\"" + id + "\"}")
                        .then().statusCode(200).extract().path("Status")));
        RestAssuredJsonUtils.awsAction("RedshiftData", "CancelStatement", "{\"Id\":\"" + id + "\"}")
                .then().statusCode(200).body("Status", is(true));
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> "ABORTED".equals(
                RestAssuredJsonUtils.awsAction("RedshiftData", "DescribeStatement", "{\"Id\":\"" + id + "\"}")
                        .then().statusCode(200).extract().path("Status")));
    }

    @Test
    void cancellingBatchRollsBackEarlierWrites() {
        executeAndWait("CREATE TABLE cancel_batch (id int)");
        String id = RestAssuredJsonUtils.awsAction("RedshiftData", "BatchExecuteStatement", """
                {"Sqls":["INSERT INTO cancel_batch VALUES (1)","SELECT pg_sleep(20)",
                "INSERT INTO cancel_batch VALUES (2)"],"ClusterIdentifier":"%s","DbUser":"admin","Database":"dev"}
                """.formatted(clusterId)).then().statusCode(200).extract().path("Id");
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> {
            List<?> children = RestAssuredJsonUtils.awsAction("RedshiftData", "DescribeStatement", "{\"Id\":\"" + id + "\"}")
                    .then().statusCode(200).extract().path("SubStatements");
            return children != null && !children.isEmpty();
        });
        RestAssuredJsonUtils.awsAction("RedshiftData", "CancelStatement", "{\"Id\":\"" + id + "\"}")
                .then().statusCode(200).body("Status", is(true));
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> "ABORTED".equals(
                RestAssuredJsonUtils.awsAction("RedshiftData", "DescribeStatement", "{\"Id\":\"" + id + "\"}")
                        .then().statusCode(200).extract().path("Status")));
        String countId = executeAndWait("SELECT count(*) FROM cancel_batch");
        Object count = RestAssuredJsonUtils.awsAction("RedshiftData", "GetStatementResult", "{\"Id\":\"" + countId + "\"}")
                .then().statusCode(200).extract().path("Records[0][0].longValue");
        assertEquals(0, asInt(count));
    }
}
