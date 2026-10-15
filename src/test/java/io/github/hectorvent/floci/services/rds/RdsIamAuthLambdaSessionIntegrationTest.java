package io.github.hectorvent.floci.services.rds;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.testutil.SigV4TokenTestHelper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;

import static io.github.hectorvent.floci.testing.RdsRequests.rds;
import static io.restassured.RestAssured.given;
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
            awaitPasswordReady(port);

            assertIamAuthentication(port, accessKeyId, true);

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

    @Test
    void clusterIamToggleChangesAuthenticationAtExistingClusterAndMemberEndpoints() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String clusterId = "iam-cluster-" + suffix;
        String memberId = "iam-member-" + suffix;
        String accessKeyId = "ASIA" + suffix.toUpperCase();
        try {
            int clusterPort = rds("CreateDBCluster")
                    .formParam("DBClusterIdentifier", clusterId)
                    .formParam("Engine", "aurora-postgresql")
                    .formParam("MasterUsername", MASTER_USER)
                    .formParam("MasterUserPassword", MASTER_PASSWORD)
                    .formParam("DatabaseName", DATABASE)
                    .formParam("EnableIAMDatabaseAuthentication", "false")
                    .when().post("/").then().statusCode(200)
                    .extract().xmlPath()
                    .getInt("CreateDBClusterResponse.CreateDBClusterResult.DBCluster.Port");
            int memberPort = rds("CreateDBInstance")
                    .formParam("DBInstanceIdentifier", memberId)
                    .formParam("DBClusterIdentifier", clusterId)
                    .formParam("DBInstanceClass", "db.r5.large")
                    .formParam("Engine", "aurora-postgresql")
                    .formParam("DBName", DATABASE)
                    .formParam("AllocatedStorage", "20")
                    .when().post("/").then().statusCode(200)
                    .extract().xmlPath()
                    .getInt("CreateDBInstanceResponse.CreateDBInstanceResult.DBInstance.Endpoint.Port");
            iamService.registerLambdaExecutionRoleSession(ACCOUNT_ID, accessKeyId, SESSION_SECRET, SESSION_TOKEN,
                    "arn:aws:iam::" + ACCOUNT_ID + ":role/cluster-connect-with-iam");
            for (int port : new int[]{clusterPort, memberPort}) {
                awaitPasswordReady(port);
                assertIamAuthentication(port, accessKeyId, false);
            }
            for (boolean enabled : new boolean[]{true, false}) {
                rds("ModifyDBCluster")
                        .formParam("DBClusterIdentifier", clusterId)
                        .formParam("EnableIAMDatabaseAuthentication", Boolean.toString(enabled))
                        .when().post("/").then().statusCode(200);
                for (int port : new int[]{clusterPort, memberPort}) {
                    assertIamAuthentication(port, accessKeyId, enabled);
                    try (Connection connection = connect(port, MASTER_PASSWORD)) {
                        assertTrue(connection.isValid(5));
                    }
                }
            }
        } finally {
            iamService.unregisterSession(ACCOUNT_ID, accessKeyId);
            rds("DeleteDBInstance").formParam("DBInstanceIdentifier", memberId)
                    .formParam("SkipFinalSnapshot", "true").when().post("/");
            rds("DeleteDBCluster").formParam("DBClusterIdentifier", clusterId)
                    .formParam("SkipFinalSnapshot", "true").when().post("/");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void newClusterMemberUsesClusterIamAuthentication(boolean enabled) throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String clusterId = "inherit-cluster-" + suffix;
        String memberId = "inherit-member-" + suffix;
        String accessKeyId = "ASIA" + suffix.toUpperCase();
        try {
            rds("CreateDBCluster")
                    .formParam("DBClusterIdentifier", clusterId)
                    .formParam("Engine", "aurora-postgresql")
                    .formParam("MasterUsername", MASTER_USER)
                    .formParam("MasterUserPassword", MASTER_PASSWORD)
                    .formParam("DatabaseName", DATABASE)
                    .formParam("EnableIAMDatabaseAuthentication", Boolean.toString(enabled))
                    .when().post("/").then().statusCode(200);
            int port = rds("CreateDBInstance")
                    .formParam("DBInstanceIdentifier", memberId)
                    .formParam("DBClusterIdentifier", clusterId)
                    .formParam("DBInstanceClass", "db.r5.large")
                    .formParam("Engine", "aurora-postgresql")
                    .formParam("DBName", DATABASE)
                    .formParam("EnableIAMDatabaseAuthentication", Boolean.toString(!enabled))
                    .when().post("/").then().statusCode(200)
                    .extract().xmlPath()
                    .getInt("CreateDBInstanceResponse.CreateDBInstanceResult.DBInstance.Endpoint.Port");
            iamService.registerLambdaExecutionRoleSession(ACCOUNT_ID, accessKeyId, SESSION_SECRET, SESSION_TOKEN,
                    "arn:aws:iam::" + ACCOUNT_ID + ":role/member-connect-with-iam");
            awaitPasswordReady(port);
            assertIamAuthentication(port, accessKeyId, enabled);
        } finally {
            iamService.unregisterSession(ACCOUNT_ID, accessKeyId);
            rds("DeleteDBInstance").formParam("DBInstanceIdentifier", memberId)
                    .formParam("SkipFinalSnapshot", "true").when().post("/");
            rds("DeleteDBCluster").formParam("DBClusterIdentifier", clusterId)
                    .formParam("SkipFinalSnapshot", "true").when().post("/");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void auroraMemberUsesClusterCredentialsForIamLogin(boolean conflicting) throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String clusterId = "iam-creds-cluster-" + suffix;
        String memberId = "iam-creds-member-" + suffix;
        String accessKeyId = "ASIA" + suffix.toUpperCase();
        try {
            rds("CreateDBCluster").formParam("DBClusterIdentifier", clusterId)
                    .formParam("Engine", "aurora-postgresql")
                    .formParam("MasterUsername", MASTER_USER)
                    .formParam("MasterUserPassword", MASTER_PASSWORD)
                    .formParam("DatabaseName", DATABASE)
                    .formParam("EnableIAMDatabaseAuthentication", "true")
                    .when().post("/").then().statusCode(200);
            RequestSpecification createMember = rds("CreateDBInstance")
                    .formParam("DBInstanceIdentifier", memberId)
                    .formParam("DBClusterIdentifier", clusterId)
                    .formParam("DBInstanceClass", "db.serverless")
                    .formParam("Engine", "aurora-postgresql")
                    .formParam("DBName", DATABASE);
            if (conflicting) {
                rds("CreateDBInstance").formParam("DBInstanceIdentifier", memberId)
                        .formParam("DBClusterIdentifier", clusterId).formParam("DBInstanceClass", "db.serverless")
                        .formParam("Engine", "aurora-postgresql").formParam("DBName", DATABASE)
                        .formParam("MasterUsername", "differentuser").formParam("MasterUserPassword", "DifferentPassword!")
                        .when().post("/").then().statusCode(400)
                        .body(containsString("<Code>InvalidParameterCombination</Code>"));
            }
            int port = createMember.when().post("/").then().statusCode(200).extract().xmlPath()
                    .getInt("CreateDBInstanceResponse.CreateDBInstanceResult.DBInstance.Endpoint.Port");
            iamService.registerLambdaExecutionRoleSession(ACCOUNT_ID, accessKeyId, SESSION_SECRET, SESSION_TOKEN,
                    "arn:aws:iam::" + ACCOUNT_ID + ":role/connect-with-iam");
            awaitPasswordReady(port);
            String token = SigV4TokenTestHelper.createRdsToken("127.0.0.1", port, MASTER_USER,
                    accessKeyId, SESSION_SECRET, Instant.now().minusSeconds(60), 900, SESSION_TOKEN);
            assertCurrentUser(port, token);
            rds("DescribeDBInstances").formParam("DBInstanceIdentifier", memberId)
                    .when().post("/").then().statusCode(200)
                    .body(containsString("<MasterUsername>" + MASTER_USER + "</MasterUsername>"));
            rds("RebootDBInstance").formParam("DBInstanceIdentifier", memberId)
                    .when().post("/").then().statusCode(200);
            assertCurrentUser(port, token);
        } finally {
            iamService.unregisterSession(ACCOUNT_ID, accessKeyId);
            rds("DeleteDBInstance").formParam("DBInstanceIdentifier", memberId)
                    .formParam("SkipFinalSnapshot", "true").when().post("/");
            rds("DeleteDBCluster").formParam("DBClusterIdentifier", clusterId)
                    .formParam("SkipFinalSnapshot", "true").when().post("/");
        }
    }

    @Test
    void memberPasswordModificationKeepsClusterLoginBeforeAndAfterReboot() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String cluster = "modify-owner-" + suffix;
        String member = "modify-member-" + suffix;
        String accessKey = "ASIA" + suffix.toUpperCase();
        try {
            rds("CreateDBCluster").formParam("DBClusterIdentifier", cluster)
                    .formParam("Engine", "aurora-postgresql").formParam("MasterUsername", MASTER_USER)
                    .formParam("MasterUserPassword", MASTER_PASSWORD).formParam("DatabaseName", DATABASE)
                    .formParam("EnableIAMDatabaseAuthentication", "true")
                    .when().post("/").then().statusCode(200);
            int port = rds("CreateDBInstance").formParam("DBInstanceIdentifier", member)
                    .formParam("DBClusterIdentifier", cluster).formParam("DBInstanceClass", "db.serverless")
                    .formParam("Engine", "aurora-postgresql").formParam("DBName", DATABASE)
                    .when().post("/").then().statusCode(200).extract().xmlPath()
                    .getInt("CreateDBInstanceResponse.CreateDBInstanceResult.DBInstance.Endpoint.Port");
            iamService.registerLambdaExecutionRoleSession(ACCOUNT_ID, accessKey, SESSION_SECRET, SESSION_TOKEN,
                    "arn:aws:iam::" + ACCOUNT_ID + ":role/connect-with-iam");
            awaitPasswordReady(port);
            String token = SigV4TokenTestHelper.createRdsToken("127.0.0.1", port, MASTER_USER,
                    accessKey, SESSION_SECRET, Instant.now().minusSeconds(60), 900, SESSION_TOKEN);
            assertCurrentUser(port, token);
            rds("ModifyDBInstance").formParam("DBInstanceIdentifier", member)
                    .formParam("MasterUserPassword", "MemberOnlyPassword!")
                    .formParam("PubliclyAccessible", "true").when().post("/").then().statusCode(200)
                    .body(containsString("<PubliclyAccessible>true</PubliclyAccessible>"));
            assertCurrentUser(port, MASTER_PASSWORD);
            assertCurrentUser(port, token);
            assertThrows(SQLException.class, () -> connect(port, "MemberOnlyPassword!").close());
            rds("RebootDBInstance").formParam("DBInstanceIdentifier", member)
                    .when().post("/").then().statusCode(200);
            assertCurrentUser(port, MASTER_PASSWORD);
            assertCurrentUser(port, token);
            assertThrows(SQLException.class, () -> connect(port, "MemberOnlyPassword!").close());
        } finally {
            iamService.unregisterSession(ACCOUNT_ID, accessKey);
            rds("DeleteDBInstance").formParam("DBInstanceIdentifier", member)
                    .formParam("SkipFinalSnapshot", "true").when().post("/");
            rds("DeleteDBCluster").formParam("DBClusterIdentifier", cluster)
                    .formParam("SkipFinalSnapshot", "true").when().post("/");
        }
    }

    @Test
    void memberUsesTheClusterManagedSecretBeforeAndAfterReboot() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String clusterId = "managed-cluster-" + suffix;
        String memberId = "managed-member-" + suffix;
        try {
            String secretArn = rds("CreateDBCluster").formParam("DBClusterIdentifier", clusterId)
                    .formParam("Engine", "aurora-postgresql").formParam("MasterUsername", MASTER_USER)
                    .formParam("ManageMasterUserPassword", true).formParam("DatabaseName", DATABASE)
                    .when().post("/").then().statusCode(200).extract().xmlPath()
                    .getString("CreateDBClusterResponse.CreateDBClusterResult.DBCluster.MasterUserSecret.SecretArn");
            String value = given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT_ID
                            + "/20260215/us-east-1/secretsmanager/aws4_request, SignedHeaders=host, Signature=abc")
                    .header("X-Amz-Target", "secretsmanager.GetSecretValue").contentType("application/x-amz-json-1.1")
                    .body(new ObjectMapper().writeValueAsBytes(Map.of("SecretId", secretArn)))
                    .post("/").then().statusCode(200).extract().path("SecretString");
            String password = new ObjectMapper().readTree(value).get("password").asText();
            Response member = rds("CreateDBInstance").formParam("DBInstanceIdentifier", memberId)
                    .formParam("DBClusterIdentifier", clusterId).formParam("DBInstanceClass", "db.serverless")
                    .formParam("Engine", "aurora-postgresql").formParam("DBName", DATABASE)
                    .formParam("ManageMasterUserPassword", true)
                    .when().post("/").then().statusCode(200).extract().response();
            String root = "CreateDBInstanceResponse.CreateDBInstanceResult.DBInstance.";
            assertEquals("", member.xmlPath().getString(root + "MasterUserSecret.SecretArn"));
            int port = member.xmlPath().getInt(root + "Endpoint.Port");
            awaitPasswordReady(port, password);
            assertCurrentUser(port, password);
            rds("RebootDBInstance").formParam("DBInstanceIdentifier", memberId)
                    .when().post("/").then().statusCode(200);
            assertCurrentUser(port, password);
        } finally {
            rds("DeleteDBInstance").formParam("DBInstanceIdentifier", memberId)
                    .formParam("SkipFinalSnapshot", true).when().post("/");
            rds("DeleteDBCluster").formParam("DBClusterIdentifier", clusterId)
                    .formParam("SkipFinalSnapshot", true).when().post("/");
        }
    }

    private static void awaitPasswordReady(int port) {
        awaitPasswordReady(port, MASTER_PASSWORD);
    }

    private static void awaitPasswordReady(int port, String password) {
        await().atMost(Duration.ofSeconds(60)).ignoreExceptions().until(() -> {
            try (Connection connection = connect(port, password)) {
                return connection.isValid(5);
            }
        });
    }

    private static void assertIamAuthentication(int port, String accessKeyId, boolean enabled) throws Exception {
        String token = SigV4TokenTestHelper.createRdsToken("127.0.0.1", port, MASTER_USER,
                accessKeyId, SESSION_SECRET, Instant.now().minusSeconds(60), 900, SESSION_TOKEN);
        if (enabled) {
            assertCurrentUser(port, token);
        } else {
            assertThrows(SQLException.class, () -> connect(port, token).close());
        }
    }

    private static void assertCurrentUser(int port, String password) throws SQLException {
        try (Connection connection = connect(port, password);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT current_user")) {
            assertTrue(rows.next());
            assertEquals(MASTER_USER, rows.getString(1));
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
}
