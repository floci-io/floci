package io.github.hectorvent.floci.services.rds.proxy;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.testutil.SigV4TokenTestHelper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Properties;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The parameters a client puts in its PostgreSQL StartupMessage reach the instance through the
 * proxy, as they reach an RDS for PostgreSQL endpoint: {@code options} and
 * {@code application_name} here, for a password login and for an IAM login whose session the
 * proxy hands over to the token's role.
 */
@QuarkusTest
@Tag("docker")
class RdsPostgresStartupParametersIntegrationTest {

    private static final String RDS_AUTH = "AWS4-HMAC-SHA256 Credential=test/20261006/us-east-1/rds/aws4_request";
    private static final String MASTER_USER = "master";
    private static final String MASTER_PASSWORD = "Master123!";

    @Inject
    DockerClient dockerClient;

    @BeforeEach
    void checkDocker() {
        try {
            dockerClient.pingCmd().exec();
        } catch (Exception e) {
            assumeTrue(false, "Docker is not available: " + e.getMessage());
        }
    }

    @Test
    void clientStartupParametersApplyToTheSession() throws Exception {
        String dbId = "startup-params-" + Long.toString(System.nanoTime(), 36);
        int port = rds("CreateDBInstance")
                .formParam("DBInstanceIdentifier", dbId)
                .formParam("Engine", "postgres")
                .formParam("MasterUsername", MASTER_USER)
                .formParam("MasterUserPassword", MASTER_PASSWORD)
                .formParam("DBName", "appdb")
                .formParam("AllocatedStorage", "20")
                .formParam("DBInstanceClass", "db.t3.micro")
        .when().post("/").then().statusCode(200)
                .extract().xmlPath()
                .getInt("CreateDBInstanceResponse.CreateDBInstanceResult.DBInstance.Endpoint.Port");
        try {
            Properties properties = new Properties();
            properties.setProperty("user", MASTER_USER);
            properties.setProperty("password", MASTER_PASSWORD);
            properties.setProperty("sslmode", "disable");
            properties.setProperty("options", "-c search_path=floci_opts -c work_mem=64MB");
            properties.setProperty("ApplicationName", "floci-startup-params");

            try (Connection connection = DriverManager.getConnection(
                    "jdbc:postgresql://127.0.0.1:" + port + "/appdb", properties)) {
                assertThat(show(connection, "search_path"), equalTo("floci_opts"));
                assertThat(show(connection, "work_mem"), equalTo("64MB"));
                assertThat(show(connection, "application_name"), equalTo("floci-startup-params"));
            }
        } finally {
            rds("DeleteDBInstance")
                    .formParam("DBInstanceIdentifier", dbId)
                    .formParam("SkipFinalSnapshot", "true")
            .when().post("/");
        }
    }

    @Test
    void clientStartupParametersApplyToAnIamSessionAsTheTokenRole() throws Exception {
        String dbId = "startup-params-iam-" + Long.toString(System.nanoTime(), 36);
        int port = createIamInstance(dbId);
        try {
            asMaster(port,
                    "CREATE ROLE app_user LOGIN",
                    "CREATE SCHEMA floci_opts AUTHORIZATION app_user",
                    // A database that refuses \' in string literals must still accept the parameters.
                    "ALTER DATABASE appdb SET backslash_quote = off");

            Properties iam = iamLogin(port, "app_user");
            iam.setProperty("options", "-c search_path=floci_opts -c work_mem=64MB");
            iam.setProperty("ApplicationName", "floci's startup params");

            try (Connection connection = DriverManager.getConnection(jdbcUrl(port), iam)) {
                assertThat(show(connection, "search_path"), equalTo("floci_opts"));
                assertThat(show(connection, "work_mem"), equalTo("64MB"));
                assertThat(show(connection, "application_name"), equalTo("floci's startup params"));
                assertThat(show(connection, "session_authorization"), equalTo("app_user"));
            }
        } finally {
            deleteInstance(dbId);
        }
    }

    @Test
    void anIamSessionCannotTakeTheMasterIdentityThroughStartupParameters() throws Exception {
        String dbId = "startup-params-iam-escalation-" + Long.toString(System.nanoTime(), 36);
        int port = createIamInstance(dbId);
        try {
            asMaster(port, "CREATE ROLE app_user LOGIN");

            for (String options : new String[] {
                    "-c session_authorization=" + MASTER_USER,
                    "-c session_replication_role=replica --Session-Authorization=" + MASTER_USER,
                    "-c role=" + MASTER_USER}) {
                Properties iam = iamLogin(port, "app_user");
                iam.setProperty("options", options);
                SQLException refused = assertThrows(SQLException.class,
                        () -> DriverManager.getConnection(jdbcUrl(port), iam).close(), options);
                assertThat(options, refused.getSQLState(), equalTo("42501"));
            }
        } finally {
            deleteInstance(dbId);
        }
    }

    /** Creates a PostgreSQL instance with IAM database authentication and returns its proxy port. */
    private static int createIamInstance(String dbId) {
        return rds("CreateDBInstance")
                .formParam("DBInstanceIdentifier", dbId)
                .formParam("Engine", "postgres")
                .formParam("MasterUsername", MASTER_USER)
                .formParam("MasterUserPassword", MASTER_PASSWORD)
                .formParam("DBName", "appdb")
                .formParam("AllocatedStorage", "20")
                .formParam("DBInstanceClass", "db.t3.micro")
                .formParam("EnableIAMDatabaseAuthentication", "true")
        .when().post("/").then().statusCode(200)
                .extract().xmlPath()
                .getInt("CreateDBInstanceResponse.CreateDBInstanceResult.DBInstance.Endpoint.Port");
    }

    private static void deleteInstance(String dbId) {
        rds("DeleteDBInstance")
                .formParam("DBInstanceIdentifier", dbId)
                .formParam("SkipFinalSnapshot", "true")
        .when().post("/");
    }

    /** Runs {@code statements} in order as the master user. */
    private static void asMaster(int port, String... statements) throws SQLException {
        Properties master = new Properties();
        master.setProperty("user", MASTER_USER);
        master.setProperty("password", MASTER_PASSWORD);
        master.setProperty("sslmode", "disable");
        try (Connection connection = DriverManager.getConnection(jdbcUrl(port), master);
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private static String jdbcUrl(int port) {
        return "jdbc:postgresql://localhost:" + port + "/appdb";
    }

    private static Properties iamLogin(int port, String user) throws Exception {
        Properties iam = new Properties();
        iam.setProperty("user", user);
        iam.setProperty("password", SigV4TokenTestHelper.createRdsToken("localhost", port, user,
                "test", "test", Instant.now(), 900));
        iam.setProperty("sslmode", "disable");
        return iam;
    }

    private static String show(Connection connection, String parameter) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SHOW " + parameter)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static RequestSpecification rds(String action) {
        return given().header("Authorization", RDS_AUTH)
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }
}
