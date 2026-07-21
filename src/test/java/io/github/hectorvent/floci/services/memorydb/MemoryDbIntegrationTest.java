package io.github.hectorvent.floci.services.memorydb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
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
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end MemoryDB test: JSON 1.1 control plane (CreateCluster/DescribeClusters/
 * DeleteCluster) plus the real RESP data plane (connectivity and password auth)
 * through a Docker-backed Valkey container and the TCP auth proxy.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MemoryDbIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260412/us-east-1/memorydb/aws4_request";
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String OPEN_CLUSTER = "it-mdb-open";
    private static final String AUTH_CLUSTER = "it-mdb-auth";
    private static final String AUTH_USER = "it-mdb-user";
    private static final String AUTH_ACL = "it-mdb-acl";
    private static final String AUTH_PASSWORD = "it-mdb-password";

    private static final int SOCKET_TIMEOUT_MS = 10_000;
    private static final int READ_LINE_MAX_ATTEMPTS = 3;

    private static int openPort;
    private static int authPort;

    @BeforeAll
    static void setup() {
        RestAssuredJsonUtils.configureAwsContentTypes();
        Assumptions.assumeTrue(isDockerAvailable(),
                "Docker daemon must be available for MemoryDB integration tests");
    }

    @AfterAll
    static void cleanup() {
        for (String name : List.of(OPEN_CLUSTER, OPEN_CLUSTER + "-reused", AUTH_CLUSTER)) {
            try {
                deleteCluster(name);
            } catch (Exception ignored) {
                // best-effort
            }
        }
        try {
            memorydb("DeleteACL", "{\"ACLName\":\"" + AUTH_ACL + "\"}");
        } catch (Exception ignored) {
            // best-effort
        }
        try {
            memorydb("DeleteUser", "{\"UserName\":\"" + AUTH_USER + "\"}");
        } catch (Exception ignored) {
            // best-effort
        }
    }

    @Test
    @Order(1)
    void createClusterWithoutAuth() {
        openPort = memorydb("CreateCluster", "{"
                + "\"ClusterName\":\"" + OPEN_CLUSTER + "\","
                + "\"NodeType\":\"db.t4g.small\","
                + "\"ACLName\":\"open-access\"}")
            .then()
                .statusCode(200)
                .body("Cluster.Name", equalTo(OPEN_CLUSTER))
                .body("Cluster.Status", equalTo("available"))
                .body("Cluster.ClusterEndpoint.Address", equalTo("localhost"))
                .body("Cluster.ClusterEndpoint.Port", notNullValue())
            .extract()
                .path("Cluster.ClusterEndpoint.Port");
    }

    @Test
    @Order(2)
    void describeClustersIncludesCreated() {
        memorydb("DescribeClusters", "{\"ClusterName\":\"" + OPEN_CLUSTER + "\"}")
            .then()
                .statusCode(200)
                .body("Clusters[0].Name", equalTo(OPEN_CLUSTER))
                .body("Clusters[0].ClusterEndpoint.Port", equalTo(openPort));
    }

    @Test
    @Order(3)
    void respDataPlaneWorksWithoutAuth() throws Exception {
        try (Socket socket = openSocket(openPort)) {
            write(socket, respArray("PING"));
            assertEquals("+PONG\r\n", readLine(socket));

            write(socket, respArray("SET", "mykey", "hello"));
            assertEquals("+OK\r\n", readLine(socket));

            write(socket, respArray("GET", "mykey"));
            assertEquals("$5\r\n", readLine(socket));
            assertEquals("hello\r\n", readLine(socket));
        }
    }

    @Test
    @Order(4)
    void createPasswordUserAndAcl() {
        // A password user attached to an ACL is how real MemoryDB models auth — the
        // cluster then references that ACL via ACLName.
        memorydb("CreateUser", "{"
                + "\"UserName\":\"" + AUTH_USER + "\","
                + "\"AccessString\":\"on ~* +@all\","
                + "\"AuthenticationMode\":{\"Type\":\"password\",\"Passwords\":[\"" + AUTH_PASSWORD + "\"]}}")
            .then()
                .statusCode(200)
                .body("User.Name", equalTo(AUTH_USER))
                .body("User.Authentication.Type", equalTo("password"));

        // An ACL must include the built-in "default" user (DefaultUserRequired otherwise).
        memorydb("CreateACL", "{"
                + "\"ACLName\":\"" + AUTH_ACL + "\","
                + "\"UserNames\":[\"default\",\"" + AUTH_USER + "\"]}")
            .then()
                .statusCode(200)
                .body("ACL.Name", equalTo(AUTH_ACL))
                .body("ACL.UserNames", hasItems("default", AUTH_USER));
    }

    @Test
    @Order(5)
    void createClusterReferencingAcl() {
        authPort = memorydb("CreateCluster", "{"
                + "\"ClusterName\":\"" + AUTH_CLUSTER + "\","
                + "\"ACLName\":\"" + AUTH_ACL + "\"}")
            .then()
                .statusCode(200)
                .body("Cluster.Name", equalTo(AUTH_CLUSTER))
                .body("Cluster.Status", equalTo("available"))
                .body("Cluster.ACLName", equalTo(AUTH_ACL))
            .extract()
                .path("Cluster.ClusterEndpoint.Port");
    }

    @Test
    @Order(6)
    void aclClusterRejectsUnauthenticatedCommand() throws Exception {
        assertEquals("-NOAUTH Authentication required.\r\n",
                sendCommand(authPort, respArray("PING")));
    }

    @Test
    @Order(7)
    void aclUserCredentialsAllowAccess() throws Exception {
        // Exercises end-to-end that the proxy resolves auth through the ACL's user.
        try (Socket socket = openSocket(authPort)) {
            write(socket, respArray("AUTH", AUTH_USER, AUTH_PASSWORD));
            assertEquals("+OK\r\n", readLine(socket));

            write(socket, respArray("PING"));
            assertEquals("+PONG\r\n", readLine(socket));
        }
    }

    @Test
    @Order(8)
    void wrongPasswordRejected() throws Exception {
        assertEquals("-ERR invalid username-password pair or user is disabled.\r\n",
                sendCommand(authPort, respArray("AUTH", AUTH_USER, "wrong-password")));
    }

    @Test
    @Order(9)
    void deleteClusterReleasesProxyPortForReuse() {
        deleteCluster(OPEN_CLUSTER)
            .then()
                .statusCode(200)
                .body("Cluster.Name", equalTo(OPEN_CLUSTER));

        int reusedPort = memorydb("CreateCluster",
                "{\"ClusterName\":\"" + OPEN_CLUSTER + "-reused\",\"ACLName\":\"open-access\"}")
            .then()
                .statusCode(200)
                .body("Cluster.ClusterEndpoint.Address", equalTo("localhost"))
            .extract()
                .path("Cluster.ClusterEndpoint.Port");

        assertEquals(openPort, reusedPort);

        deleteCluster(OPEN_CLUSTER + "-reused").then().statusCode(200);
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private static Response memorydb(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonMemoryDB." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
            .when()
                .post("/");
    }

    private static Response deleteCluster(String name) {
        return memorydb("DeleteCluster", "{\"ClusterName\":\"" + name + "\"}");
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

    private static Socket openSocket(int port) throws IOException {
        Socket socket = new Socket("localhost", port);
        socket.setSoTimeout(SOCKET_TIMEOUT_MS);
        return socket;
    }

    private static String sendCommand(int port, String command) throws Exception {
        try (Socket socket = openSocket(port)) {
            write(socket, command);
            return readLine(socket);
        }
    }

    private static void write(Socket socket, String command) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(command.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String readLine(Socket socket) throws IOException {
        for (int attempt = 1; attempt <= READ_LINE_MAX_ATTEMPTS; attempt++) {
            try {
                return readLineOnce(socket);
            } catch (SocketTimeoutException e) {
                if (attempt == READ_LINE_MAX_ATTEMPTS) {
                    throw new IOException("Redis response timed out after " + READ_LINE_MAX_ATTEMPTS
                            + " attempts (" + SOCKET_TIMEOUT_MS + "ms each). Confirm Docker is running and "
                            + "the Valkey container is healthy.", e);
                }
            }
        }
        throw new AssertionError("unreachable");
    }

    private static String readLineOnce(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        byte[] buffer = new byte[256];
        int offset = 0;
        while (offset < buffer.length) {
            int read;
            try {
                read = in.read();
            } catch (SocketTimeoutException e) {
                if (offset == 0) {
                    throw e;
                }
                throw new IOException("Incomplete Redis line (" + offset + " bytes) before read timeout.", e);
            }
            if (read == -1) {
                break;
            }
            buffer[offset++] = (byte) read;
            if (offset >= 2 && buffer[offset - 2] == '\r' && buffer[offset - 1] == '\n') {
                break;
            }
        }
        return new String(buffer, 0, offset, StandardCharsets.UTF_8);
    }

    private static String respArray(String... parts) {
        StringBuilder sb = new StringBuilder();
        sb.append("*").append(parts.length).append("\r\n");
        for (String part : parts) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            sb.append("$").append(bytes.length).append("\r\n");
            sb.append(part).append("\r\n");
        }
        return sb.toString();
    }
}
