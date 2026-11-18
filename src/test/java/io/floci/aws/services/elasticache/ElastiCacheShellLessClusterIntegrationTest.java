package io.floci.aws.services.elasticache;

import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.IndependentServiceSettingsProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.services.elasticache.ElastiCacheTestConnections.command;
import static io.floci.aws.services.elasticache.ElastiCacheTestConnections.containerOf;
import static io.floci.aws.services.elasticache.ElastiCacheTestConnections.docker;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cluster-mode flags reach the nodes as container arguments with the shell-less backend, and the
 * cluster forms and serves from them. Key slots: {@code bar} is 5061, the first of two shards, and
 * {@code foo} is 12182, the second.
 *
 * <p>The shell-less backend comes from {@link IndependentServiceSettingsProfile}, with the default
 * Valkey image. KiviDB, the shell-less image the single-node tests use, has no
 * {@code --cluster-announce-client-ipv4} flag, which Floci passes to every cluster-mode node, so it
 * cannot form a Floci cluster yet; Valkey takes the same container arguments and shows that the
 * cluster-mode flags arrive and the cluster forms.
 */
@QuarkusTest
@TestProfile(IndependentServiceSettingsProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElastiCacheShellLessClusterIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260412/us-east-1/elasticache/aws4_request";
    private static final String GROUP_ID = "it-sl-cluster";
    private static final List<String> NODES = List.of(GROUP_ID + "-0001-001", GROUP_ID + "-0002-001");

    @TestHTTPResource("/")
    static URI baseUri;

    private static int configurationEndpointPort;

    @BeforeAll
    static void requireDocker() throws InterruptedException {
        Assumptions.assumeTrue(DockerAvailability.isAvailable(),
                "Docker daemon must be available for ElastiCache integration tests");
    }

    @AfterAll
    static void cleanup() {
        try {
            given().port(baseUri.getPort())
                    .formParam("Action", "DeleteReplicationGroup")
                    .formParam("ReplicationGroupId", GROUP_ID)
                    .header("Authorization", AUTH_HEADER)
                    .post("/");
        } catch (RuntimeException expected) {
            // Best effort: the group is already gone when the delete test ran.
        }
    }

    @Test
    @Order(1)
    void createClusterModeGroup() {
        configurationEndpointPort = given()
                .formParam("Action", "CreateReplicationGroup")
                .formParam("ReplicationGroupId", GROUP_ID)
                .formParam("ReplicationGroupDescription", "shell-less cluster mode")
                .formParam("NumNodeGroups", "2")
                .formParam("ReplicasPerNodeGroup", "0")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("CreateReplicationGroupResponse.CreateReplicationGroupResult.ReplicationGroup.Status",
                        equalTo("available"))
                .body("CreateReplicationGroupResponse.CreateReplicationGroupResult.ReplicationGroup.ClusterEnabled",
                        equalTo("true"))
            .extract()
                .xmlPath()
                .getInt("CreateReplicationGroupResponse.CreateReplicationGroupResult.ReplicationGroup"
                        + ".ConfigurationEndpoint.Port");
    }

    @Test
    @Order(2)
    void clusterFlagsAreContainerArgumentsOnEveryNode() throws Exception {
        for (String node : NODES) {
            String container = containerOf(node);
            assertFalse(container.isEmpty(), node + " has a container");
            String command = docker("inspect", "--format", "{{json .Config.Cmd}}", container);
            assertTrue(command.contains("\"--cluster-enabled\",\"yes\""), command);
            assertTrue(command.contains("\"--cluster-announce-bus-port\",\"16379\""), command);
            assertFalse(command.contains("enable-debug-command"), command);
            String environment = docker("inspect", "--format", "{{json .Config.Env}}", container);
            assertFalse(environment.contains("VALKEY_EXTRA_FLAGS"), environment);
        }
    }

    @Test
    @Order(3)
    void theClusterServesItsSlots() throws Exception {
        try (Socket socket = new Socket("localhost", configurationEndpointPort)) {
            socket.setSoTimeout(10_000);
            assertEquals("+OK", command(socket, "SET", "bar", "1"));
            assertTrue(command(socket, "SET", "foo", "1").startsWith("-MOVED 12182 "));
        }
    }

    @Test
    @Order(4)
    void deletingTheGroupRemovesEveryNodeContainer() throws Exception {
        given()
            .formParam("Action", "DeleteReplicationGroup")
            .formParam("ReplicationGroupId", GROUP_ID)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
        for (String node : NODES) {
            await().atMost(60, TimeUnit.SECONDS).untilAsserted(() ->
                    assertEquals("", containerOf(node), node + " container is removed"));
        }
    }
}
