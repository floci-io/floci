package io.github.hectorvent.floci.services.rds;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.services.iam.IamService;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * IAM database authentication from a Lambda function, against a real PostgreSQL container behind
 * the RDS proxy. A function signs its token with its execution role's session credentials, and
 * that session token carries {@code +}, {@code /} and {@code =}, which the presigner writes
 * percent-encoded once. The proxy has to read the token back as it was signed for the session to
 * be found and the signature to match.
 */
@QuarkusTest
@Tag("docker")
class RdsIamAuthLambdaSessionIntegrationTest {

    private static final String RDS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261002/us-east-1/rds/aws4_request, "
            + "SignedHeaders=content-type;host, Signature=test";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String MASTER_USER = "master";
    private static final String MASTER_PASSWORD = "Master123!";
    private static final String DATABASE = "appdb";
    private static final String SESSION_SECRET = "lambda+session/secret=key";
    private static final String SESSION_TOKEN = "IQoJb3JpZ2luX2VjEJr+abc/def+ghi/jkl%2Bmno==";

    @Inject
    DockerClient dockerClient;

    @Inject
    IamService iamService;

    @BeforeEach
    void checkDocker() {
        try {
            dockerClient.pingCmd().exec();
        } catch (Exception e) {
            assumeTrue(false, "Docker is not available: " + e.getMessage());
        }
    }

    @Test
    void tokenSignedWithLambdaSessionCredentialsConnects() throws Exception {
        String instanceId = "iam-lambda-" + Long.toString(System.nanoTime(), 36);
        String accessKeyId = "ASIA" + Long.toString(System.nanoTime(), 36).toUpperCase();
        try {
            // Inside the try, so a create that provisions the database but fails an assertion is
            // still deleted.
            int port = rds("CreateDBInstance")
                    .formParam("DBInstanceIdentifier", instanceId)
                    .formParam("DBInstanceClass", "db.t3.micro")
                    .formParam("Engine", "postgres")
                    .formParam("MasterUsername", MASTER_USER)
                    .formParam("MasterUserPassword", MASTER_PASSWORD)
                    .formParam("DBName", DATABASE)
                    .formParam("AllocatedStorage", "20")
                    .formParam("EnableIAMDatabaseAuthentication", "true")
            .when().post("/").then().statusCode(200)
                    .body(containsString("<IAMDatabaseAuthenticationEnabled>true</IAMDatabaseAuthenticationEnabled>"))
                    .extract().xmlPath()
                    .getInt("CreateDBInstanceResponse.CreateDBInstanceResult.DBInstance.Endpoint.Port");
            iamService.registerLambdaExecutionRoleSession(ACCOUNT_ID, accessKeyId, SESSION_SECRET, SESSION_TOKEN,
                    "arn:aws:iam::" + ACCOUNT_ID + ":role/connect-with-iam");
            await().atMost(Duration.ofSeconds(60)).ignoreExceptions().until(() -> {
                try (Connection connection = connect(port, MASTER_PASSWORD)) {
                    return connection.isValid(5);
                }
            });

            String token = SigV4TokenTestHelper.createRdsToken("127.0.0.1", port, MASTER_USER,
                    accessKeyId, SESSION_SECRET, Instant.now().minusSeconds(60), 900, SESSION_TOKEN);
            try (Connection connection = connect(port, token);
                 Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT current_user")) {
                assertTrue(rows.next());
                assertEquals(MASTER_USER, rows.getString(1));
            }

            String anotherSession = SigV4TokenTestHelper.createRdsToken("127.0.0.1", port, MASTER_USER,
                    accessKeyId, SESSION_SECRET, Instant.now().minusSeconds(60), 900,
                    SESSION_TOKEN.replace('+', ' '));
            SQLException refused = assertThrows(SQLException.class, () -> connect(port, anotherSession).close());
            assertTrue(refused.getMessage().contains("password authentication failed"), refused.getMessage());
        } finally {
            iamService.unregisterSession(ACCOUNT_ID, accessKeyId);
            rds("DeleteDBInstance")
                    .formParam("DBInstanceIdentifier", instanceId)
                    .formParam("SkipFinalSnapshot", "true")
            .when().post("/");
        }
    }

    private static Connection connect(int port, String password) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", MASTER_USER);
        properties.setProperty("password", password);
        properties.setProperty("sslmode", "disable");
        properties.setProperty("connectTimeout", "5");
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + port + "/" + DATABASE, properties);
    }

    private static RequestSpecification rds(String action) {
        return given().header("Authorization", RDS_AUTH)
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }
}
