package io.github.hectorvent.floci.services.elasticache;

import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerManager;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two caches may both ask for 6379, and each is reachable at the endpoint it reports.
 *
 * <p>Nothing here requires either cache to land on 6379 itself. Another class in this shared
 * Quarkus instance may already hold it, in which case the substitution is the feature working,
 * and a test demanding 6379 would fail on correct behaviour before reaching what it exists to
 * check. What is asserted is the relationship: two caches asking for one port are served on
 * two ports, and each reports the one it is on.
 *
 * <p>6379 is the Redis default, so nearly every ElastiCache cluster uses it and on AWS they
 * coexist: each cache has a DNS name of its own and the port never distinguishes them. Floci has
 * one host, so the port is the only thing that does. It used to refuse the second cache, which
 * fails any module standing up two Redis clusters. It now serves the second cache on a port of
 * its own and reports that port.
 *
 * <p>Two assertions carry the weight, and it is worth being exact about which rules out what.
 * {@code Order(2)} compares the two REPORTED ports and requires them to differ. One host port can
 * only name one listener, so a build that reported the pinned port on both caches fails there, on
 * the control plane, before anything connects. {@code Order(6)} then writes through each cache's
 * own advertised endpoint and reads its own value back, which rules out two endpoints reaching
 * one cache even when the numbers differ.
 *
 * <p>The useful distinction is not control plane against data plane. It is whether an assertion
 * constrains the thing a wrong design could get wrong. An assertion about a SINGLE reported value
 * cannot: "the API says 6379" is satisfied whatever 6379 actually serves. An assertion about a
 * RELATIONSHIP between reported values often can, which is why {@code Order(2)} bites.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElastiCachePortSharingIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260412/us-east-1/elasticache/aws4_request";

    /** The Redis default, and the port every module in the corpus pins. */
    private static final int SHARED_PORT = 6379;

    private static final String FIRST_GROUP = "it-ec-share-a";
    private static final String SECOND_GROUP = "it-ec-share-b";
    /**
     * A token per group, because the proxy validates it against the group it was started for
     * (see the {@code validatePassword(groupId, ...)} each startProxy is given). That makes the
     * token the one thing a cache knows about its own control-plane identity, and it is what
     * lets {@code Order(6)} tell "reaches its own cache" from "reaches a distinct cache".
     */
    /** What a proxy answers AUTH with another group's token; pinned by ElastiCacheIntegrationTest. */
    private static final String WRONG_TOKEN_ERROR = "invalid username-password pair";
    private static final String FIRST_TOKEN = "token-for-group-a";
    private static final String SECOND_TOKEN = "token-for-group-b";

    private static final String CREATED_PORT =
            "CreateReplicationGroupResponse.CreateReplicationGroupResult.ReplicationGroup"
                    + ".NodeGroups.NodeGroup.PrimaryEndpoint.Port";
    private static final String DESCRIBED_PORT =
            "DescribeReplicationGroupsResponse.DescribeReplicationGroupsResult.ReplicationGroups"
                    + ".ReplicationGroup.NodeGroups.NodeGroup.PrimaryEndpoint.Port";
    private static final String MEMBER_NODE_PORT =
            "DescribeCacheClustersResponse.DescribeCacheClustersResult.CacheClusters.CacheCluster"
                    + ".CacheNodes.CacheNode.Endpoint.Port";

    private static int firstPort;
    private static int secondPort;

    /** The manager that starts these caches' containers, so the guard is the service's own. */
    @Inject
    ElastiCacheContainerManager containerManager;

    @AfterAll
    static void cleanup() {
        for (String groupId : List.of(FIRST_GROUP, SECOND_GROUP)) {
            try {
                given()
                    .formParam("Action", "DeleteReplicationGroup")
                    .formParam("ReplicationGroupId", groupId)
                    .header("Authorization", AUTH_HEADER)
                    .post("/");
            } catch (Exception ignored) {
                // Best effort: a group a test never created has nothing to tear down.
            }
        }
    }

    @Test
    @Order(1)
    void firstGroupIsServedAndReportsWhereItIs() {
        firstPort = createGroup(FIRST_GROUP, SHARED_PORT, FIRST_TOKEN)
            .statusCode(200)
            .extract().xmlPath().getInt(CREATED_PORT);

        assertTrue(firstPort > 0, "The first group must report a port it can be dialled on");
    }

    /**
     * The bug: AWS creates this group, and Floci answered InvalidParameterValue.
     *
     * <p>Not redundant against {@code Order(6)}, which is the reading this assertion invites.
     * Comparing the two REPORTED ports is what rejects a build that reports one port for both
     * caches, and it does so before anything connects, so it survives on a runner where
     * {@code Order(6)} is skipped for want of Docker. {@code Order(6)} catches what this cannot,
     * namely two distinct reported ports that do not reach the caches they name.
     */
    @Test
    @Order(2)
    void secondGroupOnTheSamePortIsCreatedRatherThanRefused() {
        secondPort = createGroup(SECOND_GROUP, SHARED_PORT, SECOND_TOKEN)
            .statusCode(200)
            .extract().xmlPath().getInt(CREATED_PORT);

        assertNotEquals(firstPort, secondPort,
                "The second group must be served somewhere of its own, not on the first's port");
    }

    @Test
    @Order(3)
    void eachGroupKeepsReportingThePortItIsOn() {
        assertEquals(firstPort, describeGroup(FIRST_GROUP).extract().xmlPath().getInt(DESCRIBED_PORT));
        assertEquals(secondPort, describeGroup(SECOND_GROUP).extract().xmlPath().getInt(DESCRIBED_PORT));
    }

    /** The terraform provider reads a member's port out of DescribeCacheClusters. */
    @Test
    @Order(4)
    void memberCacheClustersAgreeWithTheGroupEndpoint() {
        assertEquals(secondPort,
                describeMember(SECOND_GROUP + "-001").extract().xmlPath().getInt(MEMBER_NODE_PORT));
    }

    /**
     * The one range check left. The bound is the one {@code RdsService.reserveProxyPort} applies
     * to the same argument; nothing here establishes ElastiCache's real limit on AWS, so the name
     * says "accepted" rather than claiming the range is AWS's.
     */
    @Test
    @Order(5)
    void aPortOutsideTheAcceptedRangeIsStillRefused() {
        given()
            .formParam("Action", "CreateReplicationGroup")
            .formParam("ReplicationGroupId", "it-ec-share-bad-port")
            .formParam("ReplicationGroupDescription", "port sharing test")
            .formParam("Engine", "redis")
            .formParam("NumCacheClusters", "1")
            .formParam("Port", "80")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidParameterValue"))
            .body(containsString("<Error>"));
    }

    /**
     * Each group's endpoint reaches that group's own cache.
     *
     * <p>Docker is required here and only here: this is the one test that needs a container
     * behind the proxy. Orders 1 to 5 are control-plane assertions and a group reaches
     * {@code available} with no daemon reachable, so gating the whole class on Docker would
     * delete the regression test for this fix on a Docker-less runner, silently and green.
     *
     * <p>{@code Order(2)} already rejects a build reporting one port for both caches. Writing and
     * reading through each endpoint adds the case two distinct reported ports cannot rule out,
     * where both endpoints alias onto a single cache. Neither of those, on its own, separates
     * "reaches its own cache" from "reaches a distinct cache that is not its own": a consistent
     * relabelling would carry both writes and both reads with it and pass.
     *
     * <p>The AUTH is what closes that. Each proxy validates the token of the group it was started
     * for, so a token is the one thing a cache knows about its control-plane identity. An
     * endpoint that reached the other group's proxy would be refused its own group's token, which
     * the last two assertions require to be accepted and the other group's to be refused.
     */
    @Test
    @Order(6)
    void eachAdvertisedEndpointReachesItsOwnCache() throws Exception {
        Assumptions.assumeTrue(isDockerAvailable(),
                "Docker must be available: this assertion needs a real cache behind each proxy");

        assertEquals("+OK", withAuth(firstPort, FIRST_TOKEN, "SET", "who", "first").trim());
        assertEquals("+OK", withAuth(secondPort, SECOND_TOKEN, "SET", "who", "second").trim());

        assertEquals("first", bulkString(withAuth(firstPort, FIRST_TOKEN, "GET", "who")),
                "The first group's endpoint must reach the first group");
        assertEquals("second", bulkString(withAuth(secondPort, SECOND_TOKEN, "GET", "who")),
                "and the second group's endpoint must reach the second group, not the first");

        // Identity, not just distinctness: each endpoint must refuse the other group's token.
        // On the refusal itself rather than on the helper's prefix: a connection that returned
        // nothing at all would also fail to start with "+OK", and that must not read as a pass.
        assertTrue(withAuth(firstPort, SECOND_TOKEN, "GET", "who").contains(WRONG_TOKEN_ERROR),
                "The first group's endpoint must refuse the second group's token, and say so");
        assertTrue(withAuth(secondPort, FIRST_TOKEN, "GET", "who").contains(WRONG_TOKEN_ERROR),
                "The second group's endpoint must refuse the first group's token, and say so");
    }

    private static ValidatableResponse createGroup(String groupId, int port, String authToken) {
        return given()
                .formParam("Action", "CreateReplicationGroup")
                .formParam("ReplicationGroupId", groupId)
                .formParam("ReplicationGroupDescription", "port sharing test")
                .formParam("Engine", "redis")
                .formParam("NumCacheClusters", "1")
                .formParam("Port", String.valueOf(port))
                .formParam("AuthToken", authToken)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then();
    }

    private static ValidatableResponse describeGroup(String groupId) {
        return given()
                .formParam("Action", "DescribeReplicationGroups")
                .formParam("ReplicationGroupId", groupId)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    private static ValidatableResponse describeMember(String cacheClusterId) {
        return given()
                .formParam("Action", "DescribeCacheClusters")
                .formParam("CacheClusterId", cacheClusterId)
                .formParam("ShowCacheNodeInfo", "true")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    private static String resp(String... args) {
        StringBuilder sb = new StringBuilder("*").append(args.length).append("\r\n");
        for (String arg : args) {
            sb.append('$').append(arg.length()).append("\r\n").append(arg).append("\r\n");
        }
        return sb.toString();
    }

    /**
     * AUTHs with {@code token}, then runs one command on the same connection. Returns
     * {@code AUTH-REFUSED} plus the reply when the token is not accepted, which is what an
     * endpoint reaching a different group's proxy produces.
     */
    private static String withAuth(int port, String token, String... command) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(resp("AUTH", token).getBytes(StandardCharsets.UTF_8));
            out.flush();
            String authReply = readReply(in);
            if (!authReply.startsWith("+OK")) {
                return "AUTH-REFUSED: " + authReply.trim();
            }
            out.write(resp(command).getBytes(StandardCharsets.UTF_8));
            out.flush();
            return readReply(in);
        }
    }

    /**
     * Reads exactly one RESP reply. A single {@code read()} returns whatever happened to arrive,
     * which is usually the whole reply over loopback and is not guaranteed to be: TCP may split it
     * anywhere. That turns a working proxy into an intermittently failing test, which is worse
     * than no test because it teaches people to re-run.
     *
     * <p>Handles the two shapes these commands produce: a single line for {@code +OK}, {@code -ERR}
     * and the like, and a bulk string whose first line declares how many bytes follow.
     */
    private static String readReply(InputStream in) throws IOException {
        String header = readLine(in);
        if (header.isEmpty() || header.charAt(0) != '$') {
            return header;
        }
        int length = Integer.parseInt(header.substring(1).trim());
        if (length < 0) {
            return header;
        }
        byte[] body = in.readNBytes(length);
        if (body.length < length) {
            throw new IOException("Short bulk string: wanted " + length + ", read " + body.length);
        }
        readLine(in);
        return header + new String(body, StandardCharsets.UTF_8) + "\r\n";
    }

    /** One CRLF-terminated line, byte at a time so nothing of the next reply is consumed. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            line.append((char) b);
            if (line.length() >= 2
                    && line.charAt(line.length() - 2) == '\r'
                    && line.charAt(line.length() - 1) == '\n') {
                return line.toString();
            }
        }
        return line.toString();
    }

    private static String bulkString(String reply) {
        String[] lines = reply.split("\r\n");
        return lines.length > 1 ? lines[1] : reply;
    }

    /**
     * The service's own check, not a second one that happens to agree.
     *
     * <p>{@code isDockerReachable} is the method {@link ElastiCacheContainerManager} uses to tell
     * a missing daemon from a container that failed for its own reasons, and it pings the
     * {@code DockerClient} bean the containers are actually started with. The {@code docker} CLI
     * is a different oracle: {@code DockerClientProducer} resolves the daemon from the active
     * Docker context, often not the platform default (an OrbStack socket, say), so {@code docker
     * info} can fail, or the binary be absent, while Floci's client works.
     *
     * <p>This guard protects the only data-plane assertion in the class. One that fired
     * spuriously would delete exactly the test the class exists for, silently and green, which is
     * the one failure here that no amount of reading the output would catch.
     */
    private boolean isDockerAvailable() {
        return containerManager.isDockerReachable();
    }
}
