package io.github.hectorvent.floci.services.dsql;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.services.dsql.dataplane.DsqlEndpoint;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.postgresql.util.PSQLException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cluster's resource-based policy, with IAM enforcement on, as Aurora DSQL evaluates it (measured,
 * eu-west-1, 2026-10-22 and 23): an explicit Deny that applies to the caller refuses the connection,
 * with a hint naming the caller, the action and the cluster, under the conditions the statement sets,
 * and refuses the control plane calls on the cluster it names; a statement that does not apply leaves
 * the decision to the caller's own policies.
 */
@QuarkusTest
@TestProfile(DsqlEnforcementProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("docker")
class DsqlClusterPolicyIntegrationTest {

    private static final String ACCOUNT = DsqlClusterFixture.ACCOUNT;

    @Inject
    DockerClient dockerClient;

    @Inject
    DsqlEndpoint endpoint;

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "0")
    int httpPort;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private DsqlClusterFixture cluster;

    @BeforeAll
    void createCluster() {
        cluster = new DsqlClusterFixture(httpPort, endpoint);
        cluster.create(dockerClient);
    }

    @AfterAll
    void deleteCluster() {
        cluster.delete();
    }

    @AfterEach
    void removePolicy() {
        cluster.removePolicy();
    }

    @Test
    void anExplicitDenyThatAppliesToTheCallerRefusesItsConnection() throws Exception {
        String userName = "dsql-" + Long.toString(System.nanoTime(), 36);
        EnforcementFixtures.UserCredentials user = adminUser(userName);
        String userArn = "arn:aws:iam::" + ACCOUNT + ":user/" + userName;
        signsIn(user);

        String[] denials = {
            statement("Deny", "{\"AWS\":\"arn:aws:iam::" + ACCOUNT + ":root\"}", "dsql:DbConnectAdmin", "*", null),
            statement("Deny", "\"*\"", "dsql:DbConnectAdmin", "*",
                    "{\"ArnEquals\":{\"aws:PrincipalArn\":\"" + userArn + "\"}}"),
            statement("Deny", "\"*\"", "dsql:DbConnectAdmin", "*",
                    "{\"IpAddress\":{\"aws:SourceIp\":\"127.0.0.1/32\"}}"),
        };
        for (String denial : denials) {
            cluster.putPolicy(denial);
            PSQLException refused = assertThrows(PSQLException.class, () -> connect(user).close(), denial);
            assertEquals("08006", refused.getSQLState(), denial);
            assertEquals("unable to accept connection, access denied", refused.getServerErrorMessage().getMessage());
            assertTrue(refused.getServerErrorMessage().getDetail().startsWith("Session Id: "));
            assertEquals("User: " + userArn + " is not authorized to perform: dsql:DbConnectAdmin on resource: "
                    + cluster.arn() + " with an explicit deny in a resource-based policy",
                    refused.getServerErrorMessage().getHint(), denial);
        }

        cluster.removePolicy();
        signsIn(user);
    }

    @Test
    void aStatementThatDoesNotApplyLeavesTheCallersOwnPoliciesToDecide() throws Exception {
        EnforcementFixtures.UserCredentials user = adminUser("dsql-" + Long.toString(System.nanoTime(), 36));
        String[] others = {
            statement("Deny", "\"*\"", "dsql:DbConnectAdmin", "*",
                    "{\"IpAddress\":{\"aws:SourceIp\":\"192.0.2.0/24\"}}"),
            statement("Deny", "{\"AWS\":\"arn:aws:iam::" + ACCOUNT + ":root\"}", "dsql:DbConnect", "*", null),
            statement("Deny", "{\"AWS\":\"arn:aws:iam::" + ACCOUNT + ":root\"}", "dsql:DbConnectAdmin",
                    "arn:aws:dsql:" + DsqlClusterFixture.REGION + ":" + ACCOUNT + ":cluster/abcdefghijklmnopqrstuvwxyz",
                    null),
            statement("Allow", "{\"AWS\":\"arn:aws:iam::" + ACCOUNT + ":root\"}", "dsql:DbConnectAdmin", "*",
                    "{\"ArnEquals\":{\"aws:PrincipalArn\":\"arn:aws:iam::" + ACCOUNT + ":role/someone-else\"}}"),
        };
        for (String other : others) {
            cluster.putPolicy(other);
            signsIn(user);
        }
    }

    @Test
    void anExplicitDenyRefusesTheControlPlaneCallsOnTheClusterItNames() {
        String userName = "dsql-" + Long.toString(System.nanoTime(), 36);
        String authorization = EnforcementFixtures.auth(ACCOUNT, "iam");
        EnforcementFixtures.UserCredentials user = EnforcementFixtures.createUser(cleanup, httpPort, authorization,
                userName);
        EnforcementFixtures.putUserPolicy(httpPort, authorization, userName, "manage", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"dsql:*","Resource":"*"}]}""");
        String clusterPath = "/cluster/" + cluster.identifier();
        String tagsPath = "/tags/" + URLEncoder.encode(cluster.arn(), StandardCharsets.UTF_8);
        String vpcEndpointPath = "/clusters/" + cluster.identifier() + "/vpc-endpoint-service-name";
        as(user).get(clusterPath).then().statusCode(200);

        cluster.putPolicy("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Deny\",\"Principal\":{\"AWS\":"
                + "\"arn:aws:iam::" + ACCOUNT + ":root\"},\"Action\":[\"dsql:GetCluster\",\"dsql:UpdateCluster\","
                + "\"dsql:TagResource\",\"dsql:UntagResource\",\"dsql:ListTagsForResource\","
                + "\"dsql:GetVpcEndpointServiceName\"],\"Resource\":\"*\"}]}");
        as(user).get(clusterPath).then().statusCode(403).body("__type", equalTo("AccessDeniedException"));
        as(user).body(Map.of("deletionProtectionEnabled", false)).post(clusterPath).then().statusCode(403);
        as(user).urlEncodingEnabled(false).body(Map.of("tags", Map.of("team", "data"))).post(tagsPath)
                .then().statusCode(403);
        as(user).urlEncodingEnabled(false).get(tagsPath).then().statusCode(403);
        // The same tagging is served at /tags, naming the ARN in the query or the body.
        as(user).body(Map.of("resourceArn", cluster.arn(), "tags", Map.of("team", "data"))).post("/tags")
                .then().statusCode(403);
        as(user).queryParam("resourceArn", cluster.arn()).get("/tags").then().statusCode(403);
        as(user).queryParam("resourceArn", cluster.arn()).queryParam("tagKeys", "team").delete("/tags")
                .then().statusCode(403);
        as(user).get(vpcEndpointPath).then().statusCode(403);
        // The calls the policy does not name answer.
        as(user).get(clusterPath + "/policy").then().statusCode(200);
        as(user).get("/cluster").then().statusCode(200);

        cluster.removePolicy();
        as(user).get(clusterPath).then().statusCode(200);
    }

    /**
     * The control plane is checked against the resources DSQL names (Service Authorization Reference):
     * each cluster call against the cluster, and CreateCluster and ListClusters against
     * {@code cluster/*} (measured).
     */
    @Test
    void theControlPlaneIsCheckedAgainstTheClusterItNames() {
        String userName = "dsql-" + Long.toString(System.nanoTime(), 36);
        String authorization = EnforcementFixtures.auth(ACCOUNT, "iam");
        EnforcementFixtures.UserCredentials user = EnforcementFixtures.createUser(cleanup, httpPort, authorization,
                userName);
        EnforcementFixtures.putUserPolicy(httpPort, authorization, userName, "read", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"dsql:GetCluster","Resource":"%s"},
                  {"Effect":"Allow","Action":"dsql:ListClusters","Resource":"arn:aws:dsql:%s:%s:cluster/*"}]}"""
                .formatted(cluster.arn(), DsqlClusterFixture.REGION, ACCOUNT));

        as(user).get("/cluster/" + cluster.identifier()).then().statusCode(200);
        as(user).get("/cluster").then().statusCode(200);
        as(user).get("/cluster/abcdefghijklmnopqrstuvwxyz").then().statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));
        as(user).get("/cluster/" + cluster.identifier() + "/policy").then().statusCode(403);
    }

    /** A new IAM user whose own policy lets it connect as {@code admin}. */
    private EnforcementFixtures.UserCredentials adminUser(String userName) {
        String authorization = EnforcementFixtures.auth(ACCOUNT, "iam");
        EnforcementFixtures.UserCredentials user = EnforcementFixtures.createUser(cleanup, httpPort, authorization,
                userName);
        EnforcementFixtures.putUserPolicy(httpPort, authorization, userName, "connect", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"dsql:DbConnectAdmin",
                  "Resource":"*"}]}""");
        return user;
    }

    /** A control plane request signed with {@code user}'s key. */
    private RequestSpecification as(EnforcementFixtures.UserCredentials user) {
        return given().port(httpPort).contentType("application/json")
                .header("Authorization", EnforcementFixtures.auth(user.accessKeyId(), DsqlClusterFixture.REGION, "dsql"));
    }

    private Connection connect(EnforcementFixtures.UserCredentials user) throws Exception {
        return cluster.connect("admin", cluster.token("DbConnectAdmin", user.accessKeyId(), user.secretAccessKey(),
                Instant.now(), 900, null));
    }

    private void signsIn(EnforcementFixtures.UserCredentials user) throws Exception {
        try (Connection connection = connect(user);
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT current_user")) {
            assertTrue(row.next());
            assertEquals("admin", row.getString(1));
        }
    }

    private static String statement(String effect, String principal, String action, String resource,
                                    String condition) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"" + effect + "\",\"Principal\":" + principal
                + ",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\""
                + (condition == null ? "" : ",\"Condition\":" + condition) + "}]}";
    }
}
