package io.github.hectorvent.floci.services.redshiftserverless;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
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

import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs a real workgroup: the PostgreSQL container, the auth proxy on the advertised endpoint, the
 * Redshift Data API addressed by {@code WorkgroupName}, and {@code GetCredentials}. Needs Docker.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedshiftServerlessRuntimeIntegrationTest {

    private static final String NAMESPACE = "rt-it-ns";
    private static final String WORKGROUP = "rt-it-wg";
    private static final String ADMIN_PASSWORD = "Secret123";

    private static final String REGION = "us-east-1";

    @Inject
    RedshiftServerlessService service;

    private int endpointPort;
    private String workgroupArn;
    private boolean created;

    /**
     * Runs before each test but creates the workgroup once: Quarkus points RestAssured at the test
     * server in its own per-test callback, so a {@code @BeforeAll} would still see the default port.
     */
    @BeforeEach
    void ensureWorkgroup() {
        Assumptions.assumeTrue(dockerAvailable(), "Docker is required for Redshift Serverless runtime tests");
        RestAssuredJsonUtils.configureAwsContentTypes();
        if (created) {
            return;
        }
        RestAssuredJsonUtils.awsAction("RedshiftServerless", "CreateNamespace", """
                {"namespaceName":"%s","adminUsername":"admin","adminUserPassword":"%s","dbName":"dev"}
                """.formatted(NAMESPACE, ADMIN_PASSWORD)).then().statusCode(200);
        created = true;
        ExtractableResponse<Response> workgroup = RestAssuredJsonUtils.awsAction("RedshiftServerless", "CreateWorkgroup", """
                {"workgroupName":"%s","namespaceName":"%s"}
                """.formatted(WORKGROUP, NAMESPACE)).then().statusCode(200).extract();
        endpointPort = workgroup.path("workgroup.endpoint.port");
        workgroupArn = workgroup.path("workgroup.workgroupArn");
    }

    /** Through the service: Quarkus has already pointed RestAssured away from the test server by now. */
    @AfterAll
    void deleteWorkgroup() {
        if (created) {
            service.deleteWorkgroup(WORKGROUP, REGION);
            service.deleteNamespace(NAMESPACE, REGION);
        }
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

    private String jdbcUrl() {
        // 127.0.0.1 rather than the advertised address: CI sets floci.emulator.hostname to a name
        // that does not resolve from the test JVM.
        return "jdbc:postgresql://127.0.0.1:" + endpointPort + "/dev";
    }

    private Connection connect(String user, String password) throws SQLException {
        return Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(500))
                .ignoreExceptions()
                .until(() -> DriverManager.getConnection(jdbcUrl(), user, password), Objects::nonNull);
    }

    private String executeAndWait(String sql) {
        String id = RestAssuredJsonUtils.awsAction("RedshiftData", "ExecuteStatement", """
                {"Sql": "%s", "WorkgroupName": "%s", "Database": "dev"}
                """.formatted(sql, WORKGROUP)).then().statusCode(200)
                .extract().path("Id");
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(250)).until(() -> {
                    String status = RestAssuredJsonUtils.awsAction("RedshiftData", "DescribeStatement",
                            "{\"Id\":\"" + id + "\"}").then().statusCode(200).extract().path("Status");
                    assertFalse("FAILED".equals(status) || "ABORTED".equals(status),
                            () -> "statement " + id + " ended " + status);
                    return "FINISHED".equals(status);
                });
        return id;
    }

    @Test
    void theAdvertisedEndpointAcceptsTheNamespaceAdminAndRefusesAWrongPassword() throws SQLException {
        try (Connection connection = connect("admin", ADMIN_PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("select 1")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }

        assertThrows(SQLException.class, () -> DriverManager.getConnection(jdbcUrl(), "admin", "wrong-password"));
    }

    @Test
    void theDataApiRunsStatementsAgainstTheWorkgroupByNameAndByArn() throws SQLException {
        String createId = executeAndWait("CREATE TABLE sl_people (id int, name varchar(20))");
        executeAndWait("INSERT INTO sl_people VALUES (1, 'a'), (2, 'b')");
        String selectId = executeAndWait("SELECT id, name FROM sl_people ORDER BY id");

        ExtractableResponse<Response> described = RestAssuredJsonUtils.awsAction("RedshiftData", "DescribeStatement",
                "{\"Id\":\"" + selectId + "\"}").then().statusCode(200).extract();
        assertEquals(WORKGROUP, described.path("WorkgroupName"));
        assertNull(described.path("ClusterIdentifier"));
        assertNotNull(createId);

        ExtractableResponse<Response> result = RestAssuredJsonUtils.awsAction("RedshiftData", "GetStatementResult",
                "{\"Id\":\"" + selectId + "\"}").then().statusCode(200).extract();
        assertEquals(2, ((Number) result.path("TotalNumRows")).intValue());
        assertEquals("a", result.path("Records[0][1].stringValue"));

        // The same table is visible through the endpoint, and the workgroup ARN addresses the workgroup too.
        try (Connection connection = connect("admin", ADMIN_PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select count(*) from sl_people")) {
            assertTrue(rows.next());
            assertEquals(2, rows.getInt(1));
        }
        RestAssuredJsonUtils.awsAction("RedshiftData", "ExecuteStatement", """
                {"Sql": "select 1", "WorkgroupName": "%s", "Database": "dev"}
                """.formatted(workgroupArn)).then().statusCode(200);
    }

    @Test
    void theDataApiRejectsAWrongDatabaseAndBothIdentifiers() {
        RestAssuredJsonUtils.awsAction("RedshiftData", "ExecuteStatement", """
                {"Sql": "select 1", "WorkgroupName": "%s", "Database": "other"}
                """.formatted(WORKGROUP)).then().statusCode(400);
        RestAssuredJsonUtils.awsAction("RedshiftData", "ExecuteStatement", """
                {"Sql": "select 1", "WorkgroupName": "%s", "ClusterIdentifier": "wh", "Database": "dev"}
                """.formatted(WORKGROUP)).then().statusCode(400);
    }

    @Test
    void getCredentialsMintsAPasswordTheEndpointAccepts() throws SQLException {
        ExtractableResponse<Response> credentials = RestAssuredJsonUtils.awsAction("RedshiftServerless", "GetCredentials",
                "{\"workgroupName\":\"" + WORKGROUP + "\",\"dbName\":\"dev\"}").then().statusCode(200).extract();
        String dbUser = credentials.path("dbUser");
        String dbPassword = credentials.path("dbPassword");
        assertNotNull(dbUser);
        assertNotNull(dbPassword);

        try (Connection connection = connect(dbUser, dbPassword)) {
            assertTrue(connection.isValid(5));
        }
        assertThrows(SQLException.class, () -> DriverManager.getConnection(jdbcUrl(), dbUser, "stale-password"));
    }

    @Test
    void anAdminPasswordChangeAppliesToNewConnections() throws SQLException {
        RestAssuredJsonUtils.awsAction("RedshiftServerless", "UpdateNamespace",
                "{\"namespaceName\":\"" + NAMESPACE + "\",\"adminUserPassword\":\"Rotated123\"}")
                .then().statusCode(200);
        try {
            assertThrows(SQLException.class, () -> DriverManager.getConnection(jdbcUrl(), "admin", ADMIN_PASSWORD));
            try (Connection connection = connect("admin", "Rotated123")) {
                assertTrue(connection.isValid(5));
            }
        } finally {
            RestAssuredJsonUtils.awsAction("RedshiftServerless", "UpdateNamespace",
                    "{\"namespaceName\":\"" + NAMESPACE + "\",\"adminUserPassword\":\"" + ADMIN_PASSWORD + "\"}");
        }
    }

    @Test
    void deletingTheWorkgroupClosesItsEndpoint() {
        String namespace = "rt-it-del-ns";
        String workgroup = "rt-it-del-wg";
        RestAssuredJsonUtils.awsAction("RedshiftServerless", "CreateNamespace", """
                {"namespaceName":"%s","adminUsername":"admin","adminUserPassword":"%s"}
                """.formatted(namespace, ADMIN_PASSWORD)).then().statusCode(200);
        int port = RestAssuredJsonUtils.awsAction("RedshiftServerless", "CreateWorkgroup", """
                {"workgroupName":"%s","namespaceName":"%s"}
                """.formatted(workgroup, namespace)).then().statusCode(200).extract().path("workgroup.endpoint.port");
        try {
            assertTrue(isListening(port), "the endpoint must be live once the workgroup is created");
        } finally {
            RestAssuredJsonUtils.awsAction("RedshiftServerless", "DeleteWorkgroup",
                    "{\"workgroupName\":\"" + workgroup + "\"}").then().statusCode(200);
            RestAssuredJsonUtils.awsAction("RedshiftServerless", "DeleteNamespace",
                    "{\"namespaceName\":\"" + namespace + "\"}").then().statusCode(200);
        }
        assertFalse(isListening(port), "the endpoint must be closed once the workgroup is deleted");
    }

    private static boolean isListening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
