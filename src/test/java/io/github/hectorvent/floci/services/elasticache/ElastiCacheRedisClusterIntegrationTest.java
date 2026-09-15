package io.github.hectorvent.floci.services.elasticache;

import io.quarkus.test.junit.QuarkusTest;
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

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end coverage for a single-node redis cache cluster: the shape
 * {@code aws_elasticache_cluster} with {@code engine = "redis"} creates, which AWS serves through
 * CreateCacheCluster rather than CreateReplicationGroup. The node endpoint the describe reports
 * has to speak RESP, which is what tells this apart from a metadata-only record.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElastiCacheRedisClusterIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260412/us-east-1/elasticache/aws4_request";
    private static final String CLUSTER_ID = "it-redis-single";
    private static final int SOCKET_TIMEOUT_MS = 10_000;

    private static String nodeHost;
    private static int nodePort;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker daemon must be available for ElastiCache integration tests");
    }

    @AfterAll
    static void cleanup() {
        try {
            given()
                .formParam("Action", "DeleteCacheCluster")
                .formParam("CacheClusterId", CLUSTER_ID)
                .header("Authorization", AUTH_HEADER)
                .post("/");
        } catch (Exception e) {
            System.err.println("Cleanup of " + CLUSTER_ID + " failed: " + e.getMessage());
        }
    }

    @Test
    @Order(1)
    void createSingleNodeRedisCacheCluster() {
        given()
            .formParam("Action", "CreateCacheCluster")
            .formParam("CacheClusterId", CLUSTER_ID)
            .formParam("Engine", "redis")
            .formParam("EngineVersion", "7.1")
            .formParam("NumCacheNodes", "1")
            .formParam("CacheNodeType", "cache.t4g.micro")
            .formParam("SnapshotRetentionLimit", "5")
            .formParam("SnapshotWindow", "03:00-05:00")
            .formParam("PreferredMaintenanceWindow", "tue:04:00-tue:05:00")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("CreateCacheClusterResponse.CreateCacheClusterResult.CacheCluster.CacheClusterId", equalTo(CLUSTER_ID))
            .body("CreateCacheClusterResponse.CreateCacheClusterResult.CacheCluster.CacheClusterStatus", equalTo("available"))
            .body("CreateCacheClusterResponse.CreateCacheClusterResult.CacheCluster.Engine", equalTo("redis"))
            .body("CreateCacheClusterResponse.CreateCacheClusterResult.CacheCluster.NumCacheNodes", equalTo("1"));
    }

    @Test
    @Order(2)
    void describeReportsTheClusterWithItsNodeEndpoint() {
        var describe =
                given()
                    .formParam("Action", "DescribeCacheClusters")
                    .formParam("CacheClusterId", CLUSTER_ID)
                    .formParam("ShowCacheNodeInfo", "true")
                    .header("Authorization", AUTH_HEADER)
                .when()
                    .post("/")
                .then()
                    .statusCode(200)
                    .body("DescribeCacheClustersResponse.DescribeCacheClustersResult.CacheClusters.CacheCluster.Engine",
                            equalTo("redis"))
                    .body("DescribeCacheClustersResponse.DescribeCacheClustersResult.CacheClusters.CacheCluster.EngineVersion",
                            equalTo("7.1"))
                    .body("DescribeCacheClustersResponse.DescribeCacheClustersResult.CacheClusters.CacheCluster.CacheNodeType",
                            equalTo("cache.t4g.micro"))
                    .body("DescribeCacheClustersResponse.DescribeCacheClustersResult.CacheClusters.CacheCluster.CacheNodes.CacheNode.Endpoint.Address",
                            notNullValue())
                    // read back exactly as sent: an optional argument that comes back unset is a
                    // terraform diff that never settles
                    .body("DescribeCacheClustersResponse.DescribeCacheClustersResult.CacheClusters.CacheCluster.SnapshotRetentionLimit",
                            equalTo("5"))
                    .body("DescribeCacheClustersResponse.DescribeCacheClustersResult.CacheClusters.CacheCluster.SnapshotWindow",
                            equalTo("03:00-05:00"))
                    .body("DescribeCacheClustersResponse.DescribeCacheClustersResult.CacheClusters.CacheCluster.PreferredMaintenanceWindow",
                            equalTo("tue:04:00-tue:05:00"))
                .extract()
                    .xmlPath();

        nodeHost = describe.getString(
                "DescribeCacheClustersResponse.DescribeCacheClustersResult.CacheClusters.CacheCluster.CacheNodes.CacheNode.Endpoint.Address");
        nodePort = describe.getInt(
                "DescribeCacheClustersResponse.DescribeCacheClustersResult.CacheClusters.CacheCluster.CacheNodes.CacheNode.Endpoint.Port");
    }

    @Test
    @Order(3)
    void theReportedNodeEndpointSpeaksResp() throws Exception {
        try (Socket socket = openSocket(nodeHost, nodePort)) {
            write(socket, respArray("PING"));
            assertEquals("+PONG\r\n", readLine(socket));

            write(socket, respArray("SET", "single-node-key", "a-value"));
            assertEquals("+OK\r\n", readLine(socket));

            write(socket, respArray("GET", "single-node-key"));
            assertEquals("a-value", readBulk(socket));
        }
    }

    @Test
    @Order(4)
    void moreThanOneNodeIsRefused() {
        given()
            .formParam("Action", "CreateCacheCluster")
            .formParam("CacheClusterId", "it-redis-two-nodes")
            .formParam("Engine", "redis")
            .formParam("NumCacheNodes", "2")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"))
            .body("ErrorResponse.Error.Message", equalTo("NumCacheNodes should be 1 if engine is redis"));
    }

    @Test
    @Order(5)
    void deleteCacheClusterRemovesItAndFreesTheEndpoint() {
        given()
            .formParam("Action", "DeleteCacheCluster")
            .formParam("CacheClusterId", CLUSTER_ID)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeleteCacheClusterResponse.DeleteCacheClusterResult.CacheCluster.CacheClusterId", equalTo(CLUSTER_ID));

        given()
            .formParam("Action", "DescribeCacheClusters")
            .formParam("CacheClusterId", CLUSTER_ID)
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

    private static Socket openSocket(String host, int port) throws IOException {
        Socket socket = new Socket(host, port);
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
