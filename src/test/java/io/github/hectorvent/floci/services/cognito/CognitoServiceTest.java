package io.github.hectorvent.floci.services.cognito;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ReservedTags;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cognito.model.CognitoGroup;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.cognito.model.UserPoolClient;
import io.github.hectorvent.floci.services.cognito.verification.CognitoMessageDispatcher;
import io.github.hectorvent.floci.services.cognito.verification.VerificationCode;
import io.github.hectorvent.floci.services.cognito.verification.VerificationCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CognitoServiceTest {

    private CognitoService service;
    private InMemoryStorage<String, CognitoUser> userStore;
    private InMemoryStorage<String, CognitoGroup> groupStore;
    private RegionResolver regionResolver;

    @BeforeEach
    void setUp() {
        userStore = new InMemoryStorage<>();
        groupStore = new InMemoryStorage<>();
        regionResolver = new RegionResolver("us-east-1", "000000000000");
        service = new CognitoService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                userStore,
                groupStore,
                new InMemoryStorage<>(), // revokedTokenStore
                "http://localhost:4566",
                regionResolver,
                null
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

    @Test
    void createUserPoolWithOverrideIdUsesProvidedId() {
        UserPool pool = service.createUserPool(
                Map.of(
                        "PoolName", "PinnedPool",
                        "UserPoolTags", Map.of(ReservedTags.OVERRIDE_ID_KEY, "us-east-1_testpool1")
                ),
                "us-east-1"
        );

        assertEquals("us-east-1_testpool1", pool.getId());
        assertEquals("arn:aws:cognito-idp:us-east-1:000000000000:userpool/us-east-1_testpool1", pool.getArn());
    }

    @Test
    void createUserPoolWithOverrideIdStripsReservedTagOnCreate() {
        UserPool pool = service.createUserPool(
                Map.of(
                        "PoolName", "PinnedPool",
                        "UserPoolTags", Map.of(ReservedTags.OVERRIDE_ID_KEY, "us-east-1_testpool1", "env", "test")
                ),
                "us-east-1"
        );

        assertEquals(Map.of("env", "test"), pool.getUserPoolTags());
        assertFalse(pool.getUserPoolTags().containsKey(ReservedTags.OVERRIDE_ID_KEY));
    }

    @Test
    void createUserPoolWithDuplicateOverrideIdThrowsResourceConflict() {
        service.createUserPool(
                Map.of("PoolName", "PinnedPool", "UserPoolTags", Map.of(ReservedTags.OVERRIDE_ID_KEY, "us-east-1_testpool1")),
                "us-east-1"
        );

        AwsException exception = assertThrows(
                AwsException.class,
                () -> service.createUserPool(
                        Map.of("PoolName", "PinnedPool2", "UserPoolTags", Map.of(ReservedTags.OVERRIDE_ID_KEY, "us-east-1_testpool1")),
                        "us-east-1"
                )
        );

        assertEquals("ResourceConflictException", exception.getErrorCode());
    }

    // =========================================================================
    // Issue #1306 — CreateUserPoolClient extended configuration
    // =========================================================================

    @Test
    void createUserPoolClientPersistsExtendedConfiguration() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "ClientPool"), "us-east-1");

        Map<String, Object> analyticsConfiguration = Map.of(
                "ApplicationId", "d70b2ba36a8c4dc5a04a0451a31a1e12",
                "ExternalId", "my-external-id",
                "RoleArn", "arn:aws:iam::123456789012:role/test-cognitouserpool-role",
                "UserDataShared", true
        );
        Map<String, String> tokenValidityUnits = Map.of(
                "AccessToken", "hours",
                "IdToken", "minutes",
                "RefreshToken", "days"
        );
        Map<String, Object> refreshTokenRotation = Map.of(
                "Feature", "ENABLED",
                "RetryGracePeriodSeconds", 30
        );

        UserPoolClient client = service.createUserPoolClient(
                pool.getId(),
                "my-test-app-client",
                true,
                true,
                List.of(" code ", "code"),
                List.of("aws.cognito.signin.user.admin", "openid"),
                analyticsConfiguration,
                List.of("https://example.com", "http://localhost", "myapp://example"),
                "https://example.com",
                List.of("ALLOW_USER_AUTH", "ALLOW_ADMIN_USER_PASSWORD_AUTH", "ALLOW_USER_PASSWORD_AUTH",
                        "ALLOW_REFRESH_TOKEN_AUTH"),
                6,
                6,
                List.of("https://example.com/logout"),
                "ENABLED",
                List.of("email", "address", "preferred_username"),
                6,
                List.of("SignInWithApple", "MySSO"),
                tokenValidityUnits,
                List.of("family_name", "email"),
                refreshTokenRotation,
                true
        );

        assertNotNull(client.getClientId());
        assertEquals(pool.getId(), client.getUserPoolId());
        assertEquals("my-test-app-client", client.getClientName());
        assertTrue(client.isGenerateSecret());
        assertNotNull(client.getClientSecret());
        assertEquals(1, client.getUserPoolClientSecrets().size());
        assertTrue(client.isAllowedOAuthFlowsUserPoolClient());
        assertEquals(List.of("code"), client.getAllowedOAuthFlows());
        assertEquals(List.of("aws.cognito.signin.user.admin", "openid"), client.getAllowedOAuthScopes());
        assertEquals(analyticsConfiguration, client.getAnalyticsConfiguration());
        assertEquals(List.of("https://example.com", "http://localhost", "myapp://example"), client.getCallbackURLs());
        assertEquals("https://example.com", client.getDefaultRedirectURI());
        assertEquals(List.of("ALLOW_USER_AUTH", "ALLOW_ADMIN_USER_PASSWORD_AUTH", "ALLOW_USER_PASSWORD_AUTH",
                "ALLOW_REFRESH_TOKEN_AUTH"), client.getExplicitAuthFlows());
        assertEquals(6, client.getAccessTokenValidity());
        assertEquals(6, client.getIdTokenValidity());
        assertEquals(List.of("https://example.com/logout"), client.getLogoutURLs());
        assertEquals("ENABLED", client.getPreventUserExistenceErrors());
        assertEquals(List.of("email", "address", "preferred_username"), client.getReadAttributes());
        assertEquals(6, client.getRefreshTokenValidity());
        assertEquals(List.of("SignInWithApple", "MySSO"), client.getSupportedIdentityProviders());
        assertEquals(tokenValidityUnits, client.getTokenValidityUnits());
        assertEquals(List.of("family_name", "email"), client.getWriteAttributes());
        assertEquals(refreshTokenRotation, client.getRefreshTokenRotation());
        assertEquals(Boolean.TRUE, client.getEnableTokenRevocation());
    }

    @Test
    void createUserPoolClientGeneratesIdSecretTimestampsAndNormalizesLists() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "ClientPool"), "us-east-1");

        UserPoolClient client = service.createUserPoolClient(
                pool.getId(),
                "basic-client",
                true,
                true,
                List.of(" code ", "code", "implicit", "", "implicit"),
                new ArrayList<>(java.util.Arrays.asList(" openid ", "openid", "email", null, "email")),
                null,
                List.of("https://example.com/callback"),
                "https://example.com/callback",
                List.of(),
                null,
                null,
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                null,
                null
        );

        assertNotNull(client.getClientId());
        assertEquals(26, client.getClientId().length());
        assertTrue(client.getClientId().chars().allMatch(Character::isLetterOrDigit));

        assertTrue(client.isGenerateSecret());
        assertNotNull(client.getClientSecret());
        assertFalse(client.getClientSecret().isBlank());
        assertEquals(1, client.getUserPoolClientSecrets().size());
        assertEquals(client.getClientSecret(), client.getUserPoolClientSecrets().get(0).getClientSecretValue());

        assertTrue(client.getCreationDate() > 0);
        assertTrue(client.getLastModifiedDate() > 0);
        assertEquals(client.getCreationDate(), client.getLastModifiedDate());

        assertEquals(List.of("code", "implicit"), client.getAllowedOAuthFlows());
        assertEquals(List.of("openid", "email"), client.getAllowedOAuthScopes());
    }

    @Test
    void createUserPoolClientAppliesAwsLikeDefaultsForSupportedIdentityProvidersAndTokenRevocation() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "ClientPool"), "us-east-1");

        UserPoolClient client = service.createUserPoolClient(
                pool.getId(),
                "defaulted-client",
                false,
                false,
                List.of(),
                List.of()
        );

        assertEquals(List.of("COGNITO"), client.getSupportedIdentityProviders());
        assertEquals(Boolean.TRUE, client.getEnableTokenRevocation());
    }

    @Test
    void createUserPoolClientRejectsInvalidTokenValidityConfiguration() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "ClientPool"), "us-east-1");

        AwsException exception = assertThrows(
                AwsException.class,
                () -> service.createUserPoolClient(
                        pool.getId(),
                        "invalid-token-validity-client",
                        false,
                        false,
                        List.of(),
                        List.of(),
                        null,
                        List.of(),
                        null,
                        List.of(),
                        -1,
                        0,
                        List.of(),
                        null,
                        List.of(),
                        -7,
                        List.of(),
                        Map.of(
                                "AccessToken", "weeks",
                                "IdToken", "minutes",
                                "RefreshToken", "days"
                        ),
                        List.of(),
                        null,
                        null
                )
        );

        assertEquals("InvalidParameterException", exception.getErrorCode());
    }

    @Test
    void createUserPoolClientAcceptsRefreshTokenValidityZeroAndCoercesToDefault() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "ClientPool"), "us-east-1");

        UserPoolClient client = service.createUserPoolClient(
                pool.getId(),
                "refresh-default-client",
                false,
                false,
                List.of(),
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                null,
                null,
                List.of(),
                null,
                List.of(),
                0,
                List.of(),
                null,
                List.of(),
                null,
                null
        );

        assertEquals(30, client.getRefreshTokenValidity());
    }

    @Test
    void createUserPoolClientRejectsLogoutUrlsWhenOAuthFlowsUserPoolClientIsFalse() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "ClientPool"), "us-east-1");

        AwsException exception = assertThrows(
                AwsException.class,
                () -> service.createUserPoolClient(
                        pool.getId(),
                        "invalid-logout-client",
                        false,
                        false,
                        List.of(),
                        List.of(),
                        null,
                        List.of(),
                        null,
                        List.of(),
                        null,
                        null,
                        List.of("https://example.com/logout"),
                        null,
                        List.of(),
                        null,
                        List.of(),
                        null,
                        List.of(),
                        null,
                        null
                )
        );

        assertEquals("InvalidParameterException", exception.getErrorCode());
    }

    @Test
    void createUserPoolClientRejectsMixedCaseTokenValidityUnits() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "ClientPool"), "us-east-1");

        AwsException exception = assertThrows(
                AwsException.class,
                () -> service.createUserPoolClient(
                        pool.getId(),
                        "invalid-token-unit-client",
                        false,
                        false,
                        List.of(),
                        List.of(),
                        null,
                        List.of(),
                        null,
                        List.of(),
                        1,
                        1,
                        List.of(),
                        null,
                        List.of(),
                        7,
                        List.of(),
                        Map.of(
                                "AccessToken", "Hours",
                                "IdToken", "minutes",
                                "RefreshToken", "days"
                        ),
                        List.of(),
                        null,
                        null
                )
        );

        assertEquals("InvalidParameterException", exception.getErrorCode());
    }

    @Test
    void createUserPoolClientRejectsInconsistentOAuthFlowConfiguration() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "ClientPool"), "us-east-1");

        AwsException exception = assertThrows(
                AwsException.class,
                () -> service.createUserPoolClient(
                        pool.getId(),
                        "invalid-oauth-client",
                        false,
                        false,
                        List.of("code"),
                        List.of("openid"),
                        null,
                        List.of("https://example.com/callback"),
                        "https://example.com/callback",
                        List.of(),
                        null,
                        null,
                        List.of(),
                        null,
                        List.of(),
                        null,
                        List.of(),
                        null,
                        List.of(),
                        null,
                        null
                )
        );

        assertEquals("InvalidParameterException", exception.getErrorCode());
    }

    @Test
    void createUserPoolClientRejectsDefaultRedirectUriNotInCallbackUrls() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "ClientPool"), "us-east-1");

        AwsException exception = assertThrows(
                AwsException.class,
                () -> service.createUserPoolClient(
                        pool.getId(),
                        "invalid-redirect-client",
                        false,
                        true,
                        List.of("code"),
                        List.of("openid"),
                        null,
                        List.of("https://example.com/callback"),
                        "https://different.example.com/callback",
                        List.of(),
                        null,
                        null,
                        List.of(),
                        null,
                        List.of(),
                        null,
                        List.of(),
                        null,
                        List.of(),
                        null,
                        null
                )
        );

        assertEquals("InvalidParameterException", exception.getErrorCode());
    }

    @Test
    void updateUserPoolClientAllowsClearingListFieldsWithEmptyArrays() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "ClientPool"), "us-east-1");

        UserPoolClient client = service.createUserPoolClient(
                pool.getId(),
                "client",
                false,
                true,
                List.of("code"),
                List.of("openid"),
                null,
                List.of("https://example.com"),
                "https://example.com",
                List.of("ALLOW_USER_AUTH"),
                null,
                null,
                List.of("https://example.com/logout"),
                null,
                List.of("email"),
                null,
                List.of("COGNITO", "Google"),
                null,
                List.of("family_name"),
                null,
                null
        );

        service.updateUserPoolClient(
                pool.getId(),
                client.getClientId(),
                null,
                false,
                List.of(),
                List.of(),
                null,
                List.of(),
                "",
                List.of(),
                null,
                null,
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                null,
                null
        );

        UserPoolClient updated = service.describeUserPoolClient(pool.getId(), client.getClientId());
        assertEquals(List.of(), updated.getCallbackURLs());
        assertEquals(List.of(), updated.getExplicitAuthFlows());
        assertEquals(List.of(), updated.getLogoutURLs());
        assertEquals(List.of(), updated.getReadAttributes());
        assertEquals(List.of(), updated.getSupportedIdentityProviders());
        assertEquals(List.of(), updated.getWriteAttributes());
    }

    @Test
    void updateUserPoolClientAcceptsRefreshTokenValidityZeroAndCoercesToDefault() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "ClientPool"), "us-east-1");

        UserPoolClient client = service.createUserPoolClient(
                pool.getId(),
                "client",
                false,
                false,
                List.of(),
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                null,
                null,
                List.of(),
                null,
                List.of(),
                7,
                List.of(),
                null,
                List.of(),
                null,
                null
        );

        UserPoolClient updated = service.updateUserPoolClient(
                pool.getId(),
                client.getClientId(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                null,
                null,
                null,
                null,
                null
        );

        assertEquals(30, updated.getRefreshTokenValidity());
    }

    @Test
    void createUserPoolWithBlankOverrideIdThrowsValidation() {
        AwsException exception = assertThrows(
                AwsException.class,
                () -> service.createUserPool(
                        Map.of("PoolName", "PinnedPool", "UserPoolTags", Map.of(ReservedTags.OVERRIDE_ID_KEY, "   ")),
                        "us-east-1"
                )
        );

        assertEquals("ValidationException", exception.getErrorCode());
    }

    @Test
    void createUserPoolWithSlashInOverrideThrowsValidation() {
        AwsException exception = assertThrows(
                AwsException.class,
                () -> service.createUserPool(
                        Map.of("PoolName", "PinnedPool", "UserPoolTags", Map.of(ReservedTags.OVERRIDE_ID_KEY, "bad/pool")),
                        "us-east-1"
                )
        );

        assertEquals("ValidationException", exception.getErrorCode());
    }

    @Test
    void createUserPoolWithQuestionMarkOrHashInOverrideThrowsValidation() {
        AwsException questionMarkException = assertThrows(
                AwsException.class,
                () -> service.createUserPool(
                        Map.of("PoolName", "PinnedPool", "UserPoolTags", Map.of(ReservedTags.OVERRIDE_ID_KEY, "bad?pool")),
                        "us-east-1"
                )
        );
        assertEquals("ValidationException", questionMarkException.getErrorCode());

        AwsException hashException = assertThrows(
                AwsException.class,
                () -> service.createUserPool(
                        Map.of("PoolName", "PinnedPool", "UserPoolTags", Map.of(ReservedTags.OVERRIDE_ID_KEY, "bad#pool")),
                        "us-east-1"
                )
        );
        assertEquals("ValidationException", hashException.getErrorCode());
    }

    @Test
    void updateUserPoolWithReservedTagStripsIt() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "PinnedPool"), "us-east-1");

        service.updateUserPool(
                Map.of(
                        "UserPoolId", pool.getId(),
                        "UserPoolTags", Map.of(ReservedTags.OVERRIDE_ID_KEY, "late-id", "env", "test")
                ),
                "us-east-1"
        );

        UserPool updated = service.describeUserPool(pool.getId());
        assertEquals(Map.of("env", "test"), updated.getUserPoolTags());
    }

    @Test
    void tagResourceAddsAndOverwritesTags() {
        UserPool pool = service.createUserPool(
                Map.of("PoolName", "TaggedPool", "UserPoolTags", Map.of("env", "dev")),
                "us-east-1"
        );

        service.tagResource(pool.getArn(), Map.of("team", "platform", "env", "test"));

        assertEquals(Map.of("env", "test", "team", "platform"), service.listTagsForResource(pool.getArn()));
    }

    @Test
    void tagResourceRejectsReservedKey() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TaggedPool"), "us-east-1");

        AwsException exception = assertThrows(
                AwsException.class,
                () -> service.tagResource(pool.getArn(), Map.of(ReservedTags.OVERRIDE_ID_KEY, "late-id"))
        );

        assertEquals("ValidationException", exception.getErrorCode());
    }

    @Test
    void tagResourceRejectsEmptyTags() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TaggedPool"), "us-east-1");

        AwsException exception = assertThrows(
                AwsException.class,
                () -> service.tagResource(pool.getArn(), Map.of())
        );

        assertEquals("InvalidParameterException", exception.getErrorCode());
    }

    @Test
    void tagResourceWithUnknownArnThrowsNotFound() {
        AwsException exception = assertThrows(
                AwsException.class,
                () -> service.tagResource("arn:aws:cognito-idp:us-east-1:000000000000:userpool/us-east-1_missing", Map.of("env", "test"))
        );

        assertEquals("ResourceNotFoundException", exception.getErrorCode());
    }

    @Test
    void untagResourceRemovesRequestedKeysAndAllowsReservedRemoval() {
        UserPool pool = service.createUserPool(
                Map.of("PoolName", "TaggedPool", "UserPoolTags", Map.of("env", "test", "team", "platform")),
                "us-east-1"
        );

        service.untagResource(pool.getArn(), List.of("team", ReservedTags.OVERRIDE_ID_KEY));

        assertEquals(Map.of("env", "test"), service.listTagsForResource(pool.getArn()));
    }

    @Test
    void listTagsForResourceReturnsCurrentTags() {
        UserPool pool = service.createUserPool(
                Map.of("PoolName", "TaggedPool", "UserPoolTags", Map.of("env", "test")),
                "us-east-1"
        );

        assertEquals(Map.of("env", "test"), service.listTagsForResource(pool.getArn()));
    }

    @Test
    void updateUserPoolAndTagResourceShareConsistentVisibleTagBehavior() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TaggedPool"), "us-east-1");

        service.updateUserPool(
                Map.of(
                        "UserPoolId", pool.getId(),
                        "UserPoolTags", Map.of(ReservedTags.OVERRIDE_ID_KEY, "late-id", "env", "test")
                ),
                "us-east-1"
        );
        service.tagResource(pool.getArn(), Map.of("team", "platform"));

        assertEquals(Map.of("env", "test", "team", "platform"), service.listTagsForResource(pool.getArn()));
    }

    @Test
    void issuerUrlForPinnedPoolResolvesAsBaseUrlSlashPoolId() {
        UserPool pool = service.createUserPool(
                Map.of("PoolName", "PinnedPool", "UserPoolTags", Map.of(ReservedTags.OVERRIDE_ID_KEY, "custompool")),
                "us-east-1"
        );

        assertEquals("http://localhost:4566/custompool", service.getIssuer(pool.getId()));
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
    void adminCreateUserResendRefreshesExistingForceChangePasswordUser() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        CognitoUser created = service.adminCreateUser(pool.getId(),
                "alice",
                Map.of("email", "alice@example.com"),
                "TempPass1!");

        // Backdate lastModifiedDate so RESEND's refresh is unambiguously observable
        // without relying on wall-clock sleep (lastModifiedDate has 1s precision).
        long backdated = (System.currentTimeMillis() / 1000L) - 60;
        created.setLastModifiedDate(backdated);

        CognitoUser resent = service.adminCreateUser(pool.getId(), "alice",
                Map.of("email", "alice@example.com"), null, "RESEND");

        assertEquals(created.getAttributes().get("sub"), resent.getAttributes().get("sub"),
                "RESEND must not recreate the user");
        assertEquals("FORCE_CHANGE_PASSWORD", resent.getUserStatus());
        assertTrue(resent.getLastModifiedDate() > backdated,
                "RESEND should refresh lastModifiedDate");
    }

    @Test
    void adminCreateUserResendThrowsUserNotFoundForMissingUser() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        AwsException ex = assertThrows(AwsException.class, () ->
                service.adminCreateUser(pool.getId(), "ghost",
                        Map.of("email", "g@example.com"), null, "RESEND"));
        assertEquals("UserNotFoundException", ex.getErrorCode());
    }

    @Test
    void adminCreateUserResendThrowsUnsupportedStateForConfirmedUser() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.adminCreateUser(pool.getId(), "bob", Map.of("email", "bob@example.com"), "TempPass1!");
        service.adminSetUserPassword(pool.getId(), "bob", "Permanent1!", true);

        AwsException ex = assertThrows(AwsException.class, () ->
                service.adminCreateUser(pool.getId(), "bob",
                        Map.of("email", "bob@example.com"), null, "RESEND"));
        assertEquals("UnsupportedUserStateException", ex.getErrorCode());
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
    void signUpWithoutDeliveryTargetFailsBeforePersistingUser() {
        VerificationCodeService verificationCodeService = mock(VerificationCodeService.class);
        CognitoMessageDispatcher messageDispatcher = mock(CognitoMessageDispatcher.class);
        CognitoService serviceWithVerification = new CognitoService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                "http://localhost:4566",
                regionResolver,
                null,
                verificationCodeService,
                messageDispatcher
        );

        UserPool pool = serviceWithVerification.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        pool.setAutoVerifiedAttributes(List.of("email"));
        UserPoolClient client = serviceWithVerification.createUserPoolClient(pool.getId(), "test-client",
                false, false, List.of(), List.of());

        AwsException ex = assertThrows(AwsException.class, () ->
                serviceWithVerification.signUp(client.getClientId(), "carol", "Pass1234!", Map.of()));
        assertEquals("InvalidParameterException", ex.getErrorCode());

        AwsException lookupEx = assertThrows(AwsException.class, () ->
                serviceWithVerification.adminGetUser(pool.getId(), "carol"));
        assertEquals("UserNotFoundException", lookupEx.getErrorCode());
    }

    @Test
    void signUpRollsBackUserWhenDispatchFails() {
        VerificationCodeService verificationCodeService = mock(VerificationCodeService.class);
        CognitoMessageDispatcher messageDispatcher = mock(CognitoMessageDispatcher.class);
        when(verificationCodeService.issue(any(), any(), eq(VerificationCode.Purpose.SIGNUP_CONFIRMATION), any()))
                .thenReturn("123456");
        doThrow(new RuntimeException("SES unavailable")).when(messageDispatcher)
                .dispatch(any(), any(), eq(VerificationCode.Purpose.SIGNUP_CONFIRMATION), eq("123456"), any());

        CognitoService serviceWithVerification = new CognitoService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                "http://localhost:4566",
                regionResolver,
                null,
                verificationCodeService,
                messageDispatcher
        );

        UserPool pool = serviceWithVerification.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        pool.setAutoVerifiedAttributes(List.of("email"));
        UserPoolClient client = serviceWithVerification.createUserPoolClient(pool.getId(), "test-client",
                false, false, List.of(), List.of());

        AwsException ex = assertThrows(AwsException.class, () ->
                serviceWithVerification.signUp(client.getClientId(), "carol", "Pass1234!",
                        Map.of("email", "carol@example.com")));
        assertEquals("CodeDeliveryFailureException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertEquals("Failed to deliver the message.", ex.getMessage());

        AwsException lookupEx = assertThrows(AwsException.class, () ->
                serviceWithVerification.adminGetUser(pool.getId(), "carol"));
        assertEquals("UserNotFoundException", lookupEx.getErrorCode());
        verify(verificationCodeService).invalidatePrevious(pool.getId(), "carol",
                VerificationCode.Purpose.SIGNUP_CONFIRMATION);
    }

    @Test
    void signUpReturnsResourceNotFoundAsBadRequestWhenClientMissing() {
        AwsException ex = assertThrows(AwsException.class, () ->
                service.signUp("missing-client", "carol", "Pass1234!", Map.of("email", "carol@example.com")));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void confirmSignUpReturnsResourceNotFoundAsBadRequestWhenClientMissing() {
        AwsException ex = assertThrows(AwsException.class, () ->
                service.confirmSignUp("missing-client", "carol", "123456"));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
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
    // USER_SRP_AUTH flow
    // =========================================================================

    @Test
    @SuppressWarnings("unchecked")
    void initiateAuthWithUserSrpAuthFlow() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        String password = "Password123!";
        service.adminCreateUser(pool.getId(), "bob", Map.of("email", "bob@example.com"), null);
        service.adminSetUserPassword(pool.getId(), "bob", password, true);
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());

        Map<String, Object> initResult = service.initiateAuth(client.getClientId(), "USER_SRP_AUTH",
                Map.of("USERNAME", "bob", "SRP_A", "ABCDEF1234567890"));

        assertEquals("PASSWORD_VERIFIER", initResult.get("ChallengeName"));
        assertNotNull(initResult.get("Session"));
        Map<String, String> params = (Map<String, String>) initResult.get("ChallengeParameters");
        assertNotNull(params.get("SALT"));
        assertNotNull(params.get("SRP_B"));
        assertNotNull(params.get("SECRET_BLOCK"));
        assertEquals("bob", params.get("USER_ID_FOR_SRP"));
        // Real AWS Cognito returns USERNAME alongside USER_ID_FOR_SRP; the .NET
        // Amazon.Extensions.CognitoAuthentication SRP client requires it (issue #1305).
        assertEquals("bob", params.get("USERNAME"));
    }

    @Test
    void respondToAuthChallengeWithInvalidSrpSignatureRejects() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        String password = "Password123!";
        service.adminCreateUser(pool.getId(), "bob", Map.of("email", "bob@example.com"), null);
        service.adminSetUserPassword(pool.getId(), "bob", password, true);
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());

        Map<String, Object> initResult = service.initiateAuth(client.getClientId(), "USER_SRP_AUTH",
                Map.of("USERNAME", "bob", "SRP_A", "ABCDEF1234567890"));
        String session = (String) initResult.get("Session");

        AwsException ex = assertThrows(AwsException.class, () ->
                service.respondToAuthChallenge(client.getClientId(), "PASSWORD_VERIFIER", session,
                        Map.of(
                                "USERNAME", "bob",
                                "PASSWORD_CLAIM_SIGNATURE", "invalid-sig",
                                "TIMESTAMP", "Wed Apr 8 12:00:00 UTC 2026"
                        )));
        assertEquals("NotAuthorizedException", ex.getErrorCode());
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
    // AdminGetUser resolves configured identifiers
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
        UserPool pool = service.createUserPool(
                Map.of("PoolName", "TestPool", "AliasAttributes", List.of("email")),
                "us-east-1"
        );
        service.adminCreateUser(pool.getId(), "bob",
                Map.of("email", "bob@example.com", "email_verified", "true"), null);

        CognitoUser found = service.adminGetUser(pool.getId(), "bob@example.com");
        assertEquals("bob", found.getUsername());
    }

    @Test
    void adminGetUserByPhoneNumberAlias() {
        UserPool pool = service.createUserPool(
                Map.of("PoolName", "TestPool", "AliasAttributes", List.of("phone_number")),
                "us-east-1"
        );
        service.adminCreateUser(pool.getId(), "bob",
                Map.of("phone_number", "+15551234567", "phone_number_verified", "true"), null);

        CognitoUser found = service.adminGetUser(pool.getId(), "+15551234567");
        assertEquals("bob", found.getUsername());
    }

    @Test
    void adminGetUserByPreferredUsernameAlias() {
        UserPool pool = service.createUserPool(
                Map.of("PoolName", "TestPool", "AliasAttributes", List.of("preferred_username")),
                "us-east-1"
        );
        service.adminCreateUser(pool.getId(), "bob", Map.of("preferred_username", "bobby"), null);

        CognitoUser found = service.adminGetUser(pool.getId(), "bobby");
        assertEquals("bob", found.getUsername());
    }

    @Test
    void adminGetUserByEmailUsernameAttribute() {
        UserPool pool = service.createUserPool(
                Map.of("PoolName", "TestPool", "UsernameAttributes", List.of("email")),
                "us-east-1"
        );
        service.adminCreateUser(pool.getId(), "bob", Map.of("email", "bob@example.com"), null);

        CognitoUser found = service.adminGetUser(pool.getId(), "bob@example.com");
        assertEquals("bob", found.getUsername());
    }

    @Test
    void adminGetUserByPhoneNumberUsernameAttribute() {
        UserPool pool = service.createUserPool(
                Map.of("PoolName", "TestPool", "UsernameAttributes", List.of("phone_number")),
                "us-east-1"
        );
        service.adminCreateUser(pool.getId(), "bob", Map.of("phone_number", "+15551234567"), null);

        CognitoUser found = service.adminGetUser(pool.getId(), "+15551234567");
        assertEquals("bob", found.getUsername());
    }

    @Test
    void adminGetUserRejectsEmailWithoutConfiguredAlias() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.adminCreateUser(pool.getId(), "bob", Map.of("email", "bob@example.com"), null);

        AwsException ex = assertThrows(AwsException.class,
                () -> service.adminGetUser(pool.getId(), "bob@example.com"));
        assertEquals("UserNotFoundException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void adminGetUserRejectsUnverifiedEmailAlias() {
        UserPool pool = service.createUserPool(
                Map.of("PoolName", "TestPool", "AliasAttributes", List.of("email")),
                "us-east-1"
        );
        service.adminCreateUser(pool.getId(), "bob", Map.of("email", "bob@example.com"), null);

        AwsException ex = assertThrows(AwsException.class,
                () -> service.adminGetUser(pool.getId(), "bob@example.com"));
        assertEquals("UserNotFoundException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void adminGetUserRejectsUnknownPoolWithResourceNotFound() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.adminGetUser("us-east-1_missing", "bob"));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void adminGetUserRejectsAmbiguousLookupValueCreatedViaAdminCreateUser() {
        UserPool pool = service.createUserPool(
                Map.of("PoolName", "TestPool", "AliasAttributes", List.of("email")),
                "us-east-1"
        );
        service.adminCreateUser(pool.getId(), "shared-lookup", Map.of("email", "owner@example.com"), null);
        service.adminCreateUser(pool.getId(), "alice",
                Map.of("email", "shared-lookup", "email_verified", "true"), null);

        AwsException ex = assertThrows(AwsException.class,
                () -> service.adminGetUser(pool.getId(), "shared-lookup"));
        assertEquals("InvalidParameterException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void adminGetUserRejectsAmbiguousLookupValueCreatedViaAdminUpdateUserAttributes() {
        UserPool pool = service.createUserPool(
                Map.of("PoolName", "TestPool", "AliasAttributes", List.of("email")),
                "us-east-1"
        );
        service.adminCreateUser(pool.getId(), "shared-lookup", Map.of("email", "owner@example.com"), null);
        service.adminCreateUser(pool.getId(), "alice", Map.of("email", "alice@example.com"), null);
        service.adminUpdateUserAttributes(pool.getId(), "alice",
                Map.of("email", "shared-lookup", "email_verified", "true"));

        AwsException ex = assertThrows(AwsException.class,
                () -> service.adminGetUser(pool.getId(), "shared-lookup"));
        assertEquals("InvalidParameterException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
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
        String[] parts = decoded.split("\\|", 5);
        assertEquals(5, parts.length, "Refresh token should encode 5 pipe-separated fields");
        assertEquals(pool.getId(), parts[0]);
        assertEquals("alice", parts[1]);
        assertEquals(client.getClientId(), parts[2]);
        assertFalse(parts[3].isBlank(), "Refresh token should encode its issued-at timestamp");
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

    // =========================================================================
    // Issue #1306 — Refresh token expiry respects client token validity
    // =========================================================================

    @Test
    void getTokensFromRefreshTokenExpiredTokenThrows() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(),
                "c",
                false,
                false,
                List.of(),
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                null,
                null,
                List.of(),
                null,
                List.of(),
                1,
                List.of(),
                Map.of("RefreshToken", "seconds"),
                List.of(),
                null,
                null
        );

        long issuedAt = (System.currentTimeMillis() / 1000L) - 5;
        String raw = pool.getId() + "|alice|" + client.getClientId() + "|" + issuedAt + "|" + java.util.UUID.randomUUID();
        String expiredRefreshToken = Base64.getEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));

        AwsException exception = assertThrows(AwsException.class, () ->
                service.getTokensFromRefreshToken(client.getClientId(), expiredRefreshToken));
        assertEquals("NotAuthorizedException", exception.getErrorCode());
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

    // =========================================================================
    // Issue #433 — AdminEnableUser / AdminDisableUser
    // =========================================================================

    @Test
    void adminDisableUserSetsEnabledFalse() {
        UserPool pool = createPoolAndUser();

        CognitoUser before = service.adminGetUser(pool.getId(), "alice");
        assertTrue(before.isEnabled(), "User should be enabled by default");

        service.adminDisableUser(pool.getId(), "alice");

        CognitoUser after = service.adminGetUser(pool.getId(), "alice");
        assertFalse(after.isEnabled(), "User should be disabled after adminDisableUser");
    }

    @Test
    void adminEnableUserSetsEnabledTrue() {
        UserPool pool = createPoolAndUser();
        service.adminDisableUser(pool.getId(), "alice");

        service.adminEnableUser(pool.getId(), "alice");

        CognitoUser user = service.adminGetUser(pool.getId(), "alice");
        assertTrue(user.isEnabled(), "User should be enabled after adminEnableUser");
    }

    @Test
    void disabledUserCannotAuthenticate() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "c", false, false, List.of(), List.of());

        service.adminDisableUser(pool.getId(), "alice");

        AwsException ex = assertThrows(AwsException.class, () ->
                service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                        Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!")));
        assertEquals("UserNotConfirmedException", ex.getErrorCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void reEnabledUserCanAuthenticate() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "c", false, false, List.of(), List.of());

        service.adminDisableUser(pool.getId(), "alice");
        service.adminEnableUser(pool.getId(), "alice");

        Map<String, Object> result = service.initiateAuth(
                client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));
        assertNotNull(((Map<String, Object>) result.get("AuthenticationResult")).get("AccessToken"));
    }

    @Test
    void adminDisableUserNonexistentThrows() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");

        assertThrows(AwsException.class, () ->
                service.adminDisableUser(pool.getId(), "ghost"));
    }

    @Test
    void adminEnableUserNonexistentThrows() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");

        assertThrows(AwsException.class, () ->
                service.adminEnableUser(pool.getId(), "ghost"));
    }

    // =========================================================================
    // CUSTOM_AUTH flow (requires Lambda triggers)
    // =========================================================================

    @Test
    void customAuthInitiateFailsWhenDefineTriggerIsMissing() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "c", false, false, List.of(), List.of());

        AwsException ex = assertThrows(AwsException.class, () ->
                service.initiateAuth(client.getClientId(), "CUSTOM_AUTH",
                        Map.of("USERNAME", "alice", "CHALLENGE_NAME", "SRP_A")));
        assertEquals("InvalidUserPoolConfigurationException", ex.getErrorCode());
    }

    @Test
    void customAuthRejectsWhenNoLambdaTriggersAreConfigured() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "c", false, false, List.of(), List.of());

        AwsException ex = assertThrows(AwsException.class, () ->
                service.initiateAuth(client.getClientId(), "CUSTOM_AUTH",
                        Map.of("USERNAME", "alice")));
        assertEquals("InvalidUserPoolConfigurationException", ex.getErrorCode());
    }

    @Test
    void customChallengeWithUnknownSessionThrows() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "c", false, false, List.of(), List.of());

        AwsException ex = assertThrows(AwsException.class, () ->
                service.respondToAuthChallenge(client.getClientId(), "CUSTOM_CHALLENGE",
                        "not-a-real-session", Map.of("USERNAME", "alice", "ANSWER", "x")));
        assertEquals("NotAuthorizedException", ex.getErrorCode());
    }

    // =========================================================================
    // NEW_PASSWORD_REQUIRED — challenge response shape + userAttributes updates
    // =========================================================================

    @Test
    @SuppressWarnings("unchecked")
    void newPasswordRequiredChallengeReturnsUserAttributesJson() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.adminCreateUser(pool.getId(), "carol",
                Map.of("email", "carol@example.com", "given_name", "Carol"), "TempPass1!");
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "c", false, false, List.of(), List.of());

        Map<String, Object> result = service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "carol", "PASSWORD", "TempPass1!"));

        assertEquals("NEW_PASSWORD_REQUIRED", result.get("ChallengeName"));
        Map<String, String> params = (Map<String, String>) result.get("ChallengeParameters");
        String userAttrsJson = params.get("userAttributes");
        assertNotNull(userAttrsJson);
        assertTrue(userAttrsJson.contains("\"email\":\"carol@example.com\""),
                "userAttributes JSON should include user's email; was: " + userAttrsJson);
        assertTrue(userAttrsJson.contains("\"given_name\":\"Carol\""),
                "userAttributes JSON should include given_name; was: " + userAttrsJson);
    }

    @Test
    @SuppressWarnings("unchecked")
    void newPasswordRequiredAppliesUserAttributeUpdates() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.adminCreateUser(pool.getId(), "carol", Map.of("email", "carol@example.com"), "TempPass1!");
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "c", false, false, List.of(), List.of());

        Map<String, Object> challengeResp = service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "carol", "PASSWORD", "TempPass1!"));
        String session = (String) challengeResp.get("Session");

        Map<String, String> responses = new HashMap<>();
        responses.put("USERNAME", "carol");
        responses.put("NEW_PASSWORD", "Permanent99!");
        responses.put("userAttributes.given_name", "Carolyn");
        responses.put("userAttributes.family_name", "Smith");

        Map<String, Object> tokens = service.respondToAuthChallenge(
                client.getClientId(), "NEW_PASSWORD_REQUIRED", session, responses);
        assertNotNull(((Map<String, Object>) tokens.get("AuthenticationResult")).get("AccessToken"));

        CognitoUser user = service.adminGetUser(pool.getId(), "carol");
        assertEquals("Carolyn", user.getAttributes().get("given_name"));
        assertEquals("Smith", user.getAttributes().get("family_name"));
        assertEquals("CONFIRMED", user.getUserStatus());
    }

    // =========================================================================
    // SECRET_HASH validation
    // =========================================================================

    @Test
    void initiateAuthRejectsMissingSecretHashWhenClientHasSecret() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "c", true, false, List.of(), List.of());

        AwsException ex = assertThrows(AwsException.class, () ->
                service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                        Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!")));
        assertEquals("InvalidParameterException", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("SECRET_HASH"));
    }

    @Test
    void initiateAuthRejectsWrongSecretHash() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "c", true, false, List.of(), List.of());

        AwsException ex = assertThrows(AwsException.class, () ->
                service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                        Map.of("USERNAME", "alice",
                                "PASSWORD", "Perm1234!",
                                "SECRET_HASH", "wrong-hash")));
        assertEquals("NotAuthorizedException", ex.getErrorCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void initiateAuthAcceptsCorrectSecretHash() throws Exception {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "c", true, false, List.of(), List.of());

        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                client.getClientSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String secretHash = Base64.getEncoder().encodeToString(
                mac.doFinal(("alice" + client.getClientId()).getBytes(StandardCharsets.UTF_8)));

        Map<String, Object> result = service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice",
                        "PASSWORD", "Perm1234!",
                        "SECRET_HASH", secretHash));
        Map<String, Object> auth = (Map<String, Object>) result.get("AuthenticationResult");
        assertNotNull(auth);
        assertNotNull(auth.get("AccessToken"));
    }

    // =========================================================================
    // AdminRespondToAuthChallenge
    // =========================================================================

    @Test
    @SuppressWarnings("unchecked")
    void adminRespondToAuthChallengeNewPasswordRequired() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.adminCreateUser(pool.getId(), "bob", Map.of("email", "bob@example.com"), "TempPass1!");
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "c", false, false, List.of(), List.of());

        Map<String, Object> challengeResp = service.adminInitiateAuth(
                pool.getId(), client.getClientId(), "ADMIN_USER_PASSWORD_AUTH",
                Map.of("USERNAME", "bob", "PASSWORD", "TempPass1!"), Map.of());
        assertEquals("NEW_PASSWORD_REQUIRED", challengeResp.get("ChallengeName"));
        String session = (String) challengeResp.get("Session");

        Map<String, Object> result = service.adminRespondToAuthChallenge(
                pool.getId(), client.getClientId(), "NEW_PASSWORD_REQUIRED", session,
                Map.of("USERNAME", "bob", "NEW_PASSWORD", "Permanent99!"));
        Map<String, Object> auth = (Map<String, Object>) result.get("AuthenticationResult");
        assertNotNull(auth, "AuthenticationResult should be present");
        assertNotNull(auth.get("AccessToken"));
        assertNotNull(auth.get("IdToken"));
        assertNotNull(auth.get("RefreshToken"));

        CognitoUser user = service.adminGetUser(pool.getId(), "bob");
        assertEquals("CONFIRMED", user.getUserStatus());
    }

    @Test
    void adminRespondToAuthChallengeInvalidPool() {
        UserPool pool1 = service.createUserPool(Map.of("PoolName", "Pool1"), "us-east-1");
        UserPool pool2 = service.createUserPool(Map.of("PoolName", "Pool2"), "us-east-1");
        service.adminCreateUser(pool1.getId(), "alice", Map.of("email", "a@example.com"), "TempPass1!");
        UserPoolClient client = service.createUserPoolClient(
                pool1.getId(), "c", false, false, List.of(), List.of());

        AwsException ex = assertThrows(AwsException.class, () ->
                service.adminRespondToAuthChallenge(
                        pool2.getId(), client.getClientId(), "NEW_PASSWORD_REQUIRED", null,
                        Map.of("USERNAME", "alice", "NEW_PASSWORD", "NewPass1!")));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminRespondToAuthChallengeWithUserAttributes() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.adminCreateUser(pool.getId(), "carol", Map.of("email", "carol@example.com"), "TempPass1!");
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "c", false, false, List.of(), List.of());

        Map<String, Object> challengeResp = service.adminInitiateAuth(
                pool.getId(), client.getClientId(), "ADMIN_USER_PASSWORD_AUTH",
                Map.of("USERNAME", "carol", "PASSWORD", "TempPass1!"), Map.of());
        String session = (String) challengeResp.get("Session");

        Map<String, String> responses = new HashMap<>();
        responses.put("USERNAME", "carol");
        responses.put("NEW_PASSWORD", "Permanent99!");
        responses.put("userAttributes.given_name", "Carolyn");

        Map<String, Object> result = service.adminRespondToAuthChallenge(
                pool.getId(), client.getClientId(), "NEW_PASSWORD_REQUIRED", session, responses);
        assertNotNull(((Map<String, Object>) result.get("AuthenticationResult")).get("AccessToken"));

        CognitoUser user = service.adminGetUser(pool.getId(), "carol");
        assertEquals("Carolyn", user.getAttributes().get("given_name"));
    }
}
