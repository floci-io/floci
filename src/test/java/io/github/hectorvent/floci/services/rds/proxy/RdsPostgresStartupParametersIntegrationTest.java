package io.github.hectorvent.floci.services.rds.proxy;

import com.github.dockerjava.api.DockerClient;
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
import java.util.Properties;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The parameters a client puts in its PostgreSQL StartupMessage reach the instance through the
 * proxy, as they reach an RDS for PostgreSQL endpoint: {@code options} and
 * {@code application_name} here.
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
