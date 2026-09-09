package io.github.hectorvent.floci.services.redshiftdata;

import io.github.hectorvent.floci.services.redshift.RedshiftService;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class RedshiftDataApiIntegrationTest {

    @Inject
    RedshiftService redshift;

    private String clusterId;

    @BeforeAll
    static void configure() {
        Assumptions.assumeTrue(dockerAvailable(), "Docker is required for Redshift Data API integration tests");
        RestAssuredJsonUtils.configureAwsContentTypes();
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

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            redshift.deleteCluster(clusterId);
            clusterId = null;
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
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250)).until(() -> {
            String status = RestAssuredJsonUtils.awsAction("RedshiftData", "DescribeStatement",
                    "{\"Id\":\"" + id + "\"}").then().statusCode(200).extract().path("Status");
            assertTrue(!"FAILED".equals(status) && !"ABORTED".equals(status),
                    () -> "statement " + id + " ended " + status);
            return "FINISHED".equals(status);
        });
    }

    private static int asInt(Object value) {
        return ((Number) value).intValue();
    }

    @Test
    void executeDescribeGetResultRoundTrip() {
        clusterId = "it-rsdata-roundtrip";
        redshift.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        executeAndWait("CREATE TABLE t (id int, name varchar(20))");
        String insertId = executeAndWait("INSERT INTO t VALUES (1, 'a'), (2, 'b')");
        Object insertRows = RestAssuredJsonUtils.awsAction("RedshiftData", "DescribeStatement",
                "{\"Id\":\"" + insertId + "\"}").then().statusCode(200).extract().path("ResultRows");
        assertEquals(2, asInt(insertRows));

        String selectId = executeAndWait("SELECT id, name FROM t ORDER BY id");
        var result = RestAssuredJsonUtils.awsAction("RedshiftData", "GetStatementResult",
                "{\"Id\":\"" + selectId + "\"}").then().statusCode(200).extract();
        assertEquals(2, asInt(result.path("TotalNumRows")));
        assertEquals("id", result.path("ColumnMetadata[0].name"));
        assertEquals(1, asInt(result.path("Records[0][0].longValue")));
        assertEquals("a", result.path("Records[0][1].stringValue"));
    }

    @Test
    void parameterisedStatement() {
        clusterId = "it-rsdata-param";
        redshift.createCluster(clusterId, "dc2.large", "admin", "Secret123");
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
        clusterId = "it-rsdata-batch";
        redshift.createCluster(clusterId, "dc2.large", "admin", "Secret123");
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
        clusterId = "it-rsdata-batch-rollback";
        redshift.createCluster(clusterId, "dc2.large", "admin", "Secret123");
        executeAndWait("CREATE TABLE br (id int)");

        String id = RestAssuredJsonUtils.awsAction("RedshiftData", "BatchExecuteStatement", """
                {"Sqls": ["INSERT INTO br VALUES (1)", "INSERT INTO br (nope) VALUES (2)"],
                 "ClusterIdentifier": "%s", "DbUser": "admin", "Database": "dev"}
                """.formatted(clusterId)).then().statusCode(200).extract().path("Id");
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250)).until(() ->
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
        clusterId = "it-rsdata-schema";
        redshift.createCluster(clusterId, "dc2.large", "admin", "Secret123");
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
        clusterId = "it-rsdata-err";
        redshift.createCluster(clusterId, "dc2.large", "admin", "Secret123");
        String id = RestAssuredJsonUtils.awsAction("RedshiftData", "ExecuteStatement", """
                {"Sql": "SELECT * FROM nope", "ClusterIdentifier": "%s", "DbUser": "admin", "Database": "dev"}
                """.formatted(clusterId)).then().statusCode(200).extract().path("Id");
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250)).until(() ->
                "FAILED".equals(RestAssuredJsonUtils.awsAction("RedshiftData", "DescribeStatement",
                        "{\"Id\":\"" + id + "\"}").then().statusCode(200).extract().path("Status")));
    }
}
