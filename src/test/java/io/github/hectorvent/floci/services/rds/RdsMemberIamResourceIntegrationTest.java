package io.github.hectorvent.floci.services.rds;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testutil.SigV4TokenTestHelper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.xml.XmlPath;
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
import java.util.Map;
import java.util.Properties;

import static io.github.hectorvent.floci.testing.RdsRequests.rds;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Cluster-scoped IAM policies must work on an Aurora member endpoint. */
@QuarkusTest
@Tag("docker")
@TestProfile(IamEnforcementProfile.class)
class RdsMemberIamResourceIntegrationTest {

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
    void memberEndpointUsesClusterPolicyBeforeAndAfterReboot() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String clusterId = "iam-cluster-" + suffix;
        String memberId = "iam-member-" + suffix;
        String roleName = "iam-connect-" + suffix;
        String accessKeyId = "ASIA" + suffix.toUpperCase();
        boolean roleCreated = false;
        boolean policyCreated = false;
        try {
            String resourceId = rds("CreateDBCluster")
                    .formParam("DBClusterIdentifier", clusterId)
                    .formParam("Engine", "aurora-postgresql")
                    .formParam("MasterUsername", MASTER_USER)
                    .formParam("MasterUserPassword", MASTER_PASSWORD)
                    .formParam("DatabaseName", DATABASE)
                    .formParam("EnableIAMDatabaseAuthentication", "true")
                    .when().post("/").then().statusCode(200).extract().xmlPath()
                    .getString("CreateDBClusterResponse.CreateDBClusterResult.DBCluster.DbClusterResourceId");
            // Explicit matching credentials and IAM flag isolate the resource binding from
            // the separate member credential/flag inheritance gaps.
            XmlPath member = rds("CreateDBInstance")
                    .formParam("DBInstanceIdentifier", memberId)
                    .formParam("DBClusterIdentifier", clusterId)
                    .formParam("DBInstanceClass", "db.serverless")
                    .formParam("Engine", "aurora-postgresql")
                    .formParam("DBName", DATABASE)
                    .formParam("EnableIAMDatabaseAuthentication", "true")
                    .when().post("/").then().statusCode(200).extract().xmlPath();
            int port = member.getInt("CreateDBInstanceResponse.CreateDBInstanceResult.DBInstance.Endpoint.Port");
            String instanceResourceId = member.getString(
                    "CreateDBInstanceResponse.CreateDBInstanceResult.DBInstance.DbiResourceId");
            String roleArn = iamService.createRole(roleName, "/", """
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                    "Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                    """, null, 3600, Map.of()).getArn();
            roleCreated = true;
            iamService.registerLambdaExecutionRoleSession(ACCOUNT_ID, accessKeyId,
                    SESSION_SECRET, SESSION_TOKEN, roleArn);
            await().atMost(Duration.ofSeconds(60)).ignoreExceptions().until(() -> {
                try (Connection connection = connect(port, MASTER_PASSWORD)) {
                    return connection.isValid(5);
                }
            });
            String token = SigV4TokenTestHelper.createRdsToken("127.0.0.1", port, MASTER_USER,
                    accessKeyId, SESSION_SECRET, Instant.now().minusSeconds(60), 900, SESSION_TOKEN);
            iamService.putRolePolicy(roleName, "connect", connectPolicy(resourceId));
            policyCreated = true;
            assertIamLogin(port, token);
            rds("RebootDBInstance").formParam("DBInstanceIdentifier", memberId)
                    .when().post("/").then().statusCode(200);
            assertIamLogin(port, token);
            // A member's DbiResourceId must not grant cluster access.
            iamService.putRolePolicy(roleName, "connect", connectPolicy(instanceResourceId));
            SQLException refused = assertThrows(SQLException.class, () -> connect(port, token).close());
            assertTrue(refused.getMessage().contains("password authentication failed"), refused.getMessage());
            iamService.putRolePolicy(roleName, "connect", connectPolicy(resourceId));
            assertIamLogin(port, token);
        } finally {
            iamService.unregisterSession(ACCOUNT_ID, accessKeyId);
            if (policyCreated) {
                iamService.deleteRolePolicy(roleName, "connect");
            }
            if (roleCreated) {
                iamService.deleteRole(roleName);
            }
            rds("DeleteDBInstance").formParam("DBInstanceIdentifier", memberId)
                    .formParam("SkipFinalSnapshot", "true").when().post("/");
            rds("DeleteDBCluster").formParam("DBClusterIdentifier", clusterId)
                    .formParam("SkipFinalSnapshot", "true").when().post("/");
        }
    }

    private static String connectPolicy(String resourceId) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"rds-db:connect",
                "Resource":"arn:aws:rds-db:us-east-1:%s:dbuser:%s/%s"}]}
                """.formatted(ACCOUNT_ID, resourceId, MASTER_USER);
    }

    private static void assertIamLogin(int port, String token) throws SQLException {
        try (Connection connection = connect(port, token);
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
