package io.github.hectorvent.floci.services.cognito;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cognito.model.CognitoGroup;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.cognito.model.UserPoolClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CognitoServiceTest {

    private CognitoService service;

    @BeforeEach
    void setUp() {
        service = new CognitoService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>()
        );
    }

    private UserPool createPoolAndUser() {
        UserPool pool = service.createUserPool("TestPool", "us-east-1");
        service.adminCreateUser(pool.getId(), "alice", Map.of("email", "alice@example.com"), "TempPass1!");
        service.adminSetUserPassword(pool.getId(), "alice", "Perm1234!", true);
        return pool;
    }

    // =========================================================================
    // Groups
    // =========================================================================

    @Test
    void createGroup() {
        UserPool pool = service.createUserPool("TestPool", "us-east-1");
        CognitoGroup group = service.createGroup(pool.getId(), "admins", "Admin group", 1, null);

        assertEquals("admins", group.getGroupName());
        assertEquals(pool.getId(), group.getUserPoolId());
        assertEquals("Admin group", group.getDescription());
        assertEquals(1, group.getPrecedence());
        assertNull(group.getRoleArn());
        assertTrue(group.getCreationDate() > 0);
        assertTrue(group.getLastModifiedDate() > 0);
    }

    @Test
    void createGroupDuplicateThrows() {
        UserPool pool = service.createUserPool("TestPool", "us-east-1");
        service.createGroup(pool.getId(), "admins", "Admin group", 1, null);

        assertThrows(AwsException.class, () ->
                service.createGroup(pool.getId(), "admins", "Another desc", 2, null));
    }

    @Test
    void getGroup() {
        UserPool pool = service.createUserPool("TestPool", "us-east-1");
        service.createGroup(pool.getId(), "admins", "Admin group", 1, null);

        CognitoGroup fetched = service.getGroup(pool.getId(), "admins");
        assertEquals("admins", fetched.getGroupName());
        assertEquals(pool.getId(), fetched.getUserPoolId());
        assertEquals("Admin group", fetched.getDescription());
        assertEquals(1, fetched.getPrecedence());
    }

    @Test
    void getGroupNotFoundThrows() {
        UserPool pool = service.createUserPool("TestPool", "us-east-1");

        assertThrows(AwsException.class, () ->
                service.getGroup(pool.getId(), "nonexistent"));
    }

    @Test
    void listGroups() {
        UserPool pool = service.createUserPool("TestPool", "us-east-1");
        service.createGroup(pool.getId(), "admins", "Admin group", 1, null);
        service.createGroup(pool.getId(), "editors", "Editor group", 2, null);

        List<CognitoGroup> groups = service.listGroups(pool.getId());
        assertEquals(2, groups.size());
    }

    @Test
    void deleteGroup() {
        UserPool pool = service.createUserPool("TestPool", "us-east-1");
        service.createGroup(pool.getId(), "admins", "Admin group", 1, null);

        service.deleteGroup(pool.getId(), "admins");

        assertThrows(AwsException.class, () ->
                service.getGroup(pool.getId(), "admins"));
    }

    @Test
    void deleteGroupCleansUpUserMembership() {
        UserPool pool = createPoolAndUser();
        service.createGroup(pool.getId(), "admins", "Admin group", 1, null);
        service.adminAddUserToGroup(pool.getId(), "admins", "alice");

        service.deleteGroup(pool.getId(), "admins");

        CognitoUser user = service.adminGetUser(pool.getId(), "alice");
        assertTrue(user.getGroupNames().isEmpty());
    }

    @Test
    void adminDeleteUserCleansUpGroupMembership() {
        UserPool pool = createPoolAndUser();
        service.createGroup(pool.getId(), "admins", "Admin group", 1, null);
        service.adminAddUserToGroup(pool.getId(), "admins", "alice");

        service.adminDeleteUser(pool.getId(), "alice");

        CognitoGroup group = service.getGroup(pool.getId(), "admins");
        assertFalse(group.getUserNames().contains("alice"));
    }

    // =========================================================================
    // Group membership
    // =========================================================================

    @Test
    void adminAddUserToGroup() {
        UserPool pool = createPoolAndUser();
        service.createGroup(pool.getId(), "admins", "Admin group", 1, null);

        service.adminAddUserToGroup(pool.getId(), "admins", "alice");

        CognitoGroup group = service.getGroup(pool.getId(), "admins");
        assertTrue(group.getUserNames().contains("alice"));

        CognitoUser user = service.adminGetUser(pool.getId(), "alice");
        assertTrue(user.getGroupNames().contains("admins"));
    }

    @Test
    void adminAddUserToGroupIdempotent() {
        UserPool pool = createPoolAndUser();
        service.createGroup(pool.getId(), "admins", "Admin group", 1, null);

        service.adminAddUserToGroup(pool.getId(), "admins", "alice");
        service.adminAddUserToGroup(pool.getId(), "admins", "alice");

        CognitoGroup group = service.getGroup(pool.getId(), "admins");
        assertEquals(1, group.getUserNames().size());
    }

    @Test
    void adminRemoveUserFromGroup() {
        UserPool pool = createPoolAndUser();
        service.createGroup(pool.getId(), "admins", "Admin group", 1, null);
        service.adminAddUserToGroup(pool.getId(), "admins", "alice");

        service.adminRemoveUserFromGroup(pool.getId(), "admins", "alice");

        CognitoGroup group = service.getGroup(pool.getId(), "admins");
        assertFalse(group.getUserNames().contains("alice"));

        CognitoUser user = service.adminGetUser(pool.getId(), "alice");
        assertFalse(user.getGroupNames().contains("admins"));
    }

    @Test
    void adminListGroupsForUser() {
        UserPool pool = createPoolAndUser();
        service.createGroup(pool.getId(), "admins", "Admin group", 1, null);
        service.createGroup(pool.getId(), "editors", "Editor group", 2, null);
        service.adminAddUserToGroup(pool.getId(), "admins", "alice");
        service.adminAddUserToGroup(pool.getId(), "editors", "alice");

        List<CognitoGroup> groups = service.adminListGroupsForUser(pool.getId(), "alice");
        assertEquals(2, groups.size());
    }

    @Test
    void adminAddUserToGroupNonexistentGroupThrows() {
        UserPool pool = createPoolAndUser();

        assertThrows(AwsException.class, () ->
                service.adminAddUserToGroup(pool.getId(), "nonexistent", "alice"));
    }

    @Test
    void adminAddUserToGroupNonexistentUserThrows() {
        UserPool pool = service.createUserPool("TestPool", "us-east-1");
        service.createGroup(pool.getId(), "admins", "Admin group", 1, null);

        assertThrows(AwsException.class, () ->
                service.adminAddUserToGroup(pool.getId(), "admins", "nonexistent"));
    }

    // =========================================================================
    // JWT groups claim
    // =========================================================================

    @Test
    @SuppressWarnings("unchecked")
    void jwtContainsGroupsClaim() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "test-client");
        String clientId = client.getClientId();

        service.createGroup(pool.getId(), "admins", "Admin group", 1, null);
        service.adminAddUserToGroup(pool.getId(), "admins", "alice");

        Map<String, Object> authResult = service.initiateAuth(
                clientId, "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));

        Map<String, Object> authenticationResult = (Map<String, Object>) authResult.get("AuthenticationResult");
        String accessToken = (String) authenticationResult.get("AccessToken");

        // Decode the JWT payload (second segment)
        String[] parts = accessToken.split("\\.");
        String payloadJson = new String(
                Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);

        assertTrue(payloadJson.contains("\"cognito:groups\":[\"admins\"]"),
                "JWT payload should contain cognito:groups claim with the group name");
    }

    @Test
    @SuppressWarnings("unchecked")
    void jwtEscapesSpecialCharsInGroupName() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "test-client");

        String specialGroup = "group\"with\\special\nchars";
        service.createGroup(pool.getId(), specialGroup, null, null, null);
        service.adminAddUserToGroup(pool.getId(), specialGroup, "alice");

        Map<String, Object> authResult = service.initiateAuth(
                client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));

        Map<String, Object> auth = (Map<String, Object>) authResult.get("AuthenticationResult");
        String token = (String) auth.get("AccessToken");
        String payloadJson = new String(
                Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);

        assertTrue(payloadJson.contains("cognito:groups"),
                "JWT should contain cognito:groups claim");
        assertTrue(payloadJson.contains("group\\\"with\\\\special\\nchars"),
                "Group name should be properly JSON-escaped in JWT payload");
    }

    // =========================================================================
    // deleteUserPool cascades groups
    // =========================================================================

    @Test
    void deleteUserPoolCascadesGroups() {
        UserPool pool = service.createUserPool("TestPool", "us-east-1");
        service.createGroup(pool.getId(), "admins", "Admin group", 1, null);
        service.createGroup(pool.getId(), "editors", "Editor group", 2, null);

        // Verify groups exist before deletion
        assertEquals(2, service.listGroups(pool.getId()).size());

        service.deleteUserPool(pool.getId());

        // Re-create pool with same ID is not possible, so verify by re-creating
        // the pool and confirming no groups carry over
        UserPool newPool = service.createUserPool("TestPool2", "us-east-1");
        // The original pool's groups should be gone — listing on the deleted pool
        // should throw since the pool no longer exists
        assertThrows(AwsException.class, () ->
                service.listGroups(pool.getId()));
    }
}
