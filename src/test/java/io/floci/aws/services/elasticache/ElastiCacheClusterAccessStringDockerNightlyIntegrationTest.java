package io.floci.aws.services.elasticache;

import io.floci.aws.services.elasticache.model.ClusterNode;
import io.floci.aws.services.elasticache.model.ReplicationGroup;
import io.floci.aws.testing.DockerAvailability;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.Socket;
import java.net.URI;
import java.util.List;

import static io.floci.aws.services.elasticache.ElastiCacheTestConnections.bulk;
import static io.floci.aws.services.elasticache.ElastiCacheTestConnections.command;
import static io.floci.aws.services.elasticache.ElastiCacheTestConnections.containerOf;
import static io.floci.aws.services.elasticache.ElastiCacheTestConnections.docker;
import static io.floci.aws.services.elasticache.ElastiCacheTestConnections.openSocket;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cluster-mode replication group with a user group: each node's proxy runs a session under the
 * signed-in user's access string on that node, across MOVED redirects, ModifyUser reaches the open
 * sessions of every node, and no session can change the cluster's topology.
 *
 * <p>Hash tags pin keys to a shard: {@code {bar}} hashes to slot 5061 (first shard of two) and
 * {@code {foo}} to slot 12182 (second shard).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElastiCacheClusterAccessStringDockerNightlyIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260412/us-east-1/elasticache/aws4_request";
    private static final String GROUP_ID = "it-ecc-acl";
    private static final String USER_GROUP = "it-ecc-acl-users";
    private static final String DEFAULT_USER = "it-ecc-acl-default";
    private static final String APP_USER = "it-ecc-acl-app";
    private static final String OPS_USER = "it-ecc-acl-ops";
    private static final String PASSWORD = "it-ecc-acl-password-0123";

    private static int firstShardPort;

    /** RestAssured's port is reset once the tests are done, so the cleanup names the server's own. */
    @TestHTTPResource("/")
    static URI baseUri;

    @Inject
    ElastiCacheService service;

    @BeforeAll
    static void requireDocker() throws InterruptedException {
        Assumptions.assumeTrue(DockerAvailability.isAvailable(), "Docker must be available for cluster-mode access strings");
    }

    @AfterAll
    static void cleanup() {
        request("DeleteReplicationGroup").formParam("ReplicationGroupId", GROUP_ID).post("/");
        request("DeleteUserGroup").formParam("UserGroupId", USER_GROUP).post("/");
        for (String user : List.of(APP_USER, OPS_USER, DEFAULT_USER)) {
            request("DeleteUser").formParam("UserId", user).post("/");
        }
    }

    @Test
    @Order(1)
    void createAClusterModeGroupWithAUserGroup() {
        createUser(DEFAULT_USER, "default", "on ~* &* +@all");
        createUser(APP_USER, APP_USER, "on ~app:* -@all +get +set +ping");
        createUser(OPS_USER, OPS_USER, "on ~* &* +@all");
        request("CreateUserGroup")
                .formParam("UserGroupId", USER_GROUP)
                .formParam("Engine", "valkey")
                .formParam("UserIds.member.1", DEFAULT_USER)
                .formParam("UserIds.member.2", APP_USER)
                .formParam("UserIds.member.3", OPS_USER)
                .post("/").then().statusCode(200);

        request("CreateReplicationGroup")
                .formParam("ReplicationGroupId", GROUP_ID)
                .formParam("ReplicationGroupDescription", "Cluster mode access strings")
                .formParam("Engine", "valkey")
                .formParam("EngineVersion", "8.2")
                .formParam("CacheParameterGroupName", "default.valkey8.cluster.on")
                .formParam("NumNodeGroups", "2")
                .formParam("ReplicasPerNodeGroup", "1")
                .formParam("AutomaticFailoverEnabled", "true")
                .formParam("TransitEncryptionEnabled", "true")
                .formParam("UserGroupIds.member.1", USER_GROUP)
                .post("/").then().statusCode(200);

        firstShardPort = group().getClusterNodes().stream()
                .filter(node -> node.isPrimary() && "0001".equals(node.getNodeGroupId()))
                .findFirst().orElseThrow().getProxyPort();
    }

    @Test
    @Order(2)
    void noNodeHoldsAReplicationPasswordBeforeAUserSignsIn() throws Exception {
        for (ClusterNode node : group().getClusterNodes()) {
            assertEquals("masterauth", nodeConfig(node, "masterauth"),
                    node.getMemberClusterId() + " must not hold a password its default sessions could read");
        }
    }

    @Test
    @Order(3)
    void theFirstSignInGivesEveryNodeItsReplicationUser() throws Exception {
        signIn(firstShardPort, APP_USER).close();

        // The node signed in to has turned its default user off, so the CLI reads the others.
        for (ClusterNode node : group().getClusterNodes()) {
            if (node.getProxyPort() != firstShardPort) {
                String reply = nodeConfig(node, "masteruser");
                assertTrue(reply.lines().toList().getLast().startsWith("floci-replication-"),
                        node.getMemberClusterId() + " must sign in to its primary as the replication user: " + reply);
            }
        }
    }

    @Test
    @Order(4)
    void aSessionRunsUnderItsUsersAccessString() throws Exception {
        try (Socket app = signIn(firstShardPort, APP_USER)) {
            assertEquals("+OK", command(app, "SET", "app:{bar}", "first"));
            assertEquals("first", bulk(app, "GET", "app:{bar}"));
            assertTrue(command(app, "SET", "other:{bar}", "value").startsWith("-NOPERM"));
            assertTrue(command(app, "FLUSHALL").startsWith("-NOPERM"));

            assertTrue(command(app, "AUTH", "default", "anything").startsWith("-WRONGPASS"),
                    "AUTH sent past the proxy must not reach the node's unrestricted default user");
            assertTrue(command(app, "SET", "other:{bar}", "value").startsWith("-NOPERM"));
        }
    }

    @Test
    @Order(5)
    void aRedirectedSessionRunsUnderItsUsersAccessStringOnTheOtherShard() throws Exception {
        int secondShardPort;
        try (Socket app = signIn(firstShardPort, APP_USER)) {
            String moved = command(app, "SET", "app:{foo}", "second");
            assertTrue(moved.startsWith("-MOVED 12182 "), "the key lives on the second shard: " + moved);
            secondShardPort = Integer.parseInt(moved.substring(moved.lastIndexOf(':') + 1));
        }
        try (Socket app = signIn(secondShardPort, APP_USER)) {
            assertEquals("+OK", command(app, "SET", "app:{foo}", "second"));
            assertTrue(command(app, "SET", "other:{foo}", "value").startsWith("-NOPERM"));
        }
    }

    @Test
    @Order(6)
    void noSessionCanChangeTheClusterTopology() throws Exception {
        try (Socket ops = signIn(firstShardPort, OPS_USER)) {
            assertTrue(bulk(ops, "CLUSTER", "INFO").contains("cluster_state:ok"));
            assertEquals("+OK", command(ops, "SET", "ops:{bar}", "value"));

            assertTrue(command(ops, "CLUSTER", "MEET", "127.0.0.1", "7000").startsWith("-NOPERM"));
            assertTrue(command(ops, "CLUSTER", "FAILOVER").startsWith("-NOPERM"));
            assertTrue(command(ops, "CLUSTER", "FORGET", "0123456789abcdef").startsWith("-NOPERM"));
            assertTrue(command(ops, "CLUSTER", "RESET").startsWith("-NOPERM"));
            assertTrue(command(ops, "CONFIG", "GET", "masterauth").startsWith("-NOPERM"),
                    "the replication password must stay out of reach");
        }
    }

    @Test
    @Order(7)
    void aChangedAccessStringReachesTheOpenSessionsOfEveryNode() throws Exception {
        int secondShardPort = group().getClusterNodes().stream()
                .filter(node -> node.isPrimary() && "0002".equals(node.getNodeGroupId()))
                .findFirst().orElseThrow().getProxyPort();
        try (Socket first = signIn(firstShardPort, APP_USER); Socket second = signIn(secondShardPort, APP_USER)) {
            assertEquals("+OK", command(first, "SET", "app:{bar}", "writable"));
            assertEquals("+OK", command(second, "SET", "app:{foo}", "writable"));

            request("ModifyUser")
                    .formParam("UserId", APP_USER)
                    .formParam("AccessString", "on ~app:* -@all +get +ping")
                    .post("/").then().statusCode(200);

            assertTrue(command(first, "SET", "app:{bar}", "read-only").startsWith("-NOPERM"));
            assertTrue(command(second, "SET", "app:{foo}", "read-only").startsWith("-NOPERM"));
            assertEquals("writable", bulk(second, "GET", "app:{foo}"));
        }
    }

    private ReplicationGroup group() {
        return service.listReplicationGroups(GROUP_ID).iterator().next();
    }

    /** The node's own value of a parameter, read with its CLI past the proxy: the parameter name, then the value. */
    private static String nodeConfig(ClusterNode node, String parameter) throws Exception {
        return docker("exec", containerOf(node.getMemberClusterId()), "valkey-cli", "CONFIG", "GET", parameter);
    }

    private static Socket signIn(int port, String user) throws Exception {
        Socket socket = openSocket("localhost", port);
        try {
            assertEquals("+OK", command(socket, "AUTH", user, PASSWORD));
            return socket;
        } catch (Throwable failure) {
            socket.close();
            throw failure;
        }
    }

    private static void createUser(String userId, String userName, String accessString) {
        request("CreateUser")
                .formParam("UserId", userId)
                .formParam("UserName", userName)
                .formParam("Engine", "valkey")
                .formParam("AccessString", accessString)
                .formParam("AuthenticationMode.Type", "password")
                .formParam("AuthenticationMode.Passwords.member.1", PASSWORD)
                .post("/").then().statusCode(200);
    }

    private static RequestSpecification request(String action) {
        return given().port(baseUri.getPort()).formParam("Action", action).header("Authorization", AUTH_HEADER);
    }
}
