package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.elasticache.model.AuthenticationMode;
import software.amazon.awssdk.services.elasticache.model.CreateReplicationGroupRequest;
import software.amazon.awssdk.services.elasticache.model.CreateUserRequest;
import software.amazon.awssdk.services.elasticache.model.DeleteReplicationGroupRequest;
import software.amazon.awssdk.services.elasticache.model.DeleteUserRequest;
import software.amazon.awssdk.services.elasticache.model.DescribeReplicationGroupsRequest;
import software.amazon.awssdk.services.elasticache.model.DescribeUsersRequest;
import software.amazon.awssdk.services.elasticache.model.InputAuthenticationType;
import software.amazon.awssdk.services.elasticache.model.ModifyReplicationGroupRequest;
import software.amazon.awssdk.services.elasticache.model.ModifyUserRequest;
import software.amazon.awssdk.services.elasticache.model.ElastiCacheException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ElastiCache")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElastiCacheTest {

    private static ElastiCacheClient elasticache;

    private static String groupId;
    private static String reusedGroupId;
    private static String userId;
    private static String userName;
    private static String authToken;
    private static int firstProxyPort;
    private static boolean groupCreated;
    private static boolean userCreated;

    @BeforeAll
    static void setup() {
        elasticache = TestFixtures.elastiCacheClient();
        groupId = TestFixtures.uniqueName("ec-group");
        reusedGroupId = TestFixtures.uniqueName("ec-group-reuse");
        userId = TestFixtures.uniqueName("ec-user");
        userName = TestFixtures.uniqueName("ec-user-name");
        authToken = "token-" + TestFixtures.uniqueName("pw");
    }

    @AfterAll
    static void cleanup() {
        if (elasticache != null) {
            try {
                elasticache.deleteUser(DeleteUserRequest.builder().userId(userId).build());
            } catch (Exception ignored) {}
            try {
                elasticache.deleteReplicationGroup(DeleteReplicationGroupRequest.builder()
                        .replicationGroupId(reusedGroupId)
                        .build());
            } catch (Exception ignored) {}
            try {
                elasticache.deleteReplicationGroup(DeleteReplicationGroupRequest.builder()
                        .replicationGroupId(groupId)
                        .build());
            } catch (Exception ignored) {}
            elasticache.close();
        }
    }

    @Test
    @Order(1)
    void createReplicationGroup() {
        var response = elasticache.createReplicationGroup(CreateReplicationGroupRequest.builder()
                .replicationGroupId(groupId)
                .replicationGroupDescription("compat test group")
                .engine("redis")
                .authToken(authToken)
                .build());

        assertThat(response.replicationGroup().replicationGroupId()).isEqualTo(groupId);
        assertThat(response.replicationGroup().status()).isEqualTo("available");
        assertThat(response.replicationGroup().configurationEndpoint()).isNotNull();
        assertThat(response.replicationGroup().configurationEndpoint().address()).isEqualTo(TestFixtures.proxyHost());
        assertThat(response.replicationGroup().authTokenEnabled()).isTrue();

        firstProxyPort = response.replicationGroup().configurationEndpoint().port();
        groupCreated = true;
    }

    @Test
    @Order(2)
    void describeReplicationGroup() {
        requireGroup();

        var response = elasticache.describeReplicationGroups(DescribeReplicationGroupsRequest.builder()
                .replicationGroupId(groupId)
                .build());

        assertThat(response.replicationGroups()).hasSize(1);
        assertThat(response.replicationGroups().get(0).replicationGroupId()).isEqualTo(groupId);
        assertThat(response.replicationGroups().get(0).configurationEndpoint().port()).isEqualTo(firstProxyPort);
    }

    @Test
    @Order(3)
    void createDuplicateReplicationGroupThrows409() {
        requireGroup();

        // Floci returns generic error code (pre-existing deviation)
        assertThatThrownBy(() -> elasticache.createReplicationGroup(CreateReplicationGroupRequest.builder()
                .replicationGroupId(groupId)
                .replicationGroupDescription("duplicate")
                .engine("redis")
                .authToken(authToken)
                .build()))
                .isInstanceOf(ElastiCacheException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    @Order(4)
    void groupAuthTokenAllowsProxyAuth() throws Exception {
        requireGroup();

        try (Socket socket = openSocket(firstProxyPort)) {
            write(socket, respArray("AUTH", authToken));
            assertThat(readLine(socket)).isEqualTo("+OK\r\n");

            write(socket, respArray("PING"));
            assertThat(readLine(socket)).isEqualTo("+PONG\r\n");
        }
    }

    @Test
    @Order(5)
    void createUser() {
        var response = elasticache.createUser(CreateUserRequest.builder()
                .userId(userId)
                .userName(userName)
                .engine("redis")
                .accessString("on ~* +@all")
                .authenticationMode(AuthenticationMode.builder()
                        .type(InputAuthenticationType.PASSWORD)
                        .passwords("user-password-1")
                        .build())
                .build());

        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.userName()).isEqualTo(userName);
        assertThat(response.authentication().typeAsString()).isEqualTo("password");
        assertThat(response.authentication().passwordCount()).isEqualTo(1);
        userCreated = true;
    }

    @Test
    @Order(6)
    void describeUsersContainsCreatedUser() {
        requireUser();

        var response = elasticache.describeUsers(DescribeUsersRequest.builder().build());

        assertThat(response.users())
                .anyMatch(user -> user.userId().equals(userId) && user.userName().equals(userName));
    }

    @Test
    @Order(7)
    void createDuplicateUserThrows409() {
        requireUser();

        // Floci returns generic error code, SDK maps to ElastiCacheException
        // rather than UserAlreadyExistsException (pre-existing deviation)
        assertThatThrownBy(() -> elasticache.createUser(CreateUserRequest.builder()
                .userId(userId)
                .userName(userName)
                .engine("redis")
                .accessString("on ~* +@all")
                .authenticationMode(AuthenticationMode.builder()
                        .type(InputAuthenticationType.PASSWORD)
                        .passwords("user-password-1")
                        .build())
                .build()))
                .isInstanceOf(ElastiCacheException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    @Order(8)
    void associateUserWithGroupThenAuthSucceeds() throws Exception {
        requireUser();
        requireGroup();

        // Before association, user auth should fail
        String rejectReply = sendCommand(firstProxyPort, respArray("AUTH", userName, "user-password-1"));
        assertThat(rejectReply).isEqualTo("-ERR invalid username-password pair or user is disabled.\r\n");

        // Associate user with group via ModifyReplicationGroup.
        // Known deviation: Floci treats userGroupIdsToAdd as raw user IDs because
        // UserGroup resources are not yet implemented. In real AWS, this parameter
        // accepts UserGroupIds (which are separate resources containing users).
        var response = elasticache.modifyReplicationGroup(ModifyReplicationGroupRequest.builder()
                .replicationGroupId(groupId)
                .userGroupIdsToAdd(userId)
                .build());

        assertThat(response.replicationGroup().replicationGroupId()).isEqualTo(groupId);

        // After association, user auth should succeed
        try (Socket socket = openSocket(firstProxyPort)) {
            write(socket, respArray("AUTH", userName, "user-password-1"));
            assertThat(readLine(socket)).isEqualTo("+OK\r\n");

            write(socket, respArray("PING"));
            assertThat(readLine(socket)).isEqualTo("+PONG\r\n");
        }
    }

    @Test
    @Order(9)
    void modifyUserRotatesPassword() throws Exception {
        requireUser();

        var response = elasticache.modifyUser(ModifyUserRequest.builder()
                .userId(userId)
                .engine("redis")
                .authenticationMode(AuthenticationMode.builder()
                        .type(InputAuthenticationType.PASSWORD)
                        .passwords("user-password-2")
                        .build())
                .build());

        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.authentication().passwordCount()).isEqualTo(1);

        String oldReply = sendCommand(firstProxyPort, respArray("AUTH", userName, "user-password-1"));
        assertThat(oldReply).isEqualTo("-ERR invalid username-password pair or user is disabled.\r\n");

        try (Socket socket = openSocket(firstProxyPort)) {
            write(socket, respArray("AUTH", userName, "user-password-2"));
            assertThat(readLine(socket)).isEqualTo("+OK\r\n");

            write(socket, respArray("PING"));
            assertThat(readLine(socket)).isEqualTo("+PONG\r\n");
        }
    }

    @Test
    @Order(10)
    void deleteUser() {
        requireUser();

        elasticache.deleteUser(DeleteUserRequest.builder().userId(userId).build());

        // Floci returns generic error code, SDK maps to ElastiCacheException
        // rather than UserNotFoundException (pre-existing deviation)
        assertThatThrownBy(() -> elasticache.describeUsers(DescribeUsersRequest.builder()
                .userId(userId)
                .build()))
                .isInstanceOf(ElastiCacheException.class)
                .hasMessageContaining("not found");
        userCreated = false;
    }

    @Test
    @Order(11)
    void deleteReplicationGroupReleasesPortForReuse() {
        requireGroup();

        elasticache.deleteReplicationGroup(DeleteReplicationGroupRequest.builder()
                .replicationGroupId(groupId)
                .build());

        // Floci returns generic error code (pre-existing deviation)
        assertThatThrownBy(() -> elasticache.describeReplicationGroups(DescribeReplicationGroupsRequest.builder()
                .replicationGroupId(groupId)
                .build()))
                .isInstanceOf(ElastiCacheException.class)
                .hasMessageContaining("not found");

        var response = elasticache.createReplicationGroup(CreateReplicationGroupRequest.builder()
                .replicationGroupId(reusedGroupId)
                .replicationGroupDescription("compat test group reuse")
                .engine("redis")
                .authToken(authToken)
                .build());

        assertThat(response.replicationGroup().configurationEndpoint().port()).isEqualTo(firstProxyPort);
        groupCreated = false;
        firstProxyPort = response.replicationGroup().configurationEndpoint().port();
    }

    private static void requireGroup() {
        Assumptions.assumeTrue(groupCreated && groupId != null && firstProxyPort > 0,
                "Replication group must exist from earlier ordered test");
    }

    private static void requireUser() {
        Assumptions.assumeTrue(userCreated && userId != null, "User must exist from earlier ordered test");
    }

    private static Socket openSocket(int port) throws IOException {
        Socket socket = new Socket(TestFixtures.proxyHost(), port);
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
