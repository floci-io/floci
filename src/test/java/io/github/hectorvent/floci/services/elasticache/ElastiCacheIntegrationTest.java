package io.github.hectorvent.floci.services.elasticache;

import io.quarkus.test.junit.QuarkusTest;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElastiCacheIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260412/us-east-1/elasticache/aws4_request";
    private static final String GROUP_ID = "it-ec-group";
    private static final String USER_ID = "it-ec-user";
    private static final String USER_NAME = "it-ec-user-name";
    private static final String INITIAL_PASSWORD = "test-password-1";
    private static final String UPDATED_PASSWORD = "test-password-2";
    private static final String GROUP_AUTH_TOKEN = "group-auth-token";
    private static final String CROSS_GROUP_ID = "it-ec-group-cross";
    private static final String CROSS_GROUP_AUTH_TOKEN = "cross-group-token";

    private static int firstProxyPort;
    private static int crossGroupPort;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker daemon must be available for ElastiCache integration tests");
    }

    @Test
    @Order(1)
    void createReplicationGroup() {
        firstProxyPort =
                given()
                    .formParam("Action", "CreateReplicationGroup")
                    .formParam("ReplicationGroupId", GROUP_ID)
                    .formParam("ReplicationGroupDescription", "Integration test group")
                    .formParam("AuthToken", GROUP_AUTH_TOKEN)
                    .header("Authorization", AUTH_HEADER)
                .when()
                    .post("/")
                .then()
                    .statusCode(200)
                    .contentType("application/xml")
                    .body("CreateReplicationGroupResponse.CreateReplicationGroupResult.ReplicationGroup.ReplicationGroupId", equalTo(GROUP_ID))
                    .body("CreateReplicationGroupResponse.CreateReplicationGroupResult.ReplicationGroup.Status", equalTo("available"))
                    .body("CreateReplicationGroupResponse.CreateReplicationGroupResult.ReplicationGroup.AuthTokenEnabled", equalTo("true"))
                    .body("CreateReplicationGroupResponse.CreateReplicationGroupResult.ReplicationGroup.ConfigurationEndpoint.Address", equalTo("localhost"))
                    .body("CreateReplicationGroupResponse.CreateReplicationGroupResult.ReplicationGroup.ConfigurationEndpoint.Port", notNullValue())
                .extract()
                    .xmlPath()
                    .getInt("CreateReplicationGroupResponse.CreateReplicationGroupResult.ReplicationGroup.ConfigurationEndpoint.Port");
    }

    @Test
    @Order(2)
    void describeReplicationGroupsIncludesCreatedGroup() {
        given()
            .formParam("Action", "DescribeReplicationGroups")
            .formParam("ReplicationGroupId", GROUP_ID)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeReplicationGroupsResponse.DescribeReplicationGroupsResult.ReplicationGroups.ReplicationGroup.ReplicationGroupId",
                    equalTo(GROUP_ID))
            .body("DescribeReplicationGroupsResponse.DescribeReplicationGroupsResult.ReplicationGroups.ReplicationGroup.ConfigurationEndpoint.Port",
                    equalTo(String.valueOf(firstProxyPort)));
    }

    @Test
    @Order(3)
    void passwordProtectedGroupRejectsUnauthenticatedCommand() throws Exception {
        String reply = sendCommand(firstProxyPort, respArray("PING"));
        assertEquals("-NOAUTH Authentication required.\r\n", reply);
    }

    @Test
    @Order(4)
    void groupAuthTokenAllowsAuthThenPing() throws Exception {
        try (Socket socket = openSocket(firstProxyPort)) {
            write(socket, respArray("AUTH", GROUP_AUTH_TOKEN));
            assertEquals("+OK\r\n", readLine(socket));

            write(socket, respArray("PING"));
            assertEquals("+PONG\r\n", readLine(socket));
        }
    }

    @Test
    @Order(5)
    void wrongPasswordIsRejected() throws Exception {
        String reply = sendCommand(firstProxyPort, respArray("AUTH", "wrong-password"));
        assertEquals("-ERR invalid username-password pair or user is disabled.\r\n", reply);
    }

    @Test
    @Order(6)
    void createUser() {
        given()
            .formParam("Action", "CreateUser")
            .formParam("UserId", USER_ID)
            .formParam("UserName", USER_NAME)
            .formParam("AuthenticationMode.Type", "password")
            .formParam("AuthenticationMode.Passwords.member.1", INITIAL_PASSWORD)
            .formParam("AccessString", "on ~* +@all")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateUserResponse.CreateUserResult.UserId", equalTo(USER_ID))
            .body("CreateUserResponse.CreateUserResult.UserName", equalTo(USER_NAME))
            .body("CreateUserResponse.CreateUserResult.Authentication.Type", equalTo("password"))
            .body("CreateUserResponse.CreateUserResult.Authentication.PasswordCount", equalTo("1"));
    }

    @Test
    @Order(7)
    void unassociatedUserIsRejected() throws Exception {
        // Before associating the user with the group, auth should fail
        String reply = sendCommand(firstProxyPort, respArray("AUTH", USER_NAME, INITIAL_PASSWORD));
        assertEquals("-ERR invalid username-password pair or user is disabled.\r\n", reply);
    }

    @Test
    @Order(8)
    void associateUserWithGroup() {
        given()
            .formParam("Action", "ModifyReplicationGroup")
            .formParam("ReplicationGroupId", GROUP_ID)
            .formParam("UserGroupIdsToAdd.member.1", USER_ID)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ModifyReplicationGroupResponse.ModifyReplicationGroupResult.ReplicationGroup.ReplicationGroupId", equalTo(GROUP_ID));
    }

    @Test
    @Order(9)
    void describeUsersIncludesCreatedUser() {
        given()
            .formParam("Action", "DescribeUsers")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeUsersResponse.DescribeUsersResult.Users.member.UserId", equalTo(USER_ID))
            .body("DescribeUsersResponse.DescribeUsersResult.Users.member.UserName", equalTo(USER_NAME));
    }

    @Test
    @Order(10)
    void crossGroupAuthIsRejected() throws Exception {
        // Create a second group and verify the user (associated with GROUP_ID only) cannot auth
        crossGroupPort = given()
                .formParam("Action", "CreateReplicationGroup")
                .formParam("ReplicationGroupId", CROSS_GROUP_ID)
                .formParam("ReplicationGroupDescription", "Cross-group isolation test")
                .formParam("AuthToken", CROSS_GROUP_AUTH_TOKEN)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
            .extract()
                .xmlPath()
                .getInt("CreateReplicationGroupResponse.CreateReplicationGroupResult.ReplicationGroup.ConfigurationEndpoint.Port");

        // User associated with GROUP_ID should be rejected on CROSS_GROUP_ID
        String reply = sendCommand(crossGroupPort, respArray("AUTH", USER_NAME, INITIAL_PASSWORD));
        assertEquals("-ERR invalid username-password pair or user is disabled.\r\n", reply);

        // Clean up the cross-group
        given()
            .formParam("Action", "DeleteReplicationGroup")
            .formParam("ReplicationGroupId", CROSS_GROUP_ID)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(11)
    void userPasswordAuthWorks() throws Exception {
        try (Socket socket = openSocket(firstProxyPort)) {
            write(socket, respArray("AUTH", USER_NAME, INITIAL_PASSWORD));
            assertEquals("+OK\r\n", readLine(socket));

            write(socket, respArray("PING"));
            assertEquals("+PONG\r\n", readLine(socket));
        }
    }

    @Test
    @Order(12)
    void modifyUserPasswordInvalidatesOldPasswordAndAcceptsNewPassword() throws Exception {
        given()
            .formParam("Action", "ModifyUser")
            .formParam("UserId", USER_ID)
            .formParam("AuthenticationMode.Passwords.member.1", UPDATED_PASSWORD)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ModifyUserResponse.ModifyUserResult.UserId", equalTo(USER_ID))
            .body("ModifyUserResponse.ModifyUserResult.Authentication.PasswordCount", equalTo("1"));

        String oldReply = sendCommand(firstProxyPort, respArray("AUTH", USER_NAME, INITIAL_PASSWORD));
        assertEquals("-ERR invalid username-password pair or user is disabled.\r\n", oldReply);

        try (Socket socket = openSocket(firstProxyPort)) {
            write(socket, respArray("AUTH", USER_NAME, UPDATED_PASSWORD));
            assertEquals("+OK\r\n", readLine(socket));

            write(socket, respArray("PING"));
            assertEquals("+PONG\r\n", readLine(socket));
        }
    }

    @Test
    @Order(13)
    void deleteUserRemovesUserFromDescribeUsers() {
        given()
            .formParam("Action", "DeleteUser")
            .formParam("UserId", USER_ID)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeleteUserResponse.DeleteUserResult.UserId", equalTo(USER_ID));

        given()
            .formParam("Action", "DescribeUsers")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeUsersResponse.DescribeUsersResult.Users.member.UserId", org.hamcrest.Matchers.not(equalTo(USER_ID)));
    }

    @Test
    @Order(14)
    void deleteReplicationGroupReleasesProxyPortForReuse() {
        given()
            .formParam("Action", "DeleteReplicationGroup")
            .formParam("ReplicationGroupId", GROUP_ID)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeleteReplicationGroupResponse.DeleteReplicationGroupResult.ReplicationGroup.ReplicationGroupId", equalTo(GROUP_ID));

        int reusedPort =
                given()
                    .formParam("Action", "CreateReplicationGroup")
                    .formParam("ReplicationGroupId", GROUP_ID + "-reused")
                    .formParam("ReplicationGroupDescription", "Reused port group")
                    .formParam("AuthToken", GROUP_AUTH_TOKEN)
                    .header("Authorization", AUTH_HEADER)
                .when()
                    .post("/")
                .then()
                    .statusCode(200)
                    .body("CreateReplicationGroupResponse.CreateReplicationGroupResult.ReplicationGroup.ConfigurationEndpoint.Address", equalTo("localhost"))
                .extract()
                    .xmlPath()
                    .getInt("CreateReplicationGroupResponse.CreateReplicationGroupResult.ReplicationGroup.ConfigurationEndpoint.Port");

        assertEquals(firstProxyPort, reusedPort);

        given()
            .formParam("Action", "DeleteReplicationGroup")
            .formParam("ReplicationGroupId", GROUP_ID + "-reused")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeleteReplicationGroupResponse.DeleteReplicationGroupResult.ReplicationGroup.ReplicationGroupId",
                    equalTo(GROUP_ID + "-reused"));
    }

    private static boolean isDockerAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true)
                    .start();
            int exit = process.waitFor();
            return exit == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static Socket openSocket(int port) throws IOException {
        Socket socket = new Socket("localhost", port);
        socket.setSoTimeout(5000);
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
        InputStream in = socket.getInputStream();
        byte[] buffer = new byte[256];
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read();
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
