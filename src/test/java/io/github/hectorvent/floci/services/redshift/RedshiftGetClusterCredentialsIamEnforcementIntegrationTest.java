package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * GetClusterCredentials needs more than redshift:GetClusterCredentials when it uses its options, as the
 * API reference lists them: redshift:JoinGroup on each {@code dbgroup} named in {@code DbGroups}, and
 * redshift:CreateClusterUser on the {@code dbuser} when {@code AutoCreate} is true. The checks run before
 * the cluster lookup, so a caller they allow gets {@code ClusterNotFound} for a cluster that does not
 * exist, and the tests need no Docker.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class RedshiftGetClusterCredentialsIamEnforcementIntegrationTest {

    private static final String ADMIN = "test";
    private static final String ARN_PREFIX = "arn:aws:redshift:us-east-1:000000000000:";

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void getClusterCredentials_dbGroupsWithoutJoinGroup_isDenied() {
        String cluster = uniqueName("gcc-groups");
        String user = uniqueName("gcc-groups");
        String accessKeyId = createUser(user);

        userPolicy(user, allow("redshift:GetClusterCredentials", "*"));
        assertDenied(credentials(accessKeyId, cluster).formParam("DbGroups.DbGroup.1", "analysts"));

        userPolicy(user, allow("redshift:GetClusterCredentials", "*") + ","
                + allow("redshift:JoinGroup", ARN_PREFIX + "dbgroup:" + cluster + "/analysts"));
        assertDenied(credentials(accessKeyId, cluster)
                .formParam("DbGroups.DbGroup.1", "analysts")
                .formParam("DbGroups.DbGroup.2", "admins"));
    }

    @Test
    void getClusterCredentials_dbGroupsWithJoinGroupOnEachGroup_passesTheIamCheck() {
        String cluster = uniqueName("gcc-groups-ok");
        String user = uniqueName("gcc-groups-ok");
        String accessKeyId = createUser(user);
        userPolicy(user, allow("redshift:GetClusterCredentials", "*") + ","
                + allow("redshift:JoinGroup", ARN_PREFIX + "dbgroup:" + cluster + "/analysts") + ","
                + allow("redshift:JoinGroup", ARN_PREFIX + "dbgroup:" + cluster + "/admins"));

        assertClusterNotFound(credentials(accessKeyId, cluster)
                .formParam("DbGroups.DbGroup.1", "analysts")
                .formParam("DbGroups.DbGroup.2", "admins"));
    }

    @Test
    void getClusterCredentials_autoCreateWithoutCreateClusterUser_isDenied() {
        String cluster = uniqueName("gcc-auto");
        String user = uniqueName("gcc-auto");
        String accessKeyId = createUser(user);
        userPolicy(user, allow("redshift:GetClusterCredentials", "*"));

        assertDenied(credentials(accessKeyId, cluster).formParam("AutoCreate", "true"));
    }

    @Test
    void getClusterCredentials_autoCreateWithCreateClusterUserOnTheDbUser_passesTheIamCheck() {
        String cluster = uniqueName("gcc-auto-ok");
        String user = uniqueName("gcc-auto-ok");
        String accessKeyId = createUser(user);
        userPolicy(user, allow("redshift:GetClusterCredentials", "*") + ","
                + allow("redshift:CreateClusterUser", ARN_PREFIX + "dbuser:" + cluster + "/analyst"));

        assertClusterNotFound(credentials(accessKeyId, cluster).formParam("AutoCreate", "true"));
    }

    @Test
    void getClusterCredentials_presignedWithoutJoinGroup_isDenied() {
        String cluster = uniqueName("gcc-presigned");
        String user = uniqueName("gcc-presigned");
        String accessKeyId = createUser(user);
        userPolicy(user, allow("redshift:GetClusterCredentials", "*"));

        assertDenied(given()
                .queryParam("X-Amz-Algorithm", "AWS4-HMAC-SHA256")
                .queryParam("X-Amz-Credential", accessKeyId + "/20261020/us-east-1/redshift/aws4_request")
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "GetClusterCredentials")
                .formParam("Version", "2012-12-01")
                .formParam("ClusterIdentifier", cluster)
                .formParam("DbUser", "analyst")
                .formParam("DbGroups.DbGroup.1", "analysts"));
    }

    @Test
    void getClusterCredentials_withoutOptions_needsOnlyGetClusterCredentials() {
        String cluster = uniqueName("gcc-plain");
        String user = uniqueName("gcc-plain");
        String accessKeyId = createUser(user);
        userPolicy(user, allow("redshift:GetClusterCredentials", "*"));

        assertClusterNotFound(credentials(accessKeyId, cluster).formParam("AutoCreate", "false"));
    }

    /** The Management Guide's policy grants GetClusterCredentials on one {@code dbuser} alone. */
    @Test
    void getClusterCredentials_grantOnTheDbUser_passesTheIamCheckForThatUserOnly() {
        String cluster = uniqueName("gcc-dbuser");
        String user = uniqueName("gcc-dbuser");
        String accessKeyId = createUser(user);
        userPolicy(user, allow("redshift:GetClusterCredentials", ARN_PREFIX + "dbuser:" + cluster + "/analyst"));

        assertClusterNotFound(credentials(accessKeyId, cluster));
        assertDenied(credentials(accessKeyId, cluster, "intruder"));
    }

    /** "If the DbName parameter is specified, the IAM policy must allow access to the resource dbname". */
    @Test
    void getClusterCredentials_dbName_needsAGrantOnTheDatabase() {
        String cluster = uniqueName("gcc-dbname");
        String user = uniqueName("gcc-dbname");
        String accessKeyId = createUser(user);
        userPolicy(user, allow("redshift:GetClusterCredentials", ARN_PREFIX + "dbuser:" + cluster + "/analyst"));
        assertDenied(credentials(accessKeyId, cluster).formParam("DbName", "testdb"));

        userPolicy(user, allow("redshift:GetClusterCredentials", ARN_PREFIX + "dbuser:" + cluster + "/analyst") + ","
                + allow("redshift:GetClusterCredentials", ARN_PREFIX + "dbname:" + cluster + "/testdb"));
        assertClusterNotFound(credentials(accessKeyId, cluster).formParam("DbName", "testdb"));
    }

    /**
     * The Management Guide's example 8 scopes GetClusterCredentials and CreateClusterUser to
     * {@code dbuser:<cluster>/${redshift:DbUser}}, the user the request names.
     */
    @Test
    void getClusterCredentials_dbUserPolicyVariable_coversBothActions() {
        String cluster = uniqueName("gcc-variable");
        String user = uniqueName("gcc-variable");
        String accessKeyId = createUser(user);
        String ownUser = ARN_PREFIX + "dbuser:" + cluster + "/${redshift:DbUser}";
        userPolicy(user, allow("redshift:GetClusterCredentials", ownUser) + ","
                + allow("redshift:CreateClusterUser", ownUser));

        assertClusterNotFound(credentials(accessKeyId, cluster).formParam("AutoCreate", "true"));
    }

    /** A condition on {@code redshift:DbUser} applies to both actions, CreateClusterUser's included. */
    @Test
    void getClusterCredentials_dbUserCondition_appliesToCreateClusterUser() {
        String cluster = uniqueName("gcc-condition");
        String user = uniqueName("gcc-condition");
        String accessKeyId = createUser(user);
        userPolicy(user, allow("redshift:GetClusterCredentials", "*") + ","
                + "{\"Effect\":\"Allow\",\"Action\":\"redshift:CreateClusterUser\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"redshift:DbUser\":\"analyst\"}}}");

        assertClusterNotFound(credentials(accessKeyId, cluster).formParam("AutoCreate", "true"));
        assertDenied(credentials(accessKeyId, cluster, "intruder").formParam("AutoCreate", "true"));
    }

    private static RequestSpecification credentials(String accessKeyId, String cluster) {
        return credentials(accessKeyId, cluster, "analyst");
    }

    private static RequestSpecification credentials(String accessKeyId, String cluster, String dbUser) {
        return given()
                .header("Authorization", auth(accessKeyId, "redshift"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "GetClusterCredentials")
                .formParam("Version", "2012-12-01")
                .formParam("ClusterIdentifier", cluster)
                .formParam("DbUser", dbUser);
    }

    private static void assertDenied(RequestSpecification request) {
        request.when().post("/").then().statusCode(403).body(containsString("AccessDenied"));
    }

    private static void assertClusterNotFound(RequestSpecification request) {
        request.when().post("/").then().statusCode(404).body(containsString("ClusterNotFound"));
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String createUser(String user) {
        return EnforcementFixtures.createUser(cleanup, baseUri.getPort(), auth(ADMIN, "iam"), user).accessKeyId();
    }

    private static String allow(String action, String resource) {
        return "{\"Effect\":\"Allow\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
    }

    private void userPolicy(String user, String statements) {
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ADMIN, "iam"), user, "p",
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
    }
}
