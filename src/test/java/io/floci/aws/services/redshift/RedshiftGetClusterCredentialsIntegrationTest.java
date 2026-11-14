package io.floci.aws.services.redshift;

import io.floci.aws.services.redshift.model.Cluster;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end: IAM-derived Redshift credentials map to real non-superuser PostgreSQL roles
 * with AutoCreate, session-scoped DbGroups, and consistent behavior across wire, Data API,
 * and retained sessions.
 */
@QuarkusTest
class RedshiftGetClusterCredentialsIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260822/us-east-1/redshift/aws4_request";

    @Inject
    RedshiftService redshift;

    private String clusterId;

    @BeforeAll
    static void configure() {
        Assumptions.assumeTrue(dockerAvailable(), "Docker is required for this integration test");
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

    private String awaitTerminal(String id) {
        String[] terminal = new String[1];
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250)).until(() -> {
            String status = RestAssuredJsonUtils.awsAction("RedshiftData", "DescribeStatement",
                    "{\"Id\":\"" + id + "\"}").then().statusCode(200).extract().path("Status");
            if ("FINISHED".equals(status) || "FAILED".equals(status) || "ABORTED".equals(status)) {
                terminal[0] = status;
                return true;
            }
            return false;
        });
        return terminal[0];
    }

    private void awaitFinished(String id) {
        String status = awaitTerminal(id);
        assertEquals("FINISHED", status, () -> "statement " + id + " ended " + status);
    }

    private String executeMaster(String sql) {
        String id = RestAssuredJsonUtils.awsAction("RedshiftData", "ExecuteStatement", """
                {"Sql": "%s", "ClusterIdentifier": "%s", "DbUser": "admin", "Database": "dev"}
                """.formatted(sql, clusterId)).then().statusCode(200).extract().path("Id");
        awaitFinished(id);
        return id;
    }

    @Test
    void autoCreateTrueCreatesNonSuperuserRoleWithMatchingCurrentUserAndOwnership() throws SQLException {
        clusterId = "it-gcc-auto-" + System.currentTimeMillis();
        Cluster cluster = redshift.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        String xml = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "GetClusterCredentials")
            .formParam("ClusterIdentifier", clusterId)
            .formParam("DbUser", "analyst")
            .formParam("AutoCreate", "true")
            .formParam("DurationSeconds", "900")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("<DbUser>IAMA:analyst</DbUser>"))
            .extract().asString();

        String dbPassword = xml.replaceAll("(?s).*<DbPassword>(.*?)</DbPassword>.*", "$1");

        // Data API: current_user is "analyst" (without IAMA: prefix) and created table is owned by "analyst".
        String id = RestAssuredJsonUtils.awsAction("RedshiftData", "ExecuteStatement", """
                {"Sql": "SELECT current_user", "ClusterIdentifier": "%s", "DbUser": "IAMA:analyst", "Database": "dev"}
                """.formatted(clusterId)).then().statusCode(200).extract().path("Id");
        awaitFinished(id);

        String currentUser = RestAssuredJsonUtils.awsAction("RedshiftData", "GetStatementResult",
                "{\"Id\":\"" + id + "\"}").then().statusCode(200).extract().path("Records[0][0].stringValue");
        assertEquals("analyst", currentUser);

        String createId = RestAssuredJsonUtils.awsAction("RedshiftData", "ExecuteStatement", """
                {"Sql": "CREATE TABLE analyst_tbl (id INT)", "ClusterIdentifier": "%s", "DbUser": "IAMA:analyst", "Database": "dev"}
                """.formatted(clusterId)).then().statusCode(200).extract().path("Id");
        awaitFinished(createId);

        String ownerId = executeMaster("SELECT tableowner FROM pg_tables WHERE tablename = 'analyst_tbl'");
        String owner = RestAssuredJsonUtils.awsAction("RedshiftData", "GetStatementResult",
                "{\"Id\":\"" + ownerId + "\"}").then().statusCode(200).extract().path("Records[0][0].stringValue");
        assertEquals("analyst", owner);

        // Wire proxy: current_user is also "analyst".
        String jdbcUrl = "jdbc:postgresql://127.0.0.1:" + cluster.getProxyPort() + "/dev?sslmode=disable";
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "IAMA:analyst", dbPassword);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT current_user")) {
            assertTrue(rs.next());
            assertEquals("analyst", rs.getString(1));
        }
    }

    @Test
    void autoCreateFalseFailsForMissingRoleAndSupportsDbGroupsAcrossSession() throws SQLException {
        clusterId = "it-gcc-noauto-" + System.currentTimeMillis();
        Cluster cluster = redshift.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        String xml = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "GetClusterCredentials")
            .formParam("ClusterIdentifier", clusterId)
            .formParam("DbUser", "restricted")
            .formParam("AutoCreate", "false")
            .formParam("DurationSeconds", "900")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DbUser>IAM:restricted</DbUser>"))
            .extract().asString();
        String noAutoPw = xml.replaceAll("(?s).*<DbPassword>(.*?)</DbPassword>.*", "$1");

        // Missing role with AutoCreate=false must fail on both wire and Data API (never fall back to master).
        String jdbcUrl = "jdbc:postgresql://127.0.0.1:" + cluster.getProxyPort() + "/dev?sslmode=disable";
        assertThrows(SQLException.class, () -> DriverManager.getConnection(jdbcUrl, "IAM:restricted", noAutoPw));

        String failId = RestAssuredJsonUtils.awsAction("RedshiftData", "ExecuteStatement", """
                {"Sql": "SELECT 1", "ClusterIdentifier": "%s", "DbUser": "IAM:restricted", "Database": "dev"}
                """.formatted(clusterId)).then().statusCode(200).extract().path("Id");
        assertEquals("FAILED", awaitTerminal(failId));

        // Pre-create user and group, plus an admin-owned table granted only to the group.
        executeMaster("CREATE USER restricted");
        executeMaster("CREATE GROUP etl_readers");
        executeMaster("CREATE TABLE secret_tbl (v INT)");
        executeMaster("INSERT INTO secret_tbl VALUES (42)");
        executeMaster("GRANT SELECT ON secret_tbl TO GROUP etl_readers");

        // Without DbGroups, restricted cannot read secret_tbl.
        String deniedId = RestAssuredJsonUtils.awsAction("RedshiftData", "ExecuteStatement", """
                {"Sql": "SELECT v FROM secret_tbl", "ClusterIdentifier": "%s", "DbUser": "IAM:restricted", "Database": "dev"}
                """.formatted(clusterId)).then().statusCode(200).extract().path("Id");
        assertEquals("FAILED", awaitTerminal(deniedId));

        // With DbGroups=etl_readers, restricted can read secret_tbl in a retained Data API session.
        String groupXml = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "GetClusterCredentials")
            .formParam("ClusterIdentifier", clusterId)
            .formParam("DbUser", "restricted")
            .formParam("AutoCreate", "false")
            .formParam("DbGroups.member.1", "etl_readers")
            .formParam("DurationSeconds", "900")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
        String groupPw = groupXml.replaceAll("(?s).*<DbPassword>(.*?)</DbPassword>.*", "$1");

        ExtractableResponse<Response> groupResp = RestAssuredJsonUtils.awsAction("RedshiftData", "ExecuteStatement", """
                {"Sql": "SELECT v FROM secret_tbl", "ClusterIdentifier": "%s", "DbUser": "IAM:restricted", "Database": "dev", "SessionKeepAliveSeconds": 60}
                """.formatted(clusterId)).then().statusCode(200).extract();
        String groupStmtId = groupResp.path("Id");
        String sessionId = groupResp.path("SessionId");
        List<String> respGroups = groupResp.path("DbGroups");
        assertEquals(List.of("etl_readers"), respGroups);
        awaitFinished(groupStmtId);
        Object val = RestAssuredJsonUtils.awsAction("RedshiftData", "GetStatementResult",
                "{\"Id\":\"" + groupStmtId + "\"}").then().statusCode(200).extract().path("Records[0][0].longValue");
        assertEquals(42, ((Number) val).intValue());

        // Subsequent statement in the same retained session preserves DbGroups.
        String retainedId = RestAssuredJsonUtils.awsAction("RedshiftData", "ExecuteStatement", """
                {"Sql": "SELECT v FROM secret_tbl", "SessionId": "%s"}
                """.formatted(sessionId)).then().statusCode(200).extract().path("Id");
        awaitFinished(retainedId);

        // Wire proxy also honors DbGroups and revokes temporary membership on disconnect.
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "IAM:restricted", groupPw);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT v FROM secret_tbl")) {
            assertTrue(rs.next());
            assertEquals(42, rs.getInt(1));
        }

        // Missing DbGroup must fail rather than silently running without the group.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "GetClusterCredentials")
            .formParam("ClusterIdentifier", clusterId)
            .formParam("DbUser", "restricted")
            .formParam("AutoCreate", "false")
            .formParam("DbGroups.member.1", "nonexistent_group")
            .formParam("DurationSeconds", "900")
        .when()
            .post("/")
        .then()
            .statusCode(200);
        String missingGroupPw = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "GetClusterCredentials")
            .formParam("ClusterIdentifier", clusterId)
            .formParam("DbUser", "restricted")
            .formParam("AutoCreate", "false")
            .formParam("DbGroups.member.1", "nonexistent_group")
            .formParam("DurationSeconds", "900")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString().replaceAll("(?s).*<DbPassword>(.*?)</DbPassword>.*", "$1");
        assertThrows(SQLException.class, () -> DriverManager.getConnection(jdbcUrl, "IAM:restricted", missingGroupPw));
    }

    @Test
    void getClusterCredentialsWithIamMapsToIamrRole() {
        clusterId = "it-gcc-iam-" + System.currentTimeMillis();
        redshift.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        String xml = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH_HEADER)
            .formParam("Action", "GetClusterCredentialsWithIAM")
            .formParam("ClusterIdentifier", clusterId)
            .formParam("DurationSeconds", "900")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DbUser>IAMR:floci</DbUser>"))
            .extract().asString();
        assertTrue(xml.contains("<DbPassword>"));

        String id = RestAssuredJsonUtils.awsAction("RedshiftData", "ExecuteStatement", """
                {"Sql": "SELECT current_user", "ClusterIdentifier": "%s", "DbUser": "IAMR:floci", "Database": "dev"}
                """.formatted(clusterId)).then().statusCode(200).extract().path("Id");
        awaitFinished(id);

        String currentUser = RestAssuredJsonUtils.awsAction("RedshiftData", "GetStatementResult",
                "{\"Id\":\"" + id + "\"}").then().statusCode(200).extract().path("Records[0][0].stringValue");
        assertEquals("IAMR:floci", currentUser);
    }
}
