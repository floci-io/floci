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
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElastiCacheMemcachedIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260412/us-east-1/elasticache/aws4_request";
    private static final String CLUSTER_ID = "it-memcached-cluster";
    private static final int SOCKET_TIMEOUT_MS = 10_000;

    private static int clusterPort;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker daemon must be available for Memcached integration tests");
    }

    @AfterAll
    static void cleanup() {
        try {
            given()
                .formParam("Action", "DeleteCacheCluster")
                .formParam("CacheClusterId", CLUSTER_ID)
                .header("Authorization", AUTH_HEADER)
                .post("/");
        } catch (Exception ignored) {}
    }

    @Test
    @Order(1)
    void createCacheCluster() {
        clusterPort =
                given()
                    .formParam("Action", "CreateCacheCluster")
                    .formParam("CacheClusterId", CLUSTER_ID)
                    .formParam("Engine", "memcached")
                    .header("Authorization", AUTH_HEADER)
                .when()
                    .post("/")
                .then()
                    .statusCode(200)
                    .contentType("application/xml")
                    .body("CreateCacheClusterResponse.CreateCacheClusterResult.CacheCluster.CacheClusterId", equalTo(CLUSTER_ID))
                    .body("CreateCacheClusterResponse.CreateCacheClusterResult.CacheCluster.CacheClusterStatus", equalTo("available"))
                    .body("CreateCacheClusterResponse.CreateCacheClusterResult.CacheCluster.Engine", equalTo("memcached"))
                    .body("CreateCacheClusterResponse.CreateCacheClusterResult.CacheCluster.ConfigurationEndpoint.Address", notNullValue())
                    .body("CreateCacheClusterResponse.CreateCacheClusterResult.CacheCluster.ConfigurationEndpoint.Port", notNullValue())
                .extract()
                    .xmlPath()
                    .getInt("CreateCacheClusterResponse.CreateCacheClusterResult.CacheCluster.ConfigurationEndpoint.Port");
    }

    @Test
    @Order(2)
    void describeCacheClustersIncludesCreatedCluster() {
        given()
            .formParam("Action", "DescribeCacheClusters")
            .formParam("CacheClusterId", CLUSTER_ID)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeCacheClustersResponse.DescribeCacheClustersResult.CacheClusters.CacheCluster.CacheClusterId",
                    equalTo(CLUSTER_ID))
            .body("DescribeCacheClustersResponse.DescribeCacheClustersResult.CacheClusters.CacheCluster.Engine",
                    equalTo("memcached"));
    }

    @Test
    @Order(3)
    void createCacheClusterWithInvalidEngineReturnsError() {
        given()
            .formParam("Action", "CreateCacheCluster")
            .formParam("CacheClusterId", "redis-attempt")
            .formParam("Engine", "redis")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(4)
    void memcachedAcceptsSetAndGet() throws Exception {
        try (Socket socket = new Socket("localhost", clusterPort)) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            String setCmd = "set testkey 0 0 5\r\nhello\r\n";
            out.write(setCmd.getBytes(StandardCharsets.UTF_8));
            out.flush();
            assertEquals("STORED", readLine(in));

            out.write("get testkey\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            String valueLine = readLine(in);
            assertTrue(valueLine.startsWith("VALUE testkey"), "Expected VALUE line, got: " + valueLine);
            assertEquals("hello", readLine(in));
            assertEquals("END", readLine(in));
        }
    }

    @Test
    @Order(5)
    void versionCommandResponds() throws Exception {
        try (Socket socket = new Socket("localhost", clusterPort)) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            socket.getOutputStream().write("version\r\n".getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            String response = readLine(socket.getInputStream());
            assertTrue(response.startsWith("VERSION"), "Expected VERSION response, got: " + response);
        }
    }

    @Test
    @Order(6)
    void deleteCacheCluster() {
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

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                in.read(); // consume \n
                break;
            }
            sb.append((char) b);
        }
        return sb.toString();
    }

    private static boolean isDockerAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
