package io.floci.aws.services.redshiftserverless;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The audit logs of a running workgroup reach the CloudWatch Logs log groups of its namespace, for connections
 * through the wire proxy and for Data API statements, and stop when the namespace stops exporting them. Needs Docker.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedshiftServerlessAuditLogIntegrationTest {

    private static final String NAMESPACE = "audit-it-ns";
    private static final String WORKGROUP = "audit-it-wg";
    private static final String ADMIN_PASSWORD = "Secret123";
    private static final String REGION = "us-east-1";
    private static final String GROUP_PREFIX = "/aws/redshift/" + NAMESPACE + "/";
    private static final String STREAM = "serverless";

    @Inject
    RedshiftServerlessService service;

    private int endpointPort;
    private boolean created;

    /** Creates the workgroup once, as {@code RedshiftServerlessRuntimeIntegrationTest} does. */
    @BeforeEach
    void ensureWorkgroup() {
        Assumptions.assumeTrue(dockerAvailable(), "Docker is required for Redshift Serverless audit log tests");
        RestAssuredJsonUtils.configureAwsContentTypes();
        if (created) {
            return;
        }
        RestAssuredJsonUtils.awsAction("RedshiftServerless", "CreateNamespace", """
                {"namespaceName":"%s","adminUsername":"admin","adminUserPassword":"%s","dbName":"dev",
                 "logExports":["connectionlog","userlog","useractivitylog"]}
                """.formatted(NAMESPACE, ADMIN_PASSWORD)).then().statusCode(200);
        created = true;
        ExtractableResponse<Response> workgroup = RestAssuredJsonUtils.awsAction("RedshiftServerless",
                "CreateWorkgroup", """
                {"workgroupName":"%s","namespaceName":"%s",
                 "configParameters":[{"parameterKey":"enable_user_activity_logging","parameterValue":"true"}]}
                """.formatted(WORKGROUP, NAMESPACE)).then().statusCode(200).extract();
        endpointPort = workgroup.path("workgroup.endpoint.port");
    }

    @AfterAll
    void deleteWorkgroup() {
        if (created) {
            service.deleteWorkgroup(WORKGROUP, REGION);
            service.deleteNamespace(NAMESPACE, REGION);
        }
    }

    private static boolean dockerAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private Connection connect() throws SQLException {
        // 127.0.0.1 rather than the advertised address: CI sets floci.emulator.hostname to a name that does not
        // resolve from the test JVM.
        String url = "jdbc:postgresql://127.0.0.1:" + endpointPort + "/dev";
        return Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(500))
                .ignoreExceptions()
                .until(() -> DriverManager.getConnection(url, "admin", ADMIN_PASSWORD), Objects::nonNull);
    }

    private void executeOnTheWire(String... statements) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private void executeThroughTheDataApi(String sql) {
        String id = RestAssuredJsonUtils.awsAction("RedshiftData", "ExecuteStatement", """
                {"Sql": "%s", "WorkgroupName": "%s", "Database": "dev"}
                """.formatted(sql, WORKGROUP)).then().statusCode(200).extract().path("Id");
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(250)).until(() -> {
                    String status = RestAssuredJsonUtils.awsAction("RedshiftData", "DescribeStatement",
                            "{\"Id\":\"" + id + "\"}").then().statusCode(200).extract().path("Status");
                    assertFalse("FAILED".equals(status) || "ABORTED".equals(status),
                            () -> "statement " + id + " ended " + status);
                    return "FINISHED".equals(status);
                });
    }

    /** The messages of a log type's log group, or none while the group does not exist yet. */
    private List<String> messages(String logType) {
        Response response = RestAssuredJsonUtils.awsAction("Logs_20140328", "GetLogEvents", """
                {"logGroupName":"%s","logStreamName":"%s","startFromHead":true}
                """.formatted(GROUP_PREFIX + logType, STREAM));
        if (response.statusCode() != 200) {
            return List.of();
        }
        return response.jsonPath().getList("events.message", String.class);
    }

    private void awaitMessage(String logType, String text) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(
                () -> assertTrue(messages(logType).stream().anyMatch(line -> line.contains(text)),
                        () -> logType + " has no record with " + text + ": " + messages(logType)));
    }

    private void exportOnly(String... logTypes) {
        String exports = String.join("\",\"", logTypes);
        RestAssuredJsonUtils.awsAction("RedshiftServerless", "UpdateNamespace", """
                {"namespaceName":"%s","logExports":["%s"]}
                """.formatted(NAMESPACE, exports)).then().statusCode(200);
    }

    @Test
    void wireConnectionsAndStatementsAreExportedToTheNamespaceLogGroups() throws Exception {
        executeOnTheWire("SELECT 'wire_marker_1'", "CREATE USER wire_user PASSWORD 'Hidden123'");

        awaitMessage("connectionlog", "admin");
        awaitMessage("useractivitylog", "wire_marker_1");
        awaitMessage("userlog", "wire_user");
        assertTrue(messages("useractivitylog").stream().noneMatch(line -> line.contains("Hidden123")),
                "a password literal must be redacted: " + messages("useractivitylog"));
    }

    @Test
    void dataApiStatementsAreExportedToTheNamespaceLogGroups() {
        executeThroughTheDataApi("SELECT 'data_api_marker_1'");

        awaitMessage("useractivitylog", "data_api_marker_1");
    }

    @Test
    void aRolledBackUserChangeIsNotLoggedAsCommitted() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("CREATE USER rolled_back_user PASSWORD 'Hidden123'");
            connection.rollback();
            statement.execute("SELECT 'after_rollback_marker'");
            connection.commit();
        }

        awaitMessage("useractivitylog", "after_rollback_marker");
        assertTrue(messages("userlog").stream().noneMatch(line -> line.contains("rolled_back_user")),
                "a rolled back user must not reach the user log: " + messages("userlog"));
    }

    @Test
    void turningAnExportOffStopsItsRecordsAndTurningItOnResumesThem() throws Exception {
        exportOnly("connectionlog", "userlog");
        try {
            executeOnTheWire("SELECT 'while_off_marker'");
            executeThroughTheDataApi("SELECT 'while_off_data_api_marker'");
            awaitMessage("connectionlog", "admin");
            Awaitility.await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertTrue(messages("useractivitylog").stream().noneMatch(line -> line.contains("while_off"))));
        } finally {
            exportOnly("connectionlog", "userlog", "useractivitylog");
        }

        executeOnTheWire("SELECT 'while_on_marker'");
        awaitMessage("useractivitylog", "while_on_marker");
        assertEquals(List.of(), messages("useractivitylog").stream().filter(line -> line.contains("while_off"))
                .toList());
    }
}
