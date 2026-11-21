package io.floci.aws.services.elasticache;

import io.floci.aws.services.elasticache.container.ElastiCacheContainerManager;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.RdsMockProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.services.elasticache.ElastiCacheTestConnections.bulk;
import static io.floci.aws.services.elasticache.ElastiCacheTestConnections.command;
import static io.floci.aws.services.elasticache.ElastiCacheTestConnections.containerOf;
import static io.floci.aws.services.elasticache.ElastiCacheTestConnections.docker;
import static io.floci.aws.services.elasticache.ElastiCacheTestConnections.dockerExit;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shell-less ElastiCache backend ({@code floci.services.elasticache.shell-less-backend}) against
 * a real image with no shell and no CLI, pinned by digest in {@link RdsMockProfile}: server
 * flags as container arguments, snapshot export over RESP and the Docker archive API, restore by
 * placing the file before the server starts, authentication and ACLs through the proxy, and cleanup
 * of the containers on delete and on a failed restore.
 */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElastiCacheShellLessBackendIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260412/us-east-1/elasticache/aws4_request";
    private static final String SOURCE_ID = "it-sl-source";
    private static final String RESTORED_ID = "it-sl-restored";
    private static final String TOKEN_ID = "it-sl-token";
    private static final String RBAC_ID = "it-sl-rbac";
    private static final String BAD_SEED_ID = "it-sl-bad-seed";
    private static final String SNAPSHOT_NAME = "it-sl-snap";
    private static final String TOKEN = "shell-less-group-token";
    private static final String DEFAULT_USER_ID = "it-sl-default-user";
    private static final String APP_USER_ID = "it-sl-app-user";
    private static final String APP_USER_NAME = "it-sl-app";
    private static final String APP_PASSWORD = "app-secret-123456";
    private static final int SOCKET_TIMEOUT_MS = 10_000;

    @TestHTTPResource("/")
    static URI baseUri;

    private static int sourcePort;
    private static int restoredPort;

    @BeforeAll
    static void requireDocker() throws InterruptedException {
        Assumptions.assumeTrue(DockerAvailability.isAvailable(),
                "Docker daemon must be available for ElastiCache integration tests");
    }

    @AfterAll
    static void cleanup() {
        for (String groupId : List.of(RBAC_ID, TOKEN_ID, RESTORED_ID, SOURCE_ID)) {
            try {
                request("DeleteReplicationGroup").port(baseUri.getPort()).formParam("ReplicationGroupId", groupId).post("/");
            } catch (RuntimeException expected) {
                // Best effort: the group may not exist when a test failed before creating it.
            }
        }
        for (String userId : List.of(APP_USER_ID, DEFAULT_USER_ID)) {
            try {
                request("DeleteUser").port(baseUri.getPort()).formParam("UserId", userId).post("/");
            } catch (RuntimeException expected) {
                // Best effort, as above.
            }
        }
        try {
            request("DeleteSnapshot").port(baseUri.getPort()).formParam("SnapshotName", SNAPSHOT_NAME).post("/");
        } catch (RuntimeException expected) {
            // Best effort, as above.
        }
        try {
            CDI.current().select(ElastiCacheContainerManager.class).get().stopByGroupId(BAD_SEED_ID);
        } catch (RuntimeException expected) {
            // Best effort, as above.
        }
    }

    @Test
    @Order(1)
    void createGroupOnAnImageWithNoShell() {
        sourcePort = createGroup(SOURCE_ID, null, null);
    }

    @Test
    @Order(2)
    void theImageReallyHasNoShell() throws Exception {
        String container = containerOf(SOURCE_ID);
        assertFalse(container.isEmpty(), "the backend container exists");
        assertNotEquals(0, dockerExit("exec", container, "sh", "-c", "true"),
                "the fixture image must have no shell, or the test proves nothing");
    }

    @Test
    @Order(3)
    void serverFlagsAreDeliveredAsContainerArguments() throws Exception {
        String container = containerOf(SOURCE_ID);
        String command = docker("inspect", "--format", "{{json .Config.Cmd}}", container);
        assertTrue(command.contains("\"--port\",\"6379\""), command);
        assertTrue(command.contains("\"--protected-mode\",\"no\""), command);
        assertFalse(command.contains("enable-debug-command"), "DEBUG is not enabled anywhere: " + command);
        String environment = docker("inspect", "--format", "{{json .Config.Env}}", container);
        assertFalse(environment.contains("VALKEY_EXTRA_FLAGS"), environment);
    }

    @Test
    @Order(4)
    void dataIsServedThroughTheProxy() throws Exception {
        try (Socket socket = open(sourcePort)) {
            assertEquals("+OK", command(socket, "SET", "snapshot-key", "kept"));
            assertEquals("kept", bulk(socket, "GET", "snapshot-key"));
        }
    }

    @Test
    @Order(5)
    void snapshotIsExportedAndRestoredBeforeTheServerStarts() throws Exception {
        request("CreateSnapshot")
            .formParam("SnapshotName", SNAPSHOT_NAME)
            .formParam("ReplicationGroupId", SOURCE_ID)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateSnapshotResponse.CreateSnapshotResult.Snapshot.SnapshotStatus", equalTo("available"));
        // Written after the snapshot: the restored group must not see it.
        try (Socket socket = open(sourcePort)) {
            assertEquals("+OK", command(socket, "SET", "later-key", "after"));
        }

        restoredPort = createGroup(RESTORED_ID, SNAPSHOT_NAME, null);

        try (Socket socket = open(restoredPort)) {
            assertEquals("kept", bulk(socket, "GET", "snapshot-key"));
            assertEquals("$-1", command(socket, "GET", "later-key"));
        }
        String command = docker("inspect", "--format", "{{json .Config.Cmd}}", containerOf(RESTORED_ID));
        assertFalse(command.contains("enable-debug-command"), "no DEBUG for a restore: " + command);
    }

    @Test
    @Order(6)
    void theSourceKeepsItsDataAndItsSnapshotSurvivesTheRestore() throws Exception {
        try (Socket socket = open(sourcePort)) {
            assertEquals("after", bulk(socket, "GET", "later-key"));
        }
        request("DescribeSnapshots")
            .formParam("SnapshotName", SNAPSHOT_NAME)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeSnapshotsResponse.DescribeSnapshotsResult.Snapshots.Snapshot.SnapshotStatus",
                    equalTo("available"));
    }

    @Test
    @Order(7)
    void groupAuthTokenIsEnforcedByTheProxy() throws Exception {
        int port = createGroup(TOKEN_ID, null, TOKEN);
        try (Socket socket = open(port)) {
            assertTrue(command(socket, "PING").startsWith("-NOAUTH"));
        }
        try (Socket socket = open(port)) {
            assertEquals("-ERR invalid username-password pair or user is disabled.",
                    command(socket, "AUTH", "wrong-token"));
        }
        try (Socket socket = open(port)) {
            assertEquals("+OK", command(socket, "AUTH", TOKEN));
            assertEquals("+PONG", command(socket, "PING"));
            assertEquals("+OK", command(socket, "SET", "token-key", "value"));
        }
    }

    @Test
    @Order(8)
    void commandsAnAccessStringRefusesAreRefusedAndTheRefusalReachesTheClient() throws Exception {
        int port = createGroup(RBAC_ID, null, null);
        createUser(DEFAULT_USER_ID, "default", "no-password-required", null, "on ~* +@all");
        createUser(APP_USER_ID, APP_USER_NAME, "password", APP_PASSWORD, "on ~app:* -@all +get +set +ping");
        request("ModifyReplicationGroup")
            .formParam("ReplicationGroupId", RBAC_ID)
            .formParam("UserGroupIdsToAdd.member.1", DEFAULT_USER_ID)
            .formParam("UserGroupIdsToAdd.member.2", APP_USER_ID)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        try (Socket socket = open(port)) {
            assertEquals("-ERR invalid username-password pair or user is disabled.",
                    command(socket, "AUTH", APP_USER_NAME, "wrong-password"));
        }
        try (Socket socket = open(port)) {
            assertEquals("+OK", command(socket, "AUTH", APP_USER_NAME, APP_PASSWORD));
            assertEquals("+OK", command(socket, "SET", "app:key", "allowed"));
            assertEquals("allowed", bulk(socket, "GET", "app:key"));
            String otherKey = command(socket, "SET", "other:key", "refused");
            assertTrue(otherKey.startsWith("-NOPERM"), otherKey);
            String flush = command(socket, "FLUSHALL");
            assertTrue(flush.startsWith("-NOPERM"), flush);
            assertEquals("allowed", bulk(socket, "GET", "app:key"));
        }
        // Floci's own connection to the backend is not authenticated, so once the proxy has set up
        // the backend's ACL a snapshot is refused rather than hanging or returning stale data.
        request("CreateSnapshot")
            .formParam("SnapshotName", "it-sl-acl-snap")
            .formParam("ReplicationGroupId", RBAC_ID)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidReplicationGroupState"));
    }

    @Test
    @Order(9)
    void aSnapshotTheServerCannotLoadFailsTheStartAndLeavesNoContainer() throws Exception {
        ElastiCacheContainerManager manager = CDI.current().select(ElastiCacheContainerManager.class).get();
        String garbage = Base64.getEncoder().encodeToString("not a snapshot".getBytes(StandardCharsets.UTF_8));

        assertThrows(ElastiCacheContainerManager.SnapshotRestoreException.class, () ->
                manager.start(BAD_SEED_ID, RdsMockProfile.ELASTICACHE_SHELL_LESS_IMAGE, List.of(), "us-east-1", garbage));
        // The caller cleans up as the service's rollback does; the failed container is not left behind.
        manager.stopByGroupId(BAD_SEED_ID);
        assertEquals("", containerOf(BAD_SEED_ID));

        // The failure touched neither the cluster the snapshot came from nor the snapshot itself.
        try (Socket socket = open(sourcePort)) {
            assertEquals("after", bulk(socket, "GET", "later-key"));
        }
        request("DescribeSnapshots")
            .formParam("SnapshotName", SNAPSHOT_NAME)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeSnapshotsResponse.DescribeSnapshotsResult.Snapshots.Snapshot.SnapshotStatus",
                    equalTo("available"));
    }

    @Test
    @Order(10)
    void deletingTheGroupsRemovesTheirContainers() throws Exception {
        for (String groupId : List.of(RBAC_ID, TOKEN_ID, RESTORED_ID, SOURCE_ID)) {
            assertFalse(containerOf(groupId).isEmpty(), groupId + " has a container before the delete");
            request("DeleteReplicationGroup")
                .formParam("ReplicationGroupId", groupId)
            .when()
                .post("/")
            .then()
                .statusCode(200);
            await().atMost(60, TimeUnit.SECONDS).untilAsserted(() ->
                    assertEquals("", containerOf(groupId), groupId + " container is removed"));
        }
        request("DeleteSnapshot")
            .formParam("SnapshotName", SNAPSHOT_NAME)
        .when()
            .post("/")
        .then()
            .statusCode(200);
        for (String userId : List.of(APP_USER_ID, DEFAULT_USER_ID)) {
            request("DeleteUser").formParam("UserId", userId).post("/").then().statusCode(200);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static RequestSpecification request(String action) {
        return given().formParam("Action", action).header("Authorization", AUTH_HEADER);
    }

    private static int createGroup(String groupId, String snapshotName, String authToken) {
        RequestSpecification request = request("CreateReplicationGroup")
                .formParam("ReplicationGroupId", groupId)
                .formParam("ReplicationGroupDescription", "shell-less backend");
        if (snapshotName != null) {
            request = request.formParam("SnapshotName", snapshotName);
        }
        if (authToken != null) {
            request = request.formParam("AuthToken", authToken);
        }
        return request
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .xmlPath()
                .getInt("CreateReplicationGroupResponse.CreateReplicationGroupResult.ReplicationGroup"
                        + ".NodeGroups.NodeGroup.PrimaryEndpoint.Port");
    }

    private static void createUser(String userId, String userName, String authType, String password,
                                   String accessString) {
        RequestSpecification request = request("CreateUser")
                .formParam("UserId", userId)
                .formParam("UserName", userName)
                .formParam("AuthenticationMode.Type", authType)
                .formParam("AccessString", accessString);
        if (password != null) {
            request = request.formParam("AuthenticationMode.Passwords.member.1", password);
        }
        request.when().post("/").then().statusCode(200);
    }

    private static Socket open(int port) throws IOException {
        Socket socket = ElastiCacheTestConnections.openSocket("localhost", port);
        socket.setSoTimeout(SOCKET_TIMEOUT_MS);
        return socket;
    }
}
