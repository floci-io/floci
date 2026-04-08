package io.github.hectorvent.floci.services.cognito;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cognito.model.CognitoGroup;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.cognito.model.UserPoolClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class CognitoServiceTest {

    private CognitoService service;
    private InMemoryStorage<String, CognitoGroup> groupStore;
    private RegionResolver regionResolver;

    @BeforeEach
    void setUp() {
        groupStore = new InMemoryStorage<>();
        regionResolver = new RegionResolver("us-east-1", "000000000000");
        service = new CognitoService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                groupStore,
                "http://localhost:4566",
                regionResolver
        );
    }

    private UserPool createPoolAndUser() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.adminCreateUser(pool.getId(), "alice", Map.of("email", "alice@example.com"), "TempPass1!");
        service.adminSetUserPassword(pool.getId(), "alice", "Perm1234!", true);
        return pool;
    }

    @Test
    void createUserPoolWithFullConfig() {
        List<Map<String, Object>> schema = List.of(
                Map.of("Name", "my-attr", "AttributeDataType", "String")
        );
        Map<String, Object> policies = Map.of(
                "PasswordPolicy", Map.of("MinimumLength", 12)
        );

        Map<String, Object> request = new HashMap<>();
        request.put("PoolName", "FullConfigPool");
        request.put("Schema", schema);
        request.put("Policies", policies);
        request.put("UsernameAttributes", List.of("email"));

        UserPool pool = service.createUserPool(request, "us-east-1");

        assertNotNull(pool.getId());
        assertEquals("FullConfigPool", pool.getName());
        assertEquals("arn:aws:cognito-idp:us-east-1:000000000000:userpool/" + pool.getId(), pool.getArn());
        assertEquals(schema, pool.getSchemaAttributes());
        assertEquals(policies, pool.getPolicies());
        assertEquals(List.of("email"), pool.getUsernameAttributes());
    }

    // =========================================================================
    // Groups
    // =========================================================================

    @Test
    void createGroup() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
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
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.createGroup(pool.getId(), "admins", "Admin group", 1, null);

        assertThrows(AwsException.class, () ->
                service.createGroup(pool.getId(), "admins", "Another desc", 2, null));
    }

    @Test
    void getGroup() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.createGroup(pool.getId(), "admins", "Admin group", 1, null);

        CognitoGroup fetched = service.getGroup(pool.getId(), "admins");
        assertEquals("admins", fetched.getGroupName());
        assertEquals(pool.getId(), fetched.getUserPoolId());
        assertEquals("Admin group", fetched.getDescription());
        assertEquals(1, fetched.getPrecedence());
    }

    @Test
    void getGroupNotFoundThrows() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");

        assertThrows(AwsException.class, () ->
                service.getGroup(pool.getId(), "nonexistent"));
    }

    @Test
    void listGroups() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.createGroup(pool.getId(), "admins", "Admin group", 1, null);
        service.createGroup(pool.getId(), "editors", "Editor group", 2, null);

        List<CognitoGroup> groups = service.listGroups(pool.getId());
        assertEquals(2, groups.size());
    }

    @Test
    void deleteGroup() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
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
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
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
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "test-client", false, false, List.of(), List.of());
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
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "test-client", false, false, List.of(), List.of());

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
    // Issue #68 — sub attribute and AdminUserGlobalSignOut
    // =========================================================================

    @Test
    void adminCreateUserAutoGeneratesSub() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        CognitoUser user = service.adminCreateUser(pool.getId(), "bob",
                Map.of("email", "bob@example.com"), null);

        assertTrue(user.getAttributes().containsKey("sub"),
                "adminCreateUser should auto-generate a sub attribute");
        assertFalse(user.getAttributes().get("sub").isBlank());
    }

    @Test
    void adminCreateUserPreservesExplicitSub() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        String explicitSub = "aaaaaaaa-1111-2222-3333-444444444444";
        CognitoUser user = service.adminCreateUser(pool.getId(), "bob",
                Map.of("email", "bob@example.com", "sub", explicitSub), null);

        assertEquals(explicitSub, user.getAttributes().get("sub"),
                "adminCreateUser should not overwrite an explicitly provided sub");
    }

    @Test
    void signUpAutoGeneratesSub() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "test-client",
                false, false, List.of(), List.of());

        CognitoUser user = service.signUp(client.getClientId(),
                "carol", "Pass1234!", Map.of("email", "carol@example.com"));

        assertTrue(user.getAttributes().containsKey("sub"),
                "signUp should auto-generate a sub attribute");
        assertFalse(user.getAttributes().get("sub").isBlank());
    }

    @Test
    @SuppressWarnings("unchecked")
    void jwtSubMatchesStoredSubAttribute() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "test-client",
                false, false, List.of(), List.of());

        String storedSub = service.adminGetUser(pool.getId(), "alice")
                .getAttributes().get("sub");
        assertNotNull(storedSub, "user should have a sub attribute after creation");

        Map<String, Object> authResult = service.initiateAuth(
                client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));

        Map<String, Object> auth = (Map<String, Object>) authResult.get("AuthenticationResult");
        String token = (String) auth.get("AccessToken");
        String payloadJson = new String(
                Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);

        assertTrue(payloadJson.contains("\"sub\":\"" + storedSub + "\""),
                "JWT sub claim must match the stored sub attribute, not be randomly generated");
    }

    @Test
    @SuppressWarnings("unchecked")
    void jwtSubIsConsistentAcrossMultipleLogins() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "test-client",
                false, false, List.of(), List.of());

        Function<String, String> extractSub = token -> {
            String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
            int start = payload.indexOf("\"sub\":\"") + 7;
            int end = payload.indexOf("\"", start);
            return payload.substring(start, end);
        };

        Map<String, Object> auth1 = (Map<String, Object>)
                ((Map<String, Object>) service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                        Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"))).get("AuthenticationResult");
        Map<String, Object> auth2 = (Map<String, Object>)
                ((Map<String, Object>) service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                        Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"))).get("AuthenticationResult");

        String sub1 = extractSub.apply((String) auth1.get("AccessToken"));
        String sub2 = extractSub.apply((String) auth2.get("AccessToken"));

        assertEquals(sub1, sub2, "JWT sub claim must be identical across multiple logins");
    }

    @Test
    void adminUserGlobalSignOutSucceedsForExistingUser() {
        UserPool pool = createPoolAndUser();
        assertDoesNotThrow(() -> service.adminUserGlobalSignOut(pool.getId(), "alice"));
    }

    @Test
    void adminUserGlobalSignOutThrowsForNonexistentUser() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        assertThrows(AwsException.class,
                () -> service.adminUserGlobalSignOut(pool.getId(), "ghost"));
    }

    // =========================================================================
    // Issue #229 — password verification
    // =========================================================================

    @Test
    void initiateAuthRejectsAnyPasswordWhenNoHashSet() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.adminCreateUser(pool.getId(), "bob", Map.of("email", "bob@example.com"), null);
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());

        AwsException ex = assertThrows(AwsException.class, () ->
                service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                        Map.of("USERNAME", "bob", "PASSWORD", "anything")));
        assertEquals("NotAuthorizedException", ex.getErrorCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void initiateAuthWorksAfterPasswordIsSet() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.adminCreateUser(pool.getId(), "bob", Map.of("email", "bob@example.com"), null);
        service.adminSetUserPassword(pool.getId(), "bob", "Perm1!", true);
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());

        Map<String, Object> result = service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "bob", "PASSWORD", "Perm1!"));
        assertNotNull(((Map<String, Object>) result.get("AuthenticationResult")).get("AccessToken"));
    }

    // =========================================================================
    // Issue #235 — AdminSetUserPassword(Permanent=false) changes the password
    // =========================================================================

    @Test
    @SuppressWarnings("unchecked")
    void adminSetUserPasswordPermanentFalseChangesPassword() {
        UserPool pool = createPoolAndUser(); // alice has permanent "Perm1234!"
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());

        service.adminSetUserPassword(pool.getId(), "alice", "NewTemp1!", false);

        // Old password now rejected
        assertThrows(AwsException.class, () ->
                service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                        Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!")));

        // New temp password triggers NEW_PASSWORD_REQUIRED challenge
        Map<String, Object> result = service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "NewTemp1!"));
        assertEquals("NEW_PASSWORD_REQUIRED", result.get("ChallengeName"));
    }

    // =========================================================================
    // Issue #228 — AccessToken contains client_id claim
    // =========================================================================

    @Test
    @SuppressWarnings("unchecked")
    void accessTokenContainsClientId() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());

        Map<String, Object> authResult = service.initiateAuth(
                client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));
        Map<String, Object> auth = (Map<String, Object>) authResult.get("AuthenticationResult");
        String accessToken = (String) auth.get("AccessToken");

        String payloadJson = new String(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]),
                StandardCharsets.UTF_8);
        assertTrue(payloadJson.contains("\"client_id\":\"" + client.getClientId() + "\""),
                "AccessToken should contain client_id claim matching the requesting client");
    }

    @Test
    @SuppressWarnings("unchecked")
    void idTokenDoesNotContainClientId() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());

        Map<String, Object> authResult = service.initiateAuth(
                client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));
        Map<String, Object> auth = (Map<String, Object>) authResult.get("AuthenticationResult");
        String idToken = (String) auth.get("IdToken");

        String payloadJson = new String(Base64.getUrlDecoder().decode(idToken.split("\\.")[1]),
                StandardCharsets.UTF_8);
        assertFalse(payloadJson.contains("\"client_id\""),
                "IdToken should not contain client_id claim");
    }

    // =========================================================================
    // Issue #220 — adminGetUser resolves sub UUID and email aliases
    // =========================================================================

    @Test
    void adminGetUserBySubUuid() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.adminCreateUser(pool.getId(), "bob", Map.of("email", "bob@example.com"), null);

        String sub = service.adminGetUser(pool.getId(), "bob").getAttributes().get("sub");
        assertNotNull(sub);

        CognitoUser found = service.adminGetUser(pool.getId(), sub);
        assertEquals("bob", found.getUsername());
    }

    @Test
    void adminGetUserByEmailAlias() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.adminCreateUser(pool.getId(), "bob", Map.of("email", "bob@example.com"), null);

        CognitoUser found = service.adminGetUser(pool.getId(), "bob@example.com");
        assertEquals("bob", found.getUsername());
    }

    // =========================================================================
    // Issue #233 — listUsers Filter
    // =========================================================================

    @Test
    void listUsersNoFilterReturnsAll() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.adminCreateUser(pool.getId(), "user1", Map.of("email", "user1@example.com"), null);
        service.adminCreateUser(pool.getId(), "user2", Map.of("email", "user2@example.com"), null);

        assertEquals(2, service.listUsers(pool.getId(), null).size());
    }

    @Test
    void listUsersFilterBySubExactMatch() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.adminCreateUser(pool.getId(), "user1", Map.of("email", "user1@example.com"), null);
        service.adminCreateUser(pool.getId(), "user2", Map.of("email", "user2@example.com"), null);

        String sub2 = service.adminGetUser(pool.getId(), "user2").getAttributes().get("sub");
        List<CognitoUser> result = service.listUsers(pool.getId(), "sub = \"" + sub2 + "\"");

        assertEquals(1, result.size());
        assertEquals("user2", result.get(0).getUsername());
    }

    @Test
    void listUsersFilterByEmailExactMatch() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.adminCreateUser(pool.getId(), "user1", Map.of("email", "user1@example.com"), null);
        service.adminCreateUser(pool.getId(), "user2", Map.of("email", "user2@example.com"), null);

        List<CognitoUser> result = service.listUsers(pool.getId(), "email = \"user1@example.com\"");
        assertEquals(1, result.size());
        assertEquals("user1", result.get(0).getUsername());
    }

    @Test
    void listUsersFilterByEmailPrefix() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.adminCreateUser(pool.getId(), "user1", Map.of("email", "alice@example.com"), null);
        service.adminCreateUser(pool.getId(), "user2", Map.of("email", "bob@example.com"), null);
        service.adminCreateUser(pool.getId(), "user3", Map.of("email", "alice2@example.com"), null);

        List<CognitoUser> result = service.listUsers(pool.getId(), "email ^= \"alice\"");
        assertEquals(2, result.size());
    }

    @Test
    void listUsersFilterNoMatchReturnsEmpty() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.adminCreateUser(pool.getId(), "user1", Map.of("email", "user1@example.com"), null);

        List<CognitoUser> result = service.listUsers(pool.getId(), "email = \"nobody@example.com\"");
        assertTrue(result.isEmpty());
    }

    // =========================================================================
    // Issue #234 — GetTokensFromRefreshToken
    // =========================================================================

    @Test
    @SuppressWarnings("unchecked")
    void refreshTokenIsStructuredAndDecodable() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());

        Map<String, Object> authResult = service.initiateAuth(
                client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));
        Map<String, Object> auth = (Map<String, Object>) authResult.get("AuthenticationResult");
        String refreshToken = (String) auth.get("RefreshToken");

        assertNotNull(refreshToken);
        // Should be parseable as base64 structured token
        String decoded = new String(Base64.getDecoder().decode(refreshToken), StandardCharsets.UTF_8);
        String[] parts = decoded.split("\\|", 4);
        assertEquals(4, parts.length, "Refresh token should encode 4 pipe-separated fields");
        assertEquals(pool.getId(), parts[0]);
        assertEquals("alice", parts[1]);
        assertEquals(client.getClientId(), parts[2]);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getTokensFromRefreshTokenReturnsNewAccessAndIdTokens() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());

        Map<String, Object> authResult = service.initiateAuth(
                client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));
        String refreshToken = (String) ((Map<String, Object>) authResult.get("AuthenticationResult")).get("RefreshToken");

        Map<String, Object> refreshResult = service.getTokensFromRefreshToken(client.getClientId(), refreshToken);
        Map<String, Object> refreshAuth = (Map<String, Object>) refreshResult.get("AuthenticationResult");

        assertNotNull(refreshAuth.get("AccessToken"), "Should return a new AccessToken");
        assertNotNull(refreshAuth.get("IdToken"), "Should return a new IdToken");
        assertNull(refreshAuth.get("RefreshToken"), "GetTokensFromRefreshToken should not return a new RefreshToken");
    }

    @Test
    void getTokensFromRefreshTokenInvalidTokenThrows() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());

        assertThrows(AwsException.class, () ->
                service.getTokensFromRefreshToken(client.getClientId(), "not-a-valid-refresh-token"));
    }

    @Test
    void refreshTokenAuthFlowReturnsNewTokens() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> firstAuth = (Map<String, Object>) service.initiateAuth(
                client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!")).get("AuthenticationResult");
        String refreshToken = (String) firstAuth.get("RefreshToken");

        @SuppressWarnings("unchecked")
        Map<String, Object> refreshed = (Map<String, Object>) service.initiateAuth(
                client.getClientId(), "REFRESH_TOKEN_AUTH",
                Map.of("REFRESH_TOKEN", refreshToken)).get("AuthenticationResult");

        assertNotNull(refreshed.get("AccessToken"));
        assertNotNull(refreshed.get("IdToken"));
    }

    // =========================================================================
    // deleteUserPool cascades groups
    // =========================================================================

    @Test
    void deleteUserPoolCascadesGroups() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.createGroup(pool.getId(), "admins", "Admin group", 1, null);
        service.createGroup(pool.getId(), "editors", "Editor group", 2, null);

        String prefix = pool.getId() + "::";
        assertEquals(2, groupStore.scan(k -> k.startsWith(prefix)).size());

        service.deleteUserPool(pool.getId());

        assertEquals(0, groupStore.scan(k -> k.startsWith(prefix)).size());
    }
}
