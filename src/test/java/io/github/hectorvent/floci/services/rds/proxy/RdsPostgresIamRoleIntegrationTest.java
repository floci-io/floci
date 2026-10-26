package io.github.hectorvent.floci.services.rds.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.rds.RdsService;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testutil.SigV4TokenTestHelper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static io.github.hectorvent.floci.testing.Ec2Requests.ec2;
import static io.github.hectorvent.floci.testing.RdsRequests.createPostgresInstance;
import static io.github.hectorvent.floci.testing.RdsRequests.deleteInstance;
import static io.github.hectorvent.floci.testing.RdsRequests.rds;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@Tag("docker")
@TestProfile(IamEnforcementProfile.class)
class RdsPostgresIamRoleIntegrationTest {
    private static final String MASTER = "master";
    private static final String PASSWORD = "Master123!";
    private static final String APP_PASSWORD = "App123!";

    @Inject
    RdsService rdsService;

    @Inject
    IamService iamService;

    @Test
    void standardDbProxyAcceptsClientIamWithoutDatabaseIamMembership() throws Exception {
        String id = uniqueId();
        String proxy = id + "-proxy";
        String role = id + "-policy";
        List<Runnable> cleanup = new ArrayList<>();
        try {
            int databasePort = createPostgresInstance(id, MASTER, PASSWORD, true);
            cleanup.add(0, () -> deleteInstance(id));
            asMaster(databasePort, "CREATE ROLE app_user LOGIN PASSWORD '" + APP_PASSWORD + "'");
            String vpc = ec2("CreateVpc", "test", "us-east-1").formParam("CidrBlock", "10.97.0.0/16")
                    .post("/").then().statusCode(200).extract().xmlPath().getString("CreateVpcResponse.vpc.vpcId");
            cleanup.add(0, () -> ec2("DeleteVpc", "test", "us-east-1").formParam("VpcId", vpc)
                    .post("/").then().statusCode(200));
            List<String> subnets = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String subnet = ec2("CreateSubnet", "test", "us-east-1").formParam("VpcId", vpc)
                        .formParam("CidrBlock", "10.97." + i + ".0/24")
                        .formParam("AvailabilityZone", i == 0 ? "us-east-1a" : "us-east-1b")
                        .post("/").then().statusCode(200).extract().xmlPath().getString("CreateSubnetResponse.subnet.subnetId");
                subnets.add(subnet);
                cleanup.add(0, () -> ec2("DeleteSubnet", "test", "us-east-1").formParam("SubnetId", subnet)
                        .post("/").then().statusCode(200));
            }
            String secret = given().header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20261018/us-east-1/secretsmanager/aws4_request")
                    .header("X-Amz-Target", "secretsmanager.CreateSecret").contentType("application/x-amz-json-1.1")
                    .body(new ObjectMapper().writeValueAsBytes(Map.of("Name", id, "SecretString", "{\"username\":\"app_user\",\"password\":\"App123!\"}")))
                    .post("/").then().statusCode(200).extract().jsonPath().getString("ARN");
            byte[] deleteSecret = new ObjectMapper().writeValueAsBytes(Map.of("SecretId", secret, "ForceDeleteWithoutRecovery", true));
            cleanup.add(0, () -> given().header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20261018/us-east-1/secretsmanager/aws4_request")
                    .header("X-Amz-Target", "secretsmanager.DeleteSecret").contentType("application/x-amz-json-1.1")
                    .body(deleteSecret).post("/").then().statusCode(200));
            String roleArn = iamService.createRole(role, "/", "{}", null, 3600, Map.of()).getArn();
            cleanup.add(0, () -> iamService.deleteRole(role));
            rds("CreateDBProxy").formParam("DBProxyName", proxy).formParam("EngineFamily", "POSTGRESQL")
                    .formParam("RoleArn", roleArn).formParam("VpcSubnetIds.member.1", subnets.get(0))
                    .formParam("VpcSubnetIds.member.2", subnets.get(1)).formParam("Auth.member.1.AuthScheme", "SECRETS")
                    .formParam("Auth.member.1.SecretArn", secret).formParam("Auth.member.1.IAMAuth", "REQUIRED")
                    .post("/").then().statusCode(200);
            cleanup.add(0, () -> rds("DeleteDBProxy").formParam("DBProxyName", proxy).post("/").then().statusCode(200));
            rds("RegisterDBProxyTargets").formParam("DBProxyName", proxy).formParam("DBInstanceIdentifiers.member.1", id)
                    .post("/").then().statusCode(200);
            int port = rdsService.getDbProxy(proxy).getProxyPort();
            String resource = rdsService.getDbProxy(proxy).getDbProxyResourceId();
            String accessKey = "ASIA" + Long.toString(System.nanoTime(), 36).toUpperCase();
            String sessionSecret = "proxy-session-secret";
            String sessionToken = "proxy-session-token";
            iamService.registerLambdaExecutionRoleSession("000000000000", accessKey, sessionSecret, sessionToken, roleArn);
            cleanup.add(0, () -> iamService.unregisterSession("000000000000", accessKey));
            String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"rds-db:connect\",\"Resource\":\"arn:aws:rds-db:us-east-1:000000000000:dbuser:" + resource + "/app_user\"}]}";
            iamService.putRolePolicy(role, "connect", policy);
            cleanup.add(0, () -> iamService.deleteRolePolicy(role, "connect"));
            Properties iam = login("app_user", SigV4TokenTestHelper.createRdsToken("localhost", port, "app_user",
                    accessKey, sessionSecret, Instant.now(), 900, sessionToken));
            assertUser(port, iam, "app_user");
            refused(databasePort, token(databasePort, "app_user"));
            asMaster(databasePort, "ALTER ROLE app_user NOLOGIN");
            refused(port, iam);
            asMaster(databasePort, "ALTER ROLE app_user LOGIN");
            assertUser(port, iam, "app_user");
            for (String mode : List.of("DISABLED", "REQUIRED")) {
                rds("ModifyDBProxy").formParam("DBProxyName", proxy)
                        .formParam("Auth.member.1.AuthScheme", "SECRETS")
                        .formParam("Auth.member.1.SecretArn", secret).formParam("Auth.member.1.IAMAuth", mode)
                        .post("/").then().statusCode(200);
                if (mode.equals("DISABLED")) {
                    refused(port, iam);
                } else {
                    assertUser(port, iam, "app_user");
                }
            }
            iamService.putRolePolicy(role, "connect", policy.replace(resource, "db-wrong-resource"));
            refused(port, iam);
            iamService.putRolePolicy(role, "connect", policy);
            assertUser(port, iam, "app_user");
        } finally {
            assertAll(cleanup.stream().map(task -> (Executable) task::run));
        }
    }

    @Test
    void iamLoginRequiresMembershipAndTheRolesOwnLoginAttribute() throws Exception {
        String id = uniqueId();
        try {
            int port = createPostgresInstance(id, MASTER, PASSWORD, true);
            asMaster(port, "CREATE ROLE app_user LOGIN PASSWORD '" + APP_PASSWORD + "'");
            Properties token = token(port, "app_user");
            refused(port, token);
            asMaster(port, "GRANT rds_iam TO app_user");
            assertUser(port, token, "app_user");
            asMaster(port, "REVOKE rds_iam FROM app_user");
            refused(port, token);
            asMaster(port, "GRANT rds_iam TO app_user", "ALTER ROLE app_user NOLOGIN");
            refused(port, token);
            asMaster(port, "ALTER ROLE app_user LOGIN");
            assertUser(port, token, "app_user");
        } finally {
            deleteInstance(id);
        }
    }

    @Test
    void iamMembershipTakesPrecedenceOverAnApplicationPassword() throws Exception {
        String id = uniqueId();
        try {
            int port = createPostgresInstance(id, MASTER, PASSWORD, true);
            asMaster(port, "CREATE ROLE app_user LOGIN PASSWORD '" + APP_PASSWORD + "'");
            Properties password = login("app_user", APP_PASSWORD);
            assertUser(port, password, "app_user");
            asMaster(port, "GRANT rds_iam TO app_user");
            pamRefused(port, password);
            assertUser(port, token(port, "app_user"), "app_user");
            rds("ModifyDBInstance").formParam("DBInstanceIdentifier", id)
                    .formParam("EnableIAMDatabaseAuthentication", false).post("/").then().statusCode(200);
            assertUser(port, password, "app_user");
            rds("ModifyDBInstance").formParam("DBInstanceIdentifier", id)
                    .formParam("EnableIAMDatabaseAuthentication", true).post("/").then().statusCode(200);
            pamRefused(port, password);
            asMaster(port, "REVOKE rds_iam FROM app_user");
            assertUser(port, password, "app_user");
        } finally {
            deleteInstance(id);
        }
    }

    @Test
    void theMasterNeedsAnActualGrantAndThenUsesIamInsteadOfItsPassword() throws Exception {
        String id = uniqueId();
        try {
            int port = createPostgresInstance(id, MASTER, PASSWORD, true);
            Properties iam = token(port, MASTER);
            refused(port, iam);
            try (Connection admin = connect(port, login(MASTER, PASSWORD));
                 Statement sql = admin.createStatement()) {
                sql.execute("GRANT rds_iam TO " + MASTER);
                assertUser(port, iam, MASTER);
                pamRefused(port, login(MASTER, PASSWORD));
                // The established administrative session remains usable after a grant.
                sql.execute("REVOKE rds_iam FROM " + MASTER);
            }
            assertUser(port, login(MASTER, PASSWORD), MASTER);
        } finally {
            deleteInstance(id);
        }
    }

    @Test
    void indirectMembershipWorksWithoutPrivilegeInheritance() throws Exception {
        String id = uniqueId();
        try {
            int port = createPostgresInstance(id, MASTER, PASSWORD, true);
            asMaster(port, "CREATE ROLE iam_group NOLOGIN", "GRANT rds_iam TO iam_group",
                    "CREATE ROLE app_user LOGIN NOINHERIT PASSWORD '" + APP_PASSWORD + "'",
                    "GRANT iam_group TO app_user");
            Properties iam = token(port, "app_user");
            assertUser(port, iam, "app_user");
            pamRefused(port, login("app_user", APP_PASSWORD));
            asMaster(port, "REVOKE rds_iam FROM iam_group");
            refused(port, iam);
            assertUser(port, login("app_user", APP_PASSWORD), "app_user");
        } finally {
            deleteInstance(id);
        }
    }

    @Test
    void roleLookupIgnoresClientEncodingAndCatalogShadows() throws Exception {
        String id = uniqueId();
        String name = "café\"reader";
        try {
            int port = createPostgresInstance(id, MASTER, PASSWORD, true);
            asMaster(port, "CREATE ROLE \"café\"\"reader\" LOGIN PASSWORD '" + APP_PASSWORD + "'",
                    "GRANT rds_iam TO \"café\"\"reader\"", "CREATE SCHEMA shadow",
                    "CREATE TABLE shadow.pg_roles (oid oid, rolname name, rolcanlogin boolean)",
                    "CREATE TABLE shadow.pg_auth_members (roleid oid, member oid)",
                    "CREATE FUNCTION shadow.name_equals_text(name, text) RETURNS boolean "
                            + "LANGUAGE sql IMMUTABLE AS 'SELECT false'",
                    "CREATE OPERATOR shadow.= (LEFTARG = name, RIGHTARG = text, "
                            + "FUNCTION = shadow.name_equals_text)");
            Properties password = login(name, APP_PASSWORD);
            password.setProperty("options", "-c search_path=shadow,pg_catalog -c client_encoding=LATIN1");
            pamRefused(port, password);
            Properties iam = token(port, name);
            iam.setProperty("options", "-c search_path=shadow,pg_catalog -c client_encoding=LATIN1");
            iam.setProperty("allowEncodingChanges", "true");
            assertUser(port, iam, name);
        } finally {
            deleteInstance(id);
        }
    }

    private static void pamRefused(int port, Properties properties) {
        SQLException exception = assertThrows(SQLException.class, () -> connect(port, properties).close());
        assertAll(
                () -> assertEquals("28000", exception.getSQLState()),
                () -> assertEquals("FATAL: PAM authentication failed for user \""
                        + properties.getProperty("user") + "\"", exception.getMessage()));
    }

    private static void refused(int port, Properties properties) {
        SQLException exception = assertThrows(SQLException.class, () -> connect(port, properties).close());
        assertEquals("28P01", exception.getSQLState());
    }

    private static void assertUser(int port, Properties properties, String user) throws SQLException {
        try (Connection connection = connect(port, properties);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT session_user")) {
            assertTrue(rows.next());
            assertEquals(user, rows.getString(1));
            assertFalse(rows.next());
        }
    }

    private static void asMaster(int port, String... statements) throws SQLException {
        try (Connection connection = connect(port, login(MASTER, PASSWORD));
             Statement sql = connection.createStatement()) {
            for (String statement : statements) {
                sql.execute(statement);
            }
        }
    }

    private static Properties login(String user, String password) {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("sslmode", "disable");
        properties.setProperty("connectTimeout", "5");
        return properties;
    }

    private static Properties token(int port, String user) throws Exception {
        return login(user, SigV4TokenTestHelper.createRdsToken("localhost", port, user,
                "test", "test", Instant.now(), 900));
    }

    private static Connection connect(int port, Properties properties) throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://localhost:" + port + "/appdb", properties);
    }

    private static String uniqueId() {
        return "iam-role-" + Long.toString(System.nanoTime(), 36);
    }
}
