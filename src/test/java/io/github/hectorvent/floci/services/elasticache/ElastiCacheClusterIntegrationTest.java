package io.github.hectorvent.floci.services.elasticache;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
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
import java.nio.charset.StandardCharsets;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage for cluster-mode replication groups: real sharded Valkey
 * topology behind per-node auth proxies, honest describe output, and MOVED
 * redirects that point clients at the proxy ports.
 *
 * <p>Key slots used below are the well-known CRC16 values: {@code bar} → 5061
 * (first shard of two) and {@code foo} → 12182 (second shard of two).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElastiCacheClusterIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260412/us-east-1/elasticache/aws4_request";
    private static final String GROUP_ID = "it-ec-cluster";

    private static final int SOCKET_TIMEOUT_MS = 10_000;

    private static int configurationEndpointPort;
    private static int secondShardPort;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker daemon must be available for ElastiCache integration tests");
    }

    @AfterAll
    static void cleanup() {
        try {
            given()
                .formParam("Action", "DeleteReplicationGroup")
                .formParam("ReplicationGroupId", GROUP_ID)
                .header("Authorization", AUTH_HEADER)
                .post("/");
        } catch (Exception e) {
            System.err.println("Cleanup of " + GROUP_ID + " failed: " + e.getMessage());
        }
    }

    @Test
    @Order(1)
    void createClusterModeReplicationGroup() {
        configurationEndpointPort =
                given()
                    .formParam("Action", "CreateReplicationGroup")
                    .formParam("ReplicationGroupId", GROUP_ID)
                    .formParam("ReplicationGroupDescription", "Cluster mode integration test")
                    .formParam("Engine", "valkey")
                    .formParam("EngineVersion", "8.2")
                    .formParam("CacheNodeType", "cache.t4g.micro")
                    .formParam("CacheParameterGroupName", "default.valkey8.cluster.on")
                    .formParam("NumNodeGroups", "2")
                    .formParam("ReplicasPerNodeGroup", "1")
                    .formParam("AutomaticFailoverEnabled", "true")
                    .header("Authorization", AUTH_HEADER)
                .when()
                    .post("/")
                .then()
                    .statusCode(200)
                    .body("CreateReplicationGroupResponse.CreateReplicationGroupResult.ReplicationGroup.ReplicationGroupId", equalTo(GROUP_ID))
                    .body("CreateReplicationGroupResponse.CreateReplicationGroupResult.ReplicationGroup.Status", equalTo("available"))
                    .body("CreateReplicationGroupResponse.CreateReplicationGroupResult.ReplicationGroup.ClusterEnabled", equalTo("true"))
                    .body("CreateReplicationGroupResponse.CreateReplicationGroupResult.ReplicationGroup.AutomaticFailover", equalTo("enabled"))
                    .body("CreateReplicationGroupResponse.CreateReplicationGroupResult.ReplicationGroup.Engine", equalTo("valkey"))
                .extract()
                    .xmlPath()
                    .getInt("CreateReplicationGroupResponse.CreateReplicationGroupResult.ReplicationGroup.ConfigurationEndpoint.Port");
    }

    @Test
    @Order(2)
    void describeReportsClusterTopology() {
        XmlPath xml = given()
                .formParam("Action", "DescribeReplicationGroups")
                .formParam("ReplicationGroupId", GROUP_ID)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
            .extract()
                .xmlPath();

        String prefix = "DescribeReplicationGroupsResponse.DescribeReplicationGroupsResult.ReplicationGroups.ReplicationGroup.";
        assertEquals("true", xml.getString(prefix + "ClusterEnabled"));
        List<String> memberClusters = xml.getList(prefix + "MemberClusters.ClusterId");
        assertEquals(List.of(GROUP_ID + "-0001-001", GROUP_ID + "-0001-002",
                GROUP_ID + "-0002-001", GROUP_ID + "-0002-002"), memberClusters);
        assertEquals(List.of("0001", "0002"), xml.getList(prefix + "NodeGroups.NodeGroup.NodeGroupId"));
        assertEquals(List.of("0-8191", "8192-16383"), xml.getList(prefix + "NodeGroups.NodeGroup.Slots"));
    }

    @Test
    @Order(3)
    void describeCacheClustersAnswersForMembers() {
        secondShardPort = given()
                .formParam("Action", "DescribeCacheClusters")
                .formParam("CacheClusterId", GROUP_ID + "-0002-001")
                .formParam("ShowCacheNodeInfo", "true")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeCacheClustersResponse.DescribeCacheClustersResult.CacheClusters.CacheCluster.CacheClusterId",
                        equalTo(GROUP_ID + "-0002-001"))
                .body("DescribeCacheClustersResponse.DescribeCacheClustersResult.CacheClusters.CacheCluster.ReplicationGroupId",
                        equalTo(GROUP_ID))
                .body("DescribeCacheClustersResponse.DescribeCacheClustersResult.CacheClusters.CacheCluster.Engine",
                        equalTo("valkey"))
                .body("DescribeCacheClustersResponse.DescribeCacheClustersResult.CacheClusters.CacheCluster.EngineVersion",
                        equalTo("8.2"))
            .extract()
                .xmlPath()
                .getInt("DescribeCacheClustersResponse.DescribeCacheClustersResult.CacheClusters.CacheCluster.CacheNodes.CacheNode.Endpoint.Port");
    }

    @Test
    @Order(4)
    void clusterStateIsOkThroughTheProxy() throws Exception {
        try (Socket socket = openSocket(configurationEndpointPort)) {
            write(socket, respArray("PING"));
            assertEquals("+PONG\r\n", readLine(socket));

            write(socket, respArray("CLUSTER", "INFO"));
            String info = readBulk(socket);
            assertTrue(info.contains("cluster_state:ok"), "Expected cluster_state:ok but got: " + info);
            assertTrue(info.contains("cluster_known_nodes:4"), "Expected 4 known nodes but got: " + info);
        }
    }

    @Test
    @Order(5)
    void movedRedirectPointsAtTheOtherShardsProxyPort() throws Exception {
        try (Socket socket = openSocket(configurationEndpointPort)) {
            write(socket, respArray("SET", "bar", "first-shard-value"));
            assertEquals("+OK\r\n", readLine(socket));

            write(socket, respArray("SET", "foo", "second-shard-value"));
            String reply = readLine(socket);
            assertTrue(reply.startsWith("-MOVED 12182 "), "Expected MOVED redirect but got: " + reply);
            String target = reply.trim().substring("-MOVED 12182 ".length());
            assertEquals("localhost:" + secondShardPort, target,
                    "MOVED must point at the announced hostname and the second shard primary's proxy port");
        }

        try (Socket socket = openSocket(secondShardPort)) {
            write(socket, respArray("SET", "foo", "second-shard-value"));
            assertEquals("+OK\r\n", readLine(socket));

            write(socket, respArray("GET", "foo"));
            assertEquals("second-shard-value", readBulk(socket));
        }
    }

    @Test
    @Order(6)
    void deleteClusterModeReplicationGroup() {
        given()
            .formParam("Action", "DeleteReplicationGroup")
            .formParam("ReplicationGroupId", GROUP_ID)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DescribeReplicationGroups")
            .formParam("ReplicationGroupId", GROUP_ID)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(404);
    }

    private static boolean isDockerAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "info").start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static Socket openSocket(int port) throws IOException {
        Socket socket = new Socket("localhost", port);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(SOCKET_TIMEOUT_MS);
        return socket;
    }

    private static void write(Socket socket, byte[] data) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(data);
        out.flush();
    }

    private static byte[] respArray(String... args) {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(args.length).append("\r\n");
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            sb.append('$').append(bytes.length).append("\r\n").append(arg).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String readLine(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            sb.append((char) b);
            if (sb.length() >= 2 && sb.charAt(sb.length() - 2) == '\r' && sb.charAt(sb.length() - 1) == '\n') {
                return sb.toString();
            }
        }
        throw new IOException("Connection closed before line terminator; read so far: " + sb);
    }

    private static String readBulk(Socket socket) throws IOException {
        String header = readLine(socket);
        if (!header.startsWith("$")) {
            throw new IOException("Expected bulk reply but got: " + header);
        }
        int length = Integer.parseInt(header.substring(1).trim());
        if (length < 0) {
            return null;
        }
        InputStream in = socket.getInputStream();
        byte[] data = in.readNBytes(length);
        if (data.length != length) {
            throw new IOException("Truncated bulk reply");
        }
        if (in.read() != '\r' || in.read() != '\n') {
            throw new IOException("Missing CRLF after bulk reply");
        }
        return new String(data, StandardCharsets.UTF_8);
    }
}
